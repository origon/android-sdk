package ai.origon.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Enums ────────────────────────────────────────────────────────────

@Serializable
enum class Channel {
    @SerialName("chat") CHAT,
    @SerialName("voice") VOICE;

    internal fun toBridge(): Int = when (this) {
        CHAT -> SessionBridge.CHANNEL_CHAT
        VOICE -> SessionBridge.CHANNEL_VOICE
    }

    internal companion object {
        fun fromBridge(value: Int): Channel = when (value) {
            SessionBridge.CHANNEL_CHAT -> CHAT
            SessionBridge.CHANNEL_VOICE -> VOICE
            else -> throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "unknown channel discriminant: $value",
            )
        }

        fun fromWire(s: String): Channel = when (s) {
            "voice" -> VOICE
            "chat" -> CHAT
            else -> throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "unknown channel: $s",
            )
        }
    }
}

@Serializable
enum class SessionControl {
    @SerialName("ai") AI,
    @SerialName("user") USER;

    internal companion object {
        fun fromBridge(value: Int): SessionControl = when (value) {
            SessionBridge.CONTROL_USER -> USER
            else -> AI
        }
    }
}

@Serializable
enum class MessageRole {
    @SerialName("ai") AI,
    @SerialName("external") EXTERNAL,
    @SerialName("user") USER,
    @SerialName("system") SYSTEM,
}

/** Delivery status of a [Message]. */
@Serializable
enum class MessageStatus {
    @SerialName("sending") SENDING,
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
}

/**
 * Generation state of a [Message]. `STREAMING` while tokens are still
 * arriving from the agent; `COMPLETED` once finalised.
 */
@Serializable
enum class MessageState {
    @SerialName("streaming") STREAMING,
    @SerialName("completed") COMPLETED,
}

/**
 * Audio output route override for a voice call — the "speakerphone" concept,
 * distinct from device selection. Android applies it via `AudioManager`
 * (speakerphone / Bluetooth SCO). Higher-level UI typically wraps this as a
 * boolean speaker toggle ([SPEAKER] / [AUTOMATIC]).
 */
enum class AudioOutputRoute {
    /** System default — earpiece, or wired/Bluetooth when present. */
    AUTOMATIC,
    /** Built-in loudspeaker (speakerphone). */
    SPEAKER,
    /** Bluetooth hands-free (SCO). */
    BLUETOOTH;

    internal fun toBridge(): Int = when (this) {
        AUTOMATIC -> SessionBridge.AUDIO_OUTPUT_DEFAULT
        SPEAKER -> SessionBridge.AUDIO_OUTPUT_SPEAKER
        BLUETOOTH -> SessionBridge.AUDIO_OUTPUT_BLUETOOTH
    }

    internal companion object {
        /** Map a bridge `AUDIO_OUTPUT_*` int back to a route; unknown → [AUTOMATIC]. */
        fun fromBridge(value: Int): AudioOutputRoute = when (value) {
            SessionBridge.AUDIO_OUTPUT_SPEAKER -> SPEAKER
            SessionBridge.AUDIO_OUTPUT_BLUETOOTH -> BLUETOOTH
            else -> AUTOMATIC
        }
    }
}

// ── Configuration / requests ─────────────────────────────────────────

data class ClientConfig(
    val endpoint: String,
    val token: String? = null,
    /**
     * Optional. When omitted, the SDK falls back to the device
     * identifier (`Settings.Secure.ANDROID_ID`) so anonymous users still
     * get a stable identity. Initialization fails only if both this and
     * the device identifier are unavailable.
     */
    val userId: String? = null,
    /**
     * Initial session-level attributes. Injected as `data.attributes`
     * on `POST /session/start`. Encoded to a JSON string via
     * `kotlinx.serialization` before crossing the native boundary.
     */
    val attributes: JsonObject? = null,
)

data class StartSessionOptions(
    val channel: Channel,
    /** Existing session id to resume; null for a new session. */
    val sessionId: String? = null,
    /** Optional consumer-defined raw JSON forwarded as `data` on the wire. */
    val data: String? = null,
)

data class StartSessionResponse(
    val sessionId: String,
    val url: String,
    /** Per-session auth token, scoped to this session only. */
    val token: String,
)

data class JoinSessionInput(
    val channel: Channel,
    val sessionId: String,
    val url: String,
    val token: String,
)

data class ActiveSession(
    val sessionId: String,
    val channel: Channel,
)

// ── Server config ────────────────────────────────────────────────────

data class AttachmentRule(
    val enabled: Boolean,
    /** Maximum allowed size in megabytes. */
    val maxSize: Int,
)

data class AttachmentPolicy(
    val images: AttachmentRule,
    val documents: AttachmentRule,
    val videos: AttachmentRule,
    val audio: AttachmentRule,
) {
    companion object {
        /** Fallback policy with every category disabled and `maxSize = 0`. */
        val DISABLED = AttachmentPolicy(
            images = AttachmentRule(enabled = false, maxSize = 0),
            documents = AttachmentRule(enabled = false, maxSize = 0),
            videos = AttachmentRule(enabled = false, maxSize = 0),
            audio = AttachmentRule(enabled = false, maxSize = 0),
        )
    }
}

data class ServerConfig(
    val startMessage: String,
    val multipleChannels: Boolean,
    val isChatEnabled: Boolean,
    val isCallEnabled: Boolean,
    val attachmentPolicy: AttachmentPolicy,
)

// ── Session history ──────────────────────────────────────────────────

/**
 * Uploaded media descriptor. Surfaced on [Message.attachments] and
 * passed back into [SendMessagePayload.attachments].
 *
 * [localUrl] is an optional client-side preview URI (e.g. `content://`
 * or `file://`) that the UI can keep displaying after a message is
 * acknowledged. It is encoded on the Kotlin↔JNI boundary so the Rust
 * SDK can stash it keyed by attachment id, and the same Rust SDK
 * strips it from the wire (`skip_serializing`) before the server-bound
 * POST. The value is then merged back onto the server-issued
 * [Message] by id, so `MessageUpdated` carries `localUrl` through to
 * the consumer's UI without the server ever seeing it.
 */
@Serializable
data class Attachment(
    val id: String = "",
    val name: String = "",
    val contentType: String = "",
    val url: String = "",
    val localUrl: String? = null,
)

/**
 * Progress update emitted while [OrigonClient.uploadAttachment] is in
 * flight.
 *
 * `totalBytes` may be `null` when the underlying transport doesn't
 * report a content length (mirrors the `total = -1` sentinel from the
 * native progress callback). `percent` is `null` in the same case.
 */
@Serializable
data class UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long? = null,
    val percent: Int? = null,
)

/**
 * One transcript line / message. Mirrors the Rust `Message` shape.
 *
 * For outbound sends the SDK fires `MessageAdded` with a provisional
 * `Message(id = "", localId = <uuid>, status = SENDING, ...)` before
 * the wire round-trip. The server-issued `id` lands on the follow-up
 * `MessageUpdated`. The stable lookup key during the sending phase is
 * `localId`; once delivered, both `id` and `localId` are populated.
 */
@Serializable
data class Message(
    val role: MessageRole = MessageRole.EXTERNAL,
    val id: String = "",
    /**
     * SDK-issued temporary id for outbound messages awaiting server
     * confirmation. Set on the provisional `MessageAdded` payload so
     * the consumer can locate the row when `MessageUpdated` arrives.
     * `null` for inbound messages or any message originating on the
     * server.
     */
    val localId: String? = null,
    val text: String? = null,
    val html: String? = null,
    val timestamp: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val errorText: String? = null,
    val status: MessageStatus = MessageStatus.DELIVERED,
    val state: MessageState = MessageState.COMPLETED,
)

/** Payload for [OrigonClient.sendMessage]. Mirrors the Rust `SendMessagePayload` shape. */
@Serializable
data class SendMessagePayload(
    val text: String? = null,
    val html: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Contact(
    val id: String,
    val name: String,
)

/** Element of the array returned by [OrigonClient.getSessions]. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val subject: String,
    val channel: Channel,
    val createdAt: String,
    val updatedAt: String,
    val lastMessage: Message? = null,
    val contact: Contact? = null,
)

/** Returned by [OrigonClient.getSession]. */
@Serializable
data class SessionHistory(
    val history: List<Message> = emptyList(),
    /** Who is currently driving the session. */
    val control: SessionControl = SessionControl.AI,
)

// ── Disconnect / events ──────────────────────────────────────────────

sealed class DisconnectReason {
    data object LocalClose : DisconnectReason()
    data object NetworkLoss : DisconnectReason()
    data object EndpointNotProvisioned : DisconnectReason()
    data object EndpointAlreadyConnected : DisconnectReason()
    data object TokenInvalid : DisconnectReason()
    data object TokenExpired : DisconnectReason()
    data object TokenReplayed : DisconnectReason()
    data object ProtocolViolation : DisconnectReason()
    data object CapabilityMissing : DisconnectReason()
    data object IllegalState : DisconnectReason()
    data object ResourceExhausted : DisconnectReason()
    data object ReplayLost : DisconnectReason()

    /**
     * The server ended the session cleanly (`SESSION_ENDED`, 0x1040):
     * the bridge collapsed (remote leg hung up / engine drained), the
     * controller destroyed the session, or the engine reaped it idle.
     * Terminal — the SDK does not reconnect; a transport close follows.
     */
    data object SessionEnded : DisconnectReason()
    data class ServerClosed(val code: Long, val detail: String?) : DisconnectReason()

    /**
     * Local transport failed before the MOQ session was established
     * (QUIC dial / DNS / TLS / etc.). [detail] carries the underlying
     * error message for diagnostics.
     */
    data class TransportClosed(val detail: String?) : DisconnectReason()

    internal companion object {
        fun fromBridge(kind: Int, serverCode: Long, serverDetail: String?): DisconnectReason =
            when (kind) {
                SessionBridge.DISCONNECT_REASON_LOCAL_CLOSE -> LocalClose
                SessionBridge.DISCONNECT_REASON_NETWORK_LOSS -> NetworkLoss
                SessionBridge.DISCONNECT_REASON_ENDPOINT_NOT_PROVISIONED -> EndpointNotProvisioned
                SessionBridge.DISCONNECT_REASON_ENDPOINT_ALREADY_CONNECTED -> EndpointAlreadyConnected
                SessionBridge.DISCONNECT_REASON_TOKEN_INVALID -> TokenInvalid
                SessionBridge.DISCONNECT_REASON_TOKEN_EXPIRED -> TokenExpired
                SessionBridge.DISCONNECT_REASON_TOKEN_REPLAYED -> TokenReplayed
                SessionBridge.DISCONNECT_REASON_PROTOCOL_VIOLATION -> ProtocolViolation
                SessionBridge.DISCONNECT_REASON_CAPABILITY_MISSING -> CapabilityMissing
                SessionBridge.DISCONNECT_REASON_ILLEGAL_STATE -> IllegalState
                SessionBridge.DISCONNECT_REASON_RESOURCE_EXHAUSTED -> ResourceExhausted
                SessionBridge.DISCONNECT_REASON_REPLAY_LOST -> ReplayLost
                SessionBridge.DISCONNECT_REASON_SESSION_ENDED -> SessionEnded
                SessionBridge.DISCONNECT_REASON_SERVER_CLOSED ->
                    ServerClosed(serverCode, serverDetail)
                SessionBridge.DISCONNECT_REASON_TRANSPORT_CLOSED ->
                    TransportClosed(serverDetail)
                else -> ServerClosed(serverCode, serverDetail)
            }
    }
}

/**
 * Async event from a session. All variants carry [sessionId] so the
 * consumer can demultiplex when several sessions are active at once.
 */
sealed class ClientEvent {
    abstract val sessionId: String

    /**
     * A message was appended to the transcript — outbound provisional
     * (`status == SENDING`), inbound peer message, or future AI
     * message. Store under the key `message.localId ?: message.id`
     * so [MessageUpdated] can find it.
     */
    data class MessageAdded(
        override val sessionId: String,
        val message: Message,
    ) : ClientEvent()

    /**
     * A previously-added message was updated. [id] matches the lookup
     * key the consumer used when the row was added — equal to the
     * provisional's `localId` for outbound ack / failure, or
     * `message.id` for server-driven updates. Always non-empty.
     */
    data class MessageUpdated(
        override val sessionId: String,
        val id: String,
        val message: Message,
    ) : ClientEvent()

    data class SessionUpdated(
        override val sessionId: String,
        val newSessionId: String,
    ) : ClientEvent()

    data class ControlUpdated(
        override val sessionId: String,
        val control: SessionControl,
    ) : ClientEvent()

    data class Typing(
        override val sessionId: String,
        val isTyping: Boolean,
    ) : ClientEvent()

    data class Connected(override val sessionId: String) : ClientEvent()

    data class Reconnecting(
        override val sessionId: String,
        val attempt: Int,
        val reason: DisconnectReason,
    ) : ClientEvent()

    data class Reconnected(override val sessionId: String) : ClientEvent()

    data class PeerAttached(
        override val sessionId: String,
        val peerEndpointId: String,
        val alias: Long,
    ) : ClientEvent()

    data class PeerDetached(
        override val sessionId: String,
        val peerEndpointId: String,
        val alias: Long,
    ) : ClientEvent()

    data class Disconnected(
        override val sessionId: String,
        val reason: DisconnectReason,
    ) : ClientEvent()

    /** Voice-side soft error. `message == null` means a previously-surfaced error has cleared. */
    data class CallError(
        override val sessionId: String,
        val message: String?,
    ) : ClientEvent()

    /**
     * The audio output route changed — the app's [OrigonClient.setAudioOutput]
     * choice or an OS-driven change (e.g. a headset plugged in mid-call). Drive
     * a speaker toggle from `route == AudioOutputRoute.SPEAKER`.
     */
    data class AudioRouteChanged(
        override val sessionId: String,
        val route: AudioOutputRoute,
    ) : ClientEvent()
}
