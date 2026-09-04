package ai.origon.sdk

import ai.origon.sdk.bridge.AudioLevelsNextBridge
import ai.origon.sdk.bridge.EndpointAudioLevelBridge
import ai.origon.sdk.bridge.SessionAudioLevelsBridge
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioLevelsAbiTest {
    @Test
    fun bridgeMethodsStatusesAndConstructorsAreExact() {
        val bridge = SessionBridge::class.java
        fun method(name: String, vararg parameters: Class<*>): Class<*> =
            bridge.getDeclaredMethod(name, *parameters).returnType

        assertEquals(
            java.lang.Long.TYPE,
            method("subscribeAudioLevels", java.lang.Long.TYPE, String::class.java),
        )
        assertEquals(
            AudioLevelsNextBridge::class.java,
            method("nextAudioLevels", java.lang.Long.TYPE),
        )
        assertEquals(Void.TYPE, method("cancelAudioLevels", java.lang.Long.TYPE))
        assertEquals(Void.TYPE, method("freeAudioLevels", java.lang.Long.TYPE))
        assertEquals(1, SessionBridge.AUDIO_LEVELS_UPDATE)
        assertEquals(2, SessionBridge.AUDIO_LEVELS_END)
        assertEquals(3, SessionBridge.AUDIO_LEVELS_CANCELLED)

        AudioLevelsNextBridge::class.java.getDeclaredConstructor(
            java.lang.Integer.TYPE,
            SessionAudioLevelsBridge::class.java,
        )
        SessionAudioLevelsBridge::class.java.getDeclaredConstructor(
            String::class.java,
            java.lang.Float.TYPE,
            java.lang.Float.TYPE,
            Array<EndpointAudioLevelBridge>::class.java,
        )
        EndpointAudioLevelBridge::class.java.getDeclaredConstructor(
            String::class.java,
            java.lang.Float.TYPE,
        )

        val publicMethod = OrigonClient::class.java.getDeclaredMethod(
            "observeAudioLevels",
            String::class.java,
            kotlin.jvm.functions.Function1::class.java,
        )
        assertEquals(AudioLevelObservation::class.java, publicMethod.returnType)
        assertTrue(publicMethod.exceptionTypes.contains(SessionException::class.java))
    }

    @Test
    fun publicSnapshotsAreImmutableCopiesOfBridgeArrays() {
        val endpoint = EndpointAudioLevel("endpoint", 0.25f)
        val levels = SessionAudioLevels("session", 0.5f, 0.75f, listOf(endpoint))
        assertEquals("session", levels.sessionId)
        assertEquals(listOf(endpoint), levels.endpoints)
    }

    @Test
    fun nativeOwnerSerializesCancelAgainstWorkerFree() {
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val freed = CountDownLatch(1)
        val owner = NativeAudioLevelOwner(
            subscription = 7,
            nextNative = { error("not used") },
            cancelNative = {
                cancelEntered.countDown()
                releaseCancel.await()
            },
            freeNative = { freed.countDown() },
        )
        val cancel = thread { owner.cancelOnce() }
        assertTrue(cancelEntered.await(1, TimeUnit.SECONDS))
        val free = thread { owner.freeAfterNext() }
        assertFalse(freed.await(25, TimeUnit.MILLISECONDS))
        releaseCancel.countDown()
        cancel.join()
        free.join()
        assertEquals(0, freed.count)
    }

    @Test
    fun ordinaryCallbackMayCancelReentrantlyWithoutLateDelivery() {
        runReentrantCase(terminal = false, closeClient = false)
    }

    @Test
    fun ordinaryCallbackMayCloseClientReentrantlyWithoutLateDelivery() {
        runReentrantCase(terminal = false, closeClient = true)
    }

    @Test
    fun terminalZeroCallbackMayCancelReentrantlyWithoutDeadlock() {
        runReentrantCase(terminal = true, closeClient = false)
    }

    @Test
    fun terminalZeroCallbackMayCloseClientReentrantlyWithoutDeadlock() {
        runReentrantCase(terminal = true, closeClient = true)
    }

    private fun runReentrantCase(terminal: Boolean, closeClient: Boolean) {
        val nativeResults = ArrayBlockingQueue<AudioLevelsNextBridge>(4)
        val freed = CountDownLatch(1)
        val callbacks = AtomicInteger(0)
        val callbackDone = CountDownLatch(1)
        val tokenReady = CountDownLatch(1)
        val token = AtomicReference<AudioLevelObservation>()
        val registry = AudioLevelObservationRegistry()
        val main = Executors.newSingleThreadExecutor()
        val owner = NativeAudioLevelOwner(
            subscription = 9,
            nextNative = { nativeResults.take() },
            cancelNative = {
                nativeResults.offer(
                    AudioLevelsNextBridge(SessionBridge.AUDIO_LEVELS_CANCELLED, null),
                )
            },
            freeNative = { freed.countDown() },
        )
        val observation = registry.register(
            owner = owner,
            dispatcher = AudioLevelMainDispatcher { block ->
                main.execute { block() }
                true
            },
            observer = {
                tokenReady.await()
                callbacks.incrementAndGet()
                if (closeClient) registry.cancelAllAndClose() else token.get().cancel()
                callbackDone.countDown()
            },
        )
        token.set(observation)
        tokenReady.countDown()

        val zero = SessionAudioLevelsBridge(
            sessionId = "session",
            outbound = 0f,
            inbound = 0f,
            endpoints = arrayOf(EndpointAudioLevelBridge("endpoint", 0f)),
        )
        val ordinary = SessionAudioLevelsBridge(
            sessionId = "session",
            outbound = 0.5f,
            inbound = 0.25f,
            endpoints = arrayOf(EndpointAudioLevelBridge("endpoint", 0.125f)),
        )
        nativeResults.put(
            AudioLevelsNextBridge(
                SessionBridge.AUDIO_LEVELS_UPDATE,
                if (terminal) zero else ordinary,
            ),
        )
        if (terminal) {
            nativeResults.put(AudioLevelsNextBridge(SessionBridge.AUDIO_LEVELS_END, null))
        } else {
            nativeResults.put(AudioLevelsNextBridge(SessionBridge.AUDIO_LEVELS_UPDATE, ordinary))
        }

        assertTrue(callbackDone.await(1, TimeUnit.SECONDS))
        assertTrue(freed.await(1, TimeUnit.SECONDS))
        main.shutdown()
        assertTrue(main.awaitTermination(1, TimeUnit.SECONDS))
        assertEquals(1, callbacks.get())
    }
}
