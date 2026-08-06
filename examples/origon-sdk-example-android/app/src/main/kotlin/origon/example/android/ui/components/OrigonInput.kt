package origon.example.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.OrigonTheme

/** iOS clamps `cornerRadius(50)` on a 48pt-tall field; 50% is the same pill. */
private val InputShape = RoundedCornerShape(percent = 50)

/** iOS `.animation(.easeInOut(duration: 0.2), value: errorMessage)`. */
private const val ERROR_FADE_MS = 200

/**
 * The endpoint field is unconditionally autocorrect-off and
 * autocapitalisation-off — an autocapitalised endpoint URL is a bug, not a
 * nicety.
 */
val OrigonInputDefaultKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

/**
 * Shared single-line input, matching the shipped apps' `OrigonInput`.
 *
 * It owns the error UX in one place: when [errorMessage] transitions to
 * non-null the field shakes, draws a red border, fires an error haptic, and
 * surfaces an inline caption underneath. Parents clear it by passing null,
 * typically on the next submit or on text change.
 *
 * Built on [BasicTextField] rather than Material's `TextField`, matching iOS's
 * `.textFieldStyle(.plain)`: Material's container ships its own indicator line,
 * label slot, content padding and disabled-colour logic, all of which would
 * have to be neutralised to land on this shape.
 */
@Composable
fun OrigonInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = OrigonInputDefaultKeyboard,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /**
     * How a screen takes focus on appear. Only the *request* half needs a
     * channel: releasing focus is `LocalFocusManager.clearFocus()` on this
     * platform, which needs nothing from the field.
     */
    focusRequester: FocusRequester? = null,
) {
    val haptics = LocalHapticFeedback.current
    var shakeTrigger by remember { mutableIntStateOf(0) }
    // Retains the text through the exit animation, so a cleared error fades out
    // with its message intact instead of collapsing to an empty caption.
    var caption by remember { mutableStateOf(errorMessage) }
    // Seeded with the initial value so first composition cannot fire — a screen
    // recomposed with an error already showing must not re-shake and re-buzz.
    var previous by remember { mutableStateOf(errorMessage) }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            caption = errorMessage
            if (errorMessage != previous) {
                shakeTrigger++
                // `Reject` is correct on the whole minSdk-23 range: Compose
                // routes it through `ViewCompat.performHapticFeedback`, which
                // down-maps REJECT to LONG_PRESS below API 30.
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            }
        }
        previous = errorMessage
    }

    val borderColor by animateColorAsState(
        targetValue = if (errorMessage != null) MaterialTheme.colorScheme.error else Color.Transparent,
        animationSpec = tween(ERROR_FADE_MS, easing = EaseInOut),
        label = "inputErrorBorder",
    )

    // No `spacedBy` here: `AnimatedVisibility` occupies a slot even while
    // hidden, so an 8dp arrangement gap would sit under the field permanently.
    // The caption carries its own top padding instead.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                // Before the background so the whole pill travels, not just text.
                .shake(shakeTrigger)
                .clip(InputShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, borderColor, InputShape)
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                ),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = OrigonTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(OrigonTheme.colors.textPrimary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { field ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = OrigonTheme.colors.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    field()
                }
            },
        )

        // iOS writes this as `.transition(.opacity.combined(with: .move(edge: .top)))`
        // inside a VStack, so the caption both fades and displaces its
        // siblings. Expand/shrink is the Compose spelling of that same reveal —
        // a bare slide would reserve the full height on frame one and read as
        // a jump.
        val captionSpec = tween<Float>(ERROR_FADE_MS, easing = EaseInOut)
        val captionSizeSpec = tween<IntSize>(ERROR_FADE_MS, easing = EaseInOut)
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn(captionSpec) + expandVertically(captionSizeSpec),
            exit = fadeOut(captionSpec) + shrinkVertically(captionSizeSpec),
        ) {
            Text(
                text = caption.orEmpty(),
                // iOS `.caption` (12pt) ⇒ Material `bodySmall` (12sp).
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                // Server errors are arbitrarily long; cap at two lines so the
                // caption never pushes the layout around.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────

@Composable
private fun InputMatrix() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OrigonInput(value = "", onValueChange = {}, placeholder = "Endpoint URL")
        OrigonInput(
            value = "https://example.origon.ai/chat/api/019f60cb",
            onValueChange = {},
            placeholder = "Endpoint URL",
        )
        OrigonInput(
            value = "not-a-url",
            onValueChange = {},
            placeholder = "Endpoint URL",
            errorMessage = "Please enter your endpoint URL",
        )
    }
}

@Preview(name = "input light")
@Composable
private fun OrigonInputLight() {
    OrigonTheme(darkTheme = false) { InputMatrix() }
}

@Preview(name = "input dark")
@Composable
private fun OrigonInputDark() {
    OrigonTheme(darkTheme = true) { InputMatrix() }
}
