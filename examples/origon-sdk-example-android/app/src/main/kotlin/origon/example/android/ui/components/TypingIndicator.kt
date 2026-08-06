package origon.example.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import origon.example.android.ui.theme.OrigonTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three bouncing dots — the peer-is-typing row, matching the shipped apps.
 *
 * iOS drives the bounce off wall-clock time through `TimelineView` because a
 * SwiftUI `@State`/`onAppear` animation can fail to re-trigger when the view's
 * identity is reused. Compose's [rememberInfiniteTransition] has no such
 * failure mode — it animates for as long as the composable is in composition,
 * across identity reuse — so the idiomatic transition IS the port of that
 * intent, not a divergence from it.
 *
 * Geometry mirrors iOS: 8dp dots at 5dp spacing, a 1s full cycle, each dot
 * phase-shifted a third of a cycle, bouncing 0..-4dp on a sine; 14/12dp
 * padding on an 18dp-corner [OrigonTheme.colors.remoteBubble] capsule
 * (`systemGray5` — deliberately NOT the peer tint the message bubbles use).
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    // 0..1 phase over one cycle; the sine below turns it into the bounce.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .background(OrigonTheme.colors.remoteBubble, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        // Lambda offset: the bounce moves the dot every frame
                        // without invalidating layout, only placement.
                        .offset {
                            val bounce =
                                (sin((phase - index / 3f) * 2f * PI.toFloat()) + 1f) / 2f
                            IntOffset(0, (-4f * bounce).dp.roundToPx())
                        }
                        .size(8.dp)
                        .background(OrigonTheme.colors.textSecondary, CircleShape),
                )
            }
        }
        // iOS `Spacer(minLength: 60)`. The capsule's intrinsic width leaves
        // far more than 60dp on any phone, so plain weight carries the min.
        Spacer(Modifier.weight(1f))
    }
}

/** Full bounce cycle (up + back down), matching iOS's `cycle = 1.0` seconds. */
private const val CYCLE_MS = 1000

@Preview(name = "typing light", showBackground = true)
@Composable
private fun TypingIndicatorPreviewLight() {
    OrigonTheme(darkTheme = false) {
        TypingIndicator(Modifier.padding(16.dp))
    }
}

@Preview(name = "typing dark", showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun TypingIndicatorPreviewDark() {
    OrigonTheme(darkTheme = true) {
        TypingIndicator(Modifier.padding(16.dp))
    }
}
