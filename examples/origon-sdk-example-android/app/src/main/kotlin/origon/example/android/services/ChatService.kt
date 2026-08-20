package origon.example.android.services

import android.net.Uri
import ai.origon.sdk.ChatAccessIntent
import ai.origon.sdk.ClientEvent
import ai.origon.sdk.DisconnectReason
import ai.origon.sdk.Message
import ai.origon.sdk.MessageRole
import ai.origon.sdk.MessageStatus
import ai.origon.sdk.OrigonClient
import ai.origon.sdk.SendMessagePayload
import ai.origon.sdk.SessionException
import ai.origon.sdk.SessionLoadPolicy
import ai.origon.sdk.SessionLoadUpdate
import ai.origon.sdk.StartChatOptions
import ai.origon.sdk.UploadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import origon.example.android.data.PendingAttachment
import origon.example.android.util.SdkErrorKinds
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns in-memory chat state for every active chat session. Mirrors the
 * iOS `ChatService`: multi-active by design, each open session keeps its
 * own [SessionUIState]; the focused session's state is projected via the
 * [messages] / [isTyping] / [pendingAttachments] flows the UI binds to.
 */
class ChatService internal constructor(
    private val scope: CoroutineScope,
    events: Flow<ClientEvent>,
    private val sdkClient: () -> OrigonClient?,
    private val destinationClient: () -> ChatSessionClient?,
    private val refreshSessions: suspend () -> Unit,
    private val endpointPolicy: () -> ExampleEndpointPolicy = { ExampleEndpointPolicy.DISABLED },
) {

    constructor(manager: SDKManager) : this(
        scope = manager.scope,
        events = manager.events,
        sdkClient = { manager.client },
        destinationClient = { manager.chatClient },
        refreshSessions = { manager.refreshSessions() },
        endpointPolicy = { manager.endpointPolicy },
    )

    enum class DestinationLoadState {
        IDLE,
        LOADING,
        CACHED,
        NETWORK,
        FRESH_EMPTY,
        REFRESH_FAILED_CACHED,
        REFRESH_FAILED_EMPTY,
        FAILED,
    }

    enum class ConnectionState { CONNECTED, RECONNECTING, DROPPED, ENDED }

    data class SessionUIState(
        val messages: List<Message> = emptyList(),
        val isTyping: Boolean = false,
        val accessGranted: Boolean = false,
        val connectionState: ConnectionState = ConnectionState.CONNECTED,
        val loadState: DestinationLoadState = DestinationLoadState.IDLE,
        val liveMessageKeys: Set<String> = emptySet(),
        val pendingAttachments: List<PendingAttachment> = emptyList(),
        /**
         * Which option the user tapped on each interactive prompt, keyed by
         * the prompt message's id.
         *
         * In memory only, and deliberately so: the server persists neither the
         * chosen `value` nor the gallery label on the reply row, so a restored
         * transcript cannot say which card was picked. This record is the only
         * thing that can — see [selectionFor], which falls back to a label
         * match for history it never saw live.
         */
        val promptSelections: Map<String, PromptSelection> = emptyMap(),
    )

    /**
     * The option a user tapped on one prompt. [cardIndex] is null for a
     * top-level button row and the card's position for a gallery pick —
     * carried because two cards may share a button label.
     */
    data class PromptSelection(val cardIndex: Int?, val buttonLabel: String)

    private val sessionsState = MutableStateFlow<Map<String, SessionUIState>>(emptyMap())
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    /**
     * Pending uploads queued before any chat session exists. Uploads no
     * longer wait on a session, so this list is drained by whichever comes
     * first: [ensureChatSession] (the lazy start on the first send) or
     * [adoptDrafts] (the user focusing an existing session).
     */
    private val draftPending = MutableStateFlow<List<PendingAttachment>>(emptyList())

    /** Transient errors for the UI to toast. */
    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val error: SharedFlow<String> = _error.asSharedFlow()

    /** Serializes the lazy chat-session start so only one POST fires. */
    private val startMutex = Mutex()
    private val clientEpoch = AtomicLong(0)
    private val destinationEpoch = AtomicLong(0)
    private val refetchToken = AtomicLong(0)
    private val refetchTokens = ConcurrentHashMap<String, Long>()
    private val refetchJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var acceptingEvents = true

    // MARK: - Focused-session projections

    val messages: StateFlow<List<Message>> =
        combine(sessionsState, _currentSessionId) { states, id ->
            id?.let { states[it]?.messages } ?: emptyList()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isTyping: StateFlow<Boolean> =
        combine(sessionsState, _currentSessionId) { states, id ->
            id?.let { states[it]?.isTyping } ?: false
        }.stateIn(scope, SharingStarted.Eagerly, false)

    val pendingAttachments: StateFlow<List<PendingAttachment>> =
        combine(sessionsState, _currentSessionId, draftPending) { states, id, draft ->
            if (id != null) states[id]?.pendingAttachments ?: emptyList() else draft
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val hasUploadingAttachments: Boolean
        get() = pendingAttachments.value.any { it.status == PendingAttachment.Status.UPLOADING }

    init {
        scope.launch {
            events.collect { handleEvent(it) }
        }
    }

    internal fun clientWillChange() {
        acceptingEvents = false
        clientEpoch.incrementAndGet()
        destinationEpoch.incrementAndGet()
        refetchJobs.values.forEach(Job::cancel)
        refetchJobs.clear()
        refetchTokens.clear()
        sessionsState.update { states ->
            states.mapValues { (_, state) -> state.copy(accessGranted = false) }
        }
    }

    internal fun clientDidChange() { acceptingEvents = true }

    internal fun stateFor(id: String): SessionUIState? = sessionsState.value[id]

    val canSend: StateFlow<Boolean> =
        combine(sessionsState, _currentSessionId) { states, id ->
            id?.let {
                val state = states[it] ?: return@let false
                when (state.connectionState) {
                    ConnectionState.CONNECTED -> state.accessGranted
                    ConnectionState.DROPPED -> true
                    ConnectionState.RECONNECTING, ConnectionState.ENDED -> false
                }
            } ?: true
        }.stateIn(scope, SharingStarted.Eagerly, true)

    internal val canSendFocusedSession: Boolean
        get() = _currentSessionId.value?.let { id ->
            val state = sessionsState.value[id] ?: return@let false
            when (state.connectionState) {
                ConnectionState.CONNECTED -> state.accessGranted
                ConnectionState.DROPPED -> true
                ConnectionState.RECONNECTING, ConnectionState.ENDED -> false
            }
        } ?: true

    val connectionState: StateFlow<ConnectionState> =
        combine(sessionsState, _currentSessionId) { states, id ->
            id?.let { states[it]?.connectionState } ?: ConnectionState.CONNECTED
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.CONNECTED)

    // MARK: - Session lifecycle

    /**
     * Focus a chat session. `null` switches to the empty "new session"
     * state. A non-nil id either focuses existing in-memory state, or
     * fetches history + opens the SDK chat channel for that id.
     */
    suspend fun openSession(id: String?) {
        val operation = destinationEpoch.incrementAndGet()
        val epoch = clientEpoch.get()
        if (id == null) {
            _currentSessionId.value = null
            return
        }
        val client = destinationClient() ?: return
        sessionsState.update { states ->
            val state = states[id] ?: SessionUIState()
            states + (id to state.copy(
                accessGranted = false,
                loadState = DestinationLoadState.LOADING,
            ))
        }
        _currentSessionId.value = id
        adoptDrafts(id)

        coroutineScope {
            val history = async { loadDestination(id, client, epoch, operation) }
            val access = async { acquireDestination(id, client, epoch, operation) }
            history.await()
            access.await()
        }
        if (destinationIsCurrent(id, epoch, operation)) runCatching { refreshSessions() }
    }

    private suspend fun loadDestination(
        id: String,
        client: ChatSessionClient,
        epoch: Long,
        operation: Long,
    ) {
        try {
            client.sessionUpdates(id).collect { update ->
                if (!destinationIsCurrent(id, epoch, operation)) return@collect
                when (update) {
                    is SessionLoadUpdate.Snapshot -> sessionsState.update { states ->
                        val current = states[id] ?: return@update states
                        val reconciled = reconcile(update.value.session.history, current)
                        val load = when {
                            !update.value.authoritative -> DestinationLoadState.CACHED
                            update.value.session.history.isEmpty() -> DestinationLoadState.FRESH_EMPTY
                            else -> DestinationLoadState.NETWORK
                        }
                        states + (id to reconciled.copy(loadState = load))
                    }
                    is SessionLoadUpdate.RefreshFailed -> {
                        sessionsState.update { states ->
                            val current = states[id] ?: return@update states
                            states + (id to current.copy(loadState = if (update.cachedSnapshotEmitted) {
                                DestinationLoadState.REFRESH_FAILED_CACHED
                            } else {
                                DestinationLoadState.REFRESH_FAILED_EMPTY
                            }))
                        }
                        _error.tryEmit(if (update.cachedSnapshotEmitted) {
                            "Showing saved messages. Could not refresh this conversation."
                        } else {
                            "Failed to load conversation: ${update.error.message}"
                        })
                    }
                }
            }
        } catch (error: Throwable) {
            if (!destinationIsCurrent(id, epoch, operation)) return
            sessionsState.update { states ->
                val current = states[id] ?: return@update states
                states + (id to current.copy(loadState = DestinationLoadState.FAILED))
            }
            _error.tryEmit("Failed to load conversation: ${error.message}")
        }
    }

    private suspend fun acquireDestination(
        id: String,
        client: ChatSessionClient,
        epoch: Long,
        operation: Long,
    ) {
        try {
            client.acquireChatAccess(id, ChatAccessIntent.EXPLICIT_NAVIGATION)
            if (!destinationIsCurrent(id, epoch, operation)) return
            sessionsState.update { states ->
                val current = states[id] ?: return@update states
                states + (id to current.copy(
                    accessGranted = true,
                    connectionState = if (current.connectionState == ConnectionState.ENDED) {
                        ConnectionState.ENDED
                    } else {
                        ConnectionState.CONNECTED
                    },
                ))
            }
        } catch (error: Throwable) {
            if (!destinationIsCurrent(id, epoch, operation)) return
            sessionsState.update { states ->
                val current = states[id] ?: return@update states
                states + (id to current.copy(accessGranted = false))
            }
            _error.tryEmit("Conversation is view-only: ${error.message}")
        }
    }

    private suspend fun destinationIsCurrent(id: String, epoch: Long, operation: Long): Boolean =
        currentCoroutineContext().isActive && clientEpoch.get() == epoch &&
            destinationEpoch.get() == operation && _currentSessionId.value == id

    /**
     * Move any draft tiles onto the session being focused.
     *
     * The draft list holds rows picked while nothing was open. Uploads no
     * longer wait on a session, so nothing drains that list on its own any
     * more — and the [pendingAttachments] projection stops reading it the
     * moment a real session is focused. Left alone, a tile picked before
     * opening a conversation would vanish from the composer (unremovable,
     * its blob stranded on the server) and then silently reappear on
     * whichever session happened to be started next.
     */
    private fun adoptDrafts(id: String) {
        val drafts = draftPending.value
        if (drafts.isEmpty()) return
        sessionsState.update { states ->
            val s = states[id] ?: return@update states
            states + (id to s.copy(pendingAttachments = s.pendingAttachments + drafts))
        }
        draftPending.value = emptyList()
    }

    /**
     * Send a text message + completed attachments.
     *
     * With a session already focused this is a plain `sendMessage`. With
     * none, the send IS the open: `startChat` carries the visitor's first
     * message, so there is no window where a session exists but has said
     * nothing (the server gates the flow on visitor content and reaps a
     * silent session — see [StartChatOptions]).
     *
     * Does not mutate [messages] directly — the SDK fires MessageAdded /
     * MessageUpdated which [handleEvent] applies.
     */
    suspend fun sendMessage(
        text: String,
        /** A prompt option's real match key. Null for a typed message. */
        value: String? = null,
        /** The card title a gallery pick came from. Null otherwise. */
        galleryLabel: String? = null,
    ) {
        if (!endpointPolicy().chatEnabled) return
        val trimmed = text.trim()
        try {
            // Read tiles through the projection: with no session focused they
            // are still on the draft list, and an attachment-only first
            // message is valid.
            val currentPending = _currentSessionId.value
                ?.let { sessionsState.value[it]?.pendingAttachments }
                ?: draftPending.value
            val completed = currentPending
                .mapNotNull { if (it.status == PendingAttachment.Status.COMPLETED) it.attachment else null }
            if (trimmed.isEmpty() && completed.isEmpty()) return
            val client = destinationClient() ?: return

            val payload = SendMessagePayload(
                text = trimmed.ifEmpty { null },
                attachments = completed,
                value = value,
                galleryLabel = galleryLabel,
            )
            val focused = _currentSessionId.value
            val id = if (focused != null) {
                val state = sessionsState.value[focused] ?: return
                if (state.connectionState == ConnectionState.ENDED) {
                    _error.tryEmit("This conversation has ended and is read-only.")
                    return
                }
                if (state.connectionState == ConnectionState.DROPPED) {
                    openAndSend(payload, focused)
                } else {
                    if (!state.accessGranted || state.connectionState != ConnectionState.CONNECTED) {
                        _error.tryEmit("Conversation is reconnecting. Try again when it is ready.")
                        return
                    }
                    client.sendMessage(focused, payload)
                    focused
                }
            } else {
                openAndSend(payload, null)
            }

            // Completed attachments now belong to the sent message.
            sessionsState.update { states ->
                val s = states[id] ?: return@update states
                states + (id to s.copy(
                    pendingAttachments = s.pendingAttachments.filter {
                        it.status != PendingAttachment.Status.COMPLETED
                    }
                ))
            }
        } catch (e: Throwable) {
            _error.tryEmit(e.message ?: "Failed to send")
        }
    }

    // MARK: - Interactive prompts

    /**
     * Answer an interactive prompt by tapping one of its options.
     *
     * Routed through [sendMessage] on purpose: a tap and a typed message share
     * the optimistic buffer, the lazy session start and the delivery
     * bookkeeping, and a second copy of that machinery would be a second place
     * to get it wrong.
     *
     * [label] becomes the message `text` (what lands in the transcript, and the
     * server's fallback match key); [value] is the real match key.
     */
    suspend fun sendButtonReply(
        promptId: String,
        cardIndex: Int?,
        label: String,
        value: String,
        galleryLabel: String?,
    ) {
        // A prompt can only exist on a session that is already live, so the
        // focused id is the right key.
        _currentSessionId.value?.let { id ->
            sessionsState.update { states ->
                val s = states[id] ?: SessionUIState()
                states + (id to s.copy(
                    promptSelections = s.promptSelections +
                        (promptId to PromptSelection(cardIndex, label)),
                ))
            }
        }
        sendMessage(text = label, value = value, galleryLabel = galleryLabel)
    }

    /**
     * Which option is highlighted on [promptId], if any.
     *
     * Two mechanisms, because neither covers the other's case. The in-memory
     * record is exact but empty after a relaunch; the label match works on a
     * restored transcript but can only compare captions — so on a prompt with
     * duplicate labels across cards it may highlight the wrong card. The server
     * persists nothing that could disambiguate it, so that over-match is
     * accepted rather than solved.
     */
    fun selectionFor(promptId: String): PromptSelection? {
        val id = _currentSessionId.value ?: return null
        val state = sessionsState.value[id] ?: return null
        state.promptSelections[promptId]?.let { return it }

        // Restored history: the visitor's reply is the row after the prompt,
        // and its text is the label they tapped.
        val promptIndex = state.messages.indexOfFirst { it.id == promptId }
        if (promptIndex < 0) return null
        val reply = state.messages
            .drop(promptIndex + 1)
            .firstOrNull { it.role == MessageRole.EXTERNAL }
        val text = reply?.text?.takeIf { it.isNotEmpty() } ?: return null
        return PromptSelection(cardIndex = null, buttonLabel = text)
    }

    /**
     * Whether [message]'s options are still answerable.
     *
     * Deliberately NOT "any later message": the server puts lifecycle rows
     * (`queued`/`joined`/`ended`) and paced flow messages on the visitor
     * stream, so an agent joining mid-prompt would disable a prompt the server
     * still considers open. The discriminator is a later **visitor-authored**
     * row, which can only come from this client's own send or from a restored
     * transcript.
     */
    fun promptIsLive(message: Message): Boolean {
        val id = _currentSessionId.value ?: return false
        val state = sessionsState.value[id] ?: return false
        val promptIndex = state.messages.indexOfFirst { it.id == message.id }
        if (promptIndex < 0) return false
        return state.messages
            .drop(promptIndex + 1)
            .none { it.role == MessageRole.EXTERNAL }
    }

    /** Notify the peer the user is typing. Cheap to call; SDK debounces. */
    fun notifyTyping() {
        val client = sdkClient() ?: return
        val id = _currentSessionId.value ?: return
        runCatching { client.notifyTyping(id) }
    }

    /** Force outbound typing off — input went empty. */
    fun stopTyping() {
        val client = sdkClient() ?: return
        val id = _currentSessionId.value ?: return
        runCatching { client.stopTyping(id) }
    }

    /** End the focused chat while retaining its transcript and draft read-only. */
    fun endCurrentSession() {
        val id = _currentSessionId.value ?: return
        sdkClient()?.let { runCatching { it.endSession(id) } }
        sessionsState.update { states ->
            val state = states[id] ?: return@update states
            states + (id to state.copy(
                connectionState = ConnectionState.ENDED,
                accessGranted = false,
                isTyping = false,
            ))
        }
    }

    // MARK: - Attachments

    /**
     * Queue a file upload onto the focused session (or the draft list
     * when no session is open yet). The tile appears immediately at
     * progress 0; live updates land via the SDK's progress callback.
     *
     * The write lane is widget-scoped, so no session is opened for the
     * upload — an attachment can be the first thing a visitor sends.
     */
    fun uploadFile(uri: Uri, fileName: String, contentType: String) {
        if (!endpointPolicy().allowsAttachment(contentType)) {
            _error.tryEmit("This attachment type is disabled for this endpoint")
            return
        }
        val localId = UUID.randomUUID().toString()
        val pending = PendingAttachment(
            id = localId,
            fileName = fileName,
            contentType = contentType,
            previewUri = if (contentType.startsWith("image/")) uri else null,
            status = PendingAttachment.Status.UPLOADING,
            progress = 0,
        )
        appendPending(pending)
        scope.launch { runUpload(localId, uri, fileName) }
    }

    fun removePendingAttachment(id: String) {
        var removed: PendingAttachment? = null

        // Search the draft list first, then every session's pending list.
        draftPending.value.firstOrNull { it.id == id }?.let { row ->
            removed = row
            draftPending.update { list -> list.filter { it.id != id } }
        }
        if (removed == null) {
            for ((sid, state) in sessionsState.value) {
                val row = state.pendingAttachments.firstOrNull { it.id == id } ?: continue
                removed = row
                sessionsState.update { states ->
                    val s = states[sid] ?: return@update states
                    states + (sid to s.copy(
                        pendingAttachments = s.pendingAttachments.filter { it.id != id }
                    ))
                }
                break
            }
        }

        val row = removed ?: return
        val client = sdkClient() ?: return
        when (row.status) {
            PendingAttachment.Status.UPLOADING -> {
                // deleteAttachment matches the local id against the SDK's
                // in-flight upload table and cancels it. Fires regardless of
                // which list hosted the row — the write lane is
                // widget-scoped, and a draft-list upload is now the common
                // case since uploads no longer wait on a session.
                scope.launch {
                    runCatching { client.deleteAttachment(id) }
                }
            }
            PendingAttachment.Status.COMPLETED -> {
                val serverId = row.attachment?.id ?: return
                scope.launch {
                    runCatching { client.deleteAttachment(serverId) }
                }
            }
            PendingAttachment.Status.ERROR -> Unit // local remove only
        }
    }

    // MARK: - Teardown

    /** End every active chat session and clear UI state. Called on logout. */
    fun destroy() {
        clientWillChange()
        sdkClient()?.let { client ->
            for (id in sessionsState.value.keys) runCatching { client.endSession(id) }
        }
        sessionsState.value = emptyMap()
        _currentSessionId.value = null
        draftPending.value = emptyList()
    }

    // MARK: - Event handling

    private fun handleEvent(event: ClientEvent) {
        if (!acceptingEvents) return
        val sid = event.sessionId

        // sessionUpdated may arrive for an id we don't hold state for yet.
        if (event is ClientEvent.SessionUpdated) {
            val state = sessionsState.value[sid] ?: return
            sessionsState.update { (it - sid) + (event.newSessionId to state) }
            if (_currentSessionId.value == sid) _currentSessionId.value = event.newSessionId
            return
        }

        // Filter everything else to sessions we own (drops voice events).
        if (sessionsState.value[sid] == null) return

        when (event) {
            is ClientEvent.MessageAdded -> sessionsState.update { states ->
                val s = states[sid] ?: return@update states
                states + (sid to applyingMessageAdded(event.message, s))
            }
            is ClientEvent.MessageUpdated -> updateMessage(sid, event.id, event.message)
            is ClientEvent.Typing -> sessionsState.update { states ->
                val s = states[sid] ?: return@update states
                states + (sid to s.copy(isTyping = event.isTyping))
            }
            is ClientEvent.Reconnecting -> updateConnection(
                sid, ConnectionState.RECONNECTING, accessGranted = false,
            )
            is ClientEvent.Reconnected -> {
                if (stateFor(sid)?.connectionState != ConnectionState.ENDED) {
                    updateConnection(sid, ConnectionState.CONNECTED, accessGranted = true)
                    refetchHistory(sid)
                }
            }
            is ClientEvent.Connected -> if (stateFor(sid)?.connectionState != ConnectionState.ENDED) {
                updateConnection(sid, ConnectionState.CONNECTED, accessGranted = true)
            }
            is ClientEvent.Disconnected -> {
                val terminal = event.reason is DisconnectReason.LocalClose ||
                    event.reason is DisconnectReason.SessionEnded
                updateConnection(
                    sid,
                    if (terminal) ConnectionState.ENDED else ConnectionState.DROPPED,
                    accessGranted = false,
                )
                if (!terminal) refetchHistory(sid)
            }
            is ClientEvent.ChatSessionEnded -> updateConnection(
                sid, ConnectionState.ENDED, accessGranted = false,
            )
            else -> Unit
        }
    }

    private fun updateConnection(
        id: String,
        connection: ConnectionState,
        accessGranted: Boolean,
    ) {
        sessionsState.update { states ->
            val state = states[id] ?: return@update states
            if (state.connectionState == ConnectionState.ENDED && connection != ConnectionState.ENDED) {
                return@update states
            }
            states + (id to state.copy(
                connectionState = connection,
                accessGranted = accessGranted,
                isTyping = if (connection == ConnectionState.CONNECTED) state.isTyping else false,
            ))
        }
    }

    /** Fill a possible event gap without passive active-chat restoration. */
    fun refetchFocusedSession() {
        _currentSessionId.value?.let(::refetchHistory)
    }

    private fun refetchHistory(id: String) {
        val client = destinationClient() ?: return
        if (sessionsState.value[id] == null) return
        val clientGeneration = clientEpoch.get()
        val token = refetchToken.incrementAndGet()
        refetchTokens[id] = token
        refetchJobs.remove(id)?.cancel()
        refetchJobs[id] = scope.launch {
            runCatching {
                client.sessionUpdates(id, SessionLoadPolicy.NETWORK_ONLY).collect { update ->
                    if (!isActive || clientEpoch.get() != clientGeneration ||
                        refetchTokens[id] != token || sessionsState.value[id] == null
                    ) return@collect
                    if (update is SessionLoadUpdate.Snapshot && update.value.authoritative) {
                        sessionsState.update { states ->
                            val state = states[id] ?: return@update states
                            val load = if (update.value.session.history.isEmpty()) {
                                DestinationLoadState.FRESH_EMPTY
                            } else {
                                DestinationLoadState.NETWORK
                            }
                            states + (id to reconcile(update.value.session.history, state)
                                .copy(loadState = load))
                        }
                    }
                }
            }
        }
    }

    internal fun receiveForTesting(event: ClientEvent) = handleEvent(event)

    internal fun installStateForTesting(
        id: String,
        state: SessionUIState,
        focused: Boolean = true,
    ) {
        sessionsState.update { it + (id to state) }
        if (focused) _currentSessionId.value = id
    }

    private fun updateMessage(sid: String, key: String, message: Message) {
        sessionsState.update { states ->
            val s = states[sid] ?: return@update states
            states + (sid to applyingMessageUpdate(key, message, s))
        }
    }

    // Outbound rows first appear with localId set and id == ""; the server
    // id lands on MessageUpdated. Prefer localId so the row tracks across
    // sending → delivered. Inbound rows have no localId, so id wins.
    companion object {
        internal fun messageKey(m: Message): String =
            m.localId?.takeIf { it.isNotEmpty() } ?: m.id

        internal fun applyingMessageUpdate(
            key: String,
            message: Message,
            state: SessionUIState,
        ): SessionUIState {
            val index = state.messages.indexOfFirst { messageKey(it) == key }
            // A MessageUpdated without its provisional MessageAdded has no
            // app-owned identity to correlate. Let the sequenced SDK snapshot
            // install it rather than inventing a second authoritative reducer.
            if (index < 0) return state
            val prior = state.messages[index]
            val replacement = overlayLocalHints(prior, message)
            val messages = state.messages.toMutableList().also { it[index] = replacement }
            return state.copy(
                messages = messages,
                liveMessageKeys = (state.liveMessageKeys - messageKey(prior)) + messageKey(replacement),
            )
        }

        internal fun applyingMessageAdded(message: Message, state: SessionUIState): SessionUIState {
            val key = messageKey(message)
            val index = state.messages.indexOfFirst { messageKey(it) == key }
            val messages = if (index < 0) {
                state.messages + message
            } else {
                state.messages.toMutableList().also { it[index] = overlayLocalHints(it[index], message) }
            }
            return state.copy(messages = messages, liveMessageKeys = state.liveMessageKeys + key)
        }

        internal fun reconcile(history: List<Message>, state: SessionUIState): SessionUIState {
            val localByServerId = state.messages
                .filter { it.id.isNotEmpty() }
                .associateBy { it.id }
            val serverIds = history.mapNotNull { it.id.takeIf(String::isNotEmpty) }.toSet()
            val consumedLive = mutableSetOf<String>()
            val authoritative = history.map { remote ->
                localByServerId[remote.id]?.let { local ->
                    consumedLive += messageKey(local)
                    overlayLocalHints(local, remote)
                } ?: remote
            }
            val tail = state.messages.filter { local ->
                if (local.id.isNotEmpty() && local.id in serverIds) return@filter false
                local.status == MessageStatus.SENDING || local.status == MessageStatus.FAILED ||
                    messageKey(local) in state.liveMessageKeys
            }
            return state.copy(
                messages = authoritative + tail,
                liveMessageKeys = state.liveMessageKeys - consumedLive,
            )
        }

        private fun overlayLocalHints(local: Message, remote: Message): Message {
            val localAttachments = local.attachments.associateBy { it.id }
            return remote.copy(
                localId = remote.localId?.takeIf(String::isNotEmpty) ?: local.localId,
                attachments = remote.attachments.map { item ->
                    localAttachments[item.id]?.localUrl?.let { preview ->
                        item.copy(localUrl = preview)
                    } ?: item
                },
            )
        }
    }

    // MARK: - Upload internals

    /**
     * Open a chat session by SENDING — `startChat` carries [payload] as the
     * visitor's first message and returns the new session id.
     *
     * This is the only path that opens a chat. Uploads are widget-scoped and
     * never open one, and the sidebar's [openSession] is view-only.
     *
     * The mutex means a second send arriving while a start is in flight
     * waits and then sends normally — it must NOT start its own session, and
     * it can't join by payload either, since the first message is already
     * spoken for.
     */
    private suspend fun openAndSend(payload: SendMessagePayload, resumeId: String?): String {
        val client = destinationClient() ?: throw IllegalStateException("SDK not initialized")
        return startMutex.withLock {
            _currentSessionId.value?.takeIf { it != resumeId }?.let { existing ->
                client.sendMessage(existing, payload)
                return@withLock existing
            }
            // startChat returns the session id BEFORE the message goes out,
            // and a first message that fails to DELIVER does not throw — it
            // arrives as MessageUpdated(FAILED) so the user can retry. Only a
            // terminal refusal throws.
            val response = client.startChat(StartChatOptions(
                firstMessage = payload,
                sessionId = resumeId,
            ))
            val newId = response.sessionId
            // Merge any draft tiles queued while the start was in flight.
            sessionsState.update { states ->
                val existing = resumeId?.let(states::get) ?: states[newId] ?: SessionUIState()
                val withoutOld = if (resumeId != null && resumeId != newId) states - resumeId else states
                withoutOld + (newId to existing.copy(
                    pendingAttachments = existing.pendingAttachments + draftPending.value,
                    connectionState = ConnectionState.CONNECTED,
                    accessGranted = true,
                ))
            }
            draftPending.value = emptyList()
            if (_currentSessionId.value == null || _currentSessionId.value == resumeId) {
                _currentSessionId.value = newId
            }
            scope.launch { runCatching { refreshSessions() } }
            if (resumeId != null) refetchHistory(newId)
            newId
        }
    }

    private suspend fun runUpload(localId: String, uri: Uri, fileName: String) {
        val client = sdkClient() ?: run {
            updatePending(localId) { it.copy(status = PendingAttachment.Status.ERROR, errorText = "Client not ready") }
            return
        }
        // `uploadFile` appends the row and then LAUNCHES this coroutine, so a
        // × tap can run in between. If the row is gone by the time we get
        // here, abort before any wire work starts — the cancel it fired
        // landed before the SDK registered its in-flight entry, so it would
        // have degraded to a DELETE of an id the server never saw, leaving an
        // orphan blob behind this upload.
        if (!pendingExists(localId)) return

        try {
            val attachment = client.uploadAttachment(
                uri = uri,
                fileName = fileName,
                uploadId = localId,
                onProgress = { progress: UploadProgress ->
                    updatePending(localId) { row ->
                        val pct = progress.percent
                            ?: progress.totalBytes?.takeIf { it > 0 }?.let {
                                (progress.bytesUploaded * 100 / it).toInt()
                            }
                        if (pct != null) row.copy(progress = pct.coerceIn(0, 100)) else row
                    }
                },
            )
            updatePending(localId) {
                it.copy(status = PendingAttachment.Status.COMPLETED, progress = 100, attachment = attachment)
            }
        } catch (e: SessionException) {
            // The SDK's ERROR_* constants live on the internal SessionBridge,
            // so we mirror the cancelled discriminant locally. A cancel means
            // the user removed the tile mid-upload — nothing left to do.
            if (e.kind == SdkErrorKinds.CANCELLED) return
            val message = e.message ?: e.code ?: "Upload failed"
            updatePending(localId) { it.copy(status = PendingAttachment.Status.ERROR, errorText = message) }
            _error.tryEmit(message)
        } catch (e: Throwable) {
            val message = e.message ?: "Upload failed"
            updatePending(localId) { it.copy(status = PendingAttachment.Status.ERROR, errorText = message) }
            _error.tryEmit(message)
        }
    }

    private fun pendingExists(localId: String): Boolean {
        if (draftPending.value.any { it.id == localId }) return true
        return sessionsState.value.values.any { state ->
            state.pendingAttachments.any { it.id == localId }
        }
    }

    private fun appendPending(row: PendingAttachment) {
        val id = _currentSessionId.value
        if (id != null) {
            sessionsState.update { states ->
                val s = states[id] ?: SessionUIState()
                states + (id to s.copy(pendingAttachments = s.pendingAttachments + row))
            }
        } else {
            draftPending.update { it + row }
        }
    }

    private fun updatePending(localId: String, mutate: (PendingAttachment) -> PendingAttachment) {
        // Draft list first.
        if (draftPending.value.any { it.id == localId }) {
            draftPending.update { list -> list.map { if (it.id == localId) mutate(it) else it } }
            return
        }
        // Then every session's pending list.
        sessionsState.update { states ->
            var changed = false
            val newStates = states.mapValues { (_, state) ->
                if (state.pendingAttachments.any { it.id == localId }) {
                    changed = true
                    state.copy(pendingAttachments = state.pendingAttachments.map {
                        if (it.id == localId) mutate(it) else it
                    })
                } else {
                    state
                }
            }
            if (changed) newStates else states
        }
    }
}
