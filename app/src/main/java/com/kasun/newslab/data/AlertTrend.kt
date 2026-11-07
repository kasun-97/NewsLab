package com.kasun.newslab.data

import java.time.Instant
import java.time.ZoneId

/**
 * The Statistics screen's "keyword matches, last 30 days" chart, reduced from the raw
 * (publishedAt, matchedRules) rows. Kept free of Android and the clock so it can be
 * tested against a pinned zone and a fixed "today".
 */
object AlertTrend {

    const val DAYS = 30
    const val TOP_RULES = 3

    data class RuleCount(val label: String, val count: Int)

    /**
     * [daily] has [DAYS] entries, oldest first, the last being today. [total] counts
     * matched articles, so one hitting two rules adds one; [topRules] counts per rule,
     * so their sum can exceed [total] and the two shouldn't be compared directly.
     * [windowStart] is the start of the oldest day, for labelling the chart's left edge.
     */
    data class Trend(
        val daily: List<Int>,
        val total: Int,
        val topRules: List<RuleCount>,
        val windowStart: Long,
    )

    /**
     * Start of each day in the window, oldest first. Stepped by calendar day rather
     * than by 24 hours, so a daylight-saving change inside the window doesn't leave
     * every bucket before it an hour off the keys [TextUtil.dayStartOf] produces.
     */
    fun dayStarts(todayStart: Long): List<Long> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(todayStart).atZone(zone).toLocalDate()
        return (DAYS - 1 downTo 0).map {
            today.minusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    /** The lower bound to query from: midnight at the start of the oldest bucket. */
    fun windowStart(todayStart: Long): Long = dayStarts(todayStart).first()

    fun aggregate(rows: List<AlertMatchRow>, rules: List<KeywordRule>, todayStart: Long): Trend {
        val starts = dayStarts(todayStart)
        val bucketOf = starts.withIndex().associate { (i, start) -> start to i }
        val daily = IntArray(DAYS)
        val perRule = HashMap<Long, Int>()
        var total = 0
        for (row in rows) {
            // A feed that dates items ahead of the clock lands past today: no bucket,
            // and leaving it out of the total keeps the total equal to the bars' sum.
            val bucket = bucketOf[TextUtil.dayStartOf(row.publishedAt)] ?: continue
            daily[bucket]++
            total++
            // Stored as ",2,5," (see Classifier.classify); a set guards a malformed
            // string listing one id twice from counting that article twice.
            row.matchedRules.split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
                .forEach { perRule[it] = (perRule[it] ?: 0) + 1 }
        }
        // A deleted rule's id lingers in older rows until they're reclassified or
        // pruned; with no label to show it can't be listed, though its matches still
        // count towards the bars and the total.
        val labels = rules.associate { it.id to it.label }
        val topRules = perRule.mapNotNull { (id, count) -> labels[id]?.let { RuleCount(it, count) } }
            .sortedWith(compareByDescending<RuleCount> { it.count }.thenBy { it.label.lowercase() })
            .take(TOP_RULES)
        return Trend(daily.toList(), total, topRules, starts.first())
    }
}
