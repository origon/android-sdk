package ai.origon.sdk

import ai.origon.sdk.bridge.AudioLevelsNextBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Idempotent owner of one background audio-level observation. */
class AudioLevelObservation internal constructor(
    private val cancelAction: () -> Unit,
) : AutoCloseable {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancelAction()
    }

    override fun close() = cancel()
}

/** Serializes native cancel with worker-only free without locking across `next`. */
internal class NativeAudioLevelOwner(
    private val subscription: Long,
    private val nextNative: (Long) -> AudioLevelsNextBridge = SessionBridge::nextAudioLevels,
    private val cancelNative: (Long) -> Unit = SessionBridge::cancelAudioLevels,
    private val freeNative: (Long) -> Unit = SessionBridge::freeAudioLevels,
) {
    private val lock = ReentrantLock()
    private var cancelled = false
    private var freed = false

    fun next(): AudioLevelsNextBridge = nextNative(subscription)

    fun cancelOnce() = lock.withLock {
        if (!cancelled && !freed) {
            cancelled = true
            cancelNative(subscription)
        }
    }

    /** Called only by the background pump after its blocking `next` has exited. */
    fun freeAfterNext() = lock.withLock {
        if (!freed) {
            if (!cancelled) {
                cancelled = true
                cancelNative(subscription)
            }
            freeNative(subscription)
            freed = true
        }
    }
}

internal fun interface AudioLevelMainDispatcher {
    /** Returns false when the main executor rejected the delivery. */
    fun post(block: () -> Unit): Boolean
}

internal fun interface AudioLevelThreadStarter {
    fun start(block: () -> Unit)
}

/** Client-owned generation registry used to invalidate every queued UI delivery on close. */
internal class AudioLevelObservationRegistry {
    private val lock = Any()
    private val active = mutableMapOf<Long, AudioLevelObservationPump>()
    private var nextGeneration = 1L
    private var closed = false

    fun register(
        owner: NativeAudioLevelOwner,
        dispatcher: AudioLevelMainDispatcher,
        observer: (SessionAudioLevels) -> Unit,
        threadStarter: AudioLevelThreadStarter = AudioLevelThreadStarter { block ->
            Thread({ block() }, "origon-audio-levels").apply {
                isDaemon = true
                start()
            }
        },
    ): AudioLevelObservation {
        val pump: AudioLevelObservationPump
        synchronized(lock) {
            check(!closed) { "audio level registry is closed" }
            val generation = nextGeneration++
            pump = AudioLevelObservationPump(
                generation = generation,
                owner = owner,
                registry = this,
                dispatcher = dispatcher,
                observer = observer,
                threadStarter = threadStarter,
            )
            active[generation] = pump
        }
        pump.start()
        return AudioLevelObservation(pump::cancel)
    }

    fun cancelAllAndClose() {
        val observations = synchronized(lock) {
            if (closed) return
            closed = true
            active.values.toList().also { active.clear() }
        }
        observations.forEach(AudioLevelObservationPump::cancelAfterRegistryRemoval)
    }

    fun isCurrent(generation: Long, pump: AudioLevelObservationPump): Boolean =
        synchronized(lock) { !closed && active[generation] === pump }

    fun retire(generation: Long, pump: AudioLevelObservationPump) {
        synchronized(lock) {
            if (active[generation] === pump) active.remove(generation)
        }
    }
}

internal class AudioLevelObservationPump(
    private val generation: Long,
    private val owner: NativeAudioLevelOwner,
    private val registry: AudioLevelObservationRegistry,
    private val dispatcher: AudioLevelMainDispatcher,
    private val observer: (SessionAudioLevels) -> Unit,
    private val threadStarter: AudioLevelThreadStarter,
) {
    private val active = AtomicBoolean(true)

    fun start() = threadStarter.start(::run)

    fun cancel() {
        registry.retire(generation, this)
        cancelAfterRegistryRemoval()
    }

    fun cancelAfterRegistryRemoval() {
        if (active.compareAndSet(true, false)) owner.cancelOnce()
    }

    private fun retireNaturally() {
        active.set(false)
        registry.retire(generation, this)
    }

    private fun run() {
        try {
            while (active.get()) {
                val next = owner.next()
                when (next.status) {
                    SessionBridge.AUDIO_LEVELS_UPDATE -> {
                        val snapshot = next.snapshot
                        if (snapshot == null) {
                            cancel()
                            break
                        }
                        val value = SessionAudioLevels(
                            sessionId = snapshot.sessionId,
                            outbound = snapshot.outbound,
                            inbound = snapshot.inbound,
                            endpoints = snapshot.endpoints.map { endpoint ->
                                EndpointAudioLevel(endpoint.endpointId, endpoint.inbound)
                            },
                        )
                        val accepted = CountDownLatch(1)
                        val posted = dispatcher.post {
                            if (!active.get() || !registry.isCurrent(generation, this)) {
                                accepted.countDown()
                                return@post
                            }
                            // Release the pump before user code so reentrant cancel/client close
                            // never forms a pump-to-main/main-to-pump wait cycle.
                            accepted.countDown()
                            observer(value)
                        }
                        if (!posted) {
                            accepted.countDown()
                            cancel()
                        }
                        accepted.await()
                    }

                    SessionBridge.AUDIO_LEVELS_END -> {
                        retireNaturally()
                        break
                    }

                    SessionBridge.AUDIO_LEVELS_CANCELLED -> {
                        retireNaturally()
                        break
                    }

                    else -> {
                        cancel()
                        break
                    }
                }
            }
        } finally {
            registry.retire(generation, this)
            owner.freeAfterNext()
        }
    }
}
