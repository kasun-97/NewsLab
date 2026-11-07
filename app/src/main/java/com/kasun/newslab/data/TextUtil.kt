package com.kasun.newslab.data

import androidx.core.text.HtmlCompat
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQuery
import java.util.Locale

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
    //   Atom feeds:              "2026-07-20T10:30:00.123+05:30"
    //
    // Every formatter must consume the whole string. SimpleDateFormat.parse(String)
    // stopped at the first unreadable character, so an ISO timestamp with a zone it
    // couldn't read (seven fraction digits, "+0530") fell through to the zoneless
    // pattern, matched as a prefix and came out stamped as Colombo time.

    // Text carrying no zone/offset comes from Sri Lankan outlets publishing
    // local wall-clock time; parsing it in the device zone would skew it
    // for any user travelling outside Asia/Colombo.
    private val COLOMBO: ZoneId = ZoneId.of("Asia/Colombo")

    private fun strict(builder: DateTimeFormatterBuilder): DateTimeFormatter =
        builder.toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT)
            .withChronology(IsoChronology.INSTANCE)

    // ISO 8601 / RFC 3339 date and time (1-9 fraction digits, seconds optional), then
    // an optional offset. The first formatter of each pair reads "Z" / "+05:30", the
    // second "+0530" / "+05". They are separate formatters rather than two optional
    // sections in one, which would also accept a doubled offset ("+05:30+0530").
    private fun iso(separator: Char, offsetPattern: String): DateTimeFormatter = strict(
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(separator)
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalStart().appendOffset(offsetPattern, "Z").optionalEnd()
    )

    private val ISO_T = listOf(iso('T', "+HH:MM"), iso('T', "+HHmm"))
    private val ISO_SPACE = listOf(iso(' ', "+HH:MM"), iso(' ', "+HHmm"))

    private val OFFSET_DATE_TIME = TemporalQuery(OffsetDateTime::from)
    private val LOCAL_DATE_TIME = TemporalQuery(LocalDateTime::from)

    // Fixed so parsing never depends on the platform's locale data.
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .withIndex().associate { (i, name) -> (i + 1).toLong() to name }

    // RFC 822 / 1123 after the day name and any zone name are dealt with (below):
    // "20 Jul 2026 10:30:00 +0000", seconds optional.
    private val RFC_822: DateTimeFormatter = strict(
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral(' ')
            .appendText(ChronoField.MONTH_OF_YEAR, MONTHS).appendLiteral(' ')
            .appendValue(ChronoField.YEAR, 4).appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
            .appendLiteral(' ')
            .appendOffset("+HHMM", "+0000")
    )

    // The day name is stripped rather than parsed: java.time would reject a day name
    // that disagrees with the date, and feeds do get it wrong. It is redundant anyway.
    private val RFC_822_DAY_NAME = Regex("^(?:mon|tue|wed|thu|fri|sat|sun),\\s*", RegexOption.IGNORE_CASE)
    private val RFC_822_ZONE_NAME = Regex(" ([a-z]{1,3})$", RegexOption.IGNORE_CASE)

    // The zone names RFC 822 defines, as the fixed offsets it gives them. (A region zone
    // such as America/New_York would apply DST to "EST", which RFC 822 does not.)
    private val RFC_822_ZONES = mapOf(
        "GMT" to "+0000", "UT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400",
        "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600",
        "PST" to "-0800", "PDT" to "-0700",
    )

    fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return System.currentTimeMillis()
        // "2026-07-20..." is ISO; anything else ("Mon, 20 Jul ...", "20 Jul ...") is RFC 822.
        // Choosing up front means the common shapes parse on the first attempt.
        val millis = if (s.length > 10 && s[4] == '-') parseIso(s) else parseRfc822(s)
        return millis ?: System.currentTimeMillis()
    }

    private fun parseIso(s: String): Long? {
        val formatters = when (s[10]) {
            'T', 't' -> ISO_T
            ' ' -> ISO_SPACE
            else -> return null
        }
        for (formatter in formatters) {
            try {
                return when (val t = formatter.parseBest(s, OFFSET_DATE_TIME, LOCAL_DATE_TIME)) {
                    is OffsetDateTime -> t.toInstant().toEpochMilli()
                    else -> (t as LocalDateTime).atZone(COLOMBO).toInstant().toEpochMilli()
                }
            } catch (_: DateTimeParseException) {
                // try the other offset style
            }
        }
        return null
    }

    private fun parseRfc822(s: String): Long? {
        var text = RFC_822_DAY_NAME.replaceFirst(s, "")
        val zone = RFC_822_ZONE_NAME.find(text)
        if (zone != null) {
            val offset = RFC_822_ZONES[zone.groupValues[1].uppercase(Locale.ROOT)] ?: return null
            text = text.substring(0, zone.range.first + 1) + offset
        }
        return try {
            OffsetDateTime.parse(text, RFC_822).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
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
