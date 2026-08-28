package ai.origon.sdk

import ai.origon.sdk.bridge.SessionEvent
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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

    internal val appContext: android.content.Context = context.applicationContext

    /**
     * Random app-install UUID under no-backup storage. Never hardware-derived.
     */
    private val installationId: String = InstallationIdentity.loadOrCreate(appContext)

    private val cacheRoot = config.chatCachePolicy
        .takeIf { it == ChatCachePolicy.ENABLED }
        ?.let { ChatCacheStorage.ensureRoot(appContext) }
    private val rawHandle: Long = SessionBridge.initialize(
        endpoint = config.endpoint,
        bundleId = appContext.packageName,
        token = config.token,
        userId = config.userId ?: installationId,
        installationId = installationId,
        attributesJson = config.attributes?.let { JSON.encodeToString(JsonObject.serializer(), it) },
        cacheDir = cacheRoot?.absolutePath,
    )
    private val nativeGate = NativeHandleGate(rawHandle)
    private val audioLevelObservations = AudioLevelObservationRegistry()
    private val audioLevelMainDispatcher by lazy {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        AudioLevelMainDispatcher { block -> handler.post { block() } }
    }

    init {
        // SessionBridge.initialize throws SessionException on failure;
        // a zero handle without an exception would be a bridge bug.
        if (rawHandle == 0L) {
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
        nativeGate.closeOnce(
            beforeDestroy = {
                PushRegistrar.detach(this)
                audioLevelObservations.cancelAllAndClose()
            },
            destroy = SessionBridge::destroy,
        )
    }

    private fun <T> withHandle(block: (Long) -> T): T = nativeGate.withHandle(block)

    // ── Cached /config getters ───────────────────────────────────────

    /** Pre-populated first assistant message configured for the tenant. */
    val startMessage: String
        get() = withHandle(SessionBridge::getStartMessage)

    val isChatEnabled: Boolean
        get() = withHandle(SessionBridge::isChatEnabled)

    val isCallEnabled: Boolean
        get() = withHandle(SessionBridge::isCallEnabled)

    /** True when chat and voice may share one session. */
    val multipleChannels: Boolean
        get() = withHandle(SessionBridge::isMultipleChannelsAllowed)

    val attachmentPolicy: AttachmentPolicy
        get() = withHandle { handle ->
            val raw = SessionBridge.getAttachmentPolicy(handle)
            AttachmentPolicy(
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
        val json = attributes?.let { JSON.encodeToString(JsonObject.serializer(), it) }
        withHandle { SessionBridge.setAttributes(it, json) }
    }

    /** Finite cache/network transcript load. At most cache then network is emitted. */
    fun sessionUpdates(
        id: String,
        policy: SessionLoadPolicy = SessionLoadPolicy.CACHE_THEN_NETWORK,
    ): Flow<SessionLoadUpdate> = loaderFlow(
        start = { handle -> SessionBridge.sessionLoaderStart(handle, id, policy.toBridge()) },
        decode = { result ->
            when (result.status) {
                SessionBridge.LOADER_UPDATE -> SessionLoadUpdate.Snapshot(
                    JSON.decodeFromString(SessionSnapshot.serializer(), result.payloadJson.orEmpty()),
                )
                SessionBridge.LOADER_ERROR -> SessionLoadUpdate.RefreshFailed(
                    result.toSessionException(),
                    result.cachedSnapshotEmitted,
                )
                else -> null
            }
        },
    )

    /** Finite cache/network directory load. At most cache then network is emitted. */
    fun sessionDirectoryUpdates(
        policy: SessionLoadPolicy = SessionLoadPolicy.CACHE_THEN_NETWORK,
    ): Flow<SessionsLoadUpdate> = loaderFlow(
        start = { handle -> SessionBridge.directoryLoaderStart(handle, policy.toBridge()) },
        decode = { result ->
            when (result.status) {
                SessionBridge.LOADER_UPDATE -> SessionsLoadUpdate.Snapshot(
                    JSON.decodeFromString(SessionsSnapshot.serializer(), result.payloadJson.orEmpty()),
                )
                SessionBridge.LOADER_ERROR -> SessionsLoadUpdate.RefreshFailed(
                    result.toSessionException(),
                    result.cachedSnapshotEmitted,
                )
                else -> null
            }
        },
    )

    suspend fun cachedSession(id: String): SessionSnapshot? =
        sessionUpdates(id, SessionLoadPolicy.CACHE_ONLY).firstOrNull()
            ?.let { update ->
                when (update) {
                    is SessionLoadUpdate.Snapshot -> update.value
                    is SessionLoadUpdate.RefreshFailed -> throw update.error
                }
            }

    suspend fun refreshSession(id: String): SessionSnapshot =
        requireSessionSnapshot(sessionUpdates(id, SessionLoadPolicy.NETWORK_ONLY).firstOrNull())

    suspend fun cachedSessions(): SessionsSnapshot? =
        sessionDirectoryUpdates(SessionLoadPolicy.CACHE_ONLY).firstOrNull()
            ?.let { update ->
                when (update) {
                    is SessionsLoadUpdate.Snapshot -> update.value
                    is SessionsLoadUpdate.RefreshFailed -> throw update.error
                }
            }

    suspend fun refreshSessions(): SessionsSnapshot =
        requireSessionsSnapshot(
            sessionDirectoryUpdates(SessionLoadPolicy.NETWORK_ONLY).firstOrNull(),
        )

    suspend fun removeCachedSession(id: String) = withContext(Dispatchers.IO) {
        withHandle { SessionBridge.removeCachedSession(it, id) }
    }

    suspend fun clearChatCache() = withContext(Dispatchers.IO) {
        withHandle(SessionBridge::clearChatCache)
    }

    suspend fun pruneChatCache() = withContext(Dispatchers.IO) {
        withHandle(SessionBridge::pruneChatCache)
    }

    private fun <T> loaderFlow(
        start: (Long) -> Long,
        decode: (ai.origon.sdk.bridge.SessionLoaderResult) -> T?,
    ): Flow<T> = callbackFlow {
        val loader = withHandle(start)
        check(loader != 0L) { "session bridge returned null loader" }
        val owner = NativeLoaderOwner(loader)
        val worker = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val result = SessionBridge.loaderNext(loader)
                    if (result.status == SessionBridge.LOADER_END ||
                        result.status == SessionBridge.LOADER_CANCELLED
                    ) {
                        close()
                        break
                    }
                    val update = decode(result)
                    if (update != null && trySend(update).isFailure) break
                }
            } finally {
                owner.freeAfterNext()
            }
        }
        awaitClose {
            owner.cancelOnce()
            worker.cancel()
        }
    }.buffer(capacity = 2)

    private fun ai.origon.sdk.bridge.SessionLoaderResult.toSessionException() =
        SessionException(errorKind, errorStatusCode, errorCode, errorMessage)

    private fun requireSessionSnapshot(update: SessionLoadUpdate?): SessionSnapshot = when (update) {
        is SessionLoadUpdate.Snapshot -> update.value
        is SessionLoadUpdate.RefreshFailed -> throw update.error
        null -> throw SessionException(SessionBridge.ERROR_OTHER, 0, null, "session loader ended without a snapshot")
    }

    private fun requireSessionsSnapshot(update: SessionsLoadUpdate?): SessionsSnapshot = when (update) {
        is SessionsLoadUpdate.Snapshot -> update.value
        is SessionsLoadUpdate.RefreshFailed -> throw update.error
        null -> throw SessionException(SessionBridge.ERROR_OTHER, 0, null, "directory loader ended without a snapshot")
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
        val raw = withHandle { handle ->
            SessionBridge.startCall(handle, options.sessionId, options.data)
        }
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
        val firstJson =
            JSON.encodeToString(SendMessagePayload.serializer(), options.firstMessage)
        val raw = withHandle { handle ->
            SessionBridge.startChat(handle, firstJson, options.sessionId, options.data)
        }
        return StartSessionResponse(
            sessionId = raw.sessionId,
            url = raw.url,
            token = raw.token,
        )
    }

    /** Passively attach retained active chats without replacing another install. */
    fun restoreActiveChats(): List<RestoreResult> {
        return withHandle(SessionBridge::restoreActiveChats).map { result ->
            RestoreResult(
                sessionId = result.sessionId,
                status = restoreStatus(result.status),
                error = result.error,
            )
        }
    }

    /** Attach using named authority; notification and navigation are explicit takeover intents. */
    fun openChat(sessionId: String, intent: ChatAccessIntent): StartSessionResponse {
        val result = withHandle {
            SessionBridge.openChat(it, sessionId, intent.toBridge())
        }
        return StartSessionResponse(result.sessionId, result.url, result.token)
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
        withHandle { SessionBridge.joinCall(it, input.sessionId, input.url, input.token) }
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
        withHandle { SessionBridge.joinChat(it, input.sessionId, input.url, input.token) }
    }

    fun endSession(id: String) {
        withHandle { SessionBridge.endSession(it, id) }
    }

    fun endAllSessions() {
        withHandle(SessionBridge::endAllSessions)
    }

    /** Generation-bound logout gate. Completes before returning so [close] is safe next. */
    fun unregisterForPushNotifications() {
        withHandle { }
        PushRegistrar.unregisterBlocking(this)
    }

    // ── Push notifications ───────────────────────────────────────────
    // The public, buffering entry points are the companion-object
    // `registerForPushNotifications` / `unregisterForPushNotifications`.
    // These instance methods are the blocking JNI calls they dispatch to.

    /** Blocking JNI call — invoked off the main thread by [PushRegistrar]. */
    internal fun registerPush(token: String, provider: String, environment: String?): String {
        return withHandle { SessionBridge.registerPush(it, token, provider, environment) }
    }

    /** Blocking JNI call — invoked off the main thread by [PushRegistrar]. */
    internal fun unregisterPush(token: String, provider: String, generation: String) {
        withHandle { SessionBridge.unregisterPush(it, token, provider, null, generation) }
    }

    /** Snapshot of every active session. */
    fun activeSessions(): List<ActiveSession> {
        val raw = withHandle(SessionBridge::activeSessionIds)
        return raw.map { row ->
            ActiveSession(sessionId = row[0], channel = Channel.fromWire(row[1]))
        }
    }

    // ── Voice controls ───────────────────────────────────────────────

    /**
     * Send one DTMF symbol to the active voice session's CX flow.
     *
     * [digit] must be one uppercase ASCII symbol from `0-9`, `*`, `#`, or
     * `A-D`. The SDK sends control data only; it does not synthesize audio,
     * tones, clicks, or haptics.
     */
    fun sendDtmf(id: String, digit: Char) {
        withHandle { SessionBridge.sendDtmf(it, id, validateDtmfDigit(digit)) }
    }

    fun setMute(id: String, muted: Boolean) {
        withHandle { SessionBridge.setMute(it, id, muted) }
    }

    fun setMuteAll(muted: Boolean) {
        withHandle { SessionBridge.setMuteAll(it, muted) }
    }

    /**
     * Observe combined outbound, aggregate inbound, and endpoint-attributed
     * inbound levels for one active voice session.
     *
     * Creation failures throw synchronously. Updates are pulled on a dedicated
     * background thread and delivered on the main looper. The returned token is
     * idempotent; cancelling it or closing this client invalidates queued
     * callbacks immediately without joining the pump on the main looper.
     */
    @Throws(SessionException::class)
    fun observeAudioLevels(
        sessionId: String,
        observer: (SessionAudioLevels) -> Unit,
    ): AudioLevelObservation = withHandle { handle ->
        val subscription = SessionBridge.subscribeAudioLevels(handle, sessionId)
        if (subscription == 0L) {
            throw SessionException(
                kind = SessionBridge.ERROR_OTHER,
                statusCode = 0,
                code = null,
                message = "session bridge returned null audio level subscription",
            )
        }
        audioLevelObservations.register(
            owner = NativeAudioLevelOwner(subscription),
            dispatcher = audioLevelMainDispatcher,
            observer = observer,
        )
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
        withHandle { SessionBridge.setAudioOutput(it, route.toBridge()) }
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
        val payloadJson = JSON.encodeToString(SendMessagePayload.serializer(), payload)
        val responseJson = withHandle { SessionBridge.sendMessage(it, id, payloadJson) }
        return JSON.decodeFromString(Message.serializer(), responseJson)
    }

    /**
     * Chat-only — register a keystroke on the named session. Cheap to
     * call from a `TextWatcher`; the SDK debounces outbound
     * `<sessionUrl>/typing` POSTs so only one wire call fires per
     * typing burst.
     */
    fun notifyTyping(id: String) {
        withHandle { SessionBridge.notifyTyping(it, id) }
    }

    /**
     * Chat-only — force outbound typing state to "off" immediately,
     * cancelling any in-flight debounce. UI fires this on empty-text
     * transitions; the SDK also fires it implicitly on [sendMessage]
     * and on [endSession].
     */
    fun stopTyping(id: String) {
        withHandle { SessionBridge.stopTyping(it, id) }
    }

    // ── Attachments ──────────────────────────────────────────────────

    /**
     * Upload a file from the local filesystem against the widget this
     * client was created for, and return the server-issued [Attachment].
     * The SDK streams the body straight from disk; auto-detects MIME from
     * a 256-byte head plus the [fileName] extension. Runs on
     * [Dispatchers.IO].
     *
     * There is no `sessionId` and no session prerequisite — an attachment
     * can be the first thing a visitor sends.
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
        path: String,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
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
            withHandle { handle ->
                SessionBridge.uploadAttachment(handle, uploadId, path, fileName, callback)
            }
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
        uri: android.net.Uri,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
        withHandle { }
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
        bytes: ByteArray,
        fileName: String,
        uploadId: String = UUID.randomUUID().toString(),
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): Attachment {
        withHandle { }
        val tempFile = withContext(Dispatchers.IO) {
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            val out = java.io.File.createTempFile("upload-", suffix, appContext.cacheDir)
            out.outputStream().use { it.write(bytes) }
            out
        }
        return try {
            uploadAttachment(
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
     * Cancel an in-flight upload or delete a completed attachment.
     * Session-less like [uploadAttachment].
     *
     * `attachmentId` is dual-purpose: it can be either the `uploadId`
     * passed to [uploadAttachment] (cancels the in-flight upload — no
     * network call, the upload's awaiter throws [SessionException]
     * with `kind = SessionBridge.ERROR_CANCELLED`) or the server-issued
     * `attachment.id` of a completed upload (issues `DELETE` on the
     * server). The SDK figures it out: it checks its in-flight uploads
     * table first, then falls through to the wire call.
     *
     * Runs on [Dispatchers.IO]. The server is idempotent on a missing
     * object and answers 204, so a successful return does not prove the
     * id existed; a 404 means the route did not match. An id that could
     * not form a usable path is refused by the SDK before any request.
     */
    suspend fun deleteAttachment(attachmentId: String) {
        withContext(Dispatchers.IO) {
            withHandle { SessionBridge.deleteAttachment(it, attachmentId) }
        }
    }

    // ── Events ───────────────────────────────────────────────────────

    /** Polls the next event. Returns null when the queue is idle. */
    fun pollEvent(): ClientEvent? {
        val raw = withHandle(SessionBridge::pollEvent) ?: return null
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
                ClientEvent.Typing(sid, decodeTypingState(raw.messageJson) ?: return null)

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

    /** Decode the authoritative typing snapshot from the existing JSON slot. */
    private fun decodeTypingState(json: String?): TypingState? {
        if (json.isNullOrEmpty()) return null
        return try {
            JSON.decodeFromString(TypingState.serializer(), json)
        } catch (e: Throwable) {
            // Never log the payload: it contains ephemeral participant identity.
            android.util.Log.w("OrigonSDK", "decodeTypingState: dropping event, JSON parse failed: ${e.message}")
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
        private const val DTMF_DIGITS = "0123456789*#ABCD"

        internal fun validateDtmfDigit(digit: Char): Char {
            require(digit.code <= 0x7f && digit in DTMF_DIGITS) {
                "DTMF digit must be one uppercase ASCII symbol"
            }
            return digit
        }

        /**
         * Synchronously quarantines the SDK-owned cache subtree. Close every live
         * client before calling; physical deletion may finish asynchronously.
         */
        suspend fun clearAllChatCaches(context: android.content.Context) {
            val root = ChatCacheStorage.ensureRoot(context) ?: return
            withContext(Dispatchers.IO) {
                SessionBridge.clearChatCacheRoot(root.absolutePath)
            }
        }

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

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
