package origon.example.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import kotlin.io.path.readText
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParserDependencyInstrumentedTest {
    @Test
    fun parsersAndDesugaredNioRunOnTheDevice() {
        assertEquals("device", Jsoup.parseBodyFragment("<i>device</i>").text())
        assertNotNull(
            Parser.builder()
                .extensions(listOf(StrikethroughExtension.create()))
                .build()
                .parse("~~device~~"),
        )

        val path = Files.createTempFile("origon-example-parser", ".txt")
        try {
            Files.writeString(path, "device")
            assertEquals("device", path.readText())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
