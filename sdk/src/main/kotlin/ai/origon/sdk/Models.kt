package ai.origon.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Enums ────────────────────────────────────────────────────────────

@Serializable
enum class Channel {
    // `Channel` no longer crosses the native boundary as an int: the split
    // into startCall/startChat + joinCall/joinChat removed the runtime
    // discriminator every signature used to carry. The enum survives only
    // where the SERVER names a channel in a JSON payload, which
    // `kotlinx.serialization` handles from the @SerialName values below.
    @SerialName("chat") CHAT,
    @SerialName("voice") VOICE;

    internal companion object {
        /** Parse the channel name the SERVER puts in a JSON payload. */
        fun fromWire(value: String): Channel = when (value) {
            "chat" -> CHAT
            "voice" -> VOICE
            else -> throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "unknown channel: $value",
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
     * Optional. When omitted, the SDK uses its random, no-backup app-install
     * id as an opaque anonymous id. It is never a hardware/person identity.
     */
    val userId: String? = null,
    /**
     * Initial session-level attributes. Injected as `data.attributes`
     * on `POST /session/start`. Encoded to a JSON string via
     * `kotlinx.serialization` before crossing the native boundary.
     */
    val attributes: JsonObject? = null,
    /** Durable transcript cache is enabled unless explicitly disabled. */
    val chatCachePolicy: ChatCachePolicy = ChatCachePolicy.ENABLED,
)

enum class ChatCachePolicy { ENABLED, DISABLED }

enum class SessionLoadPolicy {
    CACHE_THEN_NETWORK,
    NETWORK_ONLY,
    CACHE_ONLY;

    internal fun toBridge(): Int = when (this) {
        CACHE_THEN_NETWORK -> SessionBridge.LOAD_CACHE_THEN_NETWORK
        NETWORK_ONLY -> SessionBridge.LOAD_NETWORK_ONLY
        CACHE_ONLY -> SessionBridge.LOAD_CACHE_ONLY
    }
}

@Serializable
enum class SessionLoadSource {
    @SerialName("cache") CACHE,
    @SerialName("network") NETWORK,
}

enum class ChatAccessIntent {
    PASSIVE,
    EXPLICIT_NAVIGATION,
    NOTIFICATION;

    internal fun toBridge(): Int = when (this) {
        PASSIVE -> SessionBridge.CHAT_ACCESS_PASSIVE
        EXPLICIT_NAVIGATION -> SessionBridge.CHAT_ACCESS_EXPLICIT_NAVIGATION
        NOTIFICATION -> SessionBridge.CHAT_ACCESS_NOTIFICATION
    }
}

data class StartCallOptions(
    /** Existing session id to resume; null for a new session. */
    val sessionId: String? = null,
    /** Optional consumer-defined raw JSON forwarded as `data` on the wire. */
    val data: String? = null,
)

/**
 * Options for [OrigonClient.startChat].
 *
 * [firstMessage] is REQUIRED, and that is the whole point of the split from
 * the old `startSession`. The server runs a two-stage gate on every chat: the
 * flow does not start until the visitor has actually said something, and a
 * session that stays silent past the deadline is reaped. An API that opened a
 * session and then waited for a human to type was racing that deadline;
 * carrying the message here makes the race unreachable.
 *
 * Attachment-only is valid — the gate fires on ANY visitor content.
 */
data class StartChatOptions(
    /** The visitor's first message. Required. */
    val firstMessage: SendMessagePayload,
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

/**
 * Input for [OrigonClient.joinCall] / [OrigonClient.joinChat] — a
 * previously-obtained [StartSessionResponse]. No channel: the method carries
 * it.
 */
data class JoinInput(
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
 * One option on an interactive flow prompt — [Message.buttons], or a
 * gallery card's own button stack.
 *
 * The visitor answers by sending [SendMessagePayload.value] set to this
 * option's [value]. The server matches on **[value]**, never on [label].
 */
@Serializable
data class MessageButton(
    /**
     * The caption to render. Wire key `label` on this lane — the platform
     * GraphQL read spells the same field `text`, so payloads from that
     * source are not interchangeable with these.
     */
    val label: String = "",
    /** The match key, sent back as [SendMessagePayload.value]. */
    val value: String = "",
    /**
     * Authored kind — `"text"` / `"postback"` / `"url"`. A free string,
     * not an enum: an unknown kind must degrade, not fail to decode.
     *
     * The wire key is **`type`**; without the [SerialName] it would
     * serialize as `buttonType` and decode to empty, silently turning
     * every URL button into a plain postback.
     */
    @SerialName("type") val buttonType: String = "",
)

/** One card in a [Message.gallery] carousel. */
@Serializable
data class MessageCard(
    /**
     * Card heading. Doubles as the gallery match key — a reply sends it
     * as [SendMessagePayload.galleryLabel].
     */
    val title: String = "",
    val description: String = "",
    /**
     * **Nullable, and legitimately so** — the server emits `null` for a
     * card authored without an image. A card with no image is valid, not
     * malformed; guard before rendering.
     */
    val image: Attachment? = null,
    /** This card's own options, same shape as the top-level array. */
    val buttons: List<MessageButton> = emptyList(),
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
    /**
     * Lifecycle action for a `role == SYSTEM` row: `"queued"` | `"joined"`
     * | `"ended"`. Set by connect on lifecycle system messages; absent on
     * ordinary messages and on flow-bot system messages (which keep bubble
     * rendering — the divider discriminator is action-presence, NOT role).
     * Wire key: `action`. Connect owns the vocabulary; a distinct `"left"`
     * was considered and dropped there.
     */
    val action: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /**
     * Interactive prompt options on a flow-authored `role == SYSTEM` row.
     * Empty on every ordinary message — a non-empty list is what makes
     * this message a prompt. Note a prompt carries NO [action], so it
     * renders as a bubble, not a lifecycle divider.
     */
    val buttons: List<MessageButton> = emptyList(),
    /**
     * Gallery-card carousel on a flow-authored `role == SYSTEM` row — the
     * card-shaped sibling of [buttons]. Empty on ordinary messages.
     */
    val gallery: List<MessageCard> = emptyList(),
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
    /**
     * The chosen [MessageButton.value] when this send answers an
     * interactive prompt. The server matches a button on this, falling
     * back to [text] — so [text] must ALSO be set (to the option's
     * label): a body with no text/html/attachments is refused with a 400
     * before the flow ever sees it.
     *
     * Omitted from the wire when null (`encodeDefaults` is off).
     */
    val value: String? = null,
    /**
     * The picked [MessageCard.title] when answering a gallery prompt.
     * The server matches a gallery pick on the PAIR
     * `(galleryLabel, value)`, which is what disambiguates two cards
     * sharing a button value. Leave null for a plain button reply.
     */
    val galleryLabel: String? = null,
)

@Serializable
data class Contact(
    val id: String,
    val name: String,
)

/** Element of a session-directory snapshot. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val subject: String,
    val channel: Channel,
    /** Live only on the cx owner that served this directory row. */
    val active: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val lastMessage: Message? = null,
    val contact: Contact? = null,
)

enum class RestoreStatus {
    CONNECTED,
    ALREADY_CONNECTED,
    ACTIVE_ELSEWHERE,
    NO_LONGER_ACTIVE,
    FAILED,
}

internal fun restoreStatus(value: Int): RestoreStatus = when (value) {
    0 -> RestoreStatus.CONNECTED
    1 -> RestoreStatus.ALREADY_CONNECTED
    2 -> RestoreStatus.ACTIVE_ELSEWHERE
    3 -> RestoreStatus.NO_LONGER_ACTIVE
    else -> RestoreStatus.FAILED
}

data class RestoreResult(
    val sessionId: String,
    val status: RestoreStatus,
    val error: String? = null,
)

/** Authoritative transcript history carried by a session snapshot. */
@Serializable
data class SessionHistory(
    val history: List<Message> = emptyList(),
    /** Who is currently driving the session. */
    val control: SessionControl = SessionControl.AI,
)

@Serializable
data class SessionSnapshot(
    val source: SessionLoadSource,
    val authoritative: Boolean,
    val refreshedAt: Long,
    val session: SessionHistory,
)

@Serializable
data class SessionsSnapshot(
    val source: SessionLoadSource,
    val authoritative: Boolean,
    val refreshedAt: Long,
    val sessions: List<SessionSummary>,
)

sealed interface SessionLoadUpdate {
    data class Snapshot(val value: SessionSnapshot) : SessionLoadUpdate
    data class RefreshFailed(
        val error: SessionException,
        val cachedSnapshotEmitted: Boolean,
    ) : SessionLoadUpdate
}

sealed interface SessionsLoadUpdate {
    data class Snapshot(val value: SessionsSnapshot) : SessionsLoadUpdate
    data class RefreshFailed(
        val error: SessionException,
        val cachedSnapshotEmitted: Boolean,
    ) : SessionsLoadUpdate
}

// ── Disconnect / events ──────────────────────────────────────────────

/**
 * After-Call-Work offer carried on a chat [ClientEvent.ChatSessionEnded]
 * for an **agent** participant (the wrap-up screen). Absent for the widget
 * / visitor side. Wire keys mirror connect's chat `sessionEnded.acw` block.
 */
@Serializable
data class Acw(
    /** Always `true` when the block is present — its presence is the signal. */
    val enabled: Boolean = false,
    /** Wrap-up window in seconds. `0` ⇒ open-ended server-side. */
    val duration: Long = 0,
    /** The agent cannot finish wrap-up without a disposition. */
    val enforce: Boolean = false,
    /** RFC3339 instant the agent entered ACW. Wire key: `startedAt`. */
    val startedAt: String? = null,
    /** The team's disposition tags — the wrap-up chips. */
    val dispositions: List<String> = emptyList(),
)

/** Decoded shape of the `EVENT_CHAT_SESSION_ENDED` `messageJson` slot. */
@Serializable
internal data class ChatSessionEndedPayload(
    val reason: String = "",
    val acw: Acw? = null,
)

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

    /**
     * The chat session ended cleanly by an explicit server signal
     * (connect's chat `sessionEnded` SSE frame) — distinct from a
     * transport-level [Disconnected], which does NOT follow it. [acw]
     * carries the agent wrap-up offer; `null` for the widget / visitor
     * side. Android ships no chat UI today — surfaced for wire parity.
     */
    data class ChatSessionEnded(
        override val sessionId: String,
        val reason: String,
        val acw: Acw? = null,
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
