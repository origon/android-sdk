package ai.origon.sdk

import ai.origon.sdk.bridge.AttachmentPolicy
import ai.origon.sdk.bridge.SessionEvent
import ai.origon.sdk.bridge.StartSessionResponse

/**
 * Native-bridge progress sink for [SessionBridge.uploadAttachment].
 *
 * **Threading.** Fires on a Rust worker thread; the JNI bridge
 * attaches the thread to the JVM via `AttachCurrentThreadAsDaemon`
 * before invoking [onProgress]. Implementations must not touch UI
 * state directly — dispatch to the main thread (or use the
 * higher-level [OrigonClient.uploadAttachment] which exposes a
 * coroutine `Flow`).
 *
 * **ABI-locked.** The Rust JNI bridge looks up `onProgress` by name
 * with signature `(JJ)V`. Don't rename or change the parameter types.
 */
fun interface UploadProgressCallback {
    /**
     * @param uploaded bytes transferred so far.
     * @param total total bytes expected, or `-1` when unknown.
     */
    fun onProgress(uploaded: Long, total: Long)
}

/**
 * JNI bridge to the Rust `session` crate.
 *
 * The Rust counterpart lives at `client-sdk/session/src/jni_bridge.rs`
 * and is compiled into `libsession.so` with `--features jni` (see
 * `client-sdk/session/scripts/build-android.sh`). `System.loadLibrary`
 * resolves to that .so via `jniLibs/<abi>/libsession.so`.
 *
 * **ABI-locked.** Method names + signatures here are mirrored by the
 * `Java_ai_origon_sdk_SessionBridge_<methodName>` exports on the Rust
 * side. Renaming here without renaming there will produce
 * `UnsatisfiedLinkError` at first call.
 *
 * Internal — consumers go through `OrigonClient`, which wraps this
 * bridge with Kotlin-idiomatic error handling, coroutines, etc.
 */
internal object SessionBridge {

    init {
        System.loadLibrary("session")
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @JvmStatic external fun initLogging(filter: String?)

    /**
     * Returns a non-zero opaque handle on success; throws
     * [SessionException] otherwise. Caller owns the handle and must
     * eventually call [destroy].
     */
    @JvmStatic external fun initialize(
        endpoint: String,
        bundleId: String?,
        token: String?,
        userId: String?,
        deviceId: String?,
        attributesJson: String?,
    ): Long

    @JvmStatic external fun destroy(handle: Long)

    /**
     * Replace session-level attributes injected as `data.attributes` on
     * subsequent `startSession` calls. Null or empty clears.
     */
    @JvmStatic external fun setAttributes(handle: Long, attributesJson: String?)

    // ── Local getters (read from cached /config body) ────────────────

    @JvmStatic external fun getStartMessage(handle: Long): String
    @JvmStatic external fun isMultipleChannelsAllowed(handle: Long): Boolean
    @JvmStatic external fun isChatEnabled(handle: Long): Boolean
    @JvmStatic external fun isCallEnabled(handle: Long): Boolean
    @JvmStatic external fun getAttachmentPolicy(handle: Long): AttachmentPolicy

    // ── Session history fetchers ─────────────────────────────────────

    /** `GET /sessions`. Returns the response body as a JSON string. */
    @JvmStatic external fun getSessions(handle: Long): String

    /** `GET /session/<id>`. Returns the response body as a JSON string. */
    @JvmStatic external fun getSession(handle: Long, id: String): String

    // ── Per-session lifecycle ────────────────────────────────────────

    /**
     * `POST /session/start` — opens a new session, dials its transport.
     * Throws [SessionException] on failure; returns the new session's
     * id, transport URL, and per-session auth token on success.
     *
     * @param sessionId pass an existing session id to resume; null to create new.
     * @param dataJson optional consumer-defined raw JSON payload.
     */
    @JvmStatic external fun startSession(
        handle: Long,
        channel: Int,
        sessionId: String?,
        dataJson: String?,
    ): StartSessionResponse

    /**
     * Attach to a session whose start-session response was obtained out
     * of band (multi-device handoff, deeplink, persisted session).
     * Skips the HTTPS call and dials the transport directly.
     */
    @JvmStatic external fun joinSession(
        handle: Long,
        channel: Int,
        sessionId: String,
        url: String,
        token: String,
    )

    @JvmStatic external fun endSession(handle: Long, id: String)
    @JvmStatic external fun endAllSessions(handle: Long)

    // ── Push notifications ───────────────────────────────────────────

    /**
     * `POST /push/register`. [token] is the FCM token; [provider] is
     * `"fcm"`; [environment] is unused for FCM and passed as null.
     * No-ops when the client was created without a device id.
     */
    @JvmStatic external fun registerPush(
        handle: Long,
        token: String,
        provider: String,
        environment: String?,
    )

    /** `POST /push/unregister`. No-ops when there is no device id. */
    @JvmStatic external fun unregisterPush(handle: Long)

    // ── Voice controls ───────────────────────────────────────────────

    @JvmStatic external fun setMute(handle: Long, id: String, muted: Boolean)
    @JvmStatic external fun setMuteAll(handle: Long, muted: Boolean)

    /** Override the audio output route. [route] is one of `AUDIO_OUTPUT_*`. */
    @JvmStatic external fun setAudioOutput(handle: Long, route: Int)

    // ── Chat ─────────────────────────────────────────────────────────

    /**
     * POST `<sessionUrl>/message`. `payloadJson` is a JSON-encoded
     * [SendMessagePayload]; pass `null` for an empty payload. Returns
     * the server-issued [Message] as a JSON string for the high-level
     * wrapper to decode.
     */
    @JvmStatic external fun sendMessage(handle: Long, id: String, payloadJson: String?): String

    /** Register a keystroke. The SDK debounces outbound `/typing` POSTs. */
    @JvmStatic external fun notifyTyping(handle: Long, id: String)

    /** Force outbound typing state to "off" immediately. */
    @JvmStatic external fun stopTyping(handle: Long, id: String)

    // ── Attachments ──────────────────────────────────────────────────

    /**
     * Upload `bytes` as an attachment on the named session. MIME is
     * auto-detected by the SDK from the content + [name]; the caller
     * does not pass a content type.
     *
     * **Path-based, streamed from disk.** The SDK opens the file at
     * [path] off-thread (smol `blocking`) inside its own process and
     * streams it through lumen's multipart encoder — the body is
     * never fully resident in memory, safe for arbitrarily large
     * files. Blocking — performs the HTTPS multipart POST on the
     * calling thread. Use from [Dispatchers.IO] (the [OrigonClient]
     * wrapper does this automatically).
     *
     * [path] must be a filesystem location the process can open
     * directly. `context.cacheDir` / `context.filesDir` paths work
     * without permissions. `content://` URIs are NOT openable —
     * callers must copy them into one of those directories first
     * (the high-level `OrigonClient.uploadAttachment(uri, ...)`
     * convenience wrapper does this automatically).
     *
     * [uploadId] is a caller-supplied opaque correlation key. Pass the
     * same value to [deleteAttachment] (as the `key` argument) to
     * cancel this upload before it completes. After upload completes
     * successfully, use the server-issued `attachment.id` for deletion
     * instead. The pair (`sessionId`, `uploadId`) must be unique
     * across active uploads.
     *
     * [progressCb] is optional. When provided, its `onProgress` fires
     * from a Rust worker thread (see [UploadProgressCallback]).
     *
     * Returns the server-issued [Attachment] as a JSON string. Throws
     * [SessionException] with `kind = ERROR_OTHER` for filesystem
     * errors (ENOENT, EACCES, etc.), `kind = ERROR_ATTACHMENT` for
     * precheck failures (`empty_file`, `policy_unsupported_type`,
     * `policy_type_disabled`, `policy_too_large`), `kind = ERROR_HTTP`
     * / `ERROR_SERVER_UNAVAILABLE` for wire failures, or `kind =
     * ERROR_CANCELLED` when cancelled via [deleteAttachment].
     */
    @JvmStatic external fun uploadAttachment(
        handle: Long,
        sessionId: String,
        uploadId: String,
        path: String,
        name: String,
        progressCb: UploadProgressCallback?,
    ): String

    /**
     * Cancel an in-flight upload or delete a completed attachment.
     * `key` is dual-purpose: matched against the SDK's in-flight
     * upload table (keyed by `uploadId`) first; if found, the upload
     * is cancelled with no network call. Otherwise `key` is treated
     * as a server-issued `attachment.id` and the SDK calls
     * `DELETE <sessionUrl>/attachment/:key`. Blocking — use from
     * [Dispatchers.IO].
     */
    @JvmStatic external fun deleteAttachment(
        handle: Long,
        sessionId: String,
        key: String,
    )

    // ── Active sessions snapshot ─────────────────────────────────────

    /**
     * Returns `[[id, "voice"|"chat"], ...]` — one inner array per
     * active session. Empty array if none.
     */
    @JvmStatic external fun activeSessionIds(handle: Long): Array<Array<String>>

    // ── Event polling ────────────────────────────────────────────────

    /**
     * Non-blocking. Returns null if no event is ready; otherwise a
     * populated [SessionEvent] whose `kind` field selects which
     * variant-specific fields are meaningful.
     */
    @JvmStatic external fun pollEvent(handle: Long): SessionEvent?

    // ── Discriminant constants (mirrored from Rust) ──────────────────

    // Channel — see SessionBridge.startSession(channel: Int).
    const val CHANNEL_CHAT = 0
    const val CHANNEL_VOICE = 1

    // Audio output route — see SessionBridge.setAudioOutput(route: Int).
    const val AUDIO_OUTPUT_DEFAULT = 0
    const val AUDIO_OUTPUT_SPEAKER = 1
    const val AUDIO_OUTPUT_BLUETOOTH = 2

    // SessionControl — value of SessionEvent.control on CONTROL_UPDATED.
    const val CONTROL_AI = 0
    const val CONTROL_USER = 1

    // Event discriminants — value of SessionEvent.kind.
    const val EVENT_MESSAGE_ADDED = 1
    const val EVENT_MESSAGE_UPDATED = 2
    const val EVENT_SESSION_UPDATED = 3
    const val EVENT_CONTROL_UPDATED = 4
    const val EVENT_TYPING = 6
    const val EVENT_CONNECTED = 7
    const val EVENT_RECONNECTING = 8
    const val EVENT_RECONNECTED = 9
    const val EVENT_PEER_ATTACHED = 10
    const val EVENT_PEER_DETACHED = 11
    const val EVENT_DISCONNECTED = 12
    const val EVENT_CALL_ERROR = 13
    const val EVENT_AUDIO_ROUTE_CHANGED = 14

    /** Chat session ended cleanly by server signal — `messageJson` carries
     *  the `{reason, acw?}` payload as JSON. No `EVENT_DISCONNECTED`
     *  follows. Mirrors `SESSION_EVENT_CHAT_SESSION_ENDED` in `jni_bridge.rs`. */
    const val EVENT_CHAT_SESSION_ENDED = 15

    // Error discriminants — value of SessionException.kind.
    const val ERROR_NOT_INITIALIZED = 1
    const val ERROR_NO_SESSION = 2
    const val ERROR_SESSION = 3
    const val ERROR_MISSING_FIELD = 4
    const val ERROR_SERVER_UNAVAILABLE = 5
    const val ERROR_HTTP = 6
    const val ERROR_ATTACHMENT = 7
    const val ERROR_OTHER = 8
    /** Upload was cancelled via `deleteAttachment(...)` using the same
     *  `uploadId` passed to `uploadAttachment(...)`. Only fires on
     *  `uploadAttachment`. */
    const val ERROR_CANCELLED = 9

    // Disconnect reason discriminants — value of SessionEvent.disconnectReasonKind.
    const val DISCONNECT_REASON_LOCAL_CLOSE = 1
    const val DISCONNECT_REASON_NETWORK_LOSS = 2
    const val DISCONNECT_REASON_ENDPOINT_NOT_PROVISIONED = 3
    const val DISCONNECT_REASON_ENDPOINT_ALREADY_CONNECTED = 4
    const val DISCONNECT_REASON_TOKEN_INVALID = 5
    const val DISCONNECT_REASON_TOKEN_EXPIRED = 6
    const val DISCONNECT_REASON_TOKEN_REPLAYED = 7
    const val DISCONNECT_REASON_PROTOCOL_VIOLATION = 8
    const val DISCONNECT_REASON_CAPABILITY_MISSING = 9
    const val DISCONNECT_REASON_ILLEGAL_STATE = 10
    const val DISCONNECT_REASON_RESOURCE_EXHAUSTED = 11
    const val DISCONNECT_REASON_REPLAY_LOST = 12
    const val DISCONNECT_REASON_SERVER_CLOSED = 13
    const val DISCONNECT_REASON_TRANSPORT_CLOSED = 14
    /** Server ended the session (`SESSION_ENDED`, 0x1040) — bridge collapse,
     *  controller destroy, or idle-GC reap. Terminal; a transport close
     *  follows. Mirrors `SESSION_DISCONNECT_REASON_SESSION_ENDED` in the
     *  Rust JNI bridge (`apps/sdk/session/src/jni_bridge.rs`). */
    const val DISCONNECT_REASON_SESSION_ENDED = 15
}
