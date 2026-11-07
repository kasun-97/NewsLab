package com.kasun.newslab.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class AlertTrendTest {

    private lateinit var savedZone: TimeZone

    // New York rather than Colombo because it has daylight saving: 2026-11-01 falls
    // inside the window, which is the case a 24-hour step would get wrong.
    private val zone = ZoneId.of("America/New_York")

    @Before
    fun pinZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restore() {
        TimeZone.setDefault(savedZone)
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val todayStart get() = at(2026, 11, 8, 0)

    private fun rule(id: Long, label: String) = KeywordRule(id = id, label = label, query = label.lowercase())

    private fun row(publishedAt: Long, vararg ruleIds: Long) =
        AlertMatchRow(publishedAt, ruleIds.joinToString(",", ",", ","))

    @Test
    fun windowIsThirtyCalendarDaysEndingToday() {
        val starts = AlertTrend.dayStarts(todayStart)
        assertEquals(30, starts.size)
        assertEquals(todayStart, starts.last())
        // Local midnight on 10 Oct, though that is 29 days and an hour back across the DST change.
        assertEquals(at(2026, 10, 10, 0), starts.first())
        assertEquals(starts.first(), AlertTrend.windowStart(todayStart))
    }

    @Test
    fun rowsLandInTheirLocalDayOldestFirst() {
        val trend = AlertTrend.aggregate(
            listOf(
                row(at(2026, 11, 8, 9), 1),
                row(at(2026, 11, 8, 23), 1),
                row(at(2026, 10, 20, 0), 1), // on the stroke of midnight, before the DST change
                row(at(2026, 10, 10, 0), 1),
            ),
            listOf(rule(1, "IMF")),
            todayStart,
        )
        assertEquals(2, trend.daily[29])
        assertEquals(1, trend.daily[10])
        assertEquals(1, trend.daily[0])
        assertEquals(4, trend.daily.sum())
        assertEquals(4, trend.total)
    }

    @Test
    fun rowsOutsideTheWindowAreDropped() {
        val trend = AlertTrend.aggregate(
            listOf(
                row(at(2026, 10, 9, 23), 1), // the day before the window opens
                row(at(2026, 11, 9, 1), 1), // a feed dating an item into tomorrow
            ),
            listOf(rule(1, "IMF")),
            todayStart,
        )
        assertEquals(List(30) { 0 }, trend.daily)
        assertEquals(0, trend.total)
        assertEquals(emptyList<AlertTrend.RuleCount>(), trend.topRules)
    }

    @Test
    fun anArticleHittingTwoRulesCountsOnceInTheTotalAndOncePerRule() {
        val trend = AlertTrend.aggregate(
            listOf(row(at(2026, 11, 7), 1, 2)),
            listOf(rule(1, "IMF"), rule(2, "Rates")),
            todayStart,
        )
        assertEquals(1, trend.total)
        assertEquals(1, trend.daily[28])
        assertEquals(
            listOf(AlertTrend.RuleCount("IMF", 1), AlertTrend.RuleCount("Rates", 1)),
            trend.topRules,
        )
    }

    @Test
    fun topRulesAreTheThreeLargestTiesByLabelDeletedRulesSkipped() {
        val day = at(2026, 11, 1)
        val rows = buildList {
            repeat(5) { add(row(day, 9)) } // rule 9 has since been deleted
            repeat(4) { add(row(day, 3)) }
            repeat(2) { add(row(day, 1)) }
            repeat(2) { add(row(day, 2)) }
            add(row(day, 4))
        }
        val rules = listOf(rule(1, "rates"), rule(2, "Banks"), rule(3, "IMF"), rule(4, "Tea"))
        val trend = AlertTrend.aggregate(rows, rules, todayStart)
        assertEquals(
            listOf(
                AlertTrend.RuleCount("IMF", 4),
                AlertTrend.RuleCount("Banks", 2),
                AlertTrend.RuleCount("rates", 2),
            ),
            trend.topRules,
        )
        // The deleted rule's matches still count; only its listing is skipped.
        assertEquals(14, trend.total)
    }

    @Test
    fun noRowsGivesAnEmptyTrend() {
        val trend = AlertTrend.aggregate(emptyList(), listOf(rule(1, "IMF")), todayStart)
        assertEquals(List(30) { 0 }, trend.daily)
        assertEquals(0, trend.total)
        assertEquals(emptyList<AlertTrend.RuleCount>(), trend.topRules)
        assertEquals(at(2026, 10, 10, 0), trend.windowStart)
    }
}
