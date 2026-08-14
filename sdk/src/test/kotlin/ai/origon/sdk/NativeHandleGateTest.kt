package ai.origon.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeHandleGateTest {
    @Test
    fun closeWaitsForActiveCallAndRejectsLaterCalls() {
        val gate = NativeHandleGate(42)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val call = thread {
            gate.withHandle {
                assertEquals(42, it)
                entered.countDown()
                release.await()
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val close = thread {
            gate.closeOnce({}, { destroyed.set(true) })
        }
        Thread.sleep(25)
        assertFalse(destroyed.get())
        release.countDown()
        call.join()
        close.join()
        assertTrue(destroyed.get())
        assertFailsWith<SessionException> { gate.withHandle { } }
    }

    @Test
    fun ordinaryCallsAreConcurrent() {
        val gate = NativeHandleGate(42)
        val bothEntered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = List(2) {
            thread {
                gate.withHandle {
                    bothEntered.countDown()
                    release.await()
                }
            }
        }
        assertTrue(bothEntered.await(1, TimeUnit.SECONDS))
        release.countDown()
        calls.forEach(Thread::join)
    }
}
