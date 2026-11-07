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
    fun parsesIsoWithOffset() {
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T16:00:00+05:30"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T10:30:00Z"))
        assertEquals(utc1030 + 123, TextUtil.parseDate("2026-07-20T10:30:00.123Z"))
    }

    @Test
    fun zonelessDatesAreColomboTime() {
        // Colombo is UTC+05:30 year-round.
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20 16:00:00"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20T16:00:00"))
        assertEquals(utc1030, TextUtil.parseDate("2026-07-20 16:00"))
    }

    @Test
    fun unparseableOrEmptyFallsBackToNow() {
        for (raw in listOf("", "   ", "not a date")) {
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
