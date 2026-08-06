package origon.example.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import origon.example.android.ui.theme.OrigonTheme

/** iOS `DispatchQueue.main.asyncAfter(deadline: .now() + 3.0)`. */
private const val TOAST_DURATION_MS = 3_000L

private val ToastShape = RoundedCornerShape(8.dp)

/** The endpoint screen's inset; the chat surface passes 96dp to clear the composer. */
private val BOTTOM_INSET = 32.dp

/**
 * SwiftUI's `.spring(response: 0.4, dampingFraction: 0.8)`. SwiftUI states a
 * spring by its *response* — the period of the equivalent undamped oscillation
 * — while Compose states it by stiffness. For unit mass they relate as
 * `stiffness = (2π / response)²`, so `(2π / 0.4)² ≈ 247`.
 */
private const val TOAST_STIFFNESS = 247f
private const val TOAST_DAMPING = 0.8f

/**
 * Holder for the floating toast. Created with [rememberToastState] and handed
 * to a [ToastHost] placed over the screen's content.
 */
@Stable
class ToastState internal constructor() {

    var message: String? by mutableStateOf(null)
        private set

    /**
     * Bumped on every [show]. The host keys its dismissal timer on this rather
     * than on [message], so showing the *same* text twice restarts the clock
     * instead of inheriting whatever was left of the first show's window.
     */
    internal var showId by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        this.message = message
        showId++
    }

    fun dismiss() {
        message = null
    }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * The floating toast overlay — one shared host for both screens.
 *
 * [bottomPadding] defaults to the endpoint screen's 32dp; the chat surface
 * passes 96dp so the pill clears the composer.
 *
 * Place it as the last child of a `Box` covering the screen. It never consumes
 * touches: the host declares no gesture modifiers, so it is pass-through by
 * construction.
 *
 * Dismissal keys a coroutine on [ToastState.showId], so a second toast inside
 * the window cancels the previous wait rather than being cut short by it.
 */
@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = BOTTOM_INSET,
) {
    val message = state.message

    // Retains the text through the exit animation, so a dismissing toast slides
    // out carrying its message rather than collapsing to an empty pill.
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) shown = message }

    LaunchedEffect(state.showId) {
        if (state.message != null) {
            delay(TOAST_DURATION_MS)
            state.dismiss()
        }
    }

    // `slideInVertically`'s own default supplies this threshold; omitting it
    // lets the spring settle to 0.01px and run measurably longer than intended.
    val slideSpec = spring(TOAST_DAMPING, TOAST_STIFFNESS, IntOffset.VisibilityThreshold)
    val fadeSpec = spring<Float>(TOAST_DAMPING, TOAST_STIFFNESS)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomPadding),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically(slideSpec) { it } + fadeIn(fadeSpec),
            exit = slideOutVertically(slideSpec) { it } + fadeOut(fadeSpec),
        ) {
            Text(
                text = shown.orEmpty(),
                // iOS `.subheadline.weight(.medium)` (15pt medium) ⇒ Material
                // `titleSmall` (14sp, Medium), the nearest token on the scale.
                style = MaterialTheme.typography.titleSmall,
                color = OrigonTheme.colors.toastForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    // iOS `.shadow(color: .black.opacity(0.15), radius: 8, y: 4)`.
                    // Compose's shadow takes an elevation rather than a
                    // radius/offset pair; 6dp is the nearest equivalent drop.
                    .shadow(6.dp, ToastShape)
                    .clip(ToastShape)
                    .background(OrigonTheme.colors.toastBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────

@Composable
private fun ToastProof(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            "screen content",
            color = OrigonTheme.colors.textPrimary,
            modifier = Modifier.padding(20.dp),
        )
        val state = rememberToastState()
        // Seeded so the preview renders the visible frame; the 3 s dismissal
        // only runs on device.
        LaunchedEffect(Unit) { state.show(message) }
        ToastHost(state)
    }
}

@Preview(name = "toast light")
@Composable
private fun ToastLight() {
    OrigonTheme(darkTheme = false) { ToastProof("Couldn't reach the server") }
}

@Preview(name = "toast dark")
@Composable
private fun ToastDark() {
    OrigonTheme(darkTheme = true) { ToastProof("Couldn't reach the server") }
}
