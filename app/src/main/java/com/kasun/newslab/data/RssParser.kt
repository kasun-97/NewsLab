package com.kasun.newslab.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Defensive RSS 2.0 / Atom parser built on XmlPullParser.
 *
 * Handles the shapes used by the bundled feeds:
 *  - EconomyNext (WordPress RSS): CDATA titles/descriptions, multiple
 *    <category> tags, RFC-822 pubDate, <content:encoded> full HTML.
 *  - Daily Mirror: plain items, "yyyy-MM-dd HH:mm:ss" pubDate, CDATA
 *    descriptions with embedded image markup, guid == link.
 */
object RssParser {

    data class RssItem(
        val title: String,
        val link: String,
        val guid: String,
        val description: String,
        val pubDateRaw: String,
        val categories: List<String>,
        val imageUrl: String,
    )

    data class ParseResult(val items: List<RssItem>, val isAtom: Boolean)

    /** Cap per captured element; RSS fields past this are hostile or broken, not content. */
    private const val MAX_FIELD_CHARS = 200_000

    // Matches <img> and the AMP <amp-img> variant some WordPress themes emit.
    private val imgTag = Regex("""<(?:amp-)?img\b[^>]*?\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * Feed-supplied image URLs are untrusted: entity-decode (CDATA HTML keeps
     * &amp; literal, breaking query strings) and allow only web schemes so a
     * feed can't point the image loader at file:// or content:// URIs.
     */
    private fun sanitizeImageUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val url = raw.trim()
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
        return when {
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            else -> ""
        }
    }

    private fun firstImageUrl(html: String): String =
        sanitizeImageUrl(imgTag.find(html)?.groupValues?.get(1))

    fun parse(input: InputStream): ParseResult {
        val items = mutableListOf<RssItem>()
        var isAtom = false
        var rootSeen = false
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var inItem = false
            var itemDepth = -1
            var title = ""
            var link = ""
            var guid = ""
            var description = ""
            var content = ""
            var pubDate = ""
            var mediaImageUrl = ""
            val categories = mutableListOf<String>()
            var currentTag: String? = null
            var text: StringBuilder? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (!rootSeen) {
                            // The root element is the one reliable signal: <feed> is Atom,
                            // <rss>/<rdf> is RSS — unlike checking for <entry> vs <item>,
                            // this still works on a feed with zero entries.
                            rootSeen = true
                            isAtom = name == "feed"
                        }
                        if ((name == "item" || name == "entry") && !inItem) {
                            inItem = true
                            itemDepth = parser.depth
                            title = ""; link = ""; guid = ""; description = ""
                            content = ""; pubDate = ""; mediaImageUrl = ""
                            categories.clear()
                        } else if (inItem) {
                            // Only direct children switch the capture target. Deeper
                            // tags (Atom xhtml <content><div>…, markup inside a field)
                            // must not reset the buffer or their text would be lost.
                            if (parser.depth == itemDepth + 1) {
                                currentTag = name
                                text = StringBuilder()
                                when (name) {
                                    "link" -> {
                                        // Atom-style <link rel="..." href="..."/>. Entries carry
                                        // several (self/edit/replies/...) — only rel="alternate"
                                        // (or no rel) is the article page; never let the others
                                        // overwrite it.
                                        val rel = parser.getAttributeValue(null, "rel")
                                        val href = parser.getAttributeValue(null, "href")
                                        if (!href.isNullOrBlank() &&
                                            (rel == null || rel == "alternate" || link.isBlank())
                                        ) {
                                            link = href
                                        }
                                        // Atom's equivalent of RSS <enclosure>: an attached image.
                                        if (mediaImageUrl.isBlank() && rel == "enclosure" &&
                                            parser.getAttributeValue(null, "type")?.startsWith("image") == true
                                        ) {
                                            mediaImageUrl = sanitizeImageUrl(href)
                                        }
                                    }
                                    "category" -> {
                                        // Atom's <category term="..."/> is a spec-correct empty
                                        // element — the value lives in an attribute, not text
                                        // content like RSS's <category>Business</category>.
                                        parser.getAttributeValue(null, "term")
                                            ?.trim()?.takeIf { it.isNotEmpty() }
                                            ?.let { categories += it }
                                    }
                                }
                            }
                            // Image-bearing tags can sit at any depth (media:group wraps
                            // media:thumbnail/media:content), so check them regardless.
                            when (name) {
                                "media:thumbnail" -> if (mediaImageUrl.isBlank()) {
                                    mediaImageUrl = sanitizeImageUrl(parser.getAttributeValue(null, "url"))
                                }
                                "media:content" -> if (mediaImageUrl.isBlank()) {
                                    val medium = parser.getAttributeValue(null, "medium")
                                    val type = parser.getAttributeValue(null, "type")
                                    if (medium == "image" || type?.startsWith("image") == true) {
                                        mediaImageUrl = sanitizeImageUrl(parser.getAttributeValue(null, "url"))
                                    }
                                }
                                "enclosure" -> if (mediaImageUrl.isBlank()) {
                                    val type = parser.getAttributeValue(null, "type")
                                    if (type?.startsWith("image") == true) {
                                        mediaImageUrl = sanitizeImageUrl(parser.getAttributeValue(null, "url"))
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                        text?.let { if (it.length < MAX_FIELD_CHARS) it.append(parser.text ?: "") }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if ((name == "item" || name == "entry") && inItem && parser.depth == itemDepth) {
                            inItem = false
                            itemDepth = -1
                            currentTag = null
                            text = null
                            // Full-text feeds (e.g. WordPress) put an excerpt in <description>
                            // and the complete article in <content:encoded> — prefer the latter.
                            val body = content.ifBlank { description }
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items += RssItem(
                                    title = title,
                                    link = link,
                                    guid = guid.ifBlank { link },
                                    description = body,
                                    pubDateRaw = pubDate,
                                    categories = categories.toList(),
                                    imageUrl = mediaImageUrl.ifBlank {
                                        firstImageUrl(content).ifBlank { firstImageUrl(description) }
                                    },
                                )
                            }
                        } else if (inItem && parser.depth == itemDepth + 1 && name == currentTag) {
                            val value = text?.toString()?.trim() ?: ""
                            when (name) {
                                "title" -> title = value
                                "link" -> if (value.isNotBlank()) link = value
                                "guid", "id" -> guid = value
                                "description", "summary" -> description = value
                                "content:encoded", "content" -> content = value
                                "pubdate", "published", "updated", "dc:date" ->
                                    if (pubDate.isBlank()) pubDate = value
                                "category" -> if (value.isNotBlank()) categories += value
                            }
                            currentTag = null
                            text = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed tail — keep whatever parsed successfully.
        }
        return ParseResult(items, isAtom)
    }
}
