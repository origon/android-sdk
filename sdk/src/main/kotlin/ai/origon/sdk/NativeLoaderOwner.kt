package ai.origon.sdk

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes cancel with free without holding a lock across blocking `next`. */
internal class NativeLoaderOwner(
    private val loader: Long,
    private val cancelNative: (Long) -> Unit = SessionBridge::loaderCancel,
    private val freeNative: (Long) -> Unit = SessionBridge::loaderFree,
) {
    private val lock = ReentrantLock()
    private var cancelled = false
    private var freed = false

    fun cancelOnce() = lock.withLock {
        if (!cancelled && !freed) {
            cancelled = true
            cancelNative(loader)
        }
    }

    /** Called only by the worker after its blocking `next` has exited. */
    fun freeAfterNext() = lock.withLock {
        if (!freed) {
            if (!cancelled) {
                cancelled = true
                cancelNative(loader)
            }
            freeNative(loader)
            freed = true
        }
    }
}
