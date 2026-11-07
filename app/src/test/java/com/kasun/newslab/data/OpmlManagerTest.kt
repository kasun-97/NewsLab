package com.kasun.newslab.data

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

// Robolectric for android.util.Xml and a real ContentResolver over file:// URIs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpmlManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun parseText(opml: String): List<OpmlManager.OpmlEntry>? {
        val file = File.createTempFile("input", ".opml", tmp.root).apply { writeText(opml.trimIndent()) }
        return runBlocking { OpmlManager.parse(context, Uri.fromFile(file)) }
    }

    @Test
    fun exportThenParseRoundTrips() {
        val a = OpmlManager.OpmlFeed("Daily News & Views <LK>", "https://example.com/a.xml?x=1&y=2")
        val b = OpmlManager.OpmlFeed("B", "https://example.com/b")
        val c = OpmlManager.OpmlFeed("C", "https://example.com/c")
        val d = OpmlManager.OpmlFeed("D", "https://example.com/d")
        val file = File(tmp.root, "out.opml")

        val exported = runBlocking {
            OpmlManager.export(
                context,
                Uri.fromFile(file),
                groups = listOf(
                    OpmlManager.OpmlGroup("Local", listOf(a, b)),
                    OpmlManager.OpmlGroup("Markets", listOf(b, c)),
                ),
                ungrouped = listOf(d),
            )
        }
        assertEquals(ImportResult.Success, exported)

        val parsed = runBlocking { OpmlManager.parse(context, Uri.fromFile(file)) }
        assertEquals(
            listOf(
                OpmlManager.OpmlEntry(a.title, a.xmlUrl, "Local"),
                OpmlManager.OpmlEntry("B", b.xmlUrl, "Local"),
                OpmlManager.OpmlEntry("B", b.xmlUrl, "Markets"),
                OpmlManager.OpmlEntry("C", c.xmlUrl, "Markets"),
                OpmlManager.OpmlEntry("D", d.xmlUrl, null),
            ),
            parsed,
        )
    }

    @Test
    fun parseUsesNearestFolderAndTitleFallback() {
        val parsed = parseText(
            """
            <?xml version="1.0"?>
            <opml version="1.0">
              <head><title>Export</title></head>
              <body>
                <outline text="News">
                  <outline title="Only title" xmlurl=" https://example.com/1 "/>
                  <outline text="Sri Lanka">
                    <outline text="Nested" type="rss" xmlUrl="https://example.com/2"/>
                  </outline>
                  <outline text="Back in News" xmlUrl="https://example.com/3"/>
                  <outline/>
                  <outline text="After empty" xmlUrl="https://example.com/4"/>
                </outline>
                <outline text="Root" xmlUrl="https://example.com/5"/>
              </body>
            </opml>
            """,
        )
        assertEquals(
            listOf(
                OpmlManager.OpmlEntry("Only title", "https://example.com/1", "News"),
                OpmlManager.OpmlEntry("Nested", "https://example.com/2", "Sri Lanka"),
                OpmlManager.OpmlEntry("Back in News", "https://example.com/3", "News"),
                OpmlManager.OpmlEntry("After empty", "https://example.com/4", "News"),
                OpmlManager.OpmlEntry("Root", "https://example.com/5", null),
            ),
            parsed,
        )
    }

    @Test
    fun truncatedFileKeepsOutlinesReadSoFar() {
        // Current behaviour: the platform pull parser reaches END_DOCUMENT on a file cut
        // off mid-element without throwing, so outlines read before the cut are kept.
        assertEquals(
            listOf(OpmlManager.OpmlEntry("x", "https://e.com", null)),
            parseText("<opml><body><outline text=\"x\" xmlUrl=\"https://e.com\"/><outline text=\"F\">"),
        )
    }

    @Test
    fun parseFailuresReturnNull() {
        assertNull(parseText("<opml><body><outline text=\"News & Views\" xmlUrl=\"https://e.com\"/></body></opml>"))
        assertNull(parseText("not xml at all"))
        val missing = Uri.fromFile(File(tmp.root, "missing.opml"))
        assertNull(runBlocking { OpmlManager.parse(context, missing) })
    }

    @Test
    fun exportFileNameIsTimestamped() {
        assertTrue(Regex("""newslab-feeds-\d{4}-\d{2}-\d{2}-\d{6}\.opml""").matches(OpmlManager.exportFileName()))
    }
}
