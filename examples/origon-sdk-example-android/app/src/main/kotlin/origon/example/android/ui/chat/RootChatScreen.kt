package origon.example.android.ui.chat

import ai.origon.sdk.Attachment
import ai.origon.sdk.Message
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import ai.origon.sdk.Channel
import ai.origon.sdk.SessionSummary
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleResumeEffect
import origon.example.android.R
import origon.example.android.data.PendingAttachment
import origon.example.android.services.ChatService
import origon.example.android.services.ExampleEndpointPolicy
import origon.example.android.services.EXAMPLE_NEW_MESSAGES_ACCESSIBILITY_LABEL
import origon.example.android.services.exampleNewestEligibleMessageId
import origon.example.android.services.exampleStableKey
import origon.example.android.services.exampleTranscriptDecision
import origon.example.android.services.exampleUnreadAnchor
import origon.example.android.services.SDKManager
import origon.example.android.ui.call.CallView
import origon.example.android.ui.components.AttachmentsPreview
import origon.example.android.ui.components.MessageBubble
import origon.example.android.ui.components.exampleMessageAuthor
import origon.example.android.ui.components.exampleShouldShowAuthor
import origon.example.android.ui.components.OrigonSpinner
import origon.example.android.ui.components.PrimaryButton
import origon.example.android.ui.components.SessionHeader
import origon.example.android.ui.components.ToastHost
import origon.example.android.ui.components.TypingIndicator
import origon.example.android.ui.components.rememberAttachmentDownloader
import origon.example.android.ui.components.rememberToastState
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.OrigonTheme
import kotlin.math.roundToInt

/**
 * The chat surface: a navigation drawer (the session list) over the transcript
 * and composer, plus the voice-call and attachment-preview overlays. Boots the
 * SDK on first appearance.
 *
 * Purpose-built for this example rather than reduced from the shipped app's
 * chat root — that one is structured around the account surfaces this example
 * has none of, and a de-featured copy of it would read as the shipped app with
 * holes where this reads as an example.
 */
@Composable
fun RootChatScreen(
    sdk: SDKManager,
    endpoint: String,
    onChangeEndpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var boot by remember { mutableStateOf<BootState>(BootState.Loading) }
    var bootAttempt by remember { mutableIntStateOf(0) }

    // **Guarded on `sdk.isReady`, not merely keyed.** `SDKManager` is
    // process-scoped, so an Activity recreate re-enters this composition with
    // the client already connected; re-initializing would build a second native
    // handle and leak the first.
    LaunchedEffect(bootAttempt) {
        if (sdk.isReady.value) {
            runCatching { sdk.refreshSessions() }
            sdk.chat.openSession(null)
            boot = BootState.Ready
            return@LaunchedEffect
        }
        boot = BootState.Loading
        try {
            sdk.initialize(endpoint = endpoint)
            runCatching { sdk.refreshSessions() }
            boot = BootState.Ready
            sdk.chat.openSession(null)
        } catch (e: Throwable) {
            boot = BootState.Failed("Failed to connect: ${e.message}")
        }
    }

    Box(modifier.fillMaxSize().background(OrigonTheme.colors.screenBackground)) {
        when (val state = boot) {
            BootState.Loading -> BootingLogo()
            is BootState.Failed -> BootError(
                message = state.message,
                onRetry = { bootAttempt++ },
                onChangeEndpoint = onChangeEndpoint,
            )
            BootState.Ready -> ChatContent(
                sdk = sdk,
                onChangeEndpoint = onChangeEndpoint,
            )
        }
    }
}

private sealed interface BootState {
    data object Loading : BootState
    data class Failed(val message: String) : BootState
    data object Ready : BootState
}

/**
 * The mark, breathing, while the SDK comes up — the shipped app's
 * `BreathingLogo`, spelled the same so the two splashes are the same splash.
 */
@Composable
private fun BootingLogo() {
    val transition = rememberInfiniteTransition(label = "breathing")
    val spec = infiniteRepeatable<Float>(
        animation = tween(BREATH_MS, easing = EaseInOut),
        repeatMode = RepeatMode.Reverse,
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = spec,
        label = "breathingScale",
    )
    val opacity by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = spec,
        label = "breathingOpacity",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_origon_logo),
            contentDescription = null,
            modifier = Modifier
                // Lifted off centre so the mark sits where the eye expects a
                // splash rather than in the geometric middle.
                .padding(bottom = 76.dp)
                // 56, not 72. Anchored to the SCREEN, not the safe area: from
                // API 31 the system draws its own splash first and centres that
                // icon on the screen, so a safe-area-relative mark lands
                // somewhere different per navigation mode and the handover
                // visibly jumps.
                .size(56.dp)
                .scale(scale)
                .alpha(opacity),
        )
    }
}

/** The shipped app's 1.6 s autoreversing ease. */
private const val BREATH_MS = 1600

@Composable
private fun BootError(message: String, onRetry: () -> Unit, onChangeEndpoint: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = OrigonTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
        PrimaryButton(title = stringResource(R.string.retry), onClick = onRetry)
        Text(
            text = stringResource(R.string.sidebar_change_endpoint),
            style = MaterialTheme.typography.bodyMedium,
            color = OrigonTheme.colors.textTertiary,
            modifier = Modifier
                .padding(top = 16.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onChangeEndpoint,
                )
                .padding(8.dp),
        )
    }
}

// ── The connected surface ────────────────────────────────────────────────

@Composable
private fun ChatContent(sdk: SDKManager, onChangeEndpoint: () -> Unit) {
    val chat = sdk.chat
    val messages by chat.messages.collectAsState()
    val isTyping by chat.isTyping.collectAsState()
    val pending by chat.pendingAttachments.collectAsState()
    val sessions by sdk.sessions.collectAsState()
    val currentSessionId by chat.currentSessionId.collectAsState()
    val connectionState by chat.connectionState.collectAsState()
    val canSend by chat.canSend.collectAsState()
    val focusedLoadState by chat.focusedLoadState.collectAsState()
    val serverConfig by sdk.serverConfig.collectAsState()
    val endpointPolicy = ExampleEndpointPolicy.from(serverConfig)

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val drawerPx = with(LocalDensity.current) { DRAWER_WIDTH.toPx() }
    val toast = rememberToastState()
    val downloader = rememberAttachmentDownloader { error -> toast.show(error ?: "Saved") }
    val keyboard = LocalSoftwareKeyboardController.current

    var foreground by remember { mutableStateOf(false) }
    LifecycleResumeEffect(chat) {
        foreground = true
        chat.refetchFocusedSession()
        onPauseOrDispose { foreground = false }
    }

    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var callActive by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }
    var explicitSendSequence by remember { mutableIntStateOf(0) }
    var checkpointLoaded by remember { mutableStateOf(false) }
    var checkpointLastSeenId by remember { mutableStateOf<String?>(null) }
    var unreadAnchor by remember { mutableStateOf<Int?>(null) }
    var anchorFixed by remember { mutableStateOf(false) }
    var lastMarkedCandidate by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentSessionId, sdk.checkpointEndpoint) {
        checkpointLoaded = false
        checkpointLastSeenId = null
        unreadAnchor = null
        anchorFixed = false
        lastMarkedCandidate = null
        val id = currentSessionId ?: run {
            checkpointLoaded = true
            return@LaunchedEffect
        }
        checkpointLastSeenId = sdk.checkpoint(id)?.lastSeenMessageId
        checkpointLoaded = true
    }

    val historyAuthoritative = focusedLoadState == ChatService.DestinationLoadState.NETWORK ||
        focusedLoadState == ChatService.DestinationLoadState.FRESH_EMPTY
    LaunchedEffect(checkpointLoaded, historyAuthoritative, messages.map { it.id to it.localId }) {
        if (checkpointLoaded && historyAuthoritative && !anchorFixed) {
            unreadAnchor = exampleUnreadAnchor(messages, checkpointLastSeenId)
            anchorFixed = true
        }
    }

    /**
     * The row the sidebar picked, kept whole rather than as an id: the voice
     * detail renders entirely from the summary, so throwing it away here would
     * mean fetching it back.
     */
    var selectedSession by remember { mutableStateOf<SessionSummary?>(null) }

    // ── Drawer ───────────────────────────────────────────────────────────
    //
    // Push-and-peek, not an overlay: the content slides right by the drawer's
    // width so the history button peeks out from behind a dim, exactly as the
    // shipped app does. Material's `ModalNavigationDrawer` cannot express that
    // — it draws over the content — which is why this is hand-rolled.
    //
    // **The geometry is a settled value PLUS a live drag offset, and that split
    // is load-bearing.** Folding both into one `Animatable` and pushing every
    // drag delta through `snapTo` looks equivalent and is not: each launched
    // `snapTo` cancels the one before it, so a fast drag's last deltas are
    // dropped, the release reads a stale position, and the drawer simply does
    // not open. A drag is synchronous and an animation is not, so they are two
    // things and are stored as two.

    /** Where the drawer rests: 0 = closed, 1 = fully open. Animated. */
    val settled = remember { Animatable(0f) }

    /** What the finger has added since the drag began. Written synchronously. */
    var dragProgress by remember { mutableFloatStateOf(0f) }

    /** Changes once per gesture, not once per frame — hit-testing reads it. */
    var isOpen by remember { mutableStateOf(false) }

    val shown = { (settled.value + dragProgress).coerceIn(0f, 1f) }

    /**
     * Come to rest open or closed. The live drag is folded into [settled] first
     * and in this same coroutine, so the spring starts from where the finger
     * actually left the drawer rather than from wherever it rested before.
     */
    suspend fun settle(open: Boolean) {
        // Here rather than at the button, so the edge swipe is covered too: the
        // composer takes focus whenever a session resolves, and this layer sits
        // outside its `safeDrawing` padding — so an IME left up would cover the
        // bottom of the session list, including the taps meant for it.
        if (open) focusManager.clearFocus()
        isOpen = open
        settled.snapTo(shown())
        dragProgress = 0f
        settled.animateTo(if (open) 1f else 0f, DRAWER_SPRING)
    }

    /** Take the drawer off any running animation so the finger owns it. */
    fun beginDrag() {
        scope.launch { settled.stop() }
    }

    /** Synchronous, on the gesture's own thread. */
    fun drag(delta: Float) {
        dragProgress = (dragProgress + delta / drawerPx)
            .coerceIn(-settled.value, 1f - settled.value)
    }

    LaunchedEffect(Unit) { chat.error.collect { toast.show(it) } }

    // ── Attachment pickers ───────────────────────────────────────────────
    val enqueue: (Uri) -> Unit = { uri ->
        val (name, type) = context.queryFileInfo(uri)
        chat.uploadFile(uri, name, type)
    }
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(enqueue) }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(enqueue) }

    // ── Call permissions ─────────────────────────────────────────────────
    //
    // A voice call needs runtime ("dangerous") permissions the manifest
    // declaration alone doesn't grant. Requesting them is the consumer app's
    // job, not the SDK's — the SDK has no Activity to drive the dialog, it only
    // *declares* them, and they merge into this app.
    //
    //   - RECORD_AUDIO (all API levels): REQUIRED. Mic capture is silent
    //     without it, so the call is gated on it.
    //   - BLUETOOTH_CONNECT (API 31+): OPTIONAL, and only worth prompting for
    //     when a Bluetooth headset is actually connected — otherwise every user
    //     sees the "Nearby devices" dialog for nothing.
    val micRequired = stringResource(R.string.call_mic_required)
    val requestCallPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Gate only on the mic — re-check the live grant, since it may have been
        // granted earlier and not be part of this request. A denied (or absent)
        // BLUETOOTH_CONNECT is fine: the call proceeds on the built-in device.
        if (context.micGranted()) callActive = true else toast.show(micRequired)
    }

    val startCall = {
        keyboard?.hide()
        val needed = buildList {
            if (!context.micGranted()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.bluetoothHeadsetConnected() &&
                context.checkSelfPermissionCompat(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isEmpty()) callActive = true else requestCallPermissions.launch(needed.toTypedArray())
    }

    val hasContent = draft.isNotBlank() || pending.isNotEmpty()

    val send = {
        val text = draft.trim()
        if (text.isNotEmpty() || pending.isNotEmpty()) {
            explicitSendSequence++
            scope.launch {
                // Wait out any in-flight uploads so the send carries them.
                if (chat.hasUploadingAttachments) {
                    sending = true
                    while (chat.hasUploadingAttachments) delay(100)
                    sending = false
                }
                draft = ""
                chat.sendMessage(text)
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize()) {
        // Bottom layer — the root content, pushed right by the drawer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The lambda form: read in the layout phase, so an animating
                // drawer relayouts without recomposing this subtree.
                .offset { IntOffset((drawerPx * shown()).roundToInt(), 0) }
                // Opaque, or the drawer sitting underneath in the stack shows
                // through the content it is supposed to be behind.
                .background(OrigonTheme.colors.screenBackground),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // `safeDrawing`, not `statusBarsPadding()
                    // .navigationBarsPadding().imePadding()` — that chain ADDS
                    // the navigation-bar inset to the IME inset while the
                    // keyboard is up, lifting the composer a nav-bar's height
                    // above the keyboard. safeDrawing is the union of system
                    // bars, the IME and the display cutout.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val voice = selectedSession?.takeIf { it.channel == Channel.VOICE }

                SessionHeader(
                    onMenuTap = { scope.launch { settle(open = true) } },
                    // A voice session gets the same header the transcript wears,
                    // titled. Rendering a bare title would strand the user with
                    // no menu button, and the edge swipe cannot stand in — the
                    // system owns that gesture outside the bottom band.
                    title = if (voice != null) "Voice call" else null,
                    // Only offer "new session" once there is something to leave:
                    // a conversation with content, or a voice row being viewed.
                    showPlus = voice != null || messages.isNotEmpty(),
                    onNewSession = {
                        scope.launch {
                            chat.endCurrentSession()
                            chat.openSession(null)
                        }
                        selectedSession = null
                    },
                )

                if (voice != null) {
                    // A voice row is a different VIEW OF THE ROOT, not a place
                    // to navigate to — so it fills the content slot in place.
                    // The drawer still swipes open over it, and a voice tap can
                    // no longer fall through to the chat view and render an
                    // empty transcript with a composer under it.
                    VoiceSessionDetail(selectedSession!!)
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (messages.isEmpty() && !isTyping) {
                            EmptyTranscript(endpointPolicy.greeting)
                        } else {
                            Transcript(
                                messages = messages,
                                isTyping = isTyping,
                                revealedKey = revealedKey,
                                onToggleRevealed = { key ->
                                    revealedKey = if (revealedKey == key) null else key
                                },
                                onAttachmentTap = { message, index ->
                                    preview = PreviewRequest(message.attachments, index)
                                },
                                onDownloadAttachment = downloader::download,
                                promptIsLive = { endpointPolicy.promptSendEnabled && chat.promptIsLive(it) },
                                promptSelection = chat::selectionFor,
                                onPromptReply = { promptId, cardIndex, label, value, galleryLabel ->
                                    scope.launch {
                                        chat.sendButtonReply(
                                            promptId = promptId,
                                            cardIndex = cardIndex,
                                            label = label,
                                            value = value,
                                            galleryLabel = galleryLabel,
                                        )
                                    }
                                },
                                unreadAnchor = unreadAnchor,
                                checkpointLoaded = checkpointLoaded,
                                authoritative = historyAuthoritative,
                                explicitSendSequence = explicitSendSequence,
                                onLatestRowVisible = {
                                    val id = currentSessionId
                                    val candidate = exampleNewestEligibleMessageId(messages)
                                    if (id != null && candidate != null && foreground &&
                                        historyAuthoritative && candidate != lastMarkedCandidate
                                    ) {
                                        lastMarkedCandidate = candidate
                                        scope.launch {
                                            sdk.markCheckpointSeen(id, foreground = true, latestRowVisible = true)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    ConnectionStatus(
                        sessionId = currentSessionId,
                        connection = connectionState,
                        canSend = canSend,
                    )
                    if (endpointPolicy.showsVoiceOnlyAction) {
                        PrimaryButton(
                            title = "Start a call",
                            onClick = startCall,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    } else if (!endpointPolicy.showsComposer) {
                        Text(
                            text = "Messaging and calls are unavailable for this endpoint.",
                            color = OrigonTheme.colors.textSecondary,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                    } else Composer(
                        draft = draft,
                        onDraftChange = { value ->
                            draft = value
                            if (value.isBlank()) chat.stopTyping() else chat.notifyTyping()
                        },
                        pending = pending,
                        onRemovePending = chat::removePendingAttachment,
                        sending = sending,
                        hasContent = hasContent,
                        onAttach = { kind ->
                            keyboard?.hide()
                            when (kind) {
                                AttachKind.MEDIA -> pickMedia.launch(
                                    PickVisualMediaRequest(
                                        when {
                                            endpointPolicy.attachments.images && endpointPolicy.attachments.videos ->
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                            endpointPolicy.attachments.images ->
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            else -> ActivityResultContracts.PickVisualMedia.VideoOnly
                                        },
                                    ),
                                )
                                AttachKind.FILE -> pickFile.launch(
                                    if (endpointPolicy.attachments.documents) arrayOf("*/*")
                                    else arrayOf("audio/*"),
                                )
                            }
                        },
                        onSend = send,
                        onStartCall = startCall,
                        enabled = currentSessionId == null || canSend,
                        allowMedia = endpointPolicy.attachments.images || endpointPolicy.attachments.videos,
                        allowFiles = endpointPolicy.attachments.documents || endpointPolicy.attachments.audio,
                        voiceActionEnabled = endpointPolicy.showsComposerVoiceAction,
                    )
                }
            }
        }

        // Middle layer — the dim. Above the content so it darkens the peek,
        // below the drawer so the drawer keeps full contrast. `drawBehind` for
        // the same reason `offset` takes a lambda: it is a draw-phase read.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = DIM_ALPHA * shown()) }
                .then(
                    // Only intercepts once the drawer has settled open. During
                    // an edge drag the gesture belongs to the strip below.
                    if (!isOpen) {
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { scope.launch { settle(open = false) } }
                            }
                            .pointerInput(drawerPx) {
                                val tracker = VelocityTracker()
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        tracker.resetTracking()
                                        beginDrag()
                                    },
                                    onDragEnd = {
                                        val vx = tracker.calculateVelocity().x
                                        scope.launch {
                                            settle(shouldOpen(shown(), vx, drawerPx))
                                        }
                                    },
                                    // Without this a cancelled drag runs neither
                                    // arm and strands the drawer part-open with
                                    // no animation pending.
                                    onDragCancel = { scope.launch { settle(open = true) } },
                                ) { change, delta ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    drag(delta)
                                }
                            }
                    },
                ),
        )

        // Top layer — the drawer itself.
        Box(
            modifier = Modifier
                .width(DRAWER_WIDTH)
                .fillMaxHeight()
                .offset { IntOffset((-drawerPx * (1f - shown())).roundToInt(), 0) }
                // Without a background the peeking content reads through the
                // drawer...
                .background(OrigonTheme.colors.screenBackground)
                // ...and `background` alone is not enough: Compose's is a
                // DRAW-phase modifier and does not hit-test. Without an absorber
                // a tap or drag on the drawer's blank area falls through to the
                // dim beneath and closes the drawer the user just touched.
                .absorbPointers(),
        ) {
            Sidebar(
                sessions = sessions,
                selectedSessionId = selectedSession?.sessionId ?: currentSessionId,
                onSessionPicked = { session ->
                    selectedSession = session
                    scope.launch {
                        settle(open = false)
                        // Only a chat row opens a session; a voice row is
                        // read-only history and opening it would attach a
                        // participant to a finished call.
                        if (session.channel != Channel.VOICE) {
                            chat.openSession(session.sessionId)
                        }
                    }
                },
                onChangeEndpoint = {
                    scope.launch {
                        settle(open = false)
                        onChangeEndpoint()
                    }
                },
            )
        }

        // The strip an opening swipe must start in, at the bottom-left. Bounded
        // to the band the platform will actually yield to an app — a larger
        // request is silently clamped, not honoured.
        if (!isOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(EDGE_WIDTH)
                    .height(EDGE_BAND)
                    .systemGestureExclusion()
                    .pointerInput(drawerPx) {
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                beginDrag()
                            },
                            onDragEnd = {
                                val vx = tracker.calculateVelocity().x
                                scope.launch { settle(shouldOpen(shown(), vx, drawerPx)) }
                            },
                            onDragCancel = { scope.launch { settle(open = false) } },
                        ) { change, delta ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            drag(delta)
                        }
                    },
            )
        }
    }

    // ── Overlays ─────────────────────────────────────────────────────────

    preview?.let { request ->
        AttachmentsPreview(
            attachments = request.attachments,
            activeIndex = request.index,
            onDismiss = { preview = null },
        )
    }

    if (callActive) {
        CallView(sdk = sdk, onClose = { callActive = false })
    }

    // Back closes the drawer before it leaves the screen. The call and preview
    // overlays take the gesture themselves while they are up.
    BackHandler(enabled = isOpen) { scope.launch { settle(open = false) } }

    // 96dp so the pill clears the composer.
    ToastHost(toast, bottomPadding = 96.dp)
}

private class PreviewRequest(val attachments: List<Attachment>, val index: Int)

@Composable
private fun ConnectionStatus(
    sessionId: String?,
    connection: ChatService.ConnectionState,
    canSend: Boolean,
) {
    if (sessionId == null) return
    val text = when (connection) {
        ChatService.ConnectionState.CONNECTED -> if (canSend) null else "Opening conversation…"
        ChatService.ConnectionState.RECONNECTING -> "Reconnecting…"
        ChatService.ConnectionState.DROPPED -> "Connection lost. Your next message will retry."
        ChatService.ConnectionState.ENDED -> "Conversation ended. This transcript is read-only."
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OrigonTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyTranscript(greeting: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_origon_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp).padding(bottom = 24.dp),
        )
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineSmall,
            color = OrigonTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun Transcript(
    messages: List<Message>,
    isTyping: Boolean,
    revealedKey: String?,
    onToggleRevealed: (String) -> Unit,
    onAttachmentTap: (Message, Int) -> Unit,
    onDownloadAttachment: (Attachment) -> Unit,
    promptIsLive: (Message) -> Boolean,
    promptSelection: (String) -> ChatService.PromptSelection?,
    /** `(promptId, cardIndex, label, value, galleryLabel)`. */
    onPromptReply: (String, Int?, String, String, String?) -> Unit,
    unreadAnchor: Int?,
    checkpointLoaded: Boolean,
    authoritative: Boolean,
    explicitSendSequence: Int,
    onLatestRowVisible: () -> Unit,
) {
    val listState = rememberLazyListState()
    var positioned by remember { mutableStateOf(false) }
    var passiveWasAtTail by remember { mutableStateOf(true) }
    var explicitPending by remember { mutableStateOf(false) }
    var outgoingBeforeSend by remember { mutableStateOf<Set<String>>(emptySet()) }
    val outgoingNow = messages.mapIndexedNotNull { index, message ->
        message.exampleStableKey(index).takeIf { message.role == ai.origon.sdk.MessageRole.EXTERNAL }
    }.toSet()
    val messageKeys = messages.mapIndexed { index, message -> message.exampleStableKey(index) }
    val atTail by remember(messages.size, isTyping) {
        derivedStateOf {
            val lastMessage = messages.lastIndex
            lastMessage < 0 || listState.layoutInfo.visibleItemsInfo.any { it.index == lastMessage }
        }
    }

    LaunchedEffect(explicitSendSequence) {
        if (explicitSendSequence > 0) {
            explicitPending = true
            outgoingBeforeSend = outgoingNow
        }
    }

    LaunchedEffect(checkpointLoaded, authoritative, unreadAnchor, messageKeys) {
        if (!positioned && checkpointLoaded && authoritative) {
            val target = unreadAnchor ?: messages.lastIndex
            if (target >= 0) listState.scrollToItem(target)
            positioned = true
            passiveWasAtTail = unreadAnchor == null
        }
    }

    LaunchedEffect(messageKeys) {
        if (!positioned) return@LaunchedEffect
        val decision = exampleTranscriptDecision(
            explicitSendPending = explicitPending,
            outgoingKeysBeforeSend = outgoingBeforeSend,
            outgoingKeysNow = outgoingNow,
            positioned = positioned,
            wasAtTail = passiveWasAtTail,
        )
        if (decision.consumeSend) explicitPending = false
        if (decision.followTail && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(listState, messages.size) {
        snapshotFlow { atTail }.collect { latest ->
            passiveWasAtTail = latest
            if (latest && messages.isNotEmpty()) onLatestRowVisible()
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Keyed on the message's stable key (`localId ?: id`): `MessageBubble`
        // holds position-memoized state, so an un-keyed list would bleed one
        // row's reveal state onto another as rows are inserted.
        itemsIndexed(messages, key = { index, message -> message.exampleStableKey(index) }) { index, message ->
            if (index == unreadAnchor) NewMessagesDivider()
            val key = message.exampleStableKey(index)
            val hasPrompt = message.buttons.isNotEmpty() || message.gallery.isNotEmpty()
            MessageBubble(
                message = message,
                author = exampleMessageAuthor(message),
                showsAuthor = exampleShouldShowAuthor(message, messages.getOrNull(index - 1)),
                revealed = revealedKey == key,
                onToggleRevealed = { onToggleRevealed(key) },
                onAttachmentTap = { index -> onAttachmentTap(message, index) },
                onDownloadAttachment = onDownloadAttachment,
                // Computed only for rows that actually carry a prompt — both
                // reads scan the transcript, and paying that on every plain
                // bubble would make the list O(n²) in message count.
                promptIsLive = hasPrompt && promptIsLive(message),
                promptSelection = if (hasPrompt) promptSelection(message.id) else null,
                onPromptReply = if (!hasPrompt) {
                    null
                } else {
                    { cardIndex, label, value, galleryLabel ->
                        onPromptReply(message.id, cardIndex, label, value, galleryLabel)
                    }
                },
            )
        }
        if (isTyping) {
            item(key = "typing") { TypingIndicator() }
        }
    }
}

@Composable
private fun NewMessagesDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = EXAMPLE_NEW_MESSAGES_ACCESSIBILITY_LABEL
                heading()
            },
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(OrigonTheme.colors.border))
        Text(
            "NEW MESSAGES",
            style = MaterialTheme.typography.labelSmall,
            color = OrigonTheme.colors.textSecondary,
        )
        Box(Modifier.weight(1f).height(1.dp).background(OrigonTheme.colors.border))
    }
}

/**
 * Outbound rows first appear with `localId` set and `id == ""`; the server id
 * lands on MessageUpdated. Prefer `localId` so the row tracks across
 * sending → delivered. Inbound rows have no `localId`, so `id` wins. Mirrors
 * `ChatService`'s own key.
 */
// ── Composer ─────────────────────────────────────────────────────────────

private enum class AttachKind { MEDIA, FILE }

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    pending: List<PendingAttachment>,
    onRemovePending: (String) -> Unit,
    sending: Boolean,
    hasContent: Boolean,
    onAttach: (AttachKind) -> Unit,
    onSend: () -> Unit,
    onStartCall: () -> Unit,
    enabled: Boolean,
    allowMedia: Boolean,
    allowFiles: Boolean,
    voiceActionEnabled: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val attachInteraction = remember { MutableInteractionSource() }
    val sendInteraction = remember { MutableInteractionSource() }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
    ) {
        if (pending.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                items(pending, key = { it.id }) { row ->
                    PendingTile(row, onRemove = { onRemovePending(row.id) })
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (allowMedia || allowFiles) Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            interactionSource = attachInteraction,
                            indication = null,
                            enabled = enabled,
                            onClickLabel = "Attach",
                            onClick = { menuOpen = true },
                        ),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_paperclip),
                        contentDescription = "Attach",
                        tint = OrigonTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (allowMedia) DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photo_library)) },
                        onClick = {
                            menuOpen = false
                            onAttach(AttachKind.MEDIA)
                        },
                    )
                    if (allowFiles) DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_files)) },
                        onClick = {
                            menuOpen = false
                            onAttach(AttachKind.FILE)
                        },
                    )
                }
            }

            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = OrigonTheme.colors.textPrimary),
                cursorBrush = SolidColor(OrigonTheme.colors.textPrimary),
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = OrigonTheme.colors.textTertiary,
                            )
                        }
                        field()
                    }
                },
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(
                        interactionSource = sendInteraction,
                        indication = null,
                        enabled = enabled && !sending && (hasContent || voiceActionEnabled),
                        onClickLabel = if (hasContent) "Send" else "Start a call",
                        onClick = { if (hasContent) onSend() else onStartCall() },
                    ),
            ) {
                if (sending) {
                    OrigonSpinner(MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        painterResource(
                            if (hasContent || !voiceActionEnabled) R.drawable.ic_send else R.drawable.ic_voice,
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** One pending-upload tile: thumbnail or extension, progress, error, remove. */
@Composable
private fun PendingTile(row: PendingAttachment, onRemove: () -> Unit) {
    val removeInteraction = remember { MutableInteractionSource() }
    Box(Modifier.size(72.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(OrigonTheme.colors.peerBubble),
        ) {
            val previewUri = row.previewUri
            if (row.isImage && previewUri != null) {
                AsyncImage(
                    model = previewUri,
                    contentDescription = row.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = row.fileExtension.ifEmpty { "FILE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = OrigonTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            when (row.status) {
                PendingAttachment.Status.UPLOADING -> Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${row.progress}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                PendingAttachment.Status.ERROR -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_error),
                        contentDescription = row.errorText,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                PendingAttachment.Status.COMPLETED -> Unit
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = removeInteraction,
                    indication = null,
                    onClickLabel = stringResource(R.string.attach_remove),
                    onClick = onRemove,
                ),
        ) {
            Icon(
                painterResource(R.drawable.ic_cross_small),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

// ── Platform helpers ─────────────────────────────────────────────────────

private fun android.content.Context.micGranted(): Boolean =
    checkSelfPermissionCompat(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun android.content.Context.checkSelfPermissionCompat(permission: String): Int =
    androidx.core.content.ContextCompat.checkSelfPermission(this, permission)

/**
 * Whether a Bluetooth (hands-free / SCO) headset is currently connected. Uses
 * `AudioManager.getDevices`, which needs no Bluetooth permission, so it is safe
 * to call *before* deciding whether to request BLUETOOTH_CONNECT.
 */
private fun android.content.Context.bluetoothHeadsetConnected(): Boolean {
    val am = getSystemService(AudioManager::class.java) ?: return false
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}

private fun android.content.Context.queryFileInfo(uri: Uri): Pair<String, String> {
    val type = contentResolver.getType(uri) ?: "application/octet-stream"
    var name = "file"
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = c.getString(idx) ?: name
        }
    }
    return name to type
}

// ── Drawer geometry ──────────────────────────────────────────────────────
//
// These are the shipped app's numbers, carried over rather than re-picked: a
// drawer that opens a different distance, at a different speed, off a different
// threshold is a different control, and this example exists to be copied.

/** The sidebar's width. Material's own default is ~360 and reads too wide. */
private val DRAWER_WIDTH = 320.dp

/** The strip an opening swipe must start in — UIKit's screen-edge width. */
private val EDGE_WIDTH = 20.dp

/**
 * How much of the edge the platform will actually yield to an app. A larger
 * request is silently clamped rather than honoured, and the value comes from an
 * overlayable framework config, so an OEM may set it lower — re-read
 * `adb shell dumpsys window` on a new device class rather than trusting this.
 */
private val EDGE_BAND = 200.dp

private const val DIM_ALPHA = 0.35f

/**
 * Where a released drag settles: `translation + velocity * 0.25 > half`,
 * expressed in progress rather than pixels.
 *
 * The velocity term is what makes a **flick** work. Without it a fast, short
 * swipe — which is what an edge gesture actually is, since the finger starts at
 * the screen edge and has little room — stops short of half and springs back,
 * so the drawer feels like it ignores you. `detectHorizontalDragGestures` does
 * not report velocity, hence the explicit `VelocityTracker`.
 */
private fun shouldOpen(progress: Float, velocityX: Float, drawerPx: Float): Boolean =
    progress + (velocityX * DRAWER_PROJECTION_SECONDS) / drawerPx > DRAWER_THRESHOLD

/** A quarter second of coasting. */
private const val DRAWER_PROJECTION_SECONDS = 0.25f

private const val DRAWER_THRESHOLD = 0.5f

/**
 * `interactiveSpring(response: 0.35, dampingFraction: 0.85)`.
 *
 * SwiftUI's `response` is the spring's period: `w0 = 2*PI / response`, and both
 * frameworks define stiffness as `w0^2` for unit mass — so 0.35 s gives
 * `(2*PI/0.35)^2` ~= 322, and `dampingFraction` is Compose's `dampingRatio`
 * unchanged. Spelled out rather than reached for by feel.
 */
private val DRAWER_SPRING = spring<Float>(dampingRatio = 0.85f, stiffness = 322f)

/**
 * Swallow every pointer event that reaches this node.
 *
 * Compose's `background` is a draw-phase modifier and does not participate in
 * hit testing, so a tap on a "solid" surface with nothing clickable under the
 * finger falls straight through to whatever is behind it.
 */
private fun Modifier.absorbPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}
