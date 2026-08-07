package origon.example.android.ui.components

import ai.origon.sdk.MessageButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import origon.example.android.ui.theme.OrigonTheme

/** A pill's shape — the same capsule on the button row and inside a card. */
private val PillShape = RoundedCornerShape(percent = 50)

/**
 * The option row under a flow-authored Button prompt.
 *
 * Options are rendered by **index**, never keyed by label or value: a flow
 * author is free to repeat either, so a key built from the caption would
 * collide.
 *
 * `FlowRow` rather than a plain `Row`: a prompt's options are author-written
 * text of unpredictable width, and a single row would push them off-screen.
 * (iOS has to hand-write a `Layout` for this; Compose ships it.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageButtons(
    buttons: List<MessageButton>,
    /**
     * `false` once the prompt has been answered or the session ended — the
     * pills stay visible (they are part of the transcript) but stop
     * responding, so the user can still read what was offered.
     */
    isLive: Boolean,
    /**
     * The option the user picked, if any. Matched on the caption because that
     * is all a restored transcript can offer.
     */
    selectedLabel: String?,
    onTap: (MessageButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        for (button in buttons) {
            PromptPill(
                label = button.label,
                isSelected = selectedLabel == button.label,
                isLive = isLive,
                onClick = { onTap(button) },
            )
        }
    }
}

/**
 * One tappable option. Shared by the button row and each gallery card's own
 * stack so the two cannot drift apart visually.
 */
@Composable
internal fun PromptPill(
    label: String,
    isSelected: Boolean,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val foreground = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isLive -> accent
        else -> OrigonTheme.colors.textTertiary
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = foreground,
        modifier = modifier
            // A picked option stays fully legible after the prompt closes; the
            // ones NOT picked fade, so a glance at old history shows what was
            // chosen without re-reading every label.
            .alpha(if (isLive || isSelected) 1f else 0.5f)
            .clip(PillShape)
            .background(if (isSelected) accent else Color.Transparent)
            .border(1.dp, if (isSelected) accent else OrigonTheme.colors.border, PillShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = isLive,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Preview(name = "buttons light", showBackground = true, widthDp = 320)
@Composable
private fun MessageButtonsLight() {
    OrigonTheme(darkTheme = false) { ButtonsMatrix() }
}

@Preview(name = "buttons dark", showBackground = true, backgroundColor = 0xFF111111, widthDp = 320)
@Composable
private fun MessageButtonsDark() {
    OrigonTheme(darkTheme = true) { ButtonsMatrix() }
}

@Composable
private fun ButtonsMatrix() {
    val options = listOf(
        MessageButton(label = "Track my order", value = "track"),
        MessageButton(label = "Talk to a person", value = "agent"),
        // Duplicate caption: proves the row renders by index, not by key.
        MessageButton(label = "Track my order", value = "track-2"),
    )
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .background(OrigonTheme.colors.screenBackground)
            .padding(16.dp),
    ) {
        MessageButtons(options, isLive = true, selectedLabel = null, onTap = {})
        MessageButtons(options, isLive = false, selectedLabel = "Talk to a person", onTap = {})
    }
}
