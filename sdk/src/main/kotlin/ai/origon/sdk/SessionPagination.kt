package ai.origon.sdk

import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SessionDirectoryPager {
    private val lock = ReentrantLock()
    private var generation = 0L
    private var search: String? = null
    private val sessions = mutableListOf<SessionSummary>()
    private val live = mutableListOf<SessionSummary>()
    private var nextCursor: String? = null
    private var emptyContinuations = 0

    fun begin(rawSearch: String? = null, cached: List<SessionSummary> = emptyList()): Long =
        lock.withLock {
            generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
            search = normalizeSessionSearch(rawSearch.orEmpty()).ifEmpty { null }
            sessions.clear()
            if (search == null) sessions += cached
            live.clear()
            nextCursor = null
            emptyContinuations = 0
            generation
        }

    fun applyLive(summary: SessionSummary) = lock.withLock {
        live.removeAll { it.sessionId == summary.sessionId }
        sessions.removeAll { it.sessionId == summary.sessionId }
        if (search?.let { sessionSummaryMatches(summary, it) } != false) {
            live += summary
            sessions += summary
        }
        sessions.sortByDescending(SessionSummary::updatedAt)
    }

    fun merge(page: SessionDirectoryPage, candidate: Long, firstPage: Boolean): Boolean =
        lock.withLock {
            if (candidate != generation) return@withLock false
            if (firstPage) sessions.clear()
            page.sessions.forEach { sessions.upsert(it) }
            live.forEach { sessions.upsert(it) }
            sessions.sortByDescending(SessionSummary::updatedAt)
            nextCursor = page.nextCursor
            emptyContinuations = if (page.sessions.isEmpty() && nextCursor != null) {
                (emptyContinuations + 1).coerceAtMost(255)
            } else {
                0
            }
            true
        }

    fun snapshot(): SessionDirectoryPageSnapshot = lock.withLock {
        SessionDirectoryPageSnapshot(
            generation = generation,
            search = search,
            sessions = sessions.toList(),
            nextCursor = nextCursor,
            emptyContinuations = emptyContinuations,
        )
    }
}

class SessionHistoryPager {
    private val lock = ReentrantLock()
    private var generation = 0L
    private val history = mutableListOf<Message>()
    private val live = mutableListOf<Message>()
    private var control = SessionControl.AI
    private var nextCursor: String? = null
    private var emptyContinuations = 0

    fun begin(cached: SessionHistory? = null): Long = lock.withLock {
        generation = if (generation == Long.MAX_VALUE) 1 else generation + 1
        history.clear()
        history += cached?.history.orEmpty()
        live.clear()
        control = cached?.control ?: SessionControl.AI
        nextCursor = null
        emptyContinuations = 0
        generation
    }

    fun applyLive(message: Message) = lock.withLock {
        live.upsert(message)
        history.upsert(message)
    }

    fun merge(page: SessionHistoryPage, candidate: Long, firstPage: Boolean): Boolean =
        lock.withLock {
            if (candidate != generation) return@withLock false
            if (firstPage) {
                history.clear()
                history += page.history
            } else {
                val merged = page.history.toMutableList()
                history.forEach { merged.upsert(it) }
                history.clear()
                history += merged
            }
            live.forEach { history.upsert(it) }
            control = page.control
            nextCursor = page.nextCursor
            emptyContinuations = if (page.history.isEmpty() && nextCursor != null) {
                (emptyContinuations + 1).coerceAtMost(255)
            } else {
                0
            }
            true
        }

    fun snapshot(): SessionHistoryPageSnapshot = lock.withLock {
        SessionHistoryPageSnapshot(
            generation = generation,
            history = history.toList(),
            control = control,
            nextCursor = nextCursor,
            emptyContinuations = emptyContinuations,
        )
    }
}

fun normalizeSessionSearch(value: String): String {
    val result = StringBuilder()
    var pendingSpace = false
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        offset += Character.charCount(codePoint)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = result.isNotEmpty()
            continue
        }
        if (Character.getType(codePoint) == Character.CONTROL.toInt()) continue
        if (pendingSpace) {
            result.append(' ')
            pendingSpace = false
        }
        result.append(String(Character.toChars(codePoint)).lowercase(Locale.ROOT))
    }
    return result.toString()
}

fun sessionSummaryMatches(summary: SessionSummary, normalizedSearch: String): Boolean {
    fun String?.containsSearch(): Boolean =
        this?.let { normalizeSessionSearch(it).contains(normalizedSearch) } == true
    return summary.subject.containsSearch() ||
        summary.contact?.name.containsSearch() ||
        summary.lastMessage?.text.containsSearch() ||
        summary.lastMessage?.attachments?.any { it.name.containsSearch() } == true
}

private fun MutableList<SessionSummary>.upsert(summary: SessionSummary) {
    val index = indexOfFirst { it.sessionId == summary.sessionId }
    if (index >= 0) this[index] = summary else add(summary)
}

private fun sameMessage(existing: Message, incoming: Message): Boolean =
    (incoming.id.isNotEmpty() && (existing.id == incoming.id || existing.localId == incoming.id)) ||
        incoming.localId?.let { existing.id == it || existing.localId == it } == true

private fun MutableList<Message>.upsert(message: Message) {
    val index = indexOfFirst { sameMessage(it, message) }
    if (index >= 0) this[index] = message else add(message)
}

private val PAGE_JSON = Json { ignoreUnknownKeys = true }

internal fun decodeSessionDirectoryPage(payload: String): SessionDirectoryPage {
    val element = PAGE_JSON.parseToJsonElement(payload)
    require(element.jsonObject.keys == setOf("sessions", "nextCursor")) {
        "unexpected page envelope keys"
    }
    return PAGE_JSON.decodeFromJsonElement(SessionDirectoryPage.serializer(), element)
}

internal fun decodeSessionHistoryPage(payload: String): SessionHistoryPage {
    val element = PAGE_JSON.parseToJsonElement(payload)
    require(element.jsonObject.keys == setOf("history", "control", "nextCursor")) {
        "unexpected page envelope keys"
    }
    return PAGE_JSON.decodeFromJsonElement(SessionHistoryPage.serializer(), element)
}
