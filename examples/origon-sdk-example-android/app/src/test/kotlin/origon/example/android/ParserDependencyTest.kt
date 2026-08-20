package origon.example.android

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.commonmark.parser.Parser
import org.jsoup.Jsoup

class ParserDependencyTest {
    @Test
    fun exactParserSurfaceAndNioAreExecutable() {
        assertEquals("safe", Jsoup.parseBodyFragment("<b>safe</b>").text())
        assertNotNull(Parser.builder().build().parse("~~safe~~"))

        val path = Files.createTempFile("origon-example-parser", ".txt")
        try {
            Files.writeString(path, "safe")
            assertEquals("safe", path.readText())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
