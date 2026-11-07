package com.kasun.newslab.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

// Robolectric because clean() goes through androidx.core.text.HtmlCompat.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextUtilTest {

    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before
    fun pinZoneAndLocale() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        // Deliberately not Asia/Colombo, so zoneless dates prove they ignore the device zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    // ---- clean ----

    @Test
    fun cleanKeepsParagraphBreaksAndCollapsesOtherWhitespace() {
        assertEquals(
            "First para\n\nSecond para",
            TextUtil.clean("<p>First   para</p>\n<p>Second\npara</p>"),
        )
        // A single <br> is a line wrap, not a paragraph.
        assertEquals("Bold text more", TextUtil.clean("<b>Bold</b> text<br>more"))
    }

    @Test
    fun cleanDecodesEntitiesAndNbsp() {
        assertEquals("Tom & Jerry", TextUtil.clean("Tom &amp; Jerry"))
        assertEquals("A B C", TextUtil.clean("A&nbsp;B C"))
    }

    @Test
    fun cleanRemovesImagesAndBareUrls() {
        assertEquals("Intro end", TextUtil.clean("Intro ![alt text](https://x.com/i.png) end"))
        assertEquals("Read more at today", TextUtil.clean("Read more at https://example.com/a?b=1 today"))
        assertEquals("Before after", TextUtil.clean("Before <img src=\"https://x.com/i.png\"> after"))
    }

    @Test
    fun cleanBlankIsEmpty() {
        assertEquals("", TextUtil.clean(""))
        assertEquals("", TextUtil.clean("   \n "))
        assertEquals("", TextUtil.clean("<p></p>"))
    }

    // ---- parseDate ----

    private val utc1030 = 1_784_543_400_000L // 2026-07-20T10:30:00Z

    @Test
    fun parsesRfc822WithOffsetAndGmt() {
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 10:30:00 +0000"))
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 16:00:00 +0530"))
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 10:30:00 GMT"))
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 10:30 +0000"))
        assertEquals(utc1030, TextUtil.parseDate("20 Jul 2026 10:30:00 +0000"))
    }

    @Test
    fun parsesRfc822Variants() {
        // Without seconds, with and without the day name.
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 10:30 GMT"))
        assertEquals(utc1030, TextUtil.parseDate("20 Jul 2026 10:30 +0000"))
        assertEquals(utc1030, TextUtil.parseDate("20 Jul 2026 10:30:00 GMT"))
        // Single-digit day.
        assertEquals(utc1030 - 14 * 86_400_000L, TextUtil.parseDate("Mon, 6 Jul 2026 10:30:00 +0000"))
        // Negative offset.
        assertEquals(utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 05:30:00 -0500"))
        // English names, any case.
        assertEquals(utc1030, TextUtil.parseDate("MON, 20 JUL 2026 10:30:00 gmt"))
        assertEquals(utc1030, TextUtil.parseDate("mon, 20 jul 2026 10:30:00 +0000"))
        // A day name that disagrees with the date is ignored, as SimpleDateFormat did.
        assertEquals(utc1030, TextUtil.parseDate("Tue, 20 Jul 2026 10:30:00 +0000"))
    }

    @Test
    fun parsesRfc822ZoneNamesAsFixedOffsets() {
        for (zone in listOf("GMT", "UT", "UTC", "Z")) {
            assertEquals(zone, utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 10:30:00 $zone"))
        }
        // RFC 822 gives each US zone a fixed offset; EST stays -05:00 even in July.
        val local = mapOf(
            "EST" to "05:30", "EDT" to "06:30",
            "CST" to "04:30", "CDT" to "05:30",
            "MST" to "03:30", "MDT" to "04:30",
            "PST" to "02:30", "PDT" to "03:30",
        )
        for ((zone, time) in local) {
            assertEquals(zone, utc1030, TextUtil.parseDate("Mon, 20 Jul 2026 $time:00 $zone"))
        }
    }

    @Test
    fun parsesIsoWithOffset() {
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T16:00:00+05:30"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T10:30:00Z"))
        assertEquals(utc1030 + 123, TextUtil.parseDate("2026-07-20T10:30:00.123Z"))
    }

    @Test
    fun parsesIsoVariants() {
        assertEquals(utc1030 + 100, TextUtil.parseDate("2026-07-20T10:30:00.1Z"))
        assertEquals(utc1030 + 123, TextUtil.parseDate("2026-07-20T10:30:00.123456789Z"))
        assertEquals(utc1030 + 500, TextUtil.parseDate("2026-07-20T16:00:00.5+05:30"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T05:30:00-05:00"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T05:30:00-0500"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T15:30:00+05"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T10:30:00+00:00"))
        // RFC 3339 allows lower-case separators.
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20t10:30:00z"))
    }

    @Test
    fun isoOffsetsTheOldParserMisreadAsColomboTime() {
        // Seven fraction digits: was cut at the seconds and read as Colombo time.
        assertEquals(utc1030 + 123, TextUtil.parseDate("2026-07-20T10:30:00.1234567Z"))
        // Offset without a colon: 10:30 at +05:30 is 05:00 UTC.
        assertEquals(utc1030 - 19_800_000L, TextUtil.parseDate("2026-07-20T10:30:00+0530"))
    }

    @Test
    fun zonelessDatesAreColomboTime() {
        // Colombo is UTC+05:30 year-round.
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20 16:00:00"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T16:00:00"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20 16:00"))
        assertEquals(
            TextUtil.parseDate("2026-07-20T21:08:00+05:30"),
            TextUtil.parseDate("2026-07-20 21:08:00"),
        )
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        assertEquals(utc1030, TextUtil.parseDate("  2026-07-20T10:30:00Z \n"))
        assertEquals(utc1030, TextUtil.parseDate("\tMon, 20 Jul 2026 10:30:00 GMT  "))
        assertEquals(utc1030, TextUtil.parseDate(" 2026-07-20 16:00:00 "))
    }

    @Test
    fun unparseableOrEmptyFallsBackToNow() {
        val bad = listOf(
            "", "   ", "not a date",
            // Trailing text: the whole string has to be a date.
            "2026-07-20T10:30:00Z garbage",
            "2026-07-20 16:00:00 junk",
            "Mon, 20 Jul 2026 10:30:00 +0000 extra",
            "2026-07-20T10:30:00+05:30+0530",
            // Unknown zone name, impossible date or time.
            "Mon, 20 Jul 2026 10:30:00 XYZ",
            "2026-02-31T10:00:00Z",
            "Mon, 20 Jul 2026 25:00:00 +0000",
        )
        for (raw in bad) {
            val before = System.currentTimeMillis()
            val parsed = TextUtil.parseDate(raw)
            val after = System.currentTimeMillis()
            assertTrue("'$raw' -> $parsed", parsed in before..after)
        }
    }

    // ---- md5 ----

    @Test
    fun md5KnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", TextUtil.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", TextUtil.md5("abc"))
    }

    // ---- timeAgo / dayLabel ----

    @Test
    fun timeAgoBuckets() {
        val now = System.currentTimeMillis()
        assertEquals("now", TextUtil.timeAgo(now - 30_000))
        assertEquals("now", TextUtil.timeAgo(now + 3_600_000)) // future clamps to now
        assertEquals("5m", TextUtil.timeAgo(now - 5 * 60_000))
        assertEquals("3h", TextUtil.timeAgo(now - 3 * 3_600_000))
        assertEquals("2d", TextUtil.timeAgo(now - 2 * 86_400_000L))
    }

    @Test
    fun timeAgoOlderThanAWeekShowsDate() {
        assertEquals("Jan 5", TextUtil.timeAgo(1_767_614_400_000L)) // 2026-01-05T12:00Z
    }

    @Test
    fun dayLabels() {
        val today = 1_784_505_600_000L // Mon 2026-07-20T00:00Z
        assertEquals("TODAY", TextUtil.dayLabel(today, today))
        assertEquals("YESTERDAY", TextUtil.dayLabel(today - 86_400_000L, today))
        assertEquals("SAT, JUL 18", TextUtil.dayLabel(today - 2 * 86_400_000L, today))
    }

    // ---- formatBytes ----

    @Test
    fun formatBytesUnits() {
        assertEquals("0 B", TextUtil.formatBytes(0))
        assertEquals("1023 B", TextUtil.formatBytes(1023))
        assertEquals("1 KB", TextUtil.formatBytes(1024))
        assertEquals("2 KB", TextUtil.formatBytes(1536))
        assertEquals("1.0 MB", TextUtil.formatBytes(1024L * 1024))
        assertEquals("1.5 MB", TextUtil.formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("1.00 GB", TextUtil.formatBytes(1024L * 1024 * 1024))
    }
}
