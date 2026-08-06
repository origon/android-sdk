package origon.example.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * SwiftUI's `.easeInOut`, spelled exactly.
 *
 * Compose's `tween` defaults to `FastOutSlowInEasing` (0.4, 0, 0.2, 1) —
 * Material's standard curve, which enters harder and settles harder than Core
 * Animation's symmetric ease. The value below is the standard timing function
 * SwiftUI's `.easeInOut` resolves to (`kCAMediaTimingFunctionEaseInEaseOut`),
 * so the chat animations read the same on both platforms.
 */
val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * SwiftUI's `.easeOut`, spelled exactly.
 *
 * Compose's nearest stock curve, `LinearOutSlowInEasing`, is Material's
 * decelerate (0, 0, 0.2, 1) — a noticeably harder stop than Core Animation's
 * ease-out. This is the curve the user feels on every single tap, so it is
 * worth being exact rather than near.
 */
val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * iOS `.scaleEffect(0.95)` / `.animation(.easeOut(duration: 0.12))` — the
 * press feedback every tappable control gets.
 *
 * The scale and the duration live beside [EaseOut] rather than in one of the
 * callers because they are one gesture, not three independent numbers: a
 * button that shrinks by a different amount or over a different time is a
 * different control.
 */
const val PRESSED_SCALE = 0.95f
const val PRESS_ANIM_MS = 120
