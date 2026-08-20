package origon.example.android.ui.components

import ai.origon.sdk.Attachment
import ai.origon.sdk.Message
import ai.origon.sdk.MessageButton
import ai.origon.sdk.MessageRole
import ai.origon.sdk.MessageStatus
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import origon.example.android.R
import origon.example.android.services.ChatService
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.OrigonTheme
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.min

/**
 * One transcript row, matching the shipped Origon apps.
 *
 * A lifecycle system row (action present: queued / joined / ended) renders as
 * a centered divider; a `role == SYSTEM` row WITHOUT an action is a connect
 * flow-bot message and keeps bubble rendering. **The discriminator is
 * action-presence, NOT role** — branching on `role == SYSTEM` is silently
 * wrong, because the server builds flow-bot prompts as exactly that shape.
 *
 * The vocabulary (`queued` / `joined` / `ended`) is connect's to define; see
 * the `Message.action` field in the SDK's `Models.kt`. Do not add values here.
 *
 * Reveal state is hoisted ([revealed]/[onToggleRevealed]) — the chat screen
 * owns which single row shows its timestamp. Attachment open and download are
 * hoisted the same way: the full-screen pager and the download helper belong
 * to the screen, so this component exposes the affordances and owns neither.
 */
@Composable
fun MessageBubble(
    message: Message,
    revealed: Boolean,
    onToggleRevealed: () -> Unit,
    onAttachmentTap: (Int) -> Unit,
    onDownloadAttachment: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this message's prompt options are still answerable. */
    promptIsLive: Boolean = false,
    /** Which option was picked on this prompt, if any. */
    promptSelection: ChatService.PromptSelection? = null,
    /** `(cardIndex, label, value, galleryLabel)`. Null disables prompts. */
    onPromptReply: ((Int?, String, String, String?) -> Unit)? = null,
) {
    val action = message.action
    if (!action.isNullOrEmpty()) {
        SystemDivider(text = message.text.orEmpty(), modifier = modifier)
    } else {
        BubbleBody(
            message = message,
            revealed = revealed,
            onToggleRevealed = onToggleRevealed,
            onAttachmentTap = onAttachmentTap,
            onDownloadAttachment = onDownloadAttachment,
            promptIsLive = promptIsLive,
            promptSelection = promptSelection,
            onPromptReply = onPromptReply,
            modifier = modifier,
        )
    }
}

/**
 * Centered divider for a lifecycle system row. The label is the server's
 * formatted `text` — "Bo has joined", "Conversation has ended" — rendered
 * VERBATIM. The server owns the phrasing and pins the actor into `text`, so
 * the client never builds wording from `userName`.
 */
@Composable
private fun SystemDivider(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        DividerLine(Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = OrigonTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        DividerLine(Modifier.weight(1f))
    }
}

@Composable
private fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.height(1.dp).background(OrigonTheme.colors.border))
}

@Composable
private fun BubbleBody(
    message: Message,
    revealed: Boolean,
    onToggleRevealed: () -> Unit,
    onAttachmentTap: (Int) -> Unit,
    onDownloadAttachment: (Attachment) -> Unit,
    promptIsLive: Boolean,
    promptSelection: ChatService.PromptSelection?,
    onPromptReply: ((Int?, String, String, String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isSelfUser = message.role == MessageRole.EXTERNAL
    val hasText = !message.text.isNullOrEmpty() || !message.html.isNullOrEmpty()
    val rich by produceState<List<ExampleRichBlock>>(
        initialValue = emptyList(), message.html, message.text,
    ) {
        value = ExampleRichText.parse(message.html, message.text).blocks
    }
    val interaction = remember { MutableInteractionSource() }

    // iOS `Spacer(minLength: 60)` on the far side. SwiftUI's Spacer expands
    // greedily, so the inset is exactly 60 and the bubble may use the rest;
    // padding on the full-width row reproduces that.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isSelfUser) 60.dp else 0.dp,
                end = if (isSelfUser) 0.dp else 60.dp,
            ),
        horizontalArrangement = if (isSelfUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isSelfUser) Alignment.End else Alignment.Start,
            modifier = Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggleRevealed,
            ),
        ) {
            if (hasText) {
                ExampleRichMessageText(
                    blocks = rich,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelfUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        OrigonTheme.colors.textPrimary
                    },
                    modifier = Modifier
                        .bubbleShadow(BubbleShape)
                        .background(
                            if (isSelfUser) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                OrigonTheme.colors.peerBubble
                            },
                            BubbleShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            message.attachments.forEachIndexed { index, attachment ->
                AttachmentRow(
                    attachment = attachment,
                    isSelfUser = isSelfUser,
                    onTap = { onAttachmentTap(index) },
                    onDownload = { onDownloadAttachment(attachment) },
                    // iOS VStack spacing 6; the first row sits flush when no
                    // text bubble precedes it.
                    modifier = Modifier.padding(
                        top = if (index == 0 && !hasText) 0.dp else 6.dp,
                    ),
                )
            }

            // Interactive prompt options, below the text bubble. A prompt rides
            // `role: SYSTEM` with NO action, so it reached us on the bubble
            // branch above — which is what makes "under the text" the right
            // place rather than an assumption.
            if (onPromptReply != null) {
                val handleTap: (Int?, String?, MessageButton) -> Unit =
                    { cardIndex, galleryLabel, button ->
                        // A `"url"` option opens the link **and** posts the
                        // reply, matching the web client: the flow still has to
                        // walk that option's edge, otherwise tapping a link
                        // would strand the conversation on a waiter that never
                        // resolves.
                        if (button.buttonType == "url" && button.value.isNotEmpty()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, button.value.toUri()),
                                )
                            }.onFailure { Log.w(TAG, "couldn't open ${button.value}: $it") }
                        }
                        onPromptReply(cardIndex, button.label, button.value, galleryLabel)
                    }

                if (message.buttons.isNotEmpty()) {
                    MessageButtons(
                        buttons = message.buttons,
                        isLive = promptIsLive,
                        selectedLabel = promptSelection?.buttonLabel,
                        onTap = { button -> handleTap(null, null, button) },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                if (message.gallery.isNotEmpty()) {
                    MessageGallery(
                        cards = message.gallery,
                        isLive = promptIsLive,
                        selection = promptSelection,
                        onTap = { cardIndex, card, button ->
                            handleTap(cardIndex, card.title, button)
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (message.status == MessageStatus.FAILED) {
                Text(
                    message.errorText?.takeIf { it.isNotEmpty() } ?: "Failed to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
            }

            // Computed per composition — the parse is microseconds, and a memo
            // keyed on the string alone would bake the device timezone into the
            // cached stamp.
            val stamp = message.timestamp?.let(::formatTimestamp).orEmpty()
            AnimatedVisibility(
                visible = revealed && message.status != MessageStatus.SENDING &&
                    stamp.isNotEmpty(),
                enter = fadeIn(tween(REVEAL_MS, easing = EaseInOut)) +
                    expandVertically(tween(REVEAL_MS, easing = EaseInOut)),
                exit = fadeOut(tween(REVEAL_MS, easing = EaseInOut)) +
                    shrinkVertically(tween(REVEAL_MS, easing = EaseInOut)),
            ) {
                Text(
                    stamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = OrigonTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

// ── Attachment row ───────────────────────────────────────────────────────
//
// A 44dp-tall rounded capsule with an image thumbnail or file glyph on the
// left, the filename, and a download affordance on the right.

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    isSelfUser: Boolean,
    onTap: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val downloadInteraction = remember { MutableInteractionSource() }
    val foreground = if (isSelfUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        OrigonTheme.colors.textPrimary
    }
    val secondary = if (isSelfUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        OrigonTheme.colors.textSecondary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .heightIn(min = 44.dp)
            .bubbleShadow(CapsuleShape)
            .background(
                if (isSelfUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    OrigonTheme.colors.peerBubble
                },
                CapsuleShape,
            )
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // A thumbnail needs BOTH an image type and a usable url, so an
        // image-typed row with a blank url falls to the file glyph rather than
        // a never-resolving box.
        if (attachment.contentType.startsWith("image/") && attachment.url.isNotBlank()) {
            // The wire's `url` is rendered verbatim — the server mints it on a
            // route that needs no header coil cannot send.
            AsyncImage(
                model = attachment.url,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Gray.copy(alpha = 0.2f)),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                // Black @ 12% in BOTH themes — a literal, not an adaptive
                // colour; carried over unchanged.
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
            ) {
                Icon(
                    painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    tint = secondary,
                )
            }
        }

        // weight(fill = true): the text's box absorbs the slack the way iOS's
        // greedy Spacer does, keeping the download affordance flush right —
        // and, like iOS, growing the capsule to its 280dp cap.
        Text(
            attachment.name,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clickable(
                    interactionSource = downloadInteraction,
                    indication = null,
                    onClickLabel = "Download ${attachment.name}",
                    onClick = onDownload,
                ),
        ) {
            Icon(
                painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── Shapes + shadow ──────────────────────────────────────────────────────

/**
 * iOS `BubbleShape`: corner radius `min(height / 2, 22)` — a pill on a
 * single-line bubble, 22 on taller ones. iOS draws `.continuous`
 * (superellipse) corners; Compose has no continuous style, so circular
 * corners are the closest available rendering.
 */
private object BubbleShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = min(size.height / 2f, with(density) { 22.dp.toPx() })
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius, radius)))
    }
}

private val CapsuleShape = RoundedCornerShape(10.dp)

/**
 * iOS's `.shadow(color: .black.opacity(0.04), radius: 2, y: 1)` over the
 * bubble stack. Two recorded deviations: iOS shadows the *entire* stack —
 * error text and timestamp glyphs included — where this applies only to the
 * shaped surfaces (Compose's `shadow` wants a shape, and shadowed text glyphs
 * read as smearing); and below API 28 the ambient/spot colours are ignored, so
 * the platform's default shadow alpha applies — slightly more visible than
 * intended, accepted (the 4% shadow is barely perceptible either way).
 */
private fun Modifier.bubbleShadow(shape: Shape): Modifier = shadow(
    elevation = 2.dp,
    shape = shape,
    ambientColor = Color.Black.copy(alpha = 0.04f),
    spotColor = Color.Black.copy(alpha = 0.04f),
)

// ── Timestamp ────────────────────────────────────────────────────────────

/** Display formatter — iOS `"h:mm a"`. Locale pinned so it never drifts. */
private val TIME_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * `ISO_OFFSET_DATE_TIME` accepts an optional fractional-seconds part, which is
 * the two-stage parse iOS spells by hand. Rendered in the device zone.
 *
 * Needs core-library desugaring at this app's minSdk 23 — see
 * `app/build.gradle.kts`.
 */
internal fun formatTimestamp(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try {
    OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        .atZoneSameInstant(zone)
        .format(TIME_LABEL)
} catch (_: DateTimeParseException) {
    ""
}

private const val REVEAL_MS = 200

private const val TAG = "MessageBubble"

// ── Preview matrix (roles × states × attachments) ────────────────────────

@Composable
private fun MessageBubbleMatrix() {
    val image = Attachment(
        id = "a1",
        name = "holiday-photo.png",
        contentType = "image/png",
        url = "https://example.invalid/a1",
    )
    val pdf = Attachment(
        id = "a2",
        name = "a-quarterly-report-with-a-very-long-filename.pdf",
        contentType = "application/pdf",
        url = "https://example.invalid/a2",
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(OrigonTheme.colors.screenBackground)
            .padding(16.dp),
    ) {
        Bubble(Message(role = MessageRole.AI, id = "1", text = "Hello! How can I help you today?"))
        Bubble(
            Message(
                role = MessageRole.EXTERNAL,
                id = "2",
                text = "I need a hand with my order — it never arrived.",
            ),
        )
        Bubble(
            Message(
                role = MessageRole.EXTERNAL,
                id = "3",
                text = "Sending…",
                status = MessageStatus.SENDING,
            ),
            // Revealed but SENDING: the timestamp must stay suppressed.
            revealed = true,
        )
        Bubble(
            Message(
                role = MessageRole.EXTERNAL,
                id = "4",
                text = "This one failed.",
                status = MessageStatus.FAILED,
            ),
        )
        Bubble(Message(role = MessageRole.SYSTEM, id = "5", text = "Bo has joined", action = "joined"))
        Bubble(
            Message(
                role = MessageRole.SYSTEM,
                id = "6",
                text = "Conversation has ended",
                action = "ended",
            ),
        )
        // Flow-bot system row: NO action, so it keeps bubble rendering. This is
        // the case a `role == SYSTEM` branch would get wrong.
        Bubble(Message(role = MessageRole.SYSTEM, id = "7", text = "Pick an option below to continue."))
        Bubble(
            Message(
                role = MessageRole.EXTERNAL,
                id = "8",
                text = "Here are the files.",
                attachments = listOf(image, pdf),
            ),
        )
        Bubble(
            Message(
                role = MessageRole.AI,
                id = "9",
                text = "Revealed timestamp below.",
                timestamp = "2026-08-03T14:02:00+00:00",
            ),
            revealed = true,
        )
        TypingIndicator()
    }
}

@Composable
private fun Bubble(message: Message, revealed: Boolean = false) {
    MessageBubble(
        message = message,
        revealed = revealed,
        onToggleRevealed = {},
        onAttachmentTap = {},
        onDownloadAttachment = {},
    )
}

@Preview(name = "matrix light", showBackground = true, heightDp = 1000)
@Composable
private fun MessageBubbleMatrixLight() {
    OrigonTheme(darkTheme = false) { MessageBubbleMatrix() }
}

@Preview(name = "matrix dark", showBackground = true, backgroundColor = 0xFF111111, heightDp = 1000)
@Composable
private fun MessageBubbleMatrixDark() {
    OrigonTheme(darkTheme = true) { MessageBubbleMatrix() }
}
