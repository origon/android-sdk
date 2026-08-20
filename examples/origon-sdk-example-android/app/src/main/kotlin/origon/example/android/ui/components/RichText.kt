package origon.example.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

internal sealed interface ExampleRichBlock {
    data class Paragraph(val text: AnnotatedString) : ExampleRichBlock
    data class Heading(val level: Int, val text: AnnotatedString) : ExampleRichBlock
    data class ListRow(val ordinal: Int?, val depth: Int, val text: AnnotatedString) : ExampleRichBlock
    data class Quote(val text: AnnotatedString) : ExampleRichBlock
    data class CodeBlock(val text: String) : ExampleRichBlock
    data object Rule : ExampleRichBlock
}

internal data class ExampleRichParseResult(
    val blocks: List<ExampleRichBlock>,
    val ranOffCallerThread: Boolean,
)

internal object ExampleRichText {
    const val MAX_INPUT_BYTES = 262_144
    const val MAX_NODES = 4_096
    const val MAX_DEPTH = 64
    const val MAX_LIST_ITEMS = 1_024
    const val MAX_OUTPUT_CHARS = 131_072

    suspend fun parse(
        html: String?,
        text: String?,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ExampleRichParseResult {
        val caller = Thread.currentThread()
        return withContext(dispatcher) {
            ExampleRichParseResult(compute(html, text), Thread.currentThread() !== caller)
        }
    }

    fun computeForTest(html: String?, text: String?): List<ExampleRichBlock> = compute(html, text)

    private data class Budget(var nodes: Int = 0, var lists: Int = 0, var output: Int = 0) {
        fun take(depth: Int, chars: Int = 0, list: Boolean = false): Boolean {
            nodes++
            output += chars
            if (list) lists++
            return depth <= MAX_DEPTH && nodes <= MAX_NODES && lists <= MAX_LIST_ITEMS &&
                output <= MAX_OUTPUT_CHARS
        }
    }

    private fun compute(html: String?, text: String?): List<ExampleRichBlock> {
        if (!html.isNullOrEmpty() && html.toByteArray().size <= MAX_INPUT_BYTES) {
            htmlBlocks(html)?.let { return it }
        }
        if (!text.isNullOrEmpty()) {
            if (text.toByteArray().size <= MAX_INPUT_BYTES) markdownBlocks(text)?.let { return it }
            return plain(text)
        }
        if (!html.isNullOrEmpty()) {
            val stripped = if (html.toByteArray().size <= MAX_INPUT_BYTES) {
                runCatching { Jsoup.parseBodyFragment(html).text() }.getOrDefault(html)
            } else html
            return plain(stripped)
        }
        return emptyList()
    }

    private val blockTags = setOf(
        "p", "ul", "ol", "blockquote", "pre", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
    )
    private val inlineTags = setOf("strong", "b", "em", "i", "s", "del", "strike", "u", "code", "a", "br")

    private fun htmlBlocks(html: String): List<ExampleRichBlock>? {
        val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull() ?: return null
        if (body.children().isEmpty()) return null
        val budget = Budget()
        val result = mutableListOf<ExampleRichBlock>()
        for (node in body.childNodes()) {
            if (node is TextNode && node.isBlank) continue
            val element = node as? Element ?: return null
            if (element.tagName() !in blockTags || !mapHtml(element, 0, budget, result)) return null
        }
        return result.ifEmpty { null }
    }

    private fun mapHtml(
        element: Element, depth: Int, budget: Budget, out: MutableList<ExampleRichBlock>,
    ): Boolean {
        if (!budget.take(depth)) return false
        when (element.tagName()) {
            "p" -> htmlInline(element, depth + 1, budget)?.let { if (it.isNotEmpty()) out += ExampleRichBlock.Paragraph(it) } ?: return false
            in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> htmlInline(element, depth + 1, budget)?.let {
                out += ExampleRichBlock.Heading(element.tagName().drop(1).toInt(), it)
            } ?: return false
            "blockquote" -> {
                val value = element.text()
                if (!budget.take(depth + 1, value.length)) return false
                out += ExampleRichBlock.Quote(AnnotatedString(value))
            }
            "pre" -> {
                val value = element.wholeText().trim('\n')
                if (!budget.take(depth + 1, value.length)) return false
                out += ExampleRichBlock.CodeBlock(value)
            }
            "hr" -> out += ExampleRichBlock.Rule
            "ul", "ol" -> return mapHtmlList(element, depth, budget, out)
            else -> return false
        }
        return true
    }

    private fun mapHtmlList(
        list: Element, depth: Int, budget: Budget, out: MutableList<ExampleRichBlock>,
    ): Boolean {
        val ordered = list.tagName() == "ol"
        var ordinal = (list.attr("start").toIntOrNull() ?: 1).coerceIn(0, 999_999)
        for (item in list.children()) {
            if (item.tagName() != "li" || !budget.take(depth + 1, list = true)) return false
            val nested = item.children().filter { it.tagName() == "ul" || it.tagName() == "ol" }
            val clone = item.clone()
            clone.children().filter { it.tagName() == "ul" || it.tagName() == "ol" }.forEach(Element::remove)
            val value = htmlInline(clone, depth + 2, budget) ?: return false
            out += ExampleRichBlock.ListRow(if (ordered) ordinal else null, depth, value)
            for (child in nested) if (!mapHtmlList(child, depth + 1, budget, out)) return false
            ordinal++
        }
        return true
    }

    private fun htmlInline(element: Element, depth: Int, budget: Budget): AnnotatedString? {
        if (depth > MAX_DEPTH) return null
        return buildAnnotatedString {
            fun visit(node: org.jsoup.nodes.Node, level: Int): Boolean {
                if (!budget.take(level)) return false
                if (node is TextNode) {
                    val value = node.wholeText.replace(Regex("\\s+"), " ")
                    if (!budget.take(level, value.length)) return false
                    append(value)
                    return true
                }
                val child = node as? Element ?: return true
                if (child.tagName() !in inlineTags && child !== element) return false
                if (child.tagName() == "br") { append('\n'); return budget.take(level, 1) }
                val start = length
                for (nested in child.childNodes()) if (!visit(nested, level + 1)) return false
                if (child.tagName() == "a") safeHttpUrl(child.attr("href"))?.let {
                    addLink(LinkAnnotation.Url(it, TextLinkStyles()), start, length)
                }
                return true
            }
            for (node in element.childNodes()) if (!visit(node, depth)) return null
        }
    }

    private val parser = Parser.builder().build()

    private fun markdownBlocks(text: String): List<ExampleRichBlock>? {
        val root = runCatching { parser.parse(text) }.getOrNull() ?: return null
        val budget = Budget()
        val out = mutableListOf<ExampleRichBlock>()
        fun walk(node: Node?, depth: Int): Boolean {
            var current = node
            while (current != null) {
                if (!budget.take(depth)) return false
                when (current) {
                    is Heading -> out += ExampleRichBlock.Heading(current.level, markdownInline(current, budget, depth + 1) ?: return false)
                    is Paragraph -> out += ExampleRichBlock.Paragraph(markdownInline(current, budget, depth + 1) ?: return false)
                    is BlockQuote -> out += ExampleRichBlock.Quote(markdownInline(current, budget, depth + 1) ?: return false)
                    is FencedCodeBlock -> {
                        if (!budget.take(depth, current.literal.length)) return false
                        out += ExampleRichBlock.CodeBlock(current.literal.trim('\n'))
                    }
                    is IndentedCodeBlock -> {
                        if (!budget.take(depth, current.literal.length)) return false
                        out += ExampleRichBlock.CodeBlock(current.literal.trim('\n'))
                    }
                    is BulletList, is OrderedList -> {
                        var item = current.firstChild
                        var ordinal = (current as? OrderedList)?.markerStartNumber ?: 1
                        while (item != null) {
                            if (item is ListItem) {
                                if (!budget.take(depth, list = true)) return false
                                out += ExampleRichBlock.ListRow(
                                    if (current is OrderedList) ordinal else null, depth,
                                    markdownInline(item, budget, depth + 1) ?: return false,
                                )
                                ordinal++
                            }
                            item = item.next
                        }
                    }
                    else -> Unit
                }
                current = current.next
            }
            return true
        }
        return if (walk(root.firstChild, 0)) out.ifEmpty { null } else null
    }

    private fun markdownInline(node: Node, budget: Budget, depth: Int): AnnotatedString? = buildAnnotatedString {
        fun visit(current: Node?, level: Int): Boolean {
            var item = current
            while (item != null) {
                if (!budget.take(level)) return false
                when (item) {
                    is MarkdownText -> { if (!budget.take(level, item.literal.length)) return false; append(item.literal) }
                    is Code -> { if (!budget.take(level, item.literal.length)) return false; append(item.literal) }
                    is Link -> {
                        val start = length
                        if (!visit(item.firstChild, level + 1)) return false
                        safeHttpUrl(item.destination)?.let { addLink(LinkAnnotation.Url(it, TextLinkStyles()), start, length) }
                    }
                    else -> if (!visit(item.firstChild, level + 1)) return false
                }
                item = item.next
            }
            return true
        }
        if (!visit(node.firstChild, depth)) return null
    }

    fun safeHttpUrl(raw: String): String? {
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if ((scheme != "http" && scheme != "https") || parsed.host.isNullOrBlank()) return null
        val mark = raw.indexOf(':')
        return scheme + raw.substring(mark)
    }

    private fun plain(text: String): List<ExampleRichBlock> =
        text.take(MAX_OUTPUT_CHARS).takeIf(String::isNotEmpty)?.let {
            listOf(ExampleRichBlock.Paragraph(AnnotatedString(it)))
        }.orEmpty()
}

@Composable
internal fun ExampleRichMessageText(
    blocks: List<ExampleRichBlock>, color: Color, style: TextStyle, modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ExampleRichBlock.Paragraph -> Text(block.text, color = color, style = style)
                is ExampleRichBlock.Heading -> Text(block.text, color = color, style = style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                is ExampleRichBlock.ListRow -> Row(Modifier.padding(start = (block.depth * 12).dp)) {
                    Text(block.ordinal?.let { "$it. " } ?: "• ", color = color, style = style)
                    Text(block.text, color = color, style = style)
                }
                is ExampleRichBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(3.dp).height(24.dp).background(color.copy(alpha = .35f)))
                    Text(block.text, color = color, style = style)
                }
                is ExampleRichBlock.CodeBlock -> Text(
                    block.text, color = color, style = style.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().background(color.copy(alpha = .08f), RoundedCornerShape(6.dp)).padding(8.dp),
                )
                ExampleRichBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = .2f)))
            }
        }
    }
}
