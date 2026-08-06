package origon.example.android.ui.chat

import android.util.Log
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parse one of the SDK's ISO-8601 session timestamps.
 *
 * `2026-08-03T11:22:33.123Z` and `2026-08-03T11:22:33Z` alike — the fractional
 * part is optional in ISO-8601 and the SDK emits both, which is why iOS parses
 * twice. One formatter covers it here: `ISO_OFFSET_DATE_TIME` accepts 0-9
 * fractional digits and any real offset, not just `Z`.
 *
 * An unparseable timestamp yields null rather than throwing — one malformed row
 * must not blank the whole sidebar — but it is logged, because silently
 * vanishing history is the kind of thing nobody reports. The session id is the
 * only handle on which row went missing, and it is not a secret.
 *
 * Shared by the sidebar's day bucketing and the voice detail's Date/Duration
 * rows, which is why it is here rather than private to either: two copies of a
 * date parser is how two screens end up disagreeing about the same session.
 *
 * Needs core-library desugaring at this app's minSdk 23 — see
 * `app/build.gradle.kts`.
 */
internal fun parseInstant(sessionId: String, raw: String): Instant? = try {
    OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
} catch (_: DateTimeParseException) {
    Log.w(TAG, "unparseable timestamp, row dropped: $sessionId")
    null
}

private const val TAG = "SessionTime"
