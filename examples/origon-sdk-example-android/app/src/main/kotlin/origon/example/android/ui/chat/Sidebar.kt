package origon.example.android.ui.chat

import ai.origon.sdk.Channel
import ai.origon.sdk.SessionSummary
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import origon.example.android.R
import origon.example.android.ui.theme.OrigonTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The drawer's contents: the wordmark, the session history grouped by day, and
 * a footer that changes the endpoint.
 *
 * Purpose-built for this example rather than reduced from the shipped app's
 * sidebar. The shipped one is structured around account surfaces this example
 * deliberately has none of — an avatar, a profile screen, an internal-user
 * picker, logout. An example authenticates by endpoint and never signs a person
 * in, so changing the endpoint is the only footer action that means anything.
 *
 * The footer follows the **iOS example**, not the shipped Android app: an
 * overflow menu rather than a bare row, so the one destructive action is a
 * deliberate two-tap rather than something you can brush on the way past the
 * session list.
 */
@Composable
fun Sidebar(
    sessions: List<SessionSummary>,
    selectedSessionId: String?,
    onSessionPicked: (SessionSummary) -> Unit,
    onChangeEndpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recomputed when the list changes, not on every frame. The "today" it
    // buckets against is captured here, which is also what makes a midnight
    // rollover show up the next time the list refreshes rather than never.
    val groups = remember(sessions) { groupByDay(sessions) }

    Column(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // The wordmark's centre lines up with the history button's
                // across the fold: both sit directly under a
                // `systemBarsPadding()`, so they share an origin, and
                // 8 + 28/2 = 22 is `SessionHeader`'s 44dp button centred in its
                // 44dp row.
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_origon_wordmark),
                contentDescription = "Origon",
                modifier = Modifier.height(28.dp),
                // The drawable ships white as a placeholder; the tint is not
                // decoration — untinted it is white on white in light mode.
                colorFilter = ColorFilter.tint(OrigonTheme.colors.textPrimary),
            )
        }

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Your past sessions will appear here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrigonTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                // The **within-group** rhythm: 24 between groups, 4 between the
                // rows inside one. A flat list has only one spacing, so the
                // group gap is made up on the header instead (below). Applying
                // 24 to everything spaces the rows of one day as far apart as
                // two different days.
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEachIndexed { index, group ->
                    // `key` on the header as well as the rows: without it a
                    // regrouping (a session moving from TODAY to YESTERDAY over
                    // midnight) reuses slots by position and scrambles state.
                    // Keyed on the DAY, not the title — `MMM d` renders Aug 3
                    // 2025 and Aug 3 2026 identically, so the title alone
                    // collides across years.
                    item(key = "h:" + group.day) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontWeight = FontWeight.SemiBold),
                            color = OrigonTheme.colors.textTertiary,
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 12.dp,
                                // 20 + the list's own 4 = 24 between groups;
                                // nothing above the first one.
                                top = if (index == 0) 0.dp else 20.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                    items(group.sessions, key = { it.sessionId }) { session ->
                        SessionRow(
                            session = session,
                            selected = session.sessionId == selectedSessionId,
                            onClick = { onSessionPicked(session) },
                        )
                    }
                }
            }
        }

        OptionsFooter(onChangeEndpoint)
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val voice = session.channel == Channel.VOICE
        Image(
            painter = painterResource(
                if (voice) R.drawable.ic_voice_channel else R.drawable.ic_chat_channel,
            ),
            // Decorative — the preview text is what labels the row, and a
            // per-channel word here would be read out before every preview.
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            // Per-channel tone: at one shared tone the denser chat bubble
            // over-powers the wave bars.
            colorFilter = ColorFilter.tint(
                if (voice) OrigonTheme.colors.textSecondary
                else OrigonTheme.colors.textTertiary.copy(alpha = 0.55f),
            ),
        )
        Text(
            text = session.preview(),
            style = MaterialTheme.typography.bodyLarge,
            color = OrigonTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Row text: the last message, else the contact's name, else the subject, else a
 * neutral placeholder.
 */
private fun SessionSummary.preview(): String {
    lastMessage?.text?.takeIf { it.isNotEmpty() }?.let { return it }
    contact?.name?.takeIf { it.isNotEmpty() }?.let { return it }
    return subject.ifEmpty { "Untitled" }
}

/**
 * The footer overflow — iOS's `ellipsis.circle` opening a one-item menu.
 *
 * A menu rather than a bare "Change endpoint" row: changing the endpoint tears
 * down the client and drops every open session, and it sits directly under a
 * scrollable list the user is already dragging through. One deliberate tap to
 * open, one to confirm.
 */
@Composable
private fun OptionsFooter(onChangeEndpoint: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Options",
                    onClick = { open = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = "Options",
                // iOS `Origon.textPrimary.opacity(0.6)`.
                tint = OrigonTheme.colors.textPrimary.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.sidebar_change_endpoint),
                        // iOS marks this `role: .destructive`.
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    onChangeEndpoint()
                },
            )
        }
    }
}

// ── Grouping ─────────────────────────────────────────────────────────────

private class SessionGroup(
    /** The bucket's day — the header's IDENTITY. The title is display only. */
    val day: LocalDate,
    val title: String,
    val sessions: List<SessionSummary>,
)

/**
 * Bucket sessions by the local day of their `updatedAt` — TODAY, YESTERDAY,
 * then each older day as `MMM d`, newest first.
 *
 * **`java.time`, which is why this module desugars.** The SDK hands timestamps
 * out as raw ISO-8601 strings, and the obvious `SimpleDateFormat` port is not
 * merely clumsier — it is wrong at this app's floor: the ISO offset pattern `X`
 * is API 24+, so an API 23 device cannot parse a trailing `Z` at all, and the
 * usual workaround (a literal `'Z'` in the pattern) silently mis-reads any
 * payload that ever carries `+05:30`. See `app/build.gradle.kts`
 * `isCoreLibraryDesugaringEnabled`.
 *
 * Ordering within a group is the SDK's — the sessions list arrives newest-first
 * and this is a stable partition, so it is preserved rather than re-sorted.
 */
private fun groupByDay(
    sessions: List<SessionSummary>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<SessionGroup> {
    val yesterday = today.minusDays(1)

    val byDay = LinkedHashMap<LocalDate, MutableList<SessionSummary>>()
    for (session in sessions) {
        val day = parseInstant(session.sessionId, session.updatedAt)
            ?.atZone(zone)?.toLocalDate() ?: continue
        byDay.getOrPut(day) { mutableListOf() }.add(session)
    }

    val groups = mutableListOf<SessionGroup>()
    byDay[today]?.let { groups.add(SessionGroup(today, "TODAY", it)) }
    byDay[yesterday]?.let { groups.add(SessionGroup(yesterday, "YESTERDAY", it)) }
    byDay.entries
        .filter { it.key != today && it.key != yesterday }
        .sortedByDescending { it.key }
        .forEach {
            groups.add(
                SessionGroup(it.key, DAY_LABEL.format(it.key).uppercase(Locale.US), it.value),
            )
        }
    return groups
}

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

