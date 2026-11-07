package com.kasun.newslab.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which sources have quietly stopped working, and what re-running discovery on one
 * of them amounts to. Kept free of Android and the database so the rules are tested
 * on their own.
 */
object FeedHealth {

    /**
     * A source must have gone this long without a good fetch before it is listed. One
     * failed refresh is usually a site having a bad hour, and the source row already
     * shows that; a day of failures is a feed that moved or died.
     */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /** True when the last refresh of [feed] failed and it hasn't fetched cleanly for a day (or ever). */
    fun isProblem(feed: Feed, now: Long): Boolean =
        feed.lastError.isNotBlank() &&
            (feed.lastFetchedAt == 0L || now - feed.lastFetchedAt > STALE_AFTER_MS)

    fun problems(feeds: List<Feed>, now: Long): List<Feed> = feeds.filter { isProblem(it, now) }

    /**
     * Scheme and host (and a non-default port) of [feedUrl], e.g. `https://example.com/`.
     * Discovery starts from the site's front page rather than the dead feed address,
     * because that page is where a moved feed is announced again.
     */
    fun siteRoot(feedUrl: String): String? {
        val url = feedUrl.trim().toHttpUrlOrNull() ?: return null
        return HttpUrl.Builder().scheme(url.scheme).host(url.host).port(url.port).build().toString()
    }

    /** What re-running discovery for a source turned up, from the point of view of fixing it. */
    sealed interface Rediscovery {
        /** Nothing parseable as a feed on the site. */
        data object NotFound : Rediscovery

        /** Discovery only found the address the source already has. */
        data object SameUrl : Rediscovery

        data class One(val feed: FeedDiscovery.DiscoveredFeed) : Rediscovery

        data class Several(val feeds: List<FeedDiscovery.DiscoveredFeed>) : Rediscovery
    }

    /**
     * Sorts the [found] feeds against the source's [currentUrl]. The current address is
     * dropped first: offering to "replace" a URL with itself would fix nothing, and it
     * would turn a site with one other feed into a needless pick-one list.
     */
    fun rediscovery(found: List<FeedDiscovery.DiscoveredFeed>, currentUrl: String): Rediscovery {
        val current = sameAddressKey(currentUrl)
        val others = found.filter { sameAddressKey(it.url) != current }
        return when {
            others.size > 1 -> Rediscovery.Several(others)
            others.size == 1 -> Rediscovery.One(others.single())
            found.isNotEmpty() -> Rediscovery.SameUrl
            else -> Rediscovery.NotFound
        }
    }

    // Same leniency as the duplicate checks elsewhere: http/https, www. and a trailing
    // slash don't make a different feed.
    private fun sameAddressKey(url: String): String =
        url.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}
