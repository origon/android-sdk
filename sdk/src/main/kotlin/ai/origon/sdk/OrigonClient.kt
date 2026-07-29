package ai.origon.sdk

import ai.origon.sdk.bridge.SessionEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The primary interface to the Origon platform on Android.
 *
 * Backed by `libsession.so` via [SessionBridge]. One instance owns one
 * native handle and one smol executor; create at app start, call
 * [close] (or use `use { }`) at app shutdown.
 *
 * All fallible methods throw [SessionException] with a structured
 * `kind` / `statusCode` / `code` / `message`.
 */
class OrigonClient(
    context: android.content.Context,
    config: ClientConfig,
) : AutoCloseable {

    private val appContext: android.content.Context = context.applicationContext

    /**
     * Stable per-install device identifier (`Settings.Secure.ANDROID_ID`).
     * Sent as `deviceId` on push register/unregister, and used as the
     * `userId` fallback when the consumer omits one.
     */
    private val deviceId: String? = resolveDeviceId(appContext)

    private val handle: Long = SessionBridge.initialize(
        endpoint = config.endpoint,
        bundleId = appContext.packageName,
        token = config.token,
        // `userId` is optional at the SDK surface but required by the
        // core. Fall back to the device id so anonymous users still get
        // a stable identity; fail fast if neither is available.
        userId = config.userId ?: deviceId ?: throw SessionException(
            kind = SessionBridge.ERROR_MISSING_FIELD,
            statusCode = 0,
            code = "user_id",
            message = "userId was not provided and no device identifier is available",
        ),
        deviceId = deviceId,
        attributesJson = config.attributes?.let { JSON.encodeToString(JsonObject.serializer(), it) },
    )
    private val closed = AtomicBoolean(false)

    init {
        // SessionBridge.initialize throws SessionException on failure;
        // a zero handle without an exception would be a bridge bug.
        if (handle == 0L) {
            throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "session bridge returned null handle",
            )
        }
        // Become the active client for push registration and flush any
        // token buffered before this client existed. See Push.kt.
        PushRegistrar.attach(this)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Detach first so the registrar won't start a new
            // registration against a handle we're about to destroy.
            PushRegistrar.detach(this)
            SessionBridge.destroy(handle)
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw SessionException(
                kind = SessionBridge.ERROR_NOT_INITIALIZED,
                statusCode = 0,
                code = null,
                message = "client has been closed",
            )
        }
    }

    // ── Cached /config getters ───────────────────────────────────────

    /** Pre-populated first assistant message configured for the tenant. */
    val startMessage: String
        get() {
            ensureOpen()
            return SessionBridge.getStartMessage(handle)
        }

    val isChatEnabled: Boolean
        get() {
            ensureOpen()
            return SessionBridge.isChatEnabled(handle)
        }

    val isCallEnabled: Boolean
        get() {
            ensureOpen()
            return SessionBridge.isCallEnabled(handle)
        }

    /** True when chat and voice may share one session. */
    val multipleChannels: Boolean
        get() {
            ensureOpen()
            return SessionBridge.isMultipleChannelsAllowed(handle)
        }

    val attachmentPolicy: AttachmentPolicy
        get() {
            ensureOpen()
            val raw = SessionBridge.getAttachmentPolicy(handle)
            return AttachmentPolicy(
                images = AttachmentRule(raw.images.enabled, raw.images.maxSize),
                documents = AttachmentRule(raw.documents.enabled, raw.documents.maxSize),
                videos = AttachmentRule(raw.videos.enabled, raw.videos.maxSize),
                audio = AttachmentRule(raw.audio.enabled, raw.audio.maxSize),
            )
        }

    val serverConfig: ServerConfig
        get() = ServerConfig(
            startMessage = startMessage,
            multipleChannels = multipleChannels,
            isChatEnabled = isChatEnabled,
            isCallEnabled = isCallEnabled,
            attachmentPolicy = attachmentPolicy,
        )

    /**
     * Replace session-level attributes injected as `data.attributes` on
     * subsequent [startCall] / [startChat] calls. Pass null to clear.
     */
    fun setAttributes(attributes: JsonObject?) {
        ensureOpen()
        val json = attributes?.let { JSON.encodeToString(JsonObject.serializer(), it) }
        SessionBridge.setAttributes(handle, json)
    }

    // ── Session history ──────────────────────────────────────────────

    /** `GET /sessions` — prior sessions for the configured `userId`. */
    fun getSessions(): List<SessionSummary> {
        ensureOpen()
        val body = SessionBridge.getSessions(handle)
        return try {
            JSON.decodeFromString(body)
        } catch (e: Throwable) {
            throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "decode getSessions: ${e.message}",
            )
        }
    }

    /** `GET /session/<id>` — history for one session. */
    fun getSession(id: String): SessionHistory {
        ensureOpen()
        val body = SessionBridge.getSession(handle, id)
        return try {
            JSON.decodeFromString(body)
        } catch (e: Throwable) {
            throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "decode getSession: ${e.message}",
            )
        }
    }

    // ── Session lifecycle ────────────────────────────────────────────

    /**
     * Start a **voice call**. Posts `/session/start` and brings the media
     * plane up.
     *
     * **Returning does not mean the media plane is connected.** The MoQ dial
     * runs in the background: connect success arrives as a
     * `ClientEvent.Connected` and a dial failure as a
     * `ClientEvent.Disconnected` (`TransportClosed`) on the event stream —
     * *not* as a thrown [SessionException]. Calling [endSession] with the
     * returned id while still dialing cancels the in-flight dial. Throws only
     * for the `/session/start` HTTP failure or a malformed request.
     */
    fun startCall(options: StartCallOptions): StartSessionResponse {
        ensureOpen()
        val raw = SessionBridge.startCall(
            handle = handle,
            sessionId = options.sessionId,
            dataJson = options.data,
        )
        return StartSessionResponse(
            sessionId = raw.sessionId,
            url = raw.url,
            token = raw.token,
        )
    }

    /**
     * Start a **chat**, sending the visitor's first message as part of the
     * call.
     *
     * The first message is required — see [StartChatOptions] for why. The
     * session id comes back BEFORE the message is sent, so the provisional
     * `MessageAdded` event always has a session to belong to.
     *
     * A first message that fails to DELIVER does not throw: the session is
     * live and the failure arrives as `MessageUpdated` with `status = FAILED`,
     * so the user can retry. Only a TERMINAL refusal (the session is already
     * gone) throws — returning normally would leave the app rendering a
     * composer on a dead conversation.
     */
    fun startChat(options: StartChatOptions): StartSessionResponse {
        ensureOpen()
        val firstJson =
            JSON.encodeToString(SendMessagePayload.serializer(), options.firstMessage)
        val raw = SessionBridge.startChat(
            handle = handle,
            firstMessageJson = firstJson,
            sessionId = options.sessionId,
            dataJson = options.data,
        )
        return StartSessionResponse(
            sessionId = raw.sessionId,
            url = raw.url,
            token = raw.token,
        )
    }

    /**
     * Attach to a **voice call** whose [StartSessionResponse] was obtained out
     * of band (multi-device handoff, deeplink, persisted session).
     *
     * Like [startCall], the MoQ dial runs in the background — returning here
     * does not mean it is connected; await the `Connected` / `Disconnected`
     * event.
     */
    fun joinCall(input: JoinInput) {
        ensureOpen()
        SessionBridge.joinCall(
            handle = handle,
            sessionId = input.sessionId,
            url = input.url,
            token = input.token,
        )
    }

    /**
     * Attach to an existing **chat** obtained out of band — the agent /
     * chat-offered path. Completes the attach before returning.
     *
     * Takes no first message, unlike [startChat]: joining is entering a room
     * whose first-message gate is ALREADY released — the visitor has spoken,
     * which is why this participant is being offered the conversation — so
     * there is no deadline left to race.
     */
    fun joinChat(input: JoinInput) {
        ensureOpen()
        SessionBridge.joinChat(
            handle = handle,
            sessionId = input.sessionId,
            url = input.url,
            token = input.token,
        )
    }

    fun endSession(id: String) {
        ensureOpen()
        SessionBridge.endSession(handle, id)
    }

    fun endAllSessions() {
        ensureOpen()
        SessionBridge.endAllSessions(handle)
    }

    // ── Push notifications ───────────────────────────────────────────
    // The public, buffering entry points are the companion-object
    // `registerForPushNotifications` / `unregisterForPushNotifications`.
    // These instance methods are the blocking JNI calls they dispatch to.

    /** Blocking JNI call — invoked off the main thread by [PushRegistrar]. */
    internal fun registerPush(token: String, provider: String, environment: String?) {
        ensureOpen()
        SessionBridge.registerPush(handle, token, provider, environment)
    }

    /** Blocking JNI call — invoked off the main thread by [PushRegistrar]. */
    internal fun unregisterPush() {
        ensureOpen()
        SessionBridge.unregisterPush(handle)
    }

    /** Snapshot of every active session. */
    fun activeSessions(): List<ActiveSession> {
        ensureOpen()
        val raw = SessionBridge.activeSessionIds(handle)
        return raw.map { row ->
            ActiveSession(sessionId = row[0], channel = Channel.fromWire(row[1]))
        }
    }

    // ── Voice controls ───────────────────────────────────────────────

    fun setMute(id: String, muted: Boolean) {
        ensureOpen()
        SessionBridge.setMute(handle, id, muted)
    }

    fun setMuteAll(muted: Boolean) {
        ensureOpen()
        SessionBridge.setMuteAll(handle, muted)
    }

    /**
     * Override the audio output route (speaker / receiver / Bluetooth).
     *
     * Process-global — affects the app's single active voice session, so it
     * takes no session id. A no-op when no call is active. UI typically wraps
     * this as a boolean speaker toggle ([AudioOutputRoute.SPEAKER] /
     * [AudioOutputRoute.AUTOMATIC]).
     *
     * May block while the audio output stream is reopened; call it off the main
     * thread.
     */
    fun setAudioOutput(route: AudioOutputRoute) {
        ensureOpen()
        SessionBridge.setAudioOutput(handle, route.toBridge())
    }

    // ── Chat ─────────────────────────────────────────────────────────

    /**
     * Chat-only — send a text / HTML message on the named session.
     *
     * Requires an active chat session for [id] (call [startChat]
     * first). The SDK fires [ClientEvent.MessageAdded] (provisional,
     * `status == SENDING`) before the wire round-trip and
     * [ClientEvent.MessageUpdated] (delivered or failed) after — both
     * surface on [pollEvent]. Returns the server-issued [Message].
     */
    fun sendMessage(id: String, payload: SendMessagePayload): Message {
        ensureOpen()
        val payloadJson = JSON.encodeToString(SendMessagePayload.serializer(), payload)
        val responseJson = SessionBridge.sendMessage(handle, id, payloadJson)
        return JSON.decodeFromString(Message.serializer(), responseJson)
    }

    /**
     * Chat-only — register a keystroke on the named session. Cheap to
     * call from a `TextWatcher`; the SDK debounces outbound
     * `<sessionUrl>/typing` POSTs so only one wire call fires per
     * typing burst.
     */
    fun notifyTyping(id: String) {
        ensureOpen()
        SessionBridge.notifyTyping(handle, id)
    }

    /**
     * Chat-only — force outbound typing state to "off" immediately,
     * cancelling any in-flight debounce. UI fires this on empty-text
     * transitions; the SDK also fires it implicitly on [sendMessage]
     * and on [endSession].
     */
    fun stopTyping(id: String) {
        ensureOpen()
        SessionBridge.stopTyping(handle, id)
    }

    // ── Attachments ──────────────────────────────────────────────────

    /**
     * Upload a file from the local filesystem to the named session and
     * return the server-issued [Attachment]. The SDK streams the body
     * straight from disk; auto-detects MIME from a 256-byte head plus
     * the [fileName] extension. Runs on [Dispatchers.IO].
     *
     * [uploadId] doubles as the cancellation key — pass it as
     * `attachmentId` to [deleteAttachment] while the upload is in
     * flight to abort it (throws with `kind = ERROR_CANCELLED`). After
     * completion, use the server-issued `attachment.id` for deletion.
     *
     * [onProgress] fires from a JNI worker thread; hop to the main
     * thread before touching UI state. `percent` is `null` when the
     * total size is unknown.
     *
     * Throws [SessionException]: `ERROR_OTHER` for filesystem errors,
     * `ERROR_ATTACHMENT` for precheck failures (`empty_file`,
     * `policy_unsupported_type`, `policy_type_disabled`,
     * `policy_too_large`), `ERROR_HTTP` / `ERROR_SERVER_UNAVAILABLE`
     * for wire failures, `ERROR_CANCELLED` when cancelled.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        path: String,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
        ensureOpen()
        val callback = onProgress?.let { cb ->
            UploadProgressCallback { uploaded, total ->
                val totalOpt: Long? = if (total < 0) null else total
                val percent: Int? = totalOpt?.let {
                    (uploaded * 100 / it.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                }
                cb(UploadProgress(uploaded, totalOpt, percent))
            }
        }
        val json = withContext(Dispatchers.IO) {
            SessionBridge.uploadAttachment(
                handle,
                sessionId,
                uploadId,
                path,
                fileName,
                callback,
            )
        }
        return JSON.decodeFromString(Attachment.serializer(), json)
    }

    /**
     * Convenience overload that copies a [content://] (or `file://` /
     * `android.resource://`) [uri] into the app's cache dir before
     * uploading, then deletes the cache file once the upload settles.
     * The SDK can't open `content://` URIs directly.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        uri: android.net.Uri,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
        ensureOpen()
        val tempFile = withContext(Dispatchers.IO) {
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            val out = java.io.File.createTempFile("upload-", suffix, appContext.cacheDir)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw SessionException(
                SessionBridge.ERROR_OTHER,
                0,
                null,
                "uploadAttachment: could not open content URI $uri",
            )
            out
        }
        return try {
            uploadAttachment(
                sessionId = sessionId,
                path = tempFile.absolutePath,
                fileName = fileName,
                uploadId = uploadId,
                onProgress = onProgress,
            )
        } finally {
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }

    /**
     * Convenience overload for in-memory [bytes]: writes them to the
     * app's cache dir first, then delegates to the path-based overload.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        bytes: ByteArray,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
        ensureOpen()
        val tempFile = withContext(Dispatchers.IO) {
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            val out = java.io.File.createTempFile("upload-", suffix, appContext.cacheDir)
            out.outputStream().use { it.write(bytes) }
            out
        }
        return try {
            uploadAttachment(
                sessionId = sessionId,
                path = tempFile.absolutePath,
                fileName = fileName,
                uploadId = uploadId,
                onProgress = onProgress,
            )
        } finally {
            withContext(Dispatchers.IO) { tempFile.delete() }
        }
    }

    /**
     * Cancel an in-flight upload or delete a completed attachment on
     * the named session.
     *
     * `attachmentId` is dual-purpose: it can be either the `uploadId`
     * passed to [uploadAttachment] (cancels the in-flight upload — no
     * network call, the upload's awaiter throws [SessionException]
     * with `kind = SessionBridge.ERROR_CANCELLED`) or the server-issued
     * `attachment.id` of a completed upload (issues `DELETE` on the
     * server). The SDK figures it out: it checks its in-flight uploads
     * table first, then falls through to the wire call.
     *
     * Runs on [Dispatchers.IO]. A 404 from the server surfaces as
     * [SessionException] with `kind = SessionBridge.ERROR_HTTP` and
     * `statusCode == 404` — safe to treat as success when your intent
     * was "remove the draft from the UI".
     */
    suspend fun deleteAttachment(sessionId: String, attachmentId: String) {
        ensureOpen()
        withContext(Dispatchers.IO) {
            SessionBridge.deleteAttachment(handle, sessionId, attachmentId)
        }
    }

    // ── Events ───────────────────────────────────────────────────────

    /** Polls the next event. Returns null when the queue is idle. */
    fun pollEvent(): ClientEvent? {
        ensureOpen()
        val raw = SessionBridge.pollEvent(handle) ?: return null
        return mapEvent(raw)
    }

    private fun mapEvent(raw: SessionEvent): ClientEvent? {
        val sid = raw.sessionId ?: return null
        return when (raw.kind) {
            SessionBridge.EVENT_MESSAGE_ADDED -> {
                val msg = decodeMessage(raw.messageJson) ?: return null
                ClientEvent.MessageAdded(sid, msg)
            }

            SessionBridge.EVENT_MESSAGE_UPDATED -> {
                val msg = decodeMessage(raw.messageJson) ?: return null
                ClientEvent.MessageUpdated(sid, raw.updateId.orEmpty(), msg)
            }

            SessionBridge.EVENT_SESSION_UPDATED ->
                ClientEvent.SessionUpdated(sid, raw.newSessionId.orEmpty())

            SessionBridge.EVENT_CONTROL_UPDATED ->
                ClientEvent.ControlUpdated(sid, SessionControl.fromBridge(raw.control))

            SessionBridge.EVENT_TYPING ->
                ClientEvent.Typing(sid, raw.typing)

            SessionBridge.EVENT_CHAT_SESSION_ENDED -> {
                val payload = decodeSessionEnded(raw.messageJson)
                ClientEvent.ChatSessionEnded(sid, payload.reason, payload.acw)
            }

            SessionBridge.EVENT_CONNECTED ->
                ClientEvent.Connected(sid)

            SessionBridge.EVENT_RECONNECTING ->
                ClientEvent.Reconnecting(
                    sessionId = sid,
                    attempt = raw.reconnectAttempt,
                    reason = DisconnectReason.fromBridge(
                        raw.disconnectReasonKind,
                        raw.disconnectReasonServerCode,
                        raw.disconnectReasonServerDetail,
                    ),
                )

            SessionBridge.EVENT_RECONNECTED ->
                ClientEvent.Reconnected(sid)

            SessionBridge.EVENT_PEER_ATTACHED ->
                ClientEvent.PeerAttached(sid, raw.peerEndpointId.orEmpty(), raw.peerAlias)

            SessionBridge.EVENT_PEER_DETACHED ->
                ClientEvent.PeerDetached(sid, raw.peerEndpointId.orEmpty(), raw.peerAlias)

            SessionBridge.EVENT_DISCONNECTED ->
                ClientEvent.Disconnected(
                    sessionId = sid,
                    reason = DisconnectReason.fromBridge(
                        raw.disconnectReasonKind,
                        raw.disconnectReasonServerCode,
                        raw.disconnectReasonServerDetail,
                    ),
                )

            SessionBridge.EVENT_CALL_ERROR ->
                ClientEvent.CallError(
                    sessionId = sid,
                    message = if (raw.callErrorPresent) raw.callErrorMessage else null,
                )

            SessionBridge.EVENT_AUDIO_ROUTE_CHANGED ->
                ClientEvent.AudioRouteChanged(
                    sessionId = sid,
                    route = AudioOutputRoute.fromBridge(raw.audioRoute),
                )

            else -> null
        }
    }

    /**
     * Decode the bridge's `messageJson` field into a typed [Message].
     * Returns `null` on any parse failure (caller treats as "drop the
     * event"). Lenient — unknown keys are ignored so SDK-vs-server
     * schema drift doesn't lose the whole event.
     */
    private fun decodeMessage(json: String?): Message? {
        if (json.isNullOrEmpty()) return null
        return try {
            JSON.decodeFromString(Message.serializer(), json)
        } catch (e: Throwable) {
            // Drop the event but leave a breadcrumb: a silent drop here
            // makes messages vanish with no diagnostics if the SDK and
            // server schemas drift. Log only the exception — never the
            // raw JSON, which contains message content.
            android.util.Log.w("OrigonSDK", "decodeMessage: dropping event, JSON parse failed: ${e.message}")
            null
        }
    }

    /**
     * Decode the `messageJson` slot for [ClientEvent.ChatSessionEnded]
     * (`{reason, acw?}`). Returns a default payload (empty reason, no acw)
     * on any parse failure — the chat session is over regardless, so this
     * event must still surface.
     */
    private fun decodeSessionEnded(json: String?): ChatSessionEndedPayload {
        if (json.isNullOrEmpty()) return ChatSessionEndedPayload()
        return try {
            JSON.decodeFromString(ChatSessionEndedPayload.serializer(), json)
        } catch (e: Throwable) {
            android.util.Log.w("OrigonSDK", "decodeSessionEnded: parse failed, defaulting: ${e.message}")
            ChatSessionEndedPayload()
        }
    }

    companion object {
        /**
         * Install the global tracing subscriber. Idempotent — only the
         * first call installs; subsequent calls are no-ops.
         *
         * `filter` accepts `RUST_LOG`-style directives. Pass null for
         * the SDK default.
         */
        fun initLogging(filter: String? = null) {
            SessionBridge.initLogging(filter)
        }

        /**
         * Register this device's FCM token for push notifications.
         *
         * Safe to call before an [OrigonClient] exists — the token is
         * buffered and sent once a client is created — and repeatedly
         * (e.g. from `FirebaseMessagingService.onNewToken`), where the
         * latest token wins. Returns immediately and performs the network
         * request on a background thread; failures are logged, not thrown
         * (FCM delivers tokens through a fire-and-forget callback, so
         * there is no caller to surface an error to).
         */
        fun registerForPushNotifications(token: String) {
            PushRegistrar.register(token)
        }

        /**
         * Remove this device's push registration for the current user.
         *
         * Clears any buffered token so a later client won't re-register.
         * Returns immediately; failures are logged. Typically called on
         * logout.
         */
        fun unregisterForPushNotifications() {
            PushRegistrar.unregister()
        }

        /**
         * Resolve a stable per-install device id from
         * `Settings.Secure.ANDROID_ID`. Returns null on the rare devices
         * that report a blank id, which disables push registration and,
         * when no `userId` is supplied, surfaces as an init error.
         */
        @android.annotation.SuppressLint("HardwareIds")
        private fun resolveDeviceId(context: android.content.Context): String? =
            try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            }

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
