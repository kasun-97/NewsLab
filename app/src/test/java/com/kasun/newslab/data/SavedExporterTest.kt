package com.kasun.newslab.data

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

// Robolectric only for the file-writing test, which needs a real ContentResolver.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val colombo = ZoneId.of("Asia/Colombo")

    // 2026-11-08T08:35:00Z = 14:05 in Colombo (UTC+5:30).
    private val published = 1_794_126_900_000L

    private fun article(
        id: String = "a1",
        title: String = "Rates held",
        summary: String = "The bank kept rates on hold.",
        link: String = "https://example.com/rates",
        feedName: String = "Daily FT",
        publishedAt: Long = published,
    ) = Article(
        id = id, feedId = 1, feedName = feedName, feedColor = 0, title = title, summary = summary,
        link = link, publishedAt = publishedAt, fetchedAt = publishedAt, rssCategories = "",
        categoryIds = "", matchedRules = "", matchedLabels = "",
    )

    @Test
    fun fileNamesCarryTimestampAndExtension() {
        assertEquals(
            "newslab-saved-2026-11-08-140500.md",
            SavedExporter.fileName(SavedExporter.Format.MARKDOWN, published, colombo),
        )
        assertEquals(
            "newslab-saved-2026-11-08-140500.csv",
            SavedExporter.fileName(SavedExporter.Format.CSV, published, colombo),
        )
    }

    @Test
    fun csvFieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("plain", SavedExporter.csvField("plain"))
        assertEquals("\"a, b\"", SavedExporter.csvField("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", SavedExporter.csvField("say \"hi\""))
        assertEquals("\"line one\nline two\"", SavedExporter.csvField("line one\nline two"))
        assertEquals("\"cr\rhere\"", SavedExporter.csvField("cr\rhere"))
        assertEquals("", SavedExporter.csvField(""))
    }

    @Test
    fun csvHasHeaderUtcTimestampAndCrlfRows() {
        val csv = SavedExporter.csv(
            listOf(article(title = "Rates, again", summary = "He said \"no\".\nThen left.")),
        )
        assertEquals(
            "title,source,publishedAt,link,summary\r\n" +
                "\"Rates, again\",Daily FT,2026-11-08T08:35:00Z,https://example.com/rates," +
                "\"He said \"\"no\"\".\nThen left.\"\r\n",
            csv,
        )
    }

    @Test
    fun csvLeavesUnknownDateEmpty() {
        val row = SavedExporter.csv(listOf(article(publishedAt = 0))).split("\r\n")[1]
        assertEquals("Rates held,Daily FT,,https://example.com/rates,The bank kept rates on hold.", row)
    }

    @Test
    fun markdownSectionUsesBodyWhenCached() {
        val md = SavedExporter.markdown(
            listOf(article()),
            mapOf("a1" to "First paragraph.\n\nSecond paragraph."),
            colombo,
        )
        assertEquals(
            "# NewsLab saved articles\n" +
                "\n## Rates held\n\n" +
                "Daily FT · 2026-11-08 14:05\n\n" +
                "<https://example.com/rates>\n\n" +
                "First paragraph.\n\nSecond paragraph.\n",
            md,
        )
    }

    @Test
    fun markdownFallsBackToSummaryAndFlattensTitle() {
        val md = SavedExporter.markdown(
            listOf(article(title = "  Rates\n held  ")),
            mapOf("a1" to "   "),
            colombo,
        )
        assertTrue(md.contains("\n## Rates held\n"))
        assertTrue(md.endsWith("The bank kept rates on hold.\n"))
    }

    @Test
    fun markdownEscapesLeadingHashes() {
        assertEquals("\\# Not a heading", SavedExporter.escapeMarkdown("# Not a heading"))
        assertEquals("  \\## indented", SavedExporter.escapeMarkdown("  ## indented"))
        assertEquals("C# stays\nmid # stays", SavedExporter.escapeMarkdown("C# stays\nmid # stays"))

        val md = SavedExporter.markdown(listOf(article(summary = "#1 story\n#breaking")), emptyMap(), colombo)
        assertTrue(md.contains("\\#1 story\n\\#breaking"))
        assertFalse(md.contains("\n#1"))
    }

    @Test
    fun exportWritesUtf8WithBomForCsv() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(tmp.root, "saved.csv")
        val result = runBlocking {
            SavedExporter.export(
                context, Uri.fromFile(file), SavedExporter.Format.CSV,
                listOf(article(title = "ශ්‍රී ලංකා")), emptyMap(),
            )
        }
        assertEquals(ImportResult.Success, result)
        val bytes = file.readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertTrue(String(bytes, Charsets.UTF_8).contains("ශ්‍රී ලංකා,Daily FT"))
    }
}
