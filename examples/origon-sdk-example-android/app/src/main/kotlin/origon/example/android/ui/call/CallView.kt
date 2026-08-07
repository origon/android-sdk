package origon.example.android.ui.call

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import origon.example.android.R
import origon.example.android.services.CallService
import origon.example.android.services.SDKManager
import origon.example.android.ui.theme.EaseInOut
import origon.example.android.ui.theme.EaseOut
import origon.example.android.ui.theme.OrigonTheme
import origon.example.android.ui.theme.PRESSED_SCALE
import origon.example.android.ui.theme.PRESS_ANIM_MS
import kotlin.math.PI

// ── Brand gradient ───────────────────────────────────────────────────────
//
// The conic gradient behind the whole call surface. Stops are the brand
// gradient's degree marks over 360.

private const val TAG = "CallView"

private val BrandGradientStops = arrayOf(
    0f to Color(0xFF0092FF),
    32.4f / 360f to Color(0xFFFD9700),
    100.8f / 360f to Color(0xFFFF4400),
    176.4f / 360f to Color(0xFFFF2469),
    280.8f / 360f to Color(0xFFC65CFF),
    1f to Color(0xFF0092FF),
)

/**
 * CSS measures a conic gradient clockwise from 12 o'clock; Compose's
 * `sweepGradient` starts at 3 o'clock and takes no start angle at all, so the
 * offset is applied by rotating the drawn circle. 146.48 is the CSS `from`
 * value, less the 90 degrees between the two conventions.
 */
private const val GradientStartDegrees = 146.48f - 90f

/** CSS `at 51.32% 51.68%`, as a fraction of the circle's box. */
private const val GradientCenterX = 0.5132f
private const val GradientCenterY = 0.5168f

// ── Geometry ─────────────────────────────────────────────────────────────

private val BackdropSize = 240.dp
private val BackdropBlur = 40.dp
private val RingSize = 224.dp
private val RingBlur = 25.dp
private val LogoSize = 72.dp
private val LogoBlur = 20.dp
private val ControlSize = 48.dp

/**
 * The call-duration label: seconds under a minute, minutes+seconds under an
 * hour, hours+minutes past it — where the second-by-second tick is noise.
 */
private fun formatCallDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    if (seconds < 60) return "${seconds}s"
    if (seconds < 3600) return "${seconds / 60}m ${seconds % 60}s"
    return "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/** The phases that render the pre-connection backdrop. */
private fun isLoadingPhase(phase: CallService.Phase): Boolean =
    phase is CallService.Phase.Idle || phase is CallService.Phase.Connecting

/**
 * The message under the logo: a disconnect reason if the call ended badly,
 * otherwise the soft `callError`. `Ended(null)` is a clean user-initiated
 * hang-up and shows nothing.
 *
 * **An ended phase answers for itself, `null` reason included** — hence the
 * `if`, not an elvis over `(phase as? Ended)?.reason`. That spelling looks
 * equivalent and is not: it falls through to [lastError] on a clean end, so a
 * hang-up that followed any earlier soft error would flash that stale message
 * as the surface dismissed.
 */
private fun callErrorMessage(phase: CallService.Phase, lastError: String?): String? =
    if (phase is CallService.Phase.Ended) phase.reason else lastError

// ── Motion ───────────────────────────────────────────────────────────────

/**
 * SwiftUI `.spring(response:dampingFraction:)` in Compose's terms.
 *
 * Both model the same damped oscillator, but SwiftUI parameterises it by
 * *period* and Compose by stiffness: for unit mass, stiffness is the natural
 * frequency squared, and that frequency is `2π / response`. Converting rather
 * than eyeballing a stock `Spring.StiffnessLow` is what keeps the overshoot
 * matching iOS frame for frame.
 */
private fun swiftSpring(response: Float, dampingFraction: Float): SpringSpec<Float> {
    val frequency = 2.0 * PI / response
    return spring(dampingRatio = dampingFraction, stiffness = (frequency * frequency).toFloat())
}

/**
 * The underdamped bounce used for the logo, the inner ring and the control
 * reveal — it overshoots ~16% before settling.
 */
private val BounceSpring = swiftSpring(response = 0.7f, dampingFraction = 0.5f)

/** [swiftSpring] for a colour track — `animateColorAsState` needs its own type. */
private fun swiftSpringColor(response: Float, dampingFraction: Float): SpringSpec<Color> {
    val frequency = 2.0 * PI / response
    return spring(dampingRatio = dampingFraction, stiffness = (frequency * frequency).toFloat())
}

/** iOS's `.timingCurve(0.175, 0.885, 0.32, 1.275)` — an ease-out-back. */
private val GrowEasing = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)

// ── Gradient layers ──────────────────────────────────────────────────────

/**
 * One blurred, gradient-filled disc — the shape every layer of the cluster is
 * built from.
 *
 * **The blur is applied outermost, on purpose.** iOS writes
 * `.scaleEffect().opacity().blur()`, so the blur acts on the already-scaled
 * result; in Compose the first modifier in the chain is the outer one, so
 * putting `blur` ahead of `graphicsLayer` reproduces that. Reversed, the
 * connected backdrop's 5× scale would magnify a 40dp blur into a 200dp one.
 *
 * Rotating a disc only moves its gradient — the silhouette is
 * rotation-invariant — which is how [GradientStartDegrees] is applied without
 * a start-angle parameter Compose does not offer.
 *
 * **`Modifier.blur` needs API 31.** Below that it is a documented no-op, so
 * these layers render as flat translucent discs rather than a soft glow; this
 * app's floor is 23.
 */
@Composable
private fun BrandGlow(
    size: Dp,
    blurRadius: Dp,
    rotationDegrees: Float,
    scale: Float,
    alpha: Float,
) {
    Box(
        Modifier
            .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
            .graphicsLayer {
                rotationZ = GradientStartDegrees + rotationDegrees
                scaleX = scale
                scaleY = scale
                this.alpha = alpha.coerceIn(0f, 1f)
            }
            .size(size)
            .drawBehind {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = BrandGradientStops,
                        center = Offset(
                            this.size.width * GradientCenterX,
                            this.size.height * GradientCenterY,
                        ),
                    ),
                )
            },
    )
}

/** Pre-connection: a slow spin with a breathing pulse. */
@Composable
private fun LoadingBackdrop() {
    val transition = rememberInfiniteTransition(label = "loadingBackdrop")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "loadingRotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = EaseInOut), RepeatMode.Reverse),
        label = "loadingPulse",
    )
    BrandGlow(
        size = BackdropSize,
        blurRadius = BackdropBlur,
        rotationDegrees = rotation,
        scale = lerp(1f, 1.1f, pulse),
        alpha = lerp(0.10f, 0.15f, pulse),
    )
}

/** On connect: the disc blooms outward and fades as it goes. */
@Composable
private fun ConnectedBackdrop() {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(2000, easing = GrowEasing)) }
    BrandGlow(
        size = BackdropSize,
        blurRadius = BackdropBlur,
        rotationDegrees = 0f,
        scale = lerp(1f, 5f, grow.value),
        alpha = lerp(0.20f, 0.05f, grow.value),
    )
}

/** The inner ring: springs up on connect, then turns continuously. */
@Composable
private fun InnerScaleRing() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, BounceSpring) }
    val transition = rememberInfiniteTransition(label = "innerRing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringRotation",
    )
    BrandGlow(
        size = RingSize,
        blurRadius = RingBlur,
        rotationDegrees = rotation,
        scale = appear.value,
        alpha = appear.value * 0.2f,
    )
}

/** The brand mark, resolving out of blur once the call is up. */
@Composable
private fun ConnectedLogo(visible: Boolean) {
    val appear = remember { Animatable(0f) }
    // **Awaited, not keyed.** `LaunchedEffect(visible)` would cancel the spring
    // in flight the moment `visible` went false — a `Reconnecting` event
    // landing inside the ~0.7s bloom would freeze the mark half-scaled and
    // blurred for the whole reconnect, because nothing resets `appear` either.
    // Waiting for the first `true` and then animating uncancelled is the
    // behaviour iOS gets from its `guard newValue else { return }`.
    LaunchedEffect(Unit) {
        snapshotFlow { visible }.first { it }
        appear.animateTo(1f, BounceSpring)
    }
    val progress = appear.value
    Image(
        painter = painterResource(R.drawable.ic_origon_logo),
        contentDescription = null,
        modifier = Modifier
            // The spring overshoots past 1, which would drive the radius
            // negative — `blur` rejects that, so the floor is not cosmetic.
            .blur(lerp(LogoBlur.value, 0f, progress).coerceAtLeast(0f).dp)
            .graphicsLayer {
                scaleX = progress
                scaleY = progress
                alpha = progress.coerceIn(0f, 1f)
            }
            .size(LogoSize),
    )
}

/**
 * The cluster: backdrop, ring and mark stacked in one 240dp box.
 *
 * `Crossfade` rather than a plain `if`, because each backdrop owns a
 * self-contained animation that has to restart from rest when it appears —
 * swapping the composable is what re-runs its `LaunchedEffect`.
 */
@Composable
private fun LogoCluster(phase: CallService.Phase) {
    val connected = phase is CallService.Phase.Connected
    val ended = phase is CallService.Phase.Ended
    Box(Modifier.size(BackdropSize), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = isLoadingPhase(phase),
            animationSpec = tween(300, easing = EaseInOut),
            label = "backdrop",
        ) { loading ->
            if (loading) LoadingBackdrop() else ConnectedBackdrop()
        }
        AnimatedVisibility(
            visible = connected,
            enter = fadeIn(tween(300, easing = EaseInOut)),
            exit = fadeOut(tween(300, easing = EaseInOut)),
        ) {
            InnerScaleRing()
        }
        // Kept on screen through `ended` so the mark still frames the error row
        // rather than vanishing under it.
        ConnectedLogo(visible = connected || ended)
    }
}

// ── Controls ─────────────────────────────────────────────────────────────

/**
 * One 48dp circular call control.
 *
 * Two independent scales multiply here: the staggered entrance ([revealed],
 * 1.0 → 1.12) and the press feedback. Keeping them as separate animations
 * means a tap mid-entrance composes with it instead of cancelling it.
 *
 * `indication = null` because the scale IS the feedback — a Material ripple on
 * top would be a second, un-ported affordance.
 */
@Composable
private fun CallControlButton(
    iconRes: Int,
    contentDescription: String,
    background: Color,
    tint: Color,
    revealed: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 24.dp,
) {
    // Cross-faded rather than swapped: iOS wraps the mute write in
    // `withAnimation(.spring(response: 0.3, dampingFraction: 0.7))`, so the
    // fill and tint ease between states. The speaker button is instant on both,
    // and animating a colour that never changes costs nothing, so one path
    // serves both.
    val animatedBackground by animateColorAsState(
        targetValue = background,
        animationSpec = swiftSpringColor(response = 0.3f, dampingFraction = 0.7f),
        label = "callControlBackground",
    )
    val animatedTint by animateColorAsState(
        targetValue = tint,
        animationSpec = swiftSpringColor(response = 0.3f, dampingFraction = 0.7f),
        label = "callControlTint",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = tween(PRESS_ANIM_MS, easing = EaseOut),
        label = "callControlPress",
    )
    val revealScale by animateFloatAsState(
        targetValue = if (revealed) 1.12f else 1f,
        animationSpec = BounceSpring,
        label = "callControlReveal",
    )
    val revealAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.5f,
        animationSpec = BounceSpring,
        label = "callControlAlpha",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale * revealScale
                scaleY = pressScale * revealScale
                alpha = revealAlpha.coerceIn(0f, 1f)
            }
            .size(ControlSize)
            .clip(CircleShape)
            .background(animatedBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = animatedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

// ── Surface ──────────────────────────────────────────────────────────────

/**
 * The call surface with no service attached — every input is a parameter, so
 * the stateful [CallView] below stays a thin wrapper.
 */
@Composable
private fun CallSurface(
    phase: CallService.Phase,
    muted: Boolean,
    speakerOn: Boolean,
    lastError: String?,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = phase is CallService.Phase.Connected
    val errorMessage = callErrorMessage(phase, lastError)

    // The timer appears a beat after connect and ticks from there. Tying it to
    // `connected` means the coroutine is cancelled — and the count reset — the
    // moment the call drops, including on a reconnect.
    var elapsed by remember { mutableIntStateOf(0) }
    var showTimer by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (!connected) {
            showTimer = false
            elapsed = 0
            return@LaunchedEffect
        }
        delay(1000)
        elapsed = 1
        showTimer = true
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    // iOS staggers the three at 0.15 / 0.3 / 0.45s after connect.
    //
    // **Keyed on the loading phase, not on `connected`** — the controls clear
    // in exactly one branch, idle/connecting, and stay lit through `ended` and
    // `reconnecting`. Keying on `connected` inverts that: an error-ended call
    // would dim all three to 50% at the instant the error row appears, and End
    // is the only way off that surface.
    val loading = isLoadingPhase(phase)
    var revealMute by remember { mutableStateOf(false) }
    var revealSpeaker by remember { mutableStateOf(false) }
    var revealEnd by remember { mutableStateOf(false) }
    LaunchedEffect(connected, loading) {
        if (loading) {
            revealMute = false
            revealSpeaker = false
            revealEnd = false
            return@LaunchedEffect
        }
        if (!connected) return@LaunchedEffect
        delay(150)
        revealMute = true
        delay(150)
        revealSpeaker = true
        delay(150)
        revealEnd = true
    }

    // Held across the exit fade. `AnimatedVisibility` runs a 300ms exit, but
    // the composition reads `errorMessage` live — so without this the text
    // recomposes to empty the instant the phase clears and the icon fades out
    // alone. Reachable by a soft CallError followed by a hang-up.
    var lastShownError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(errorMessage) { if (errorMessage != null) lastShownError = errorMessage }

    val textPrimary = OrigonTheme.colors.textPrimary
    val restingFill = textPrimary.copy(alpha = 0.2f)
    val restingTint = textPrimary.copy(alpha = 0.7f)
    // iOS `Color.red`; this scheme's `error` is the same systemRed pair.
    val destructive = MaterialTheme.colorScheme.error

    Box(
        modifier
            .fillMaxSize()
            .background(OrigonTheme.colors.screenBackground),
    ) {
        // Every bottom offset below is measured from this inner box, so
        // dropping these three would shift the controls, timer and error row
        // 8dp down and the logo cluster ~22dp up against iOS. The error row's
        // own 16dp side inset composes with the 8dp here to reach iOS's 24dp.
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(top = 52.dp, bottom = 8.dp),
        ) {
            // Biased upward, leaving the lower third to the timer and controls.
            Box(
                Modifier.fillMaxSize().padding(bottom = 128.dp),
                contentAlignment = Alignment.Center,
            ) {
                LogoCluster(phase)
            }

            AnimatedVisibility(
                visible = showTimer && connected,
                enter = fadeIn(swiftSpring(0.55f, 0.7f)) + scaleIn(swiftSpring(0.55f, 0.7f), 0.9f),
                exit = fadeOut(tween(200)) + scaleOut(tween(200), 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 260.dp),
            ) {
                Text(
                    text = formatCallDuration(elapsed),
                    // Tabular figures — iOS spells `.monospacedDigit()`. The
                    // label is centred and reflows every second, so
                    // proportional digits visibly jitter it left and right.
                    style = MaterialTheme.typography.bodyLarge
                        .copy(fontFeatureSettings = "tnum"),
                    color = textPrimary.copy(alpha = 0.5f),
                )
            }

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(tween(300, easing = EaseInOut)),
                exit = fadeOut(tween(300, easing = EaseInOut)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_error),
                        contentDescription = null,
                        tint = destructive.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = lastShownError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = destructive.copy(alpha = 0.8f),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
            ) {
                CallControlButton(
                    iconRes = if (muted) R.drawable.ic_mic_muted else R.drawable.ic_mic,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    background = if (muted) destructive else restingFill,
                    tint = if (muted) Color.White else restingTint,
                    revealed = revealMute,
                    onClick = onToggleMute,
                )
                CallControlButton(
                    iconRes = R.drawable.ic_cross,
                    contentDescription = "End call",
                    background = destructive,
                    tint = Color.White,
                    revealed = revealEnd,
                    onClick = onEndCall,
                    iconSize = 32.dp,
                )
                CallControlButton(
                    iconRes = if (speakerOn) R.drawable.ic_speaker_wave else R.drawable.ic_speaker,
                    contentDescription =
                        if (speakerOn) "Turn off speaker" else "Turn on speaker",
                    background = if (speakerOn) MaterialTheme.colorScheme.primary else restingFill,
                    tint = if (speakerOn) MaterialTheme.colorScheme.onPrimary else restingTint,
                    revealed = revealSpeaker,
                    onClick = onToggleSpeaker,
                )
            }
        }
    }
}

/**
 * The voice-call surface, wired to [CallService].
 *
 * The call starts on entry — this composable IS the call, so there is no
 * separate connect affordance.
 */
@Composable
fun CallView(
    sdk: SDKManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val call = sdk.call
    val phase by call.phase.collectAsState()
    val muted by call.muted.collectAsState()
    val speakerOn by call.speakerOn.collectAsState()
    val lastError by call.lastError.collectAsState()

    // **Back ends the call; it does not merely dismiss.** Unhandled, a back
    // press would pop this surface while `CallService` kept a live session: a
    // hot mic with no UI able to end it. Routing it through the same path as
    // the End button is the only behaviour that cannot strand a call.
    BackHandler {
        call.endCall()
        onClose()
    }

    // **Guarded, because this effect re-runs on every fresh composition while
    // `CallService` is process-scoped.** An Activity recreate mid-call (locale
    // or density change, "Don't keep activities") re-enters here with the call
    // already up; starting again would overwrite `sessionId` and strand the
    // first session server-side with nothing able to end it.
    LaunchedEffect(Unit) {
        val current = call.phase.value
        if (current !is CallService.Phase.Idle && current !is CallService.Phase.Ended) {
            return@LaunchedEffect
        }
        try {
            call.startCall()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Most failures are already reflected into `phase` as
            // `Ended(message)` by the service, which is what renders the error
            // row. The exception is the "SDK not initialized" throw, which
            // happens BEFORE any phase write and would otherwise leave the
            // surface on the loading backdrop forever with nothing logged.
            Log.w(TAG, "startCall failed: ${e.message}")
            if (call.phase.value !is CallService.Phase.Ended) onClose()
        }
    }

    // A clean end dismisses; an ended-with-reason stays put so the user can
    // read why before tapping End.
    //
    // **`drop(1)` is load-bearing, not a tidy-up.** `phase` is a StateFlow that
    // still holds the PREVIOUS call's terminal value when this surface reopens,
    // and a `LaunchedEffect` keyed on it runs on first composition. Without the
    // drop, the second call of a session dismisses itself instantly on the
    // stale `Ended(null)` while `startCall` connects anyway — a live call, no
    // UI to end it, and the mic indicator left on.
    LaunchedEffect(Unit) {
        call.phase.drop(1).collect { current ->
            if (current is CallService.Phase.Ended && current.reason == null) onClose()
        }
    }

    CallSurface(
        phase = phase,
        muted = muted,
        speakerOn = speakerOn,
        lastError = lastError,
        onToggleMute = { call.setMute(!muted) },
        onToggleSpeaker = { call.setSpeaker(!speakerOn) },
        onEndCall = {
            call.endCall()
            onClose()
        },
        modifier = modifier,
    )
}
