package origon.example.android.ui.chat

import ai.origon.sdk.SessionSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import origon.example.android.R
import origon.example.android.ui.theme.OrigonTheme
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Pure formatting ──────────────────────────────────────────────────────
//
// Every function here takes its clock and zone as a parameter so the rendering
// is a function of its inputs rather than of when it runs.

/**
 * The header title: the agent's name, else the session's subject, else a
 * neutral fallback.
 */
private fun voiceDisplayTitle(session: SessionSummary): String {
    val name = session.contact?.name?.trim().orEmpty()
    if (name.isNotEmpty()) return name
    val subject = session.subject.trim()
    if (subject.isNotEmpty()) return subject
    return NO_AGENT
}

/** True when the title is the fallback — the only case rendered dimmed. */
private fun voiceTitleIsFallback(session: SessionSummary): Boolean =
    // No title-equality check: with both sources empty `voiceDisplayTitle`
    // returns the fallback by construction, so testing for it would read as a
    // special case for a session whose SUBJECT is literally "No agent" — which
    // is not special, and correctly renders undimmed.
    session.contact?.name?.trim().isNullOrEmpty() && session.subject.isBlank()

/**
 * Whether to print the Team row.
 *
 * Dropped when the header is already showing the subject — otherwise the same
 * string prints twice, once as the title and once as a row — and when there is
 * no subject at all.
 */
private fun showVoiceTeamRow(session: SessionSummary): Boolean =
    // **Both sides trimmed.** The title is trimmed on the way out, so comparing
    // it against the raw subject makes a padded `" Support "` look different
    // from the `"Support"` in the header — and prints the team twice, which is
    // the one thing this predicate exists to stop.
    session.subject.isNotBlank() && session.subject.trim() != voiceDisplayTitle(session)

/** `#` plus the last six characters of the session id, or all of a short one. */
private fun voiceSessionShort(sessionId: String): String =
    "#" + if (sessionId.length > 6) sessionId.takeLast(6) else sessionId

/**
 * "Today, 4:02pm" / "Yesterday, 9:03pm" / "24th Jul, 2:30pm", with the year
 * appended only when it differs from [today]'s.
 *
 * `—` when the timestamp cannot be read, rather than a fabricated date.
 */
private fun voiceStartedText(
    sessionId: String,
    createdAt: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val instant = parseInstant(sessionId, createdAt) ?: return EM_DASH
    val local = instant.atZone(zone)
    val time = TIME_LABEL.format(local).lowercase(Locale.US)
    val day = local.toLocalDate()
    if (day == today) return "Today, $time"
    if (day == today.minusDays(1)) return "Yesterday, $time"

    val month = MONTH_LABEL.format(local)
    // The year is printed only when it is not the current one — a date this
    // year reads "24th Jul", one from another year "24th Jul 2025".
    val year = if (day.year != today.year) " ${day.year}" else ""
    return "${ordinal(day.dayOfMonth)} $month$year, $time"
}

/**
 * The call's wall-clock span, `updatedAt − createdAt`.
 *
 * **This is the call's total span (initiate/ring → teardown), not talk time** —
 * the backend puts no talk-time anchor on the `/sessions` wire, which is why
 * the row is labelled "Duration" and never "talk time".
 *
 * A non-positive delta renders `—`: it means clock skew, or a row that never
 * really ran (a ring-timeout lands at ≈ 0), and neither is a call length.
 */
private fun voiceDurationText(
    sessionId: String,
    createdAt: String,
    updatedAt: String,
): String {
    val start = parseInstant(sessionId, createdAt) ?: return EM_DASH
    val end = parseInstant(sessionId, updatedAt) ?: return EM_DASH
    // **The DELTA is truncated, not each endpoint.** Subtracting `epochSecond`
    // discards the fractional part of both sides independently, so a 150ms row
    // spanning a second boundary (…00.900Z → …01.050Z) reports a whole second
    // and prints "1s" for a call that never happened. `Duration.between`
    // truncates once, on the difference — which is what makes the ring-timeout
    // guard below actually catch ring timeouts.
    val seconds = Duration.between(start, end).seconds
    if (seconds <= 0) return EM_DASH
    return formatVoiceDuration(seconds)
}

/** `Nh Nm` / `Nm Ns` / `Ns` — seconds are dropped once hours are in play. */
private fun formatVoiceDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    if (hours > 0) return "${hours}h ${minutes}m"
    if (minutes > 0) return "${minutes}m ${seconds}s"
    return "${seconds}s"
}

/**
 * English ordinal suffix. Hand-written because `java.time` has no ordinal
 * formatter and the desugared locale data does not carry one either; this
 * example is English-only, the same basis on which the sidebar pins
 * `Locale.US`. 11–13 are the exceptions that make a naive `% 10` wrong.
 */
private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

private const val NO_AGENT = "No agent"
private const val EM_DASH = "—"

/** iOS `"h:mma"`, lowercased. Locale pinned — desugared `java.time`. */
private val TIME_LABEL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mma", Locale.US)

/** `MMM`, never `LLL` — standalone patterns do not work when desugared. */
private val MONTH_LABEL: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM", Locale.US)

// ── Screen ───────────────────────────────────────────────────────────────

/**
 * Read-only detail for a past **voice** session.
 *
 * It fills the chat surface's content slot in place, exactly where the
 * transcript sits, rather than pushing a destination: a voice row is a
 * different *view of the root*, not a place to navigate to. Two things follow
 * and both are deliberate — the drawer still swipes open over it, and a voice
 * tap cannot fall through to the chat view's session-open and render an empty
 * chat with a composer under it.
 */
@Composable
fun VoiceSessionDetail(
    session: SessionSummary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        VoiceHeader(session)
        VoiceDetailCard(session)
    }
}

@Composable
private fun VoiceHeader(session: SessionSummary) {
    val fallback = voiceTitleIsFallback(session)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_person),
                    contentDescription = null,
                    tint = OrigonTheme.colors.textTertiary,
                    modifier = Modifier.size(52.dp),
                )
            }
            // The voice affordance, ringed in the page colour so it reads as
            // sitting on the avatar rather than merging into it.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(OrigonTheme.colors.screenBackground)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(OrigonTheme.colors.gray4),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_voice_channel),
                    contentDescription = null,
                    tint = OrigonTheme.colors.textPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Text(
            text = voiceDisplayTitle(session),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (fallback) {
                OrigonTheme.colors.textSecondary
            } else {
                OrigonTheme.colors.textPrimary
            },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoiceDetailCard(session: SessionSummary) {
    val rows = buildList {
        add("Session" to voiceSessionShort(session.sessionId))
        add("Date" to voiceStartedText(session.sessionId, session.createdAt))
        if (showVoiceTeamRow(session)) add("Team" to session.subject)
        add(
            "Duration" to
                voiceDurationText(session.sessionId, session.createdAt, session.updatedAt),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        rows.forEachIndexed { index, (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OrigonTheme.colors.textSecondary,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OrigonTheme.colors.textPrimary,
                    textAlign = TextAlign.End,
                )
            }
            if (index < rows.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = OrigonTheme.colors.border.copy(alpha = 0.2f),
                )
            }
        }
    }
}
