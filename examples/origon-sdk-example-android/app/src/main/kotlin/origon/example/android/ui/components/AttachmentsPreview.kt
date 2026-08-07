package origon.example.android.ui.components

import ai.origon.sdk.Attachment
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import origon.example.android.R
import origon.example.android.services.AudioFocus
import origon.example.android.services.Http
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.OrigonTheme
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * Fullscreen paginated preview for a message's attachments: image / video /
 * audio / PDF / unsupported views, prev/next pagination, a filename + counter
 * header, a download action, tap-to-toggle chrome, and a Photos-style vertical
 * drag-to-dismiss.
 *
 * Per-type backends are the platform's, matching iOS's platform players
 * (AVKit/PDFKit) without a new dependency: video = `VideoView` +
 * `MediaController`; audio = `MediaPlayer` behind a minimal transport (the
 * platform has no standalone audio-controls widget); PDF = fetch to cache +
 * `PdfRenderer` (the renderer needs a seekable file). One recorded deviation:
 * iOS's `PDFView` pinch-zooms; this renders fit-width scrollable pages only.
 *
 * The host presents this full-screen and removes it on [onDismiss]; chrome
 * padding uses safe-drawing insets so the header clears a cutout even with the
 * status bar hidden.
 */
@Composable
fun AttachmentsPreview(
    attachments: List<Attachment>,
    activeIndex: Int,
    onDismiss: () -> Unit,
) {
    var currentIndex by rememberSaveable {
        mutableIntStateOf(activeIndex.coerceIn(0, (attachments.size - 1).coerceAtLeast(0)))
    }
    var chromeVisible by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // A plain holder, not MutableState — nothing composes against the job.
    val settle = remember { JobRef() }
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val downloader = rememberAttachmentDownloader { error -> toast.show(error ?: "Saved") }
    val density = LocalDensity.current
    val chromeInteraction = remember { MutableInteractionSource() }

    BackHandler(onBack = onDismiss)
    HideStatusBar()

    val dismissTravelPx = with(density) { DISMISS_TRAVEL.toPx() }
    // 0 → at rest, 1 → fully dragged. Drives background fade + content scale.
    val dragProgress = (abs(dragOffset.y) / dismissTravelPx).coerceAtMost(1f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - dragProgress * 0.7f)),
        )

        Crossfade(
            targetState = currentIndex,
            animationSpec = tween(PAGE_FADE_MS, easing = EaseInOut),
            label = "page",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = (abs(dragOffset.y) / dismissTravelPx).coerceAtMost(1f)
                    scaleX = 1f - p * 0.18f
                    scaleY = 1f - p * 0.18f
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                }
                .clickable(interactionSource = chromeInteraction, indication = null) {
                    chromeVisible = !chromeVisible
                }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            settle.job?.cancel()
                            total = dragOffset
                            tracker.resetTracking()
                        },
                        onDrag = { change, delta ->
                            total += delta
                            // Fed the ACCUMULATED translation, not
                            // change.position: this node sits after the
                            // graphicsLayer that tracks the finger, so local
                            // positions are near-constant during a drag and
                            // would measure ~zero velocity — killing the
                            // flick-to-dismiss branch below.
                            tracker.addPosition(change.uptimeMillis, total)
                            // Only a predominantly vertical drag is a dismiss;
                            // horizontal stays with the content.
                            if (abs(total.y) > abs(total.x)) dragOffset = total
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            // |translation| > 150 OR |predictedEnd| > 450, the
                            // drawer's quarter-second projection.
                            val projected = dragOffset.y + velocity.y * PROJECTION_SECONDS
                            if (abs(dragOffset.y) > 150.dp.toPx() ||
                                abs(projected) > 450.dp.toPx()
                            ) {
                                onDismiss()
                            } else {
                                settle.job = scope.launch {
                                    settleBack(dragOffset, velocity.y) { dragOffset = it }
                                }
                            }
                        },
                        onDragCancel = {
                            settle.job = scope.launch {
                                settleBack(dragOffset, 0f) { dragOffset = it }
                            }
                        },
                    )
                },
        ) { index ->
            attachments.getOrNull(index)?.let { PreviewContent(it, Modifier.fillMaxSize()) }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(CHROME_FADE_MS, easing = EaseInOut)),
            exit = fadeOut(tween(CHROME_FADE_MS, easing = EaseInOut)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Header(
                    attachment = attachments.getOrNull(currentIndex),
                    index = currentIndex,
                    count = attachments.size,
                    onDownload = { attachments.getOrNull(currentIndex)?.let(downloader::download) },
                    onClose = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                if (attachments.size > 1) {
                    PagingControls(
                        index = currentIndex,
                        count = attachments.size,
                        onPrevious = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
                        onNext = {
                            currentIndex = (currentIndex + 1).coerceAtMost(attachments.size - 1)
                        },
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }
        }

        ToastHost(toast)
    }
}

/** Spring the dragged content home — iOS `interactiveSpring(0.35, 0.8)`. */
private suspend fun settleBack(from: Offset, velocityY: Float, update: (Offset) -> Unit) {
    animate(
        typeConverter = Offset.VectorConverter,
        initialValue = from,
        targetValue = Offset.Zero,
        initialVelocity = Offset(0f, velocityY),
        // response 0.35s ⇒ stiffness (2π/0.35)² ≈ 322 at unit mass.
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 322f),
        block = { value, _ -> update(value) },
    )
}

@Composable
private fun Header(
    attachment: Attachment?,
    index: Int,
    count: Int,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                attachment?.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count > 1) {
                Text(
                    "${index + 1} / $count",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }

        // Hidden for a blank url, like iOS's `URL(string:)` guard.
        if (attachment != null && attachment.url.isNotBlank()) {
            ChromeButton(
                painterId = R.drawable.ic_download,
                iconSize = 22.dp,
                label = "Download",
                onClick = onDownload,
            )
        }
        ChromeButton(
            painterId = R.drawable.ic_cross,
            iconSize = 26.dp,
            label = "Close",
            onClick = onClose,
        )
    }
}

@Composable
private fun PagingControls(
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        ChromeButton(
            painterId = R.drawable.ic_chevron_left,
            iconSize = 30.dp,
            label = "Previous",
            enabled = index > 0,
            onClick = onPrevious,
        )
        Spacer(Modifier.weight(1f))
        ChromeButton(
            painterId = R.drawable.ic_chevron_left,
            iconSize = 30.dp,
            label = "Next",
            enabled = index < count - 1,
            onClick = onNext,
            // Rotate the chevron rather than mint a mirrored asset. The whole
            // square target rotates, which is the same visual — the glyph is
            // its only content.
            modifier = Modifier.rotate(180f),
        )
    }
}

/**
 * One chrome affordance: a 44dp tap target around a white glyph, at iOS's
 * opacities (0.8 enabled / 0.2 disabled).
 */
@Composable
private fun ChromeButton(
    painterId: Int,
    iconSize: Dp,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            ),
    ) {
        Icon(
            painterResource(painterId),
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 0.8f else 0.2f),
            modifier = Modifier.size(iconSize),
        )
    }
}

// ── Per-type content ─────────────────────────────────────────────────────

private enum class Category { IMAGE, VIDEO, AUDIO, PDF, UNSUPPORTED }

private fun categoryOf(attachment: Attachment): Category {
    val t = attachment.contentType.lowercase(Locale.US)
    return when {
        t.startsWith("image/") -> Category.IMAGE
        t.startsWith("video/") -> Category.VIDEO
        t.startsWith("audio/") -> Category.AUDIO
        t == "application/pdf" -> Category.PDF
        else -> Category.UNSUPPORTED
    }
}

@Composable
private fun PreviewContent(attachment: Attachment, modifier: Modifier = Modifier) {
    val blankUrl = attachment.url.isBlank()
    Box(modifier, contentAlignment = Alignment.Center) {
        when (categoryOf(attachment)) {
            Category.IMAGE ->
                if (blankUrl) {
                    UnsupportedView(attachment.name, "image")
                } else {
                    SubcomposeAsyncImage(
                        model = attachment.url,
                        contentDescription = attachment.name,
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                OrigonSpinner(Color.White)
                            }
                        },
                        error = { UnsupportedView(attachment.name, "image") },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    )
                }
            Category.VIDEO ->
                if (blankUrl) UnsupportedView(attachment.name, "video")
                else VideoContent(attachment)
            Category.AUDIO -> AudioContent(attachment)
            Category.PDF ->
                if (blankUrl) UnsupportedView(attachment.name, "pdf")
                else PdfContent(attachment)
            Category.UNSUPPORTED -> UnsupportedView(attachment.name, "file")
        }
    }
}

@Composable
private fun VideoContent(attachment: Attachment, modifier: Modifier = Modifier) {
    // key(): a changed url tears the whole view down and rebuilds — the factory
    // runs once per key, so rebinding in place would keep playing the old URI
    // and write a stale `failed` cell.
    key(attachment.url) {
        VideoSurface(attachment, modifier)
    }
}

@Composable
private fun VideoSurface(attachment: Attachment, modifier: Modifier = Modifier) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        UnsupportedView(attachment.name, "video")
        return
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                // `true` suppresses the platform's own error dialog; the
                // composable swaps to the unsupported view instead.
                setOnErrorListener { _, _, _ ->
                    failed = true
                    true
                }
                // iOS's AVPlayer shows the first frame paused; VideoView shows
                // black until playback starts, so paint the frame without
                // autoplaying.
                setOnPreparedListener { seekTo(1) }
                setVideoURI(attachment.url.toUri())
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun AudioContent(attachment: Attachment, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape),
        ) {
            // iOS's SF `waveform` does not port; Origon's own five-bar waveform
            // (the voice-channel icon) is the catalog stand-in.
            Icon(
                painterResource(R.drawable.ic_voice_channel),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            attachment.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        if (attachment.url.isNotBlank()) {
            AudioPlayerBar(attachment.url, Modifier.width(320.dp))
        }
    }
}

/**
 * Minimal transport over the platform `MediaPlayer` — play/pause, scrub,
 * elapsed/total. iOS embeds `AVPlayerViewController`'s controls; Android has no
 * standalone audio-controls widget to embed.
 */
@Composable
private fun AudioPlayerBar(url: String, modifier: Modifier = Modifier) {
    val player = remember(url) { MediaPlayer() }
    var prepared by remember(url) { mutableStateOf(false) }
    var playing by remember(url) { mutableStateOf(false) }
    val context = LocalContext.current
    // Pauses rather than ducking when something else takes focus — a call
    // starting is the case that matters, and half-volume speech under a call is
    // worse than silence.
    val focus = remember(url) {
        AudioFocus.forMediaPlayback(context) {
            if (player.isPlaying) player.pause()
            playing = false
        }
    }
    var failed by remember(url) { mutableStateOf(false) }
    var durationMs by remember(url) { mutableIntStateOf(0) }
    var positionMs by remember(url) { mutableIntStateOf(0) }

    DisposableEffect(url) {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setOnPreparedListener {
            prepared = true
            durationMs = it.duration
        }
        // Focus goes back on EVERY path that stops playback, not just the pause
        // tap: playing a clip to the end is the likeliest way to finish with it,
        // and holding media focus with nothing playing leaves whatever we
        // interrupted paused indefinitely.
        player.setOnCompletionListener {
            playing = false
            focus.abandon()
        }
        player.setOnErrorListener { _, _, _ ->
            failed = true
            playing = false
            focus.abandon()
            true
        }
        try {
            player.setDataSource(url)
            player.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "audio source failed: $e")
            failed = true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "audio source failed: $e")
            failed = true
        }
        // Focus is given back on the way out too: a preview dismissed
        // mid-playback would otherwise leave the app holding focus with nothing
        // playing.
        onDispose {
            focus.abandon()
            player.release()
        }
    }

    // Poll while playing; the loop and `release` share the main thread, so a
    // cancelled tick can never read a released player.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition
            delay(200)
        }
    }

    if (failed) {
        Text(
            "Couldn't play this audio",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        ChromeButton(
            painterId = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            iconSize = 18.dp,
            label = if (playing) "Pause" else "Play",
            enabled = prepared,
            onClick = {
                if (playing) {
                    player.pause()
                    playing = false
                    focus.abandon()
                } else {
                    // **Focus is taken before playback, and given back on
                    // pause.** Without this, tapping play mid-call would talk
                    // over the call rather than the system arbitrating between
                    // them. A refusal still plays — the platform does not
                    // enforce focus — but the request is what lets the *other*
                    // holder react.
                    focus.request()
                    player.start()
                    playing = true
                }
            },
        )
        Slider(
            value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
            onValueChange = { fraction ->
                if (prepared && durationMs > 0) {
                    val target = (fraction * durationMs).toInt()
                    player.seekTo(target)
                    positionMs = target
                }
            },
            enabled = prepared,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${formatClock(positionMs)} / ${formatClock(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}

private fun formatClock(ms: Int): String {
    // MediaPlayer reports duration -1 for streams it cannot size — that already
    // truncated to "0:00"; the clamp closes the rest of the negative domain,
    // where ms ≤ -1000 rendered "0:-1" / "-1:-1".
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

// ── PDF ──────────────────────────────────────────────────────────────────

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data object Failed : PdfLoad
    class Ready(val doc: PdfDoc) : PdfLoad
}

/**
 * An open `PdfRenderer` plus the guards it needs: the renderer allows one open
 * page at a time and dies on use-after-close, while page rendering happens on
 * IO threads and close happens on the main one. A plain monitor serializes them
 * — `close` blocks until an in-flight page render finishes (tens of
 * milliseconds), which is the price of never racing the native handle. Closing
 * also deletes the backing temp file: the doc owns its bytes end-to-end, so
 * nothing accrues in cacheDir past the preview.
 */
private class PdfDoc(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val file: File,
) {
    private val lock = Any()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    /**
     * Null on a damaged page or after close — the caller renders a blank page,
     * never a crash. `openPage` throws `IllegalStateException` from native code
     * on corrupt content that survived the constructor, and the bitmap is capped
     * at [MAX_PAGE_PIXELS] (a 1×10000-unit page would otherwise ask for a
     * multi-GB allocation): the page renders whole at reduced resolution rather
     * than OOMing.
     */
    fun renderPage(index: Int, widthPx: Int): ImageBitmap? = synchronized(lock) {
        if (closed || widthPx <= 0) return null
        try {
            renderer.openPage(index).use { page ->
                var width = widthPx
                var height =
                    (page.height * widthPx.toFloat() / page.width).toInt().coerceAtLeast(1)
                val pixels = width.toLong() * height
                if (pixels > MAX_PAGE_PIXELS) {
                    val scale = kotlin.math.sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
                    width = (width * scale).toInt().coerceAtLeast(1)
                    height = (height * scale).toInt().coerceAtLeast(1)
                }
                val bitmap = createBitmap(width, height)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                // Null transform = fit the whole destination, the platform's
                // fit-width render.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "page $index render failed: $e")
            null
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            runCatching { renderer.close() }
                .onFailure { Log.w(TAG, "renderer close failed: $it") }
            runCatching { pfd.close() }
                .onFailure { Log.w(TAG, "pfd close failed: $it") }
            if (!file.delete()) Log.w(TAG, "couldn't delete ${file.name}")
        }
    }

    private companion object {
        /** ~48 MB of ARGB — plenty for a fit-width page, far from OOM. */
        const val MAX_PAGE_PIXELS = 12_000_000L
    }
}

/**
 * Fetch to cache, then render. `PdfRenderer` needs a seekable file, so the wire
 * stream cannot feed it directly. Pages render on demand per LazyColumn item —
 * an eagerly-rendered 100-page document would hold hundreds of MB of bitmaps.
 *
 * The backing file is a fresh temp file per load — never named from wire data
 * (an attachment id in a path is the traversal shape), never shared between two
 * concurrent loads of the same document (the Crossfade window keeps both pages
 * alive briefly), and deleted by [PdfDoc.close].
 */
@Composable
private fun PdfContent(attachment: Attachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var load by remember(attachment.url) { mutableStateOf<PdfLoad>(PdfLoad.Loading) }

    LaunchedEffect(attachment.url) {
        load = PdfLoad.Loading
        // The IO block below has no suspension points, so cancellation is seen
        // only when withContext resumes — at which point a fully-built doc would
        // be silently dropped, leaking the fd and renderer. The holder hands it
        // to the cancellation arm instead.
        var built: PdfDoc? = null
        try {
            load = withContext(Dispatchers.IO) {
                try {
                    val file = File.createTempFile("preview-", ".pdf", context.cacheDir)
                    var pfd: ParcelFileDescriptor? = null
                    try {
                        val request = Request.Builder().url(attachment.url).build()
                        Http.shared.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            file.outputStream().use { response.body.byteStream().copyTo(it) }
                        }
                        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        // The constructor throws on corrupt or
                        // password-protected input — uploader-controlled, since
                        // contentType is just a wire claim.
                        val doc = PdfDoc(pfd, PdfRenderer(pfd), file)
                        built = doc
                        PdfLoad.Ready(doc)
                    } catch (e: Throwable) {
                        runCatching { pfd?.close() }
                        file.delete()
                        throw e
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "pdf load failed: $e")
                    PdfLoad.Failed
                }
            }
        } catch (e: CancellationException) {
            built?.close()
            throw e
        }
    }
    DisposableEffect(attachment.url) {
        onDispose { (load as? PdfLoad.Ready)?.doc?.close() }
    }

    when (val state = load) {
        PdfLoad.Loading -> OrigonSpinner(Color.White, modifier)
        PdfLoad.Failed -> UnsupportedView(attachment.name, "pdf")
        is PdfLoad.Ready -> BoxWithConstraints(modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            LazyColumn(
                // Center a document shorter than the viewport — what keeps the
                // white chrome text on the black backdrop rather than on a white
                // page edge-to-edge.
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.doc.pageCount) { index ->
                    PdfPage(state.doc, index, widthPx)
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    var bitmap by remember(doc, index) { mutableStateOf<ImageBitmap?>(null) }
    var attempted by remember(doc, index) { mutableStateOf(false) }
    LaunchedEffect(doc, index, widthPx) {
        bitmap = withContext(Dispatchers.IO) { doc.renderPage(index, widthPx) }
        attempted = true
    }
    val page = bitmap
    if (page != null) {
        Image(
            page,
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // A4-ish placeholder keeps the scroll length stable: a spinner while the
        // page renders, a blank sheet if the render came back null (a damaged
        // page — the failure is logged, not fatal).
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)
                .background(if (attempted) Color.White.copy(alpha = 0.1f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (!attempted) OrigonSpinner(Color.White)
        }
    }
}

@Composable
private fun UnsupportedView(
    name: String,
    contentTypeLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
        ) {
            Icon(
                painterResource(R.drawable.ic_file),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                // The glyph's 14:16 intrinsic ratio at iOS's 40pt size.
                modifier = Modifier.size(35.dp, 40.dp),
            )
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(
            contentTypeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

// ── Status bar ───────────────────────────────────────────────────────────

/** iOS `.statusBar(hidden: true)` for the overlay's lifetime. */
@Composable
private fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
            ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        // Without this, a user edge-swipe re-shows the bar permanently for the
        // preview's whole lifetime; transient is the intended behaviour.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TAG = "AttachmentsPreview"

/** Mutable job slot with no composition semantics. */
private class JobRef {
    var job: Job? = null
}

/** iOS `dismissTravel` — drag distance to full background fade. */
private val DISMISS_TRAVEL = 250.dp

/** The drawer's quarter-second flick projection. */
private const val PROJECTION_SECONDS = 0.25f

private const val CHROME_FADE_MS = 250
private const val PAGE_FADE_MS = 200

// ── Previews ─────────────────────────────────────────────────────────────

@Preview(name = "unsupported + chrome", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AttachmentsPreviewUnsupported() {
    OrigonTheme {
        AttachmentsPreview(
            attachments = listOf(
                Attachment(
                    id = "a1",
                    name = "archive-with-a-long-name.zip",
                    contentType = "application/zip",
                    url = "https://example.invalid/a1",
                ),
                Attachment(
                    id = "a2",
                    name = "notes.txt",
                    contentType = "text/plain",
                    url = "https://example.invalid/a2",
                ),
            ),
            activeIndex = 0,
            onDismiss = {},
        )
    }
}
