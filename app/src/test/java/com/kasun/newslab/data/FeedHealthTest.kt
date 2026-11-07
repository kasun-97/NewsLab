package com.kasun.newslab.data

import com.kasun.newslab.data.FeedDiscovery.DiscoveredFeed
import com.kasun.newslab.data.FeedHealth.Rediscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedHealthTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun feed(lastError: String = "", lastFetchedAt: Long = 0, id: Long = 1) =
        Feed(id = id, name = "Example", url = "https://example.com/feed", color = 0, lastError = lastError, lastFetchedAt = lastFetchedAt)

    // ---- isProblem / problems ----

    @Test
    fun healthyFeedIsNeverAProblem() {
        assertFalse(FeedHealth.isProblem(feed(lastFetchedAt = now - 72 * hour), now))
        assertFalse(FeedHealth.isProblem(feed(lastFetchedAt = 0), now))
    }

    @Test
    fun recentFailureWithARecentGoodFetchIsNotYetAProblem() {
        assertFalse(FeedHealth.isProblem(feed("HTTP 503", lastFetchedAt = now - 2 * hour), now))
        // Exactly a day is still inside the grace period.
        assertFalse(FeedHealth.isProblem(feed("HTTP 503", lastFetchedAt = now - 24 * hour), now))
    }

    @Test
    fun failureAfterADayWithoutAGoodFetchIsAProblem() {
        assertTrue(FeedHealth.isProblem(feed("HTTP 404", lastFetchedAt = now - 24 * hour - 1), now))
    }

    @Test
    fun failingFeedThatNeverFetchedIsAProblem() {
        assertTrue(FeedHealth.isProblem(feed("Not a feed", lastFetchedAt = 0), now))
    }

    @Test
    fun blankErrorTextDoesNotCount() {
        assertFalse(FeedHealth.isProblem(feed("   ", lastFetchedAt = 0), now))
    }

    @Test
    fun problemsKeepsOnlyProblemFeedsInOrder() {
        val a = feed("HTTP 404", lastFetchedAt = 0, id = 1)
        val b = feed(lastFetchedAt = now - hour, id = 2)
        val c = feed("Timeout", lastFetchedAt = now - 48 * hour, id = 3)
        assertEquals(listOf(a, c), FeedHealth.problems(listOf(a, b, c), now))
    }

    // ---- siteRoot ----

    @Test
    fun siteRootKeepsSchemeAndHostOnly() {
        assertEquals("https://www.example.com/", FeedHealth.siteRoot("https://www.example.com/news/rss.xml?x=1"))
        assertEquals("http://example.com/", FeedHealth.siteRoot("  http://example.com/feed  "))
    }

    @Test
    fun siteRootKeepsANonDefaultPort() {
        assertEquals("https://example.com:8443/", FeedHealth.siteRoot("https://example.com:8443/feed"))
    }

    @Test
    fun siteRootOfAnUnparseableUrlIsNull() {
        assertNull(FeedHealth.siteRoot("not a url"))
        assertNull(FeedHealth.siteRoot(""))
    }

    // ---- rediscovery ----

    private val current = "https://example.com/feed"

    @Test
    fun nothingFoundIsNotFound() {
        assertEquals(Rediscovery.NotFound, FeedHealth.rediscovery(emptyList(), current))
    }

    @Test
    fun onlyTheCurrentAddressIsSameUrlEvenWithCosmeticDifferences() {
        val found = listOf(DiscoveredFeed("Example", "http://www.Example.com/feed/"))
        assertEquals(Rediscovery.SameUrl, FeedHealth.rediscovery(found, current))
    }

    @Test
    fun oneNewAddressIsOne() {
        val moved = DiscoveredFeed("Example", "https://example.com/rss.xml")
        assertEquals(Rediscovery.One(moved), FeedHealth.rediscovery(listOf(moved), current))
    }

    @Test
    fun currentAddressIsDroppedBeforeCounting() {
        val moved = DiscoveredFeed("Example", "https://example.com/rss.xml")
        val found = listOf(DiscoveredFeed("Example", current), moved)
        assertEquals(Rediscovery.One(moved), FeedHealth.rediscovery(found, current))
    }

    @Test
    fun severalNewAddressesAreSeveral() {
        val news = DiscoveredFeed("News", "https://example.com/news.xml")
        val sport = DiscoveredFeed("Sport", "https://example.com/sport.xml")
        val found = listOf(news, DiscoveredFeed("Example", current), sport)
        assertEquals(Rediscovery.Several(listOf(news, sport)), FeedHealth.rediscovery(found, current))
    }
}
