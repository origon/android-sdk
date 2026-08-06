package origon.example.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Origon colours that have no Material 3 slot.
 *
 * Material's `ColorScheme` covers accent/background/surface/error, so those
 * are mapped onto it below and read through `MaterialTheme.colorScheme`.
 * The rest of the palette — the call accent, the peer bubble tint, the
 * three-step text ramp, the toast pair — has no Material equivalent and
 * would be distorted by forcing it into one, so it lives here and is read
 * through [OrigonTheme.colors].
 */
@Immutable
data class OrigonColors(
    val callAccent: Color,
    val screenBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val peerBubble: Color,
    val remoteBubble: Color,
    val border: Color,
    val toastBackground: Color,
    val toastForeground: Color,
)

private val LightOrigonColors = OrigonColors(
    callAccent = CallAccent,
    screenBackground = ScreenBackgroundLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textTertiary = TextTertiaryLight,
    peerBubble = PeerBubbleLight,
    remoteBubble = RemoteBubbleLight,
    border = BorderLight,
    toastBackground = ToastBackgroundLight,
    toastForeground = ToastForegroundLight,
)

private val DarkOrigonColors = OrigonColors(
    callAccent = CallAccent,
    screenBackground = ScreenBackgroundDark,
    textPrimary = TextPrimaryDark,
    textSecondary = TextSecondaryDark,
    textTertiary = TextTertiaryDark,
    peerBubble = PeerBubbleDark,
    remoteBubble = RemoteBubbleDark,
    border = BorderDark,
    toastBackground = ToastBackgroundDark,
    toastForeground = ToastForegroundDark,
)

private val LocalOrigonColors = staticCompositionLocalOf { LightOrigonColors }

// `background` is the system background — the endpoint screen's backdrop.
// The chat and call surfaces paint `colors.screenBackground` instead.
private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = AccentForegroundLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    error = ErrorLight,
    outline = BorderLight,
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = AccentForegroundDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    error = ErrorDark,
    outline = BorderDark,
)

@Composable
fun OrigonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately NOT dynamicColor (Material You). The palette is the brand's
    // and must match iOS exactly; wallpaper-derived colours would break parity.
    CompositionLocalProvider(
        LocalOrigonColors provides if (darkTheme) DarkOrigonColors else LightOrigonColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}

object OrigonTheme {
    /** The Origon-specific colours with no Material slot. */
    val colors: OrigonColors
        @Composable @ReadOnlyComposable get() = LocalOrigonColors.current
}
