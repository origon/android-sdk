package origon.example.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** One shake pass, matching iOS's `.animation(.linear(duration: 0.55))`. */
private const val SHAKE_DURATION_MS = 550

/**
 * Horizontal shake driven by a monotonically increasing counter.
 *
 * Increment [trigger] to fire one pass. The wave is sculpted inside the draw
 * lambda rather than by the animation curve: the counter is interpolated
 * **linearly** so the sine stays temporally even, while the *amplitude* tapers
 * across the pass. The exponential envelope is what gives it a "hit and ring
 * out" feel instead of the flat bounce of a raw sine.
 *
 * The wave reads the **absolute** animated value, not the per-pass phase. That
 * is load-bearing, not incidental: `sin(3πn) == 0` at every integer, so each
 * pass still starts and ends at rest, but `cos(3πn)` alternates sign, which
 * means consecutive shakes kick off in opposite directions. Computing from the
 * phase alone would silently drop that alternation.
 *
 * Interrupting a pass (a second [trigger] mid-flight) animates from wherever
 * the value currently is to the new integer, so the shake retargets rather than
 * restarting.
 */
@Composable
fun Modifier.shake(
    trigger: Int,
    amount: Dp = 10.dp,
    /** Half-oscillations per pass. 3 ⇒ right → left → right → centre. */
    halfOscillations: Float = 3f,
    /** Amplitude decay exponent; 1.3 fades trailing peaks to ~10% of the hit. */
    decay: Float = 1.3f,
): Modifier {
    // Seeded at `trigger` so the first composition is already at rest — a
    // component that mounts with an error showing must not shake on arrival.
    val progress = remember { Animatable(trigger.toFloat()) }

    LaunchedEffect(trigger) {
        val target = trigger.toFloat()
        if (progress.value != target) {
            progress.animateTo(target, tween(SHAKE_DURATION_MS, easing = LinearEasing))
        }
    }

    val amountPx = with(LocalDensity.current) { amount.toPx() }

    // A graphicsLayer lambda reads the animation in the draw phase, so a pass
    // invalidates drawing only — it never recomposes the field or its children.
    return this.graphicsLayer {
        val value = progress.value
        val envelope = (1f - (value - floor(value))).pow(decay)
        translationX = amountPx * envelope * sin(value * PI.toFloat() * halfOscillations)
    }
}
