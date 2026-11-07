package com.kasun.newslab.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Writes the saved articles out as Markdown (for reading or pasting into notes) or
 * CSV (for a spreadsheet). The formatting is kept pure, apart from the file write,
 * so it can be tested without a device.
 */
object SavedExporter {

    enum class Format(val extension: String, val mimeType: String) {
        MARKDOWN("md", "text/markdown"),
        CSV("csv", "text/csv"),
    }

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    // Numeric and locale-free, so the line reads the same whatever language the phone is in.
    private val MARKDOWN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun fileName(format: Format, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(now).atZone(zone))
        return "newslab-saved-$stamp.${format.extension}"
    }

    /**
     * One `##` section per article: source and local date, the link, then the cached
     * body when the article has been opened (or pre-fetched) and the feed summary
     * otherwise. [bodies] maps article id to extracted text.
     */
    fun markdown(articles: List<Article>, bodies: Map<String, String>, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append("# NewsLab saved articles\n")
            articles.forEach { article ->
                append("\n## ").append(oneLine(article.title).ifEmpty { "Untitled" }).append("\n\n")
                val date = if (article.publishedAt > 0) {
                    MARKDOWN_DATE.format(Instant.ofEpochMilli(article.publishedAt).atZone(zone))
                } else {
                    ""
                }
                val byline = listOf(oneLine(article.feedName), date).filter { it.isNotEmpty() }.joinToString(" · ")
                if (byline.isNotEmpty()) append(escapeMarkdown(byline)).append("\n\n")
                // Angle brackets make it an autolink even when the URL holds characters
                // (underscores, parentheses) that Markdown would otherwise interpret.
                if (article.link.isNotBlank()) append('<').append(article.link.trim()).append(">\n\n")
                val text = bodies[article.id]?.takeIf { it.isNotBlank() } ?: article.summary
                if (text.isNotBlank()) append(escapeMarkdown(text.trim())).append("\n")
            }
        }

    /**
     * RFC 4180 CSV with a header row and CRLF line ends, which is what Excel and
     * Sheets both expect. publishedAt is ISO-8601 in UTC so it sorts as text and
     * converts without guessing the phone's time zone.
     */
    fun csv(articles: List<Article>): String = buildString {
        append("title,source,publishedAt,link,summary\r\n")
        articles.forEach { article ->
            val published = if (article.publishedAt > 0) {
                Instant.ofEpochMilli(article.publishedAt).truncatedTo(ChronoUnit.SECONDS).toString()
            } else {
                ""
            }
            listOf(article.title, article.feedName, published, article.link, article.summary)
                .joinTo(this, ",") { csvField(it) }
            append("\r\n")
        }
    }

    /** Quotes a field only when it needs it: a comma, a double quote or a line break. */
    internal fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * Article text is plain prose, so only the one construct that would visibly wreck
     * the document is escaped: a line starting with `#` would become a heading that
     * outranks or interleaves with the article sections. Everything else is left as
     * written rather than littered with backslashes.
     */
    internal fun escapeMarkdown(text: String): String =
        text.lines().joinToString("\n") { line ->
            val indent = line.length - line.trimStart().length
            if (line.trimStart().startsWith("#")) line.substring(0, indent) + "\\" + line.substring(indent) else line
        }

    // A heading or byline must stay on one line, whatever whitespace the feed put in it.
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    suspend fun export(
        context: Context,
        destination: Uri,
        format: Format,
        articles: List<Article>,
        bodies: Map<String, String>,
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val content = when (format) {
                Format.MARKDOWN -> markdown(articles, bodies)
                // The byte-order mark is what tells Excel the file is UTF-8; without it,
                // Sinhala and Tamil titles open as mojibake.
                Format.CSV -> "\uFEFF" + csv(articles)
            }
            val out = context.contentResolver.openOutputStream(destination)
                ?: return@withContext ImportResult.Failure("Couldn't open the destination file.")
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ImportResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "Export failed.")
        }
    }
}
