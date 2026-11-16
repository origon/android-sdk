package ai.origon.sdk

import ai.origon.sdk.bridge.OrpcCallResult
import ai.origon.sdk.bridge.OrpcNextResult
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * A product-free oRPC session held on gw's `orpc-2` ALPN: one QUIC session plus one
 * generation-fenced session-open stream (for Dock, `cx.v1.Dock/Login`).
 *
 * Bytes only: paths, metadata and payloads belong to the caller, which owns the product's
 * protobuf. Collect [items] once. After [Item.StreamEnded] call [reopen] to re-open the
 * stream on the same session (gw's persistent-session relay keeps its id), or [end].
 */
class OrpcSession internal constructor(
    private var raw: Long,
    private val native: OrpcNative = JniOrpcNative,
) {
    /** One request: a path, binary-safe metadata and an encoded body. */
    class Request(
        val path: String,
        val metadata: List<Pair<String, ByteArray>> = emptyList(),
        val body: ByteArray = ByteArray(0),
    ) {
        internal val keys: Array<String> get() = metadata.map { it.first }.toTypedArray()
        internal val values: Array<ByteArray> get() = metadata.map { it.second }.toTypedArray()
    }

    sealed interface Status {
        data object Connecting : Status
        data object Live : Status
        data class Reconnecting(val attempt: Int) : Status
    }

    /** Why the session ended; the last item before [items] completes. */
    sealed interface Terminal {
        data object Ended : Terminal
        data class SessionEnded(val detail: String) : Terminal
        data object Overflow : Terminal
    }

    sealed interface Item {
        class Frame(val generation: Long, val data: ByteArray) : Item
        data class StreamEnded(val generation: Long) : Item
        data class StatusChanged(val status: Status) : Item
        data class Ended(val terminal: Terminal) : Item
    }

    /** A successful [reopen]. */
    class Reopened(val generation: Long, val response: ByteArray)

    private val lock = ReentrantLock()
    private var cancelled = false

    /** Re-open the session-open stream; null when [expectedGeneration] was already replaced. */
    suspend fun reopen(request: Request, expectedGeneration: Long): Reopened? =
        withContext(Dispatchers.IO) {
            val result = native.reopen(current(), request, expectedGeneration) ?: return@withContext null
            Reopened(result.generation, result.response ?: ByteArray(0))
        }

    /** A unary RPC on the held session. */
    suspend fun unary(request: Request, timeoutMs: Int = 5_000): ByteArray =
        withContext(Dispatchers.IO) { native.unary(current(), request, timeoutMs) }

    /** End intentionally; [items] drains, then emits [Terminal.Ended]. Idempotent. */
    suspend fun end() = withContext(Dispatchers.IO) {
        val pointer = lock.withLock { if (cancelled) 0L else raw }
        if (pointer != 0L) native.end(pointer)
    }

    /** Items until the terminal, then completion. Collect once. */
    val items: Flow<Item> = callbackFlow {
        thread(name = "ai.origon.sdk.orpc-next") {
            while (true) {
                val pointer = lock.withLock { raw }
                if (pointer == 0L) break
                val item = toItem(native.next(pointer)) ?: break
                trySend(item)
                if (item is Item.Ended) break
            }
            close()
            freeAfterNext()
        }
        // A blocked JNI `next` ignores interrupts; native cancel unblocks it.
        awaitClose { cancel() }
    }

    private fun current(): Long = lock.withLock {
        if (cancelled || raw == 0L) {
            throw SessionException(SessionBridge.ERROR_ORPC_UNAVAILABLE, 0, null, "orpc session closed")
        }
        raw
    }

    private fun cancel() = lock.withLock {
        if (!cancelled && raw != 0L) {
            cancelled = true
            native.cancel(raw)
        }
    }

    /** Called only by the reader after its last `next` returned. */
    private fun freeAfterNext() {
        val pointer = lock.withLock {
            val pointer = raw
            raw = 0L
            cancelled = true
            pointer
        }
        if (pointer != 0L) native.free(pointer)
    }

    companion object {
        /** Dial the url's host on gw's `orpc-2` :443 and open [request] as stream 1. */
        suspend fun open(client: OrigonClient, url: String, request: Request): Pair<OrpcSession, ByteArray> =
            opened {
                client.withNativeHandle { handle ->
                    SessionBridge.orpcOpen(handle, url, request.path, request.keys, request.values, request.body)
                }
            }

        /**
         * [open] without an [OrigonClient]: the session owns its own runtime, so a product can
         * sign in over oRPC (for Dock, `cx.v1.Dock/Login`) before it can create a client.
         */
        suspend fun open(url: String, request: Request): Pair<OrpcSession, ByteArray> =
            opened { SessionBridge.orpcOpenStandalone(url, request.path, request.keys, request.values, request.body) }

        private suspend fun opened(call: () -> OrpcCallResult): Pair<OrpcSession, ByteArray> =
            withContext(Dispatchers.IO) {
                val result = call()
                OrpcSession(result.orpc) to (result.response ?: ByteArray(0))
            }

        internal fun toItem(next: OrpcNextResult): Item? = when (next.kind) {
            SessionBridge.ORPC_NEXT_FRAME -> Item.Frame(next.generation, next.data ?: ByteArray(0))
            SessionBridge.ORPC_NEXT_STREAM_ENDED -> Item.StreamEnded(next.generation)
            SessionBridge.ORPC_NEXT_STATUS -> Item.StatusChanged(
                when (next.status) {
                    SessionBridge.ORPC_STATUS_LIVE -> Status.Live
                    SessionBridge.ORPC_STATUS_RECONNECTING -> Status.Reconnecting(next.attempt)
                    else -> Status.Connecting
                },
            )
            SessionBridge.ORPC_NEXT_TERMINAL -> Item.Ended(
                when (next.terminal) {
                    SessionBridge.ORPC_TERMINAL_OVERFLOW -> Terminal.Overflow
                    SessionBridge.ORPC_TERMINAL_SESSION_ENDED -> Terminal.SessionEnded(next.detail.orEmpty())
                    else -> Terminal.Ended
                },
            )
            // CLOSED, CANCELLED: nothing more will arrive.
            else -> null
        }
    }
}

/** The native calls [OrpcSession] makes; a seam for tests. */
internal interface OrpcNative {
    fun reopen(orpc: Long, request: OrpcSession.Request, expectedGeneration: Long): OrpcCallResult?
    fun unary(orpc: Long, request: OrpcSession.Request, timeoutMs: Int): ByteArray
    fun next(orpc: Long): OrpcNextResult
    fun end(orpc: Long)
    fun cancel(orpc: Long)
    fun free(orpc: Long)
}

internal object JniOrpcNative : OrpcNative {
    override fun reopen(orpc: Long, request: OrpcSession.Request, expectedGeneration: Long) =
        SessionBridge.orpcReopen(orpc, request.path, request.keys, request.values, request.body, expectedGeneration)

    override fun unary(orpc: Long, request: OrpcSession.Request, timeoutMs: Int) =
        SessionBridge.orpcUnary(orpc, request.path, request.keys, request.values, request.body, timeoutMs)

    override fun next(orpc: Long) = SessionBridge.orpcNext(orpc)
    override fun end(orpc: Long) = SessionBridge.orpcEnd(orpc)
    override fun cancel(orpc: Long) = SessionBridge.orpcCancel(orpc)
    override fun free(orpc: Long) = SessionBridge.orpcFree(orpc)
}
