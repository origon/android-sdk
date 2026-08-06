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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import origon.example.android.R
import origon.example.android.data.PendingAttachment
import origon.example.android.services.SDKManager
import origon.example.android.ui.call.CallView
import origon.example.android.ui.components.AttachmentsPreview
import origon.example.android.ui.components.MessageBubble
import origon.example.android.ui.components.OrigonSpinner
import origon.example.android.ui.components.PrimaryButton
import origon.example.android.ui.components.SessionHeader
import origon.example.android.ui.components.ToastHost
import origon.example.android.ui.components.TypingIndicator
import origon.example.android.ui.components.rememberAttachmentDownloader
import origon.example.android.ui.components.rememberToastState
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.OrigonTheme

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
            runCatching { sdk.getSessions() }
            sdk.chat.openSession(null)
            boot = BootState.Ready
            return@LaunchedEffect
        }
        boot = BootState.Loading
        try {
            sdk.initialize(endpoint = endpoint)
            runCatching { sdk.getSessions() }
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

/** The mark breathing while the SDK connects. */
@Composable
private fun BootingLogo() {
    val transition = rememberInfiniteTransition(label = "boot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = EaseInOut),
            RepeatMode.Reverse,
        ),
        label = "bootPulse",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_origon_logo),
            contentDescription = null,
            modifier = Modifier
                .graphicsLayer {
                    val scale = lerp(1f, 1.14f, pulse)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(0.5f, 1f, pulse)
                }
                .size(72.dp),
        )
    }
}

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

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val toast = rememberToastState()
    val downloader = rememberAttachmentDownloader { error -> toast.show(error ?: "Saved") }
    val keyboard = LocalSoftwareKeyboardController.current

    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var callActive by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = OrigonTheme.colors.screenBackground,
                drawerContentColor = OrigonTheme.colors.textPrimary,
            ) {
                Sidebar(
                    sessions = sessions,
                    selectedSessionId = currentSessionId,
                    onSessionPicked = { session ->
                        scope.launch {
                            drawerState.close()
                            chat.openSession(session.sessionId)
                        }
                    },
                    onChangeEndpoint = {
                        scope.launch {
                            drawerState.close()
                            onChangeEndpoint()
                        }
                    },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(OrigonTheme.colors.screenBackground)
                // `safeDrawing`, not `statusBarsPadding().navigationBarsPadding()
                // .imePadding()` — that chain ADDS the navigation-bar inset to
                // the IME inset while the keyboard is up, lifting the composer
                // a nav-bar's height above the keyboard. safeDrawing is the
                // union of system bars, the IME and the display cutout, which
                // is the one correct answer in both states.
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            SessionHeader(
                onMenuTap = { scope.launch { drawerState.open() } },
                // Only offer "new session" once the conversation has content —
                // on an empty session it is a no-op.
                showPlus = messages.isNotEmpty(),
                onNewSession = { chat.endCurrentSession() },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !isTyping) {
                    EmptyTranscript()
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
                    )
                }
            }

            Composer(
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
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                        AttachKind.FILE -> pickFile.launch(arrayOf("*/*"))
                    }
                },
                onSend = send,
                onStartCall = startCall,
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
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 96dp so the pill clears the composer.
    ToastHost(toast, bottomPadding = 96.dp)
}

private class PreviewRequest(val attachments: List<Attachment>, val index: Int)

@Composable
private fun EmptyTranscript() {
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
            text = stringResource(R.string.chat_empty_greeting),
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
) {
    val listState = rememberLazyListState()

    // Follow the tail as rows land. Keyed on the count AND the typing row so a
    // peer starting to type also scrolls into view.
    LaunchedEffect(messages.size, isTyping) {
        val last = messages.size - if (isTyping) 0 else 1
        if (last >= 0) listState.animateScrollToItem(last.coerceAtLeast(0))
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
        items(messages, key = { it.stableKey() }) { message ->
            val key = message.stableKey()
            MessageBubble(
                message = message,
                revealed = revealedKey == key,
                onToggleRevealed = { onToggleRevealed(key) },
                onAttachmentTap = { index -> onAttachmentTap(message, index) },
                onDownloadAttachment = onDownloadAttachment,
            )
        }
        if (isTyping) {
            item(key = "typing") { TypingIndicator() }
        }
    }
}

/**
 * Outbound rows first appear with `localId` set and `id == ""`; the server id
 * lands on MessageUpdated. Prefer `localId` so the row tracks across
 * sending → delivered. Inbound rows have no `localId`, so `id` wins. Mirrors
 * `ChatService`'s own key.
 */
private fun Message.stableKey(): String =
    localId?.takeIf { it.isNotEmpty() } ?: id

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
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            interactionSource = attachInteraction,
                            indication = null,
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photo_library)) },
                        onClick = {
                            menuOpen = false
                            onAttach(AttachKind.MEDIA)
                        },
                    )
                    DropdownMenuItem(
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
                        enabled = !sending,
                        onClickLabel = if (hasContent) "Send" else "Start a call",
                        onClick = { if (hasContent) onSend() else onStartCall() },
                    ),
            ) {
                if (sending) {
                    OrigonSpinner(MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        painterResource(
                            if (hasContent) R.drawable.ic_send else R.drawable.ic_voice,
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
