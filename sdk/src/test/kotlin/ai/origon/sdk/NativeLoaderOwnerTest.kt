package ai.origon.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeLoaderOwnerTest {
    @Test
    fun freeWaitsForCancelCallToExit() {
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val freed = AtomicBoolean(false)
        val owner = NativeLoaderOwner(
            loader = 7,
            cancelNative = {
                cancelEntered.countDown()
                releaseCancel.await()
            },
            freeNative = { freed.set(true) },
        )
        val cancel = thread { owner.cancelOnce() }
        assertTrue(cancelEntered.await(1, TimeUnit.SECONDS))
        val free = thread { owner.freeAfterNext() }
        Thread.sleep(25)
        assertFalse(freed.get())
        releaseCancel.countDown()
        cancel.join()
        free.join()
        assertTrue(freed.get())
    }
}
