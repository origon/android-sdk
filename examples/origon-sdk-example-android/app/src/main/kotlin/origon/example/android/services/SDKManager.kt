package origon.example.android.services

import android.content.Context
import ai.origon.sdk.ClientConfig
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.OrigonClient
import ai.origon.sdk.SessionSummary
import ai.origon.sdk.SessionsLoadUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the [OrigonClient] for the lifetime of an authenticated session and
 * serves as the single entry point the UI uses for both call and chat.
 *
 * The app has exactly one of these (created by [origon.example.android.OrigonExampleApp]).
 * Responsibilities mirror the iOS `SDKManager`:
 *
 * - Hold the [OrigonClient] handle.
 * - Own [CallService] and [ChatService] and expose them as `call` / `chat`.
 * - Host the shared session list and the [refreshSessions] finite directory load.
 * - Drain the SDK's event queue on a 50 ms loop and broadcast every
 *   [ClientEvent] through [events]. Consumers filter by `sessionId`.
 * - Tear down cleanly on "change endpoint" so the native handle is released.
 */
class SDKManager(private val appContext: Context) {

    /** App-scoped scope for the poll loop and service event collectors. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    /** Broadcast of every event drained from [OrigonClient.pollEvent]. */
    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    var client: OrigonClient? = null
        private set

    internal val chatClient: ChatSessionClient?
        get() = client?.let(::OrigonChatSessionClient)

    // Child services subscribe to `events` in their constructors. `scope`
    // and `_events` are initialized above, so this ordering is safe.
    val call = CallService(this)
    val chat = ChatService(this)

    private var pollJob: Job? = null

    // MARK: - Lifecycle

    /** Connect to the Origon backend and start the event poll loop. */
    suspend fun initialize(endpoint: String, userId: String? = null, token: String? = null) {
        chat.clientWillChange()
        val config = ClientConfig(
            endpoint = endpoint,
            token = token,
            userId = userId,
        )
        // OrigonClient(...) blocks on the FFI runtime during the /config
        // round trip — keep it off the main thread.
        val newClient = withContext(Dispatchers.IO) { OrigonClient(appContext, config) }
        client = newClient
        _isReady.value = true
        startPolling()
    }

    /** Destroy the client, reset child services, and stop polling. */
    fun teardown() {
        stopPolling()
        chat.destroy()
        _sessions.value = emptyList()
        client?.close()
        client = null
        _isReady.value = false
    }

    // MARK: - Sessions (shared between call and chat)

    /** Refresh the cached session list from the SDK (used by the sidebar). */
    suspend fun refreshSessions() {
        val c = client ?: return
        c.sessionDirectoryUpdates().collect { update ->
            when (update) {
                is SessionsLoadUpdate.Snapshot -> _sessions.value = update.value.sessions
                is SessionsLoadUpdate.RefreshFailed -> throw update.error
            }
        }
    }

    // MARK: - Event polling

    private fun startPolling() {
        stopPolling()
        // 50 ms cadence matches the iOS app. Drain up to 50 events per
        // tick to avoid backlog if a burst arrives between ticks.
        pollJob = scope.launch {
            while (isActive) {
                val c = client
                if (c == null) {
                    delay(50)
                    continue
                }
                var drained = 0
                while (drained < 50) {
                    val event = withContext(Dispatchers.IO) { c.pollEvent() } ?: break
                    _events.emit(event)
                    drained++
                }
                delay(50)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
