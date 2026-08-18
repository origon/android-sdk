package ai.origon.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Concurrent native-call leases; close rejects new calls and waits active calls. */
internal class NativeHandleGate(private val handle: Long) {
    private val closed = AtomicBoolean(false)
    private val lock = ReentrantReadWriteLock(true)

    fun <T> withHandle(block: (Long) -> T): T {
        lock.readLock().lock()
        try {
            checkOpen()
            return block(handle)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun closeOnce(beforeDestroy: () -> Unit, destroy: (Long) -> Unit) {
        lock.writeLock().lock()
        try {
            if (closed.compareAndSet(false, true)) {
                beforeDestroy()
                destroy(handle)
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun checkOpen() {
        if (closed.get()) {
            throw SessionException(
                SessionBridge.ERROR_NOT_INITIALIZED,
                0,
                null,
                "client has been closed",
            )
        }
    }
}
