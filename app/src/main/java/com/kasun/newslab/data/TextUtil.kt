package com.kasun.newslab.data

import androidx.core.text.HtmlCompat
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Accent hues cycled through as sources are added. All sit on the cool half of the
 * wheel — blue through violet to green — so a source can never be mistaken for the
 * amber that marks the reader's own rules. Stored per feed as the light-mode cut;
 * the theme resolves the dark-mode variant at render time.
 */
object Palette {
    const val SLATE = 0xFF2F5AA8
    const val TEAL = 0xFF14666B
    const val INDIGO = 0xFF3B3E9E
    const val PLUM = 0xFF6E3B86
    const val ROSE = 0xFF9B2B57
    const val SAGE = 0xFF3F6B4A
    const val STEEL = 0xFF1F4E7A

    val colors = listOf(SLATE, TEAL, INDIGO, PLUM, ROSE, SAGE, STEEL)

    fun next(index: Int): Long = colors[index % colors.size]
}

object TextUtil {

    private val markdownImage = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val intraParagraphWhitespace = Regex("\\s+")
    private val paragraphBreak = Regex("\n\\s*\n+")

    /**
     * Converts raw RSS titles/descriptions (HTML, CDATA leftovers, markdown
     * image syntax, entities) into clean plain text. Paragraph breaks from
     * block tags (<p>, <br><br>) are kept as blank lines; everything else
     * (single line-wraps, runs of spaces) collapses to a single space.
     */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace(markdownImage, " ")
        text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        text = text.replace('\uFFFC', ' ').replace('\u00A0', ' ')
        text = text.replace(markdownImage, " ")
        text = text.replace(bareUrl, " ")
        return text.split(paragraphBreak)
            .map { it.replace(intraParagraphWhitespace, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // MessageDigest isn't thread-safe and a refresh hashes from several feed
    // coroutines at once, so each thread keeps its own. The output has to stay
    // byte-identical either way — it's the article primary key.
    private val md5Digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    fun md5(input: String): String {
        val digest = md5Digest.get()!!
        digest.reset()
        val bytes = digest.digest(input.toByteArray())
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt()
            out[i * 2] = HEX[(b shr 4) and 0xF]
            out[i * 2 + 1] = HEX[b and 0xF]
        }
        return String(out)
    }

    // Formats seen in the wild for the bundled feeds:
    //   EconomyNext (WordPress): "Mon, 20 Jul 2026 10:30:00 +0000"
    //   Daily Mirror:            "2026-07-20 21:08:00"  (Sri Lanka local time)
    private val datePatterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd MMM yyyy HH:mm:ss Z",
    )

    // Patterns carrying no zone/offset come from Sri Lankan outlets publishing
    // local wall-clock time; parsing them in the device zone would skew them
    // for any user travelling outside Asia/Colombo.
    private val zonelessPatterns = setOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
    )

    fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return System.currentTimeMillis()
        // A date starting with a letter is a day name ("Mon, 20 Jul ..."), one starting
        // with a digit isn't. Trying the matching group first turns the usual seven
        // failed parses — each filling in a stack trace — into a hit on the first go.
        // Every pattern is still tried, so nothing parses differently than before.
        val dayNameFirst = s[0].isLetter()
        val ordered = if (dayNameFirst) datePatterns else datePatterns.sortedBy { it.startsWith("EEE") }
        for (pattern in ordered) {
            try {
                val format = SimpleDateFormat(pattern, Locale.ENGLISH)
                if (pattern in zonelessPatterns) {
                    format.timeZone = TimeZone.getTimeZone("Asia/Colombo")
                }
                return format.parse(s)!!.time
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return System.currentTimeMillis()
    }

    // DateTimeFormatter is immutable and thread-safe, so unlike SimpleDateFormat these
    // can be built once instead of per call. Both are on hot paths: timeAgo runs from
    // every article row on every fling, and dayLabel from every day header.
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val DAY_HEADER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

    fun timeAgo(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> SHORT_DATE.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
        }
    }

    /** Start of the current local day; changes exactly at midnight, so it doubles as a cache key for day labels. */
    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Start of the local day [t] falls in — the grouping key behind a day header. */
    fun dayStartOf(t: Long): Long =
        Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Header for a day bucket. Takes [todayStart] rather than reading the clock so
     * callers can label a whole list against one consistent "today", and so this is
     * called once per distinct day instead of once per article.
     */
    fun dayLabel(dayStart: Long, todayStart: Long): String = when {
        dayStart >= todayStart -> "TODAY"
        dayStart >= todayStart - 86_400_000L -> "YESTERDAY"
        else -> DAY_HEADER.format(Instant.ofEpochMilli(dayStart).atZone(ZoneId.systemDefault()))
            .uppercase(Locale.ENGLISH)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
