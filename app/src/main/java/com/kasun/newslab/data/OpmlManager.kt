package com.kasun.newslab.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * OPML 2.0 import/export for the source list — the interchange format every RSS
 * reader understands. Groups map to folder outlines; a source in several groups
 * is emitted once per folder, and the importer unions those back together by URL.
 */
object OpmlManager {

    /** One source, ready to write into an outline. */
    data class OpmlFeed(val title: String, val xmlUrl: String)

    /** A folder of sources (a NewsLab feed group). */
    data class OpmlGroup(val name: String, val feeds: List<OpmlFeed>)

    /** One parsed source, with the nearest enclosing folder name (null at the root). */
    data class OpmlEntry(val title: String, val xmlUrl: String, val group: String?)

    fun exportFileName(): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd-HHmmss", System.currentTimeMillis())
        return "newslab-feeds-$stamp.opml"
    }

    suspend fun export(
        context: Context,
        destination: Uri,
        groups: List<OpmlGroup>,
        ungrouped: List<OpmlFeed>,
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(destination)
                ?: return@withContext ImportResult.Failure("Couldn't open the destination file.")
            out.use { stream ->
                val s = Xml.newSerializer()
                s.setOutput(stream, "UTF-8")
                s.startDocument("UTF-8", true)
                s.startTag(null, "opml")
                s.attribute(null, "version", "2.0")
                s.startTag(null, "head")
                s.startTag(null, "title").text("NewsLab feeds").endTag(null, "title")
                s.endTag(null, "head")
                s.startTag(null, "body")
                groups.forEach { group ->
                    s.startTag(null, "outline")
                    s.attribute(null, "text", group.name)
                    s.attribute(null, "title", group.name)
                    group.feeds.forEach { writeFeed(s, it) }
                    s.endTag(null, "outline")
                }
                ungrouped.forEach { writeFeed(s, it) }
                s.endTag(null, "body")
                s.endTag(null, "opml")
                s.endDocument()
            }
            ImportResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "Export failed.")
        }
    }

    private fun writeFeed(s: org.xmlpull.v1.XmlSerializer, feed: OpmlFeed) {
        s.startTag(null, "outline")
        s.attribute(null, "type", "rss")
        s.attribute(null, "text", feed.title)
        s.attribute(null, "title", feed.title)
        s.attribute(null, "xmlUrl", feed.xmlUrl)
        s.endTag(null, "outline")
    }

    /** Parses an OPML file into a flat list of sources, or null if it can't be read. */
    suspend fun parse(context: Context, source: Uri): List<OpmlEntry>? = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(source) ?: return@withContext null
            input.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)

                val entries = mutableListOf<OpmlEntry>()
                // The folder each outline sits under; a parallel stack records whether
                // an outline opened a folder so the matching END_TAG pops the right one.
                val folderStack = ArrayDeque<String>()
                val openedFolder = ArrayDeque<Boolean>()

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> if (parser.name.equals("outline", ignoreCase = true)) {
                            val xmlUrl = attr(parser, "xmlUrl")
                            val title = attr(parser, "text").ifBlank { attr(parser, "title") }.trim()
                            if (xmlUrl.isNotBlank()) {
                                entries += OpmlEntry(title, xmlUrl.trim(), folderStack.lastOrNull())
                                openedFolder.addLast(false)
                            } else if (title.isNotEmpty()) {
                                folderStack.addLast(title)
                                openedFolder.addLast(true)
                            } else {
                                openedFolder.addLast(false)
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name.equals("outline", ignoreCase = true)) {
                            if (openedFolder.removeLastOrNull() == true) folderStack.removeLastOrNull()
                        }
                    }
                    event = parser.next()
                }
                entries
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i) ?: ""
            }
        }
        return ""
    }
}
