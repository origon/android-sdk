package origon.example.android.services

import android.content.Context
import ai.origon.sdk.ClientConfig
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.OrigonClient
import ai.origon.sdk.ServerConfigLoadUpdate
import ai.origon.sdk.SessionException
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

    sealed interface ConfigAuthorityState {
        data object Unavailable : ConfigAuthorityState
        data object Cached : ConfigAuthorityState
        data object Authoritative : ConfigAuthorityState
        data class TransientFailure(val error: SessionException) : ConfigAuthorityState
        data class Terminal(val error: SessionException) : ConfigAuthorityState
    }

    /** App-scoped scope for the poll loop and service event collectors. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val configReplacement = ExampleConfigReplacement()
    private val checkpointStore = ExampleCheckpointStore(NoBackupCheckpointFiles(appContext))
    var checkpointEndpoint: String? = null
        private set
    private val _serverConfig = MutableStateFlow<ExampleServerConfig?>(null)
    val serverConfig: StateFlow<ExampleServerConfig?> = _serverConfig.asStateFlow()
    private val _configAuthority = MutableStateFlow<ConfigAuthorityState>(ConfigAuthorityState.Unavailable)
    val configAuthority: StateFlow<ConfigAuthorityState> = _configAuthority.asStateFlow()
    val hasAuthoritativeConfig: Boolean
        get() = _configAuthority.value === ConfigAuthorityState.Authoritative
    val endpointPolicy: ExampleEndpointPolicy
        get() = ExampleEndpointPolicy.from(_serverConfig.value, hasAuthoritativeConfig)

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
    private var configJob: Job? = null

    // MARK: - Lifecycle

    /** Connect to the Origon backend and start the event poll loop. */
    suspend fun initialize(endpoint: String, userId: String? = null, token: String? = null) {
        val configToken = configReplacement.begin()
        _serverConfig.value = null
        _configAuthority.value = ConfigAuthorityState.Unavailable
        chat.clientWillChange()
        val config = ClientConfig(
            endpoint = endpoint,
            token = token,
            userId = userId,
        )
        // OrigonClient(...) blocks on the FFI runtime during the /config
        // round trip — keep it off the main thread.
        val newClient = withContext(Dispatchers.IO) { OrigonClient(appContext, config) }
        val cachedConfig = ExampleServerConfig.from(newClient.serverConfig)
        if (!configReplacement.install(cachedConfig, configToken)) {
            newClient.close()
            return
        }
        client = newClient
        checkpointEndpoint = endpoint
        _serverConfig.value = cachedConfig
        chat.clientDidChange()
        _isReady.value = true
        startPolling()
        observeConfigUpdates(newClient, configToken, retry = false)
    }

    /** Destroy the client, reset child services, and stop polling. */
    fun teardown() {
        configJob?.cancel()
        configJob = null
        configReplacement.begin()
        _serverConfig.value = null
        _configAuthority.value = ConfigAuthorityState.Unavailable
        stopPolling()
        chat.destroy()
        _sessions.value = emptyList()
        client?.close()
        client = null
        checkpointEndpoint = null
        _isReady.value = false
    }

    fun retryServerConfig() {
        val current = client ?: return
        observeConfigUpdates(current, configReplacement.currentEpoch, retry = true)
    }

    private fun observeConfigUpdates(current: OrigonClient, token: Long, retry: Boolean) {
        if (!retry) _configAuthority.value = ConfigAuthorityState.Cached
        configJob?.cancel()
        configJob = scope.launch {
            val updates = if (retry) current.retryServerConfig() else current.serverConfigUpdates()
            runCatching {
                updates.collect { update ->
                    if (client !== current || configReplacement.currentEpoch != token) return@collect
                    when (update) {
                        is ServerConfigLoadUpdate.Snapshot -> {
                            val next = ExampleServerConfig.from(update.value.config)
                            if (!configReplacement.install(next, token)) return@collect
                            _serverConfig.value = next
                            _configAuthority.value = if (update.value.authoritative) {
                                ConfigAuthorityState.Authoritative
                            } else {
                                ConfigAuthorityState.Cached
                            }
                        }
                        is ServerConfigLoadUpdate.RefreshFailed -> {
                            applyConfigFailure(update.error)
                        }
                    }
                }
            }.onFailure { error ->
                if (client === current && configReplacement.currentEpoch == token &&
                    error is SessionException
                ) {
                    applyConfigFailure(error)
                }
            }
        }
    }

    private fun applyConfigFailure(error: SessionException) {
        if (error.statusCode in setOf(400, 401, 403, 404)) {
            configReplacement.begin()
            _serverConfig.value = null
            _sessions.value = emptyList()
            chat.destroy()
            _configAuthority.value = ConfigAuthorityState.Terminal(error)
        } else {
            _configAuthority.value = ConfigAuthorityState.TransientFailure(error)
        }
    }

    internal suspend fun checkpoint(sessionId: String): ExampleCheckpoint? {
        val endpoint = checkpointEndpoint ?: return null
        return runCatching {
            checkpointStore.read(endpoint, sessionId, System.currentTimeMillis())
        }.getOrNull()
    }

    internal suspend fun markCheckpointSeen(
        sessionId: String,
        foreground: Boolean,
        latestRowVisible: Boolean,
    ) {
        val endpoint = checkpointEndpoint ?: return
        val load = chat.focusedLoadState.value
        runCatching {
            checkpointStore.markSeen(
                endpoint = endpoint,
                sessionId = sessionId,
                messageId = exampleNewestEligibleMessageId(chat.messages.value),
                authoritative = load == ChatService.DestinationLoadState.NETWORK ||
                    load == ChatService.DestinationLoadState.FRESH_EMPTY,
                foreground = foreground,
                detailVisible = chat.currentSessionId.value == sessionId,
                latestRowVisible = latestRowVisible,
                now = System.currentTimeMillis(),
            )
        }
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
