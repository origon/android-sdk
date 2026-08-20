package origon.example.android.ui.components

import ai.origon.sdk.MessageButton
import ai.origon.sdk.MessageCard
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import origon.example.android.services.ChatService
import origon.example.android.ui.theme.OrigonTheme

/**
 * A card's fixed width. The CAROUSEL spans the full transcript width; each card
 * does not — a full-width card shows one at a time and hides that there are
 * more. This cap keeps the next card peeking, which is the affordance that says
 * "scrollable".
 */
internal object ExampleGalleryPolicy {
    const val CARD_WIDTH_DP = 280
    const val CARD_SPACING_DP = 16
    fun imageUrl(card: MessageCard): String? =
        card.image?.url?.takeIf { ExampleRichText.safeHttpUrl(it) != null }
}

private val CardWidth = ExampleGalleryPolicy.CARD_WIDTH_DP.dp
private val CardImageHeight = 150.dp
private val CardShape = RoundedCornerShape(14.dp)

/**
 * Horizontal card carousel under a flow-authored Gallery prompt.
 *
 * Cards and their buttons are rendered by **index** throughout: titles and
 * button values may legitimately repeat across cards, which is exactly why a
 * gallery reply carries the card title alongside the value.
 *
 * **Equal card heights come from `IntrinsicSize.Max`**, so a short card's
 * action buttons sit at the bottom edge rather than floating halfway up beside
 * a taller neighbour. iOS has to measure this by hand with a preference key,
 * because SwiftUI has no cross-sibling height negotiation; Compose's intrinsics
 * do it in one modifier, so the hand-rolled measurement is dropped rather than
 * ported.
 */
@Composable
fun MessageGallery(
    cards: List<MessageCard>,
    isLive: Boolean,
    /**
     * The picked option — `cardIndex` is null when it came from a restored
     * transcript, which cannot say which card it was.
     */
    selection: ChatService.PromptSelection?,
    /** `(cardIndex, card, button)`. */
    onTap: (Int, MessageCard, MessageButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(ExampleGalleryPolicy.CARD_SPACING_DP.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .height(IntrinsicSize.Max),
    ) {
        cards.forEachIndexed { cardIndex, card ->
            key(cardIndex) {
                GalleryCard(
                    card = card,
                    isLive = isLive,
                    isSelected = { button -> selection.matches(cardIndex, button) },
                    onTap = { button -> onTap(cardIndex, card, button) },
                )
            }
        }
    }
}

/**
 * A live pick knows its card, so it highlights exactly one option. A pick
 * recovered from history knows only the caption — so it may match the same
 * label on more than one card. That over-match is accepted, not an oversight:
 * the wire carries nothing that could resolve it, because the server persists
 * neither the chosen value nor the card title.
 */
private fun ChatService.PromptSelection?.matches(cardIndex: Int, button: MessageButton): Boolean {
    val selection = this ?: return false
    if (selection.buttonLabel != button.label) return false
    val picked = selection.cardIndex ?: return true
    return picked == cardIndex
}

@Composable
private fun GalleryCard(
    card: MessageCard,
    isLive: Boolean,
    isSelected: (MessageButton) -> Boolean,
    onTap: (MessageButton) -> Unit,
) {
    Column(
        // The card fills the row's tallest intrinsic height, so its actions
        // land on the bottom edge.
        modifier = Modifier
            .width(CardWidth)
            // Stretches this card to the row's tallest intrinsic height.
            .fillMaxHeight()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, OrigonTheme.colors.border, CardShape),
    ) {
        // `image` is legitimately null — the server emits null for a card
        // authored without one. Unwrapping it unconditionally would take down
        // the whole carousel, not just this card.
        val imageUrl = ExampleGalleryPolicy.imageUrl(card)
        if (!imageUrl.isNullOrEmpty()) {
            Box(
                Modifier
                    .width(CardWidth)
                    .height(CardImageHeight)
                    // Shows through while the image loads and stays if it
                    // fails, so a broken URL leaves a neutral panel rather than
                    // a hole in the card.
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // **`AsyncImage`, deliberately not `SubcomposeAsyncImage`.**
                // The carousel measures its cards with `IntrinsicSize.Max`, and
                // a SubcomposeLayout cannot answer an intrinsic query — it
                // throws. The fixed-size Box above would mask it today, but the
                // hazard would be one layout tweak away, and the loading/error
                // slots a Subcompose buys are exactly what the background here
                // already covers.
                //
                // No auth header: the server mints a public capability URL for
                // gallery images, and its GET is deliberately tokenless.
                AsyncImage(
                    model = imageUrl,
                    contentDescription = card.title.ifEmpty { null },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            if (card.title.isNotEmpty()) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = OrigonTheme.colors.textPrimary,
                )
            }
            if (card.description.isNotEmpty()) {
                Text(
                    card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OrigonTheme.colors.textSecondary,
                )
            }
        }

        // Sinks the actions to the bottom once the card is stretched to the
        // row's tallest. Its intrinsic height is 0, so it does not inflate the
        // row's max-intrinsic measurement — it only absorbs slack afterwards.
        Spacer(Modifier.weight(1f))

        for (button in card.buttons) {
            CardActionButton(
                label = button.label,
                isSelected = isSelected(button),
                isLive = isLive,
                onClick = { onTap(button) },
            )
        }
    }
}

/**
 * A gallery card's action row: full-bleed, separated by a hairline rather than
 * shaped as a pill. Card actions read as rows of the card rather than as
 * free-floating chips.
 */
@Composable
private fun CardActionButton(
    label: String,
    isSelected: Boolean,
    isLive: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = isLive,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        // The hairline sits on TOP of the row, which is the seam between this
        // action and whatever precedes it.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(OrigonTheme.colors.border),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isLive || isSelected) accent else OrigonTheme.colors.textTertiary,
        )
    }
}

@Preview(name = "gallery light", showBackground = true, widthDp = 360)
@Composable
private fun MessageGalleryLight() {
    OrigonTheme(darkTheme = false) { GalleryMatrix() }
}

@Preview(name = "gallery dark", showBackground = true, backgroundColor = 0xFF111111, widthDp = 360)
@Composable
private fun MessageGalleryDark() {
    OrigonTheme(darkTheme = true) { GalleryMatrix() }
}

@Composable
private fun GalleryMatrix() {
    val cards = listOf(
        MessageCard(
            title = "Standard delivery",
            description = "Arrives in 3–5 working days.",
            buttons = listOf(MessageButton(label = "Choose", value = "standard")),
        ),
        // Deliberately taller: proves every card pins to the row's max height
        // and the actions still land on the bottom edge.
        MessageCard(
            title = "Express delivery",
            description = "Arrives tomorrow if you order in the next two hours. " +
                "Carries a surcharge, and is not available to every postcode.",
            buttons = listOf(
                MessageButton(label = "Choose", value = "express"),
                MessageButton(label = "See the terms", value = "https://example.invalid", buttonType = "url"),
            ),
        ),
    )
    Box(
        Modifier
            .background(OrigonTheme.colors.screenBackground)
            .padding(vertical = 16.dp),
    ) {
        MessageGallery(
            cards = cards,
            isLive = true,
            selection = ChatService.PromptSelection(cardIndex = 0, buttonLabel = "Choose"),
            onTap = { _, _, _ -> },
        )
    }
}
