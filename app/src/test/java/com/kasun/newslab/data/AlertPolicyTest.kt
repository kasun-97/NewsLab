package com.kasun.newslab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private fun minutes(hour: Int, minute: Int = 0) = hour * 60 + minute

    private fun article(id: String, feedId: Long = 1, rules: String = ",1,", labels: String = "IMF") = Article(
        id = id,
        feedId = feedId,
        feedName = "Feed $feedId",
        feedColor = 0,
        title = "Title $id",
        summary = "",
        link = "https://example.com/$id",
        publishedAt = 0,
        fetchedAt = 0,
        rssCategories = "",
        categoryIds = "",
        matchedRules = rules,
        matchedLabels = labels,
    )

    private fun rule(id: Long, notify: Boolean = true, enabled: Boolean = true) =
        KeywordRule(id = id, label = "Rule $id", query = "q$id", enabled = enabled, notify = notify)

    private fun feed(id: Long, muted: Boolean = false) =
        Feed(id = id, name = "Feed $id", url = "https://example.com/$id.xml", color = 0, muted = muted)

    @Test
    fun sameDayWindowIsHalfOpen() {
        val start = minutes(13)
        val end = minutes(14, 30)
        assertFalse(AlertPolicy.inQuietHours(minutes(12, 59), start, end))
        assertTrue(AlertPolicy.inQuietHours(start, start, end))
        assertTrue(AlertPolicy.inQuietHours(minutes(14, 29), start, end))
        assertFalse(AlertPolicy.inQuietHours(end, start, end))
    }

    @Test
    fun overnightWindowWrapsPastMidnight() {
        val start = minutes(22)
        val end = minutes(7)
        assertFalse(AlertPolicy.inQuietHours(minutes(21, 59), start, end))
        assertTrue(AlertPolicy.inQuietHours(start, start, end))
        assertTrue(AlertPolicy.inQuietHours(minutes(23, 59), start, end))
        assertTrue(AlertPolicy.inQuietHours(0, start, end))
        assertTrue(AlertPolicy.inQuietHours(minutes(6, 59), start, end))
        assertFalse(AlertPolicy.inQuietHours(end, start, end))
        assertFalse(AlertPolicy.inQuietHours(minutes(12), start, end))
    }

    @Test
    fun equalBoundsAreAnEmptyWindow() {
        assertFalse(AlertPolicy.inQuietHours(minutes(8), minutes(8), minutes(8)))
        assertFalse(AlertPolicy.inQuietHours(minutes(20), minutes(8), minutes(8)))
    }

    @Test
    fun notifiesWhenAnyMatchedRuleStillNotifies() {
        val both = article("a", rules = ",1,2,")
        assertTrue(AlertPolicy.shouldNotify(both, setOf(2L), emptySet()))
        assertFalse(AlertPolicy.shouldNotify(both, setOf(3L), emptySet()))
        // ",12," is rule 12, not rules 1 and 2.
        assertFalse(AlertPolicy.shouldNotify(article("b", rules = ",12,"), setOf(1L, 2L), emptySet()))
    }

    @Test
    fun mutedSourceNeverNotifies() {
        assertFalse(AlertPolicy.shouldNotify(article("a", feedId = 7), setOf(1L), setOf(7L)))
        assertTrue(AlertPolicy.shouldNotify(article("a", feedId = 8), setOf(1L), setOf(7L)))
    }

    @Test
    fun planSplitsPostedFromSuppressed() {
        val pending = listOf(
            article("loud"),
            article("silentRule", rules = ",2,"),
            article("pausedRule", rules = ",3,"),
            article("deletedRule", rules = ",9,"),
            article("mutedFeed", feedId = 2),
            article("oneLoudOfTwo", rules = ",2,1,"),
        )
        val plan = AlertPolicy.plan(
            pending,
            rules = listOf(rule(1), rule(2, notify = false), rule(3, enabled = false)),
            feeds = listOf(feed(1), feed(2, muted = true)),
        )
        assertEquals(listOf("loud", "oneLoudOfTwo"), plan.post.map { it.id })
        assertEquals(listOf("silentRule", "pausedRule", "deletedRule", "mutedFeed"), plan.suppressed.map { it.id })
    }

    @Test
    fun digestOnlyForMoreThanOneAlert() {
        assertFalse(AlertPolicy.useDigest(digestEnabled = true, count = 1))
        assertTrue(AlertPolicy.useDigest(digestEnabled = true, count = 2))
        assertFalse(AlertPolicy.useDigest(digestEnabled = false, count = 5))
    }

    @Test
    fun digestTitleCountsAndListsDistinctLabels() {
        val sep = Classifier.LABEL_SEPARATOR
        val title = AlertPolicy.digestTitle(
            listOf(
                article("a", labels = "IMF"),
                article("b", labels = "Interest rates${sep}IMF"),
                article("c", labels = "IMF"),
            ),
        )
        assertEquals("3 new matches: IMF, Interest rates", title)
        assertEquals("1 new match", AlertPolicy.digestTitle(listOf(article("a", labels = ""))))
    }

    @Test
    fun clockLabelPadsToTwentyFourHourTime() {
        assertEquals("22:00", AlertPolicy.clockLabel(1320))
        assertEquals("07:00", AlertPolicy.clockLabel(420))
        assertEquals("00:05", AlertPolicy.clockLabel(5))
        assertEquals("23:59", AlertPolicy.clockLabel(1439))
    }
}
