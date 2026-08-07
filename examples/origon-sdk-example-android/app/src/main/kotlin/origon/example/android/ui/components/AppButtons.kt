package origon.example.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import origon.example.android.ui.theme.EaseOut
import origon.example.android.ui.theme.OrigonTheme
import origon.example.android.ui.theme.PRESSED_SCALE
import origon.example.android.ui.theme.PRESS_ANIM_MS

// The two button variants this example needs, matching the shipped apps. Both
// share a 48dp height, a pill corner, and one press-scale so taps feel
// identical everywhere.
//
//  • PrimaryButton  — brand accent fill. The affirmative action.
//  • InvertedButton — label-colour fill on background foreground. Highest
//                     contrast; the endpoint screen's Continue.
//
// The shared body lives in [PillButton]; the variants are the colour choices
// only.

/** iOS clamps `cornerRadius(50)` on a 48pt-tall button; 50% is the same pill. */
private val ButtonShape = RoundedCornerShape(percent = 50)
private val ButtonHeight = 48.dp

/** iOS `.opacity(isInactive ? 0.5 : 1)`. */
private const val INACTIVE_ALPHA = 0.5f

/** Brand accent fill, accent-foreground label. */
@Composable
fun PrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    PillButton(
        title = title,
        onClick = onClick,
        modifier = modifier,
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
        loading = loading,
        enabled = enabled,
    )
}

@Composable
fun InvertedButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    PillButton(
        title = title,
        onClick = onClick,
        modifier = modifier,
        container = OrigonTheme.colors.textPrimary,
        content = MaterialTheme.colorScheme.background,
        loading = loading,
        enabled = enabled,
    )
}

/**
 * The shared body. Hand-rolled on a [Box] rather than Material's `Button`
 * deliberately: `Button` ships elevation, a ripple, its own content padding,
 * a min-size floor and disabled-colour mapping, every one of which would have
 * to be neutralised to reach this shape. iOS has no ripple — only the press
 * scale — so `indication = null` is parity, not a shortcut.
 */
@Composable
private fun PillButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    loading: Boolean,
    enabled: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val inactive = loading || !enabled

    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = tween(PRESS_ANIM_MS, easing = EaseOut),
        label = "buttonPressScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (inactive) INACTIVE_ALPHA else 1f
            }
            .clip(ButtonShape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !inactive,
                // Material's `Button` would supply this; a bare `clickable` Box
                // does not, and without it TalkBack announces the label with no
                // indication that it is actionable.
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            OrigonSpinner(color = content)
        } else {
            Text(
                text = title,
                // iOS `.callout.weight(.medium)` (16pt medium) ⇒ Material
                // `titleMedium` (16sp, Medium) — an exact match on the scale.
                style = MaterialTheme.typography.titleMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────

@Composable
private fun ButtonMatrix() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InvertedButton(title = "Continue", onClick = {})
        InvertedButton(title = "Continue", onClick = {}, loading = true)
        PrimaryButton(title = "Try again", onClick = {})
    }
}

@Preview(name = "buttons light")
@Composable
private fun AppButtonsLight() {
    OrigonTheme(darkTheme = false) { ButtonMatrix() }
}

@Preview(name = "buttons dark")
@Composable
private fun AppButtonsDark() {
    OrigonTheme(darkTheme = true) { ButtonMatrix() }
}
