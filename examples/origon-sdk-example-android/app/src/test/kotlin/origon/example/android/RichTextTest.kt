package origon.example.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import origon.example.android.ui.components.ExampleRichBlock
import origon.example.android.ui.components.ExampleRichText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextTest {
    @Test fun safeStructureLinksAndWorkerDispatcher() = runBlocking {
        val result = ExampleRichText.parse(
            "<h2>Hello</h2><blockquote><p>quote</p></blockquote><ol><li>one</li></ol>",
            "fallback", Dispatchers.Default,
        )
        assertTrue(result.ranOffCallerThread)
        assertEquals(3, result.blocks.size)
        assertEquals("https://example.invalid/a", ExampleRichText.safeHttpUrl("HTTPS://example.invalid/a"))
        assertNull(ExampleRichText.safeHttpUrl("javascript://alert"))
        assertNull(ExampleRichText.safeHttpUrl("intent://payload"))
    }

    @Test fun hostileCapsAndMalformedInputUseBoundedFallback() {
        val oversized = "é".repeat(ExampleRichText.MAX_INPUT_BYTES)
        assertEquals(ExampleRichText.MAX_OUTPUT_CHARS, visible(ExampleRichText.computeForTest(null, oversized)).length)
        val nodes = "<p>" + "<b>x</b>".repeat(4_200) + "</p>"
        assertEquals("fallback", visible(ExampleRichText.computeForTest(nodes, "fallback")))
        val deep = "<p>" + "<b>".repeat(80) + "x" + "</b>".repeat(80) + "</p>"
        assertEquals("safe", visible(ExampleRichText.computeForTest(deep, "safe")))
        val list = "<ol>" + "<li>x</li>".repeat(1_100) + "</ol>"
        assertEquals("list", visible(ExampleRichText.computeForTest(list, "list")))
        assertEquals("plain", visible(ExampleRichText.computeForTest("<script>x</script>", "plain")))
    }

    @Test fun markdownCodeQuoteAndListStayVisible() {
        val blocks = ExampleRichText.computeForTest(null, "> quote\n\n- item\n\n```\ncode\n```")
        assertTrue(blocks.any { it is ExampleRichBlock.Quote })
        assertTrue(blocks.any { it is ExampleRichBlock.ListRow })
        assertTrue(blocks.any { it is ExampleRichBlock.CodeBlock })
        assertFalse(visible(blocks).isEmpty())
    }

    private fun visible(blocks: List<ExampleRichBlock>) = blocks.joinToString("") {
        when (it) {
            is ExampleRichBlock.Paragraph -> it.text.text
            is ExampleRichBlock.Heading -> it.text.text
            is ExampleRichBlock.ListRow -> it.text.text
            is ExampleRichBlock.Quote -> it.text.text
            is ExampleRichBlock.CodeBlock -> it.text
            ExampleRichBlock.Rule -> ""
        }
    }
}
