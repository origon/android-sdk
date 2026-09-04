package ai.origon.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPaginationTest {
    private fun summary(
        id: String,
        updatedAt: String,
        subject: String = "",
        contact: Contact? = null,
        lastMessage: Message? = null,
    ) = SessionSummary(
        sessionId = id,
        subject = subject,
        channel = Channel.CHAT,
        active = true,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage,
        contact = contact,
    )

    @Test
    fun strictPageEnvelopesRequireNullableCursorAndClosedControl() {
        assertNull(decodeSessionDirectoryPage("""{"sessions":[],"nextCursor":null}""").nextCursor)
        for (invalid in listOf(
            """{"sessions":[]}""",
            """{"sessions":[],"nextCursor":null,"extra":true}""",
        )) {
            assertFails { decodeSessionDirectoryPage(invalid) }
        }
        assertEquals(
            SessionControl.USER,
            decodeSessionHistoryPage(
                """{"history":[],"control":"user","nextCursor":null}""",
            ).control,
        )
        for (invalid in listOf(
            """{"history":[],"control":"bogus","nextCursor":null}""",
            """{"history":[],"control":"ai"}""",
        )) {
            assertFails { decodeSessionHistoryPage(invalid) }
        }
    }

    @Test
    fun directoryPagerFencesQueriesAndReconcilesLiveSearchRows() {
        val pager = SessionDirectoryPager()
        val stale = pager.begin(cached = listOf(summary("cached", "1")))
        pager.applyLive(summary("live", "4"))
        assertTrue(pager.merge(
            SessionDirectoryPage(listOf(summary("server", "3")), "next"),
            stale,
            firstPage = true,
        ))
        assertEquals(listOf("live", "server"), pager.snapshot().sessions.map { it.sessionId })

        val generation = pager.begin("Ada")
        assertFalse(pager.merge(
            SessionDirectoryPage(listOf(summary("stale", "9")), null),
            stale,
            firstPage = true,
        ))
        pager.applyLive(summary("other", "5"))
        assertTrue(pager.snapshot().sessions.isEmpty())
        pager.applyLive(summary("matching", "6", contact = Contact("private", "ADA")))
        assertEquals(listOf("matching"), pager.snapshot().sessions.map { it.sessionId })
        pager.applyLive(summary("matching", "7"))
        assertTrue(pager.snapshot().sessions.isEmpty())
        assertEquals(generation, pager.snapshot().generation)
    }

    @Test
    fun searchCorpusAndNormalizationMatchServer() {
        assertEquals("a b", normalizeSessionSearch("  A\u2003\u2003B\u0000  "))
        val row = summary(
            id = "session-private",
            updatedAt = "1",
            subject = "Billing\u2003Question",
            contact = Contact("contact-private", "Ada Lovelace"),
            lastMessage = Message(
                id = "message-private",
                text = "Latest reply",
                attachments = listOf(
                    Attachment("attachment-private", "invoice.PDF", "x", "secret-url"),
                ),
            ),
        )
        for (query in listOf("billing question", "ada", "latest reply", "invoice.pdf")) {
            assertTrue(sessionSummaryMatches(row, query))
        }
        for (query in listOf("session-private", "contact-private", "secret-url")) {
            assertFalse(sessionSummaryMatches(row, query))
        }
    }

    @Test
    fun historyPagerPrependsAndCollapsesLocalAndServerIdentity() {
        val pager = SessionHistoryPager()
        val generation = pager.begin(SessionHistory(listOf(Message(id = "cached"))))
        pager.applyLive(Message(id = "", localId = "local", status = MessageStatus.SENDING))
        pager.applyLive(Message(id = "server", localId = "local"))
        assertTrue(pager.merge(
            SessionHistoryPage(listOf(Message(id = "new")), SessionControl.USER, "older"),
            generation,
            firstPage = true,
        ))
        assertTrue(pager.merge(
            SessionHistoryPage(listOf(Message(id = "old")), SessionControl.USER, null),
            generation,
            firstPage = false,
        ))
        assertEquals(listOf("old", "new", "server"), pager.snapshot().history.map { it.id })
        assertFalse(pager.snapshot().canLoadMore)
    }
}
