package origon.example.android.services

import ai.origon.sdk.AudioOutputRoute
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.DisconnectReason
import ai.origon.sdk.SessionException
import ai.origon.sdk.StartCallOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice-call state machine. Owns the active voice session id (one at a
 * time) and reflects its lifecycle into UI-friendly state. Mirrors the
 * iOS `CallService`.
 */
class CallService(private val manager: SDKManager) {

    sealed class Phase {
        data object Idle : Phase()
        data object Connecting : Phase()
        data object Connected : Phase()
        data object Reconnecting : Phase()
        /** [reason] = null means a clean, user-initiated end. */
        data class Ended(val reason: String?) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Whether call audio is routed to the loudspeaker. Reset per call. */
    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** Soft errors surfaced via [ClientEvent.CallError]. null = cleared. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var sessionId: String? = null
        private set

    init {
        manager.scope.launch {
            manager.events.collect { handleEvent(it) }
        }
    }

    // MARK: - Voice call lifecycle

    /**
     * Open a voice session. Phase moves Idle → Connecting synchronously,
     * then Connected once the SDK fires [ClientEvent.Connected] for our
     * session id.
     */
    suspend fun startCall() {
        val client = manager.client
            ?: throw IllegalStateException("SDK not initialized")

        _phase.value = Phase.Connecting
        _lastError.value = null
        _muted.value = false
        _speakerOn.value = false
        try {
            // startCall blocks on the FFI runtime (HTTP + QUIC dial) —
            // hop off the main thread.
            val response = withContext(Dispatchers.IO) {
                client.startCall(StartCallOptions())
            }
            sessionId = response.sessionId
        } catch (e: Throwable) {
            val message = (e as? SessionException)?.message ?: e.message
            _phase.value = Phase.Ended(message)
            throw e
        }
    }

    /** Absolute mute setter. Surfaces failure via [lastError] rather than throwing. */
    fun setMute(value: Boolean) {
        val client = manager.client ?: return
        val id = sessionId ?: return
        try {
            client.setMute(id, value)
            _muted.value = value
        } catch (e: SessionException) {
            _lastError.value = e.message ?: "Failed to update mute"
        }
    }

    /**
     * Route call audio to the loudspeaker ([value] = true) or back to the
     * default route ([value] = false). Process-global, so it needs no session
     * id. Updates [speakerOn] optimistically and applies the route off the main
     * thread (it can block on an audio stream reopen); reverts on failure.
     */
    fun setSpeaker(value: Boolean) {
        val client = manager.client ?: return
        _speakerOn.value = value
        manager.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.setAudioOutput(
                        if (value) AudioOutputRoute.SPEAKER else AudioOutputRoute.AUTOMATIC
                    )
                }
            } catch (e: SessionException) {
                _speakerOn.value = !value
                _lastError.value = e.message ?: "Failed to switch speaker"
            }
        }
    }

    /**
     * User-initiated end. The SDK fires Disconnected(LocalClose) shortly
     * after; we don't wait — flip phase immediately so the UI dismisses.
     */
    fun endCall() {
        val client = manager.client
        val id = sessionId
        if (client == null || id == null) {
            _phase.value = Phase.Ended(null)
            return
        }
        runCatching { client.endSession(id) }
        sessionId = null
        _phase.value = Phase.Ended(null)
    }

    /** Reset to idle so a dismissed call surface starts clean next time. */
    fun reset() {
        _phase.value = Phase.Idle
        _lastError.value = null
        _muted.value = false
        _speakerOn.value = false
    }

    // MARK: - Event handling

    private fun handleEvent(event: ClientEvent) {
        val active = sessionId ?: return
        if (event.sessionId != active) return

        when (event) {
            is ClientEvent.Connected -> _phase.value = Phase.Connected
            is ClientEvent.Reconnecting -> _phase.value = Phase.Reconnecting
            is ClientEvent.Reconnected -> _phase.value = Phase.Connected
            is ClientEvent.Disconnected -> {
                _phase.value = Phase.Ended(disconnectReasonText(event.reason))
                sessionId = null
            }
            is ClientEvent.CallError -> _lastError.value = event.message
            // SDK is the source of truth for the route: fires for our own
            // setSpeaker and for OS-driven changes (headset plug), so the
            // toggle never goes stale.
            is ClientEvent.AudioRouteChanged ->
                _speakerOn.value = event.route == AudioOutputRoute.SPEAKER
            else -> Unit
        }
    }

    /** Map a structured [DisconnectReason] to short text. null = clean close. */
    private fun disconnectReasonText(reason: DisconnectReason): String? = when (reason) {
        is DisconnectReason.LocalClose -> null
        // Clean end from the server side — not a failure, so no error text.
        // Matches the iOS example's `.sessionEnded` treatment.
        is DisconnectReason.SessionEnded -> null
        is DisconnectReason.NetworkLoss -> "Network connection lost"
        is DisconnectReason.TokenInvalid,
        is DisconnectReason.TokenExpired,
        is DisconnectReason.TokenReplayed -> "Session expired"
        is DisconnectReason.EndpointNotProvisioned -> "Endpoint not provisioned"
        is DisconnectReason.EndpointAlreadyConnected -> "Already connected on another device"
        is DisconnectReason.CapabilityMissing,
        is DisconnectReason.ProtocolViolation,
        is DisconnectReason.IllegalState -> "Connection error"
        is DisconnectReason.ResourceExhausted -> "Server is at capacity"
        is DisconnectReason.ReplayLost -> "Connection lost"
        is DisconnectReason.ServerClosed -> reason.detail ?: "Disconnected"
        is DisconnectReason.TransportClosed -> reason.detail ?: "Could not connect"
    }
}
