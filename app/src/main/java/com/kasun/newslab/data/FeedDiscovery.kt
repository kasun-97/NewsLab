package com.kasun.newslab.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

/**
 * RSS/Atom feed auto-discovery: given whatever a user pastes — a site URL or a feed
 * URL — finds the feed(s) it can subscribe to. It checks whether the URL is itself a
 * feed, then reads the page's `<link rel="alternate">` declarations, then falls back
 * to common feed paths, validating each candidate by actually parsing it.
 */
object FeedDiscovery {

    data class DiscoveredFeed(val name: String, val url: String)

    suspend fun discover(input: String): List<DiscoveredFeed> = withContext(Dispatchers.IO) {
        val start = normalize(input)?.toHttpUrlOrNull() ?: return@withContext emptyList()
        try {
            val page = fetch(start) ?: return@withContext emptyList()

            // 1. The pasted URL is already a feed.
            asFeed(page.bytes, page.url.toString())?.let { return@withContext listOf(it) }

            // 2. Standard HTML autodiscovery — feeds a page declares in its <head>.
            val candidates = LinkedHashSet<String>()
            runCatching {
                val doc = Jsoup.parse(ByteArrayInputStream(page.bytes), null, page.url.toString())
                doc.select(
                    "link[rel=alternate][type=application/rss+xml], " +
                        "link[rel=alternate][type=application/atom+xml], " +
                        "link[rel=alternate][type=application/xml], " +
                        "link[rel=alternate][type=text/xml]",
                ).forEach { el -> el.attr("abs:href").takeIf { it.isNotBlank() }?.let { candidates.add(it) } }
            }

            // 3. Nothing declared — probe the usual feed paths against the site root.
            // These typically all resolve to the one feed (/feed, /rss, ...), so the
            // loop below stops at the first hit when it took this route.
            val usedFallback = candidates.isEmpty()
            if (usedFallback) {
                COMMON_PATHS.forEach { path -> page.url.resolve(path)?.let { candidates.add(it.toString()) } }
            }

            // 4. Keep candidates that actually parse as populated feeds.
            val found = mutableListOf<DiscoveredFeed>()
            val seen = HashSet<String>()
            for (candidate in candidates) {
                if (found.size >= MAX_RESULTS) break
                val url = candidate.toHttpUrlOrNull() ?: continue
                if (url.encodedPath.contains("comments/feed")) continue // WordPress comment-feed noise
                if (!seen.add(dedupKey(url.toString()))) continue
                val body = fetch(url) ?: continue
                val feed = asFeed(body.bytes, body.url.toString()) ?: continue
                found += feed
                if (usedFallback) break // one site feed is enough when guessing paths
            }
            found.distinctBy { dedupKey(it.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The page's own title, for naming a saved link. Prefers `og:site_name` when the
     * document title is unhelpfully long, and returns null if the page can't be read
     * (many institutional sites block non-browser requests outright).
     */
    suspend fun pageTitle(input: String): String? = withContext(Dispatchers.IO) {
        val url = normalize(input)?.toHttpUrlOrNull() ?: return@withContext null
        try {
            val page = fetch(url) ?: return@withContext null
            val doc = Jsoup.parse(ByteArrayInputStream(page.bytes), null, page.url.toString())
            val title = doc.selectFirst("title")?.text()?.cleanTitle()
            val siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.cleanTitle()
            when {
                title == null -> siteName
                // A long title is usually the article/section headline; the site name
                // reads better as a bookmark label when one is published.
                title.length > 60 && !siteName.isNullOrBlank() -> siteName
                else -> title
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun String.cleanTitle(): String? =
        replace(Regex("\\s+"), " ").trim().take(80).takeIf { it.isNotBlank() }

    private class Page(val bytes: ByteArray, val url: HttpUrl)

    private fun fetch(url: HttpUrl): Page? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .header(
                "Accept",
                "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9, */*;q=0.8",
            )
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            // response.request.url is the final URL after redirects, so relative feed
            // links and common paths resolve against the right host.
            return Page(readCapped(body.byteStream(), MAX_BYTES), response.request.url)
        }
    }

    /** A [DiscoveredFeed] if [bytes] parse as a feed with at least one item, else null. */
    private fun asFeed(bytes: ByteArray, url: String): DiscoveredFeed? {
        val result = runCatching { RssParser.parse(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (result.items.isEmpty()) return null
        return DiscoveredFeed(feedTitle(bytes) ?: hostName(url), url)
    }

    /** The feed's own channel/feed <title>, via jsoup's XML parser (item titles are ignored). */
    private fun feedTitle(bytes: ByteArray): String? = runCatching {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        (doc.selectFirst("channel > title") ?: doc.selectFirst("feed > title"))
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
    }

    private fun hostName(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    private fun dedupKey(url: String): String =
        url.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < cap) {
            val n = input.read(buffer, 0, min(buffer.size, cap - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_RESULTS = 8
    private val COMMON_PATHS = listOf(
        "/feed", "/feed/", "/rss", "/rss.xml", "/index.xml", "/atom.xml", "/?feed=rss2", "/feeds/posts/default",
    )
}
