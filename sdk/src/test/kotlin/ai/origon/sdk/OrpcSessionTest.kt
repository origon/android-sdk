package ai.origon.sdk

import ai.origon.sdk.bridge.OrpcCallResult
import ai.origon.sdk.bridge.OrpcNextResult
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class OrpcSessionTest {
    private class FakeNative : OrpcNative {
        val nexts = LinkedBlockingQueue<OrpcNextResult>()
        val calls = mutableListOf<String>()
        var reopenResult: OrpcCallResult? = null

        override fun reopen(orpc: Long, request: OrpcSession.Request, expectedGeneration: Long): OrpcCallResult? {
            calls += "reopen:${request.path}:$expectedGeneration:${request.keys.toList()}"
            return reopenResult
        }
        override fun unary(orpc: Long, request: OrpcSession.Request, timeoutMs: Int): ByteArray {
            calls += "unary:${request.path}:$timeoutMs"
            return request.body
        }
        override fun next(orpc: Long): OrpcNextResult = nexts.take()
        override fun end(orpc: Long) { calls += "end" }
        override fun cancel(orpc: Long) { calls += "cancel" }
        override fun free(orpc: Long) { calls += "free" }
    }

    private fun next(kind: Int, block: OrpcNextResult.() -> Unit = {}) =
        OrpcNextResult().apply { this.kind = kind; block() }

    @Test
    fun itemsMapEveryKindThenCompleteAndFreeAfterTheTerminal() = runBlocking {
        val native = FakeNative()
        native.nexts += next(SessionBridge.ORPC_NEXT_STATUS) { status = SessionBridge.ORPC_STATUS_RECONNECTING; attempt = 2 }
        native.nexts += next(SessionBridge.ORPC_NEXT_FRAME) { generation = 1; data = byteArrayOf(7) }
        native.nexts += next(SessionBridge.ORPC_NEXT_STREAM_ENDED) { generation = 1 }
        native.nexts += next(SessionBridge.ORPC_NEXT_TERMINAL) { terminal = SessionBridge.ORPC_TERMINAL_SESSION_ENDED; detail = "SessionExpired" }
        val items = OrpcSession(42, native).items.toList()
        assertEquals(OrpcSession.Item.StatusChanged(OrpcSession.Status.Reconnecting(2)), items[0])
        val frame = assertIs<OrpcSession.Item.Frame>(items[1])
        assertEquals(1, frame.generation)
        assertContentEquals(byteArrayOf(7), frame.data)
        assertEquals(OrpcSession.Item.StreamEnded(1), items[2])
        assertEquals(OrpcSession.Item.Ended(OrpcSession.Terminal.SessionEnded("SessionExpired")), items[3])
        assertTrue("free" in native.calls)
    }

    @Test
    fun cancelledNextCompletesWithoutATerminal() = runBlocking {
        val native = FakeNative()
        native.nexts += next(SessionBridge.ORPC_NEXT_CANCELLED)
        assertEquals(emptyList(), OrpcSession(42, native).items.toList())
    }

    @Test
    fun verbsPassRequestsThroughAndAStaleReopenIsNull() = runBlocking {
        val native = FakeNative()
        val session = OrpcSession(42, native)
        val request = OrpcSession.Request("cx.v1.Dock/Login", listOf("authorization" to "Bearer t".toByteArray()), byteArrayOf(1))
        assertNull(session.reopen(request, 1))
        native.reopenResult = OrpcCallResult().apply { generation = 2; response = byteArrayOf(9) }
        val reopened = session.reopen(request, 1)
        assertEquals(2, reopened?.generation)
        assertContentEquals(byteArrayOf(1), session.unary(OrpcSession.Request("a/B", body = byteArrayOf(1)), 250))
        session.end()
        assertEquals(
            listOf("reopen:cx.v1.Dock/Login:1:[authorization]", "reopen:cx.v1.Dock/Login:1:[authorization]", "unary:a/B:250", "end"),
            native.calls,
        )
    }

    @Test
    fun bridgeDescriptorsAndConstantsMatchTheNativeContract() {
        val bridge = SessionBridge::class.java
        val bytes = ByteArray::class.java
        val strings = Array<String>::class.java
        val byteArrays = Array<ByteArray>::class.java
        val long = Long::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        assertEquals(OrpcCallResult::class.java, bridge.getDeclaredMethod("orpcOpen", long, String::class.java, String::class.java, strings, byteArrays, bytes).returnType)
        assertEquals(OrpcCallResult::class.java, bridge.getDeclaredMethod("orpcOpenStandalone", String::class.java, String::class.java, strings, byteArrays, bytes).returnType)
        assertEquals(OrpcCallResult::class.java, bridge.getDeclaredMethod("orpcReopen", long, String::class.java, strings, byteArrays, bytes, long).returnType)
        assertEquals(bytes, bridge.getDeclaredMethod("orpcUnary", long, String::class.java, strings, byteArrays, bytes, int).returnType)
        assertEquals(OrpcNextResult::class.java, bridge.getDeclaredMethod("orpcNext", long).returnType)
        for (name in listOf("orpcEnd", "orpcCancel", "orpcFree")) {
            assertEquals(Void.TYPE, bridge.getDeclaredMethod(name, long).returnType)
        }
        assertEquals(12, SessionBridge.ERROR_ORPC_REJECTED)
        assertEquals(15, SessionBridge.ERROR_ORPC_INVALID)
        assertEquals(6, SessionBridge.ORPC_NEXT_CANCELLED)
        assertEquals(3, SessionBridge.ORPC_TERMINAL_OVERFLOW)
    }
}
