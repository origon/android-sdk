package origon.example.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Origon palette, matching the shipped Origon iOS and Android apps.
 *
 * iOS builds its palette from UIKit *dynamic* colors that resolve per trait
 * collection (`UIColor.systemBackground`, `.label`, `.separator`, …). Android
 * has no equivalent dynamic-color type, so each side of the pair is resolved
 * here to Apple's published value for that system color and selected by the
 * light/dark scheme in Theme.kt. Where iOS names a literal (callAccent,
 * screenBackground's #111111, the toast pair) the literal carries over
 * unchanged.
 *
 * This is a port, not a redesign — a colour that differs from the shipped
 * apps is a parity bug.
 */

// ── Brand ────────────────────────────────────────────────────────────────

/** Adaptive monochrome accent: black in light, white in dark. */
internal val AccentLight = Color(0xFF000000)
internal val AccentDark = Color(0xFFFFFFFF)

/**
 * Foreground for content sitting on top of [AccentLight]/[AccentDark] — the
 * inverse, never a fixed white.
 */
internal val AccentForegroundLight = Color(0xFFFFFFFF)
internal val AccentForegroundDark = Color(0xFF111111)

/**
 * Call/voice affordance accent — green. Deliberately distinct from the
 * monochrome accent so a call action reads as "start a call", not a generic
 * primary tap. Non-adaptive.
 */
internal val CallAccent = Color(0xFF16A34A)

// ── Surfaces ─────────────────────────────────────────────────────────────

/** iOS `systemBackground`. */
internal val BackgroundLight = Color(0xFFFFFFFF)
internal val BackgroundDark = Color(0xFF000000)

/** Default backdrop: #111111 in dark, system background in light. */
internal val ScreenBackgroundLight = Color(0xFFFFFFFF)
internal val ScreenBackgroundDark = Color(0xFF111111)

/** iOS `secondarySystemBackground`. */
internal val SurfaceLight = Color(0xFFF2F2F7)
internal val SurfaceDark = Color(0xFF1C1C1E)

// ── Text ─────────────────────────────────────────────────────────────────

/** iOS `label`. */
internal val TextPrimaryLight = Color(0xFF000000)
internal val TextPrimaryDark = Color(0xFFFFFFFF)

/** iOS `secondaryLabel` — 60% alpha over the label base. */
internal val TextSecondaryLight = Color(0x993C3C43)
internal val TextSecondaryDark = Color(0x99EBEBF5)

/** iOS `tertiaryLabel` — 30% alpha. */
internal val TextTertiaryLight = Color(0x4D3C3C43)
internal val TextTertiaryDark = Color(0x4DEBEBF5)

// ── Chat bubbles ─────────────────────────────────────────────────────────

/**
 * Peer (agent) bubble: black @ 6% in light, white @ 6% in dark. A subtle
 * tint rather than the brand accent, so text on top uses the adaptive
 * text-primary colour. The *self* bubble uses the brand accent.
 */
internal val PeerBubbleLight = Color(0x0F000000)
internal val PeerBubbleDark = Color(0x0FFFFFFF)

/**
 * iOS `systemGray5`. The message bubbles use accent/[PeerBubbleLight], but
 * `TypingIndicator` sits on this older grey, so it carries its own colour
 * rather than folding into the 6% tint.
 */
internal val RemoteBubbleLight = Color(0xFFE5E5EA)
internal val RemoteBubbleDark = Color(0xFF2C2C2E)

/**
 * iOS `systemGray4` — one step darker than [RemoteBubbleLight]'s `systemGray5`.
 *
 * Its one consumer is the voice detail's channel badge, where the separation
 * from the avatar's `surface` beneath it is the whole point: `systemGray5`
 * leaves barely a dozen levels between them and the badge visually merges into
 * the disc it is supposed to sit on. Not interchangeable with the bubble grey,
 * which is why it is its own pin rather than a reuse.
 */
internal val Gray4Light = Color(0xFFD1D1D6)
internal val Gray4Dark = Color(0xFF3A3A3C)

// ── Feedback ─────────────────────────────────────────────────────────────

/** iOS `Color.red` (systemRed). */
internal val ErrorLight = Color(0xFFFF3B30)
internal val ErrorDark = Color(0xFFFF453A)

/** iOS `separator`. */
internal val BorderLight = Color(0x4A3C3C43)
internal val BorderDark = Color(0xA6545458)

/** Toast pair — inverted against the backdrop on each side. */
internal val ToastBackgroundLight = Color(0xFF333333)
internal val ToastBackgroundDark = Color(0xFFFFFFFF)
internal val ToastForegroundLight = Color(0xFFFFFFFF)
internal val ToastForegroundDark = Color(0xFF333333)
