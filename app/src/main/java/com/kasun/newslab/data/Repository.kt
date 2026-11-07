package com.kasun.newslab.data

import android.content.Context
import com.kasun.newslab.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import com.kasun.newslab.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Single app-wide HTTP client: the connection pool, dispatcher threads and TLS
 * sessions are shared across the ViewModel's repository, the periodic worker's
 * (which would otherwise rebuild all of it every 15 minutes).
 */
internal object Http {
    /** Sent on every request the app makes, so sites can tell it apart from a browser. */
    val USER_AGENT = "Mozilla/5.0 (Linux; Android) NewsLab/${BuildConfig.VERSION_NAME} RSS reader"

    /**
     * The shared client with a hard deadline over the whole call. Coroutine deadlines
     * cannot bound a blocking `execute()` — cancelling a coroutine can't interrupt the
     * thread inside one, and the timeout doesn't return until that thread does — so
     * anything a person is waiting on has to be bounded by the client itself. Built
     * from [client], so the connection pool, dispatcher and TLS sessions are shared.
     */
    fun bounded(millis: Long): OkHttpClient =
        client.newBuilder().callTimeout(millis, TimeUnit.MILLISECONDS).build()

    /**
     * The address a page has to be asked for at: https, whatever the link said.
     *
     * Android has refused cleartext by default since targetSdk 28 and this app claims
     * no exemption, so an http:// request fails before it leaves the device. Feeds do
     * still carry http links — every article link in Ada Derana Sinhala's feed is one,
     * though its own host serves https perfectly well — and those articles were losing
     * both their page image and their full text to a request never made. A host with no
     * https at all fails either way, so this can only help.
     */
    fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // readTimeout is per-read; a drip-feeding server would otherwise hold a
            // fetch open indefinitely. This bounds the whole call.
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/** Truncates a response at [remaining] bytes so a pathological feed can't be buffered unbounded into memory. */
private class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Int,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = delegate.read(b, off, min(len, remaining))
        if (n > 0) remaining -= n
        return n
    }

    override fun close() = delegate.close()
}

class Repository(private val context: Context) {

    private val db = AppDb.get(context)
    val dao: NewsDao get() = db.dao()

    private val client get() = Http.client

    /** Outcome of a full refresh pass, for user-visible feedback and worker retry decisions. */
    data class RefreshResult(
        val added: Int,
        val attempted: Int,
        val failedFeeds: List<String>,
    )

    /**
     * Fetches every enabled feed concurrently, classifies new articles against the
     * current categories and keyword rules, and inserts them (duplicates ignored).
     *
     * @param notify post a notification for new keyword matches (used by the
     *   background worker; in-app refreshes pass false).
     */
    suspend fun refresh(notify: Boolean): RefreshResult = withContext(Dispatchers.IO) {
        // The manual refresh button and the periodic worker share this lock so the
        // same feed set is never double-fetched by overlapping triggers.
        refreshMutex.withLock {
            val feeds = dao.enabledFeeds()
            val categories = dao.categoriesOnce()
            val rules = dao.enabledRules()
            val now = System.currentTimeMillis()
            val cutoff = now - KEEP_DAYS * 86_400_000L
            val alertCutoff = now - ALERT_KEEP_DAYS * 86_400_000L

            val fetches: List<Pair<Feed, FetchOutcome?>> = coroutineScope {
                feeds.map { feed ->
                    async {
                        fetchSemaphore.withPermit { feed to fetchFeed(feed, categories, rules, now, cutoff, alertCutoff) }
                    }
                }.awaitAll()
            }

            var inserted = 0
            val newAlerts = mutableListOf<Article>()
            val failedFeeds = mutableListOf<String>()

            for ((feed, outcome) in fetches) {
                if (outcome == null) {
                    failedFeeds += feed.name
                    continue
                }
                // The server confirmed nothing changed since the validators we sent:
                // nothing to insert, and the stored validators are still the right ones.
                if (outcome.notModified) continue
                // Persisted on every successful fetch, not just ones with new
                // articles — detecting the format doesn't need fresh content.
                if (outcome.isAtom != feed.isAtom) {
                    dao.updateFeedIsAtom(feed.id, outcome.isAtom)
                }
                if (outcome.etag != feed.etag || outcome.lastModified != feed.lastModified) {
                    dao.updateFeedValidators(feed.id, outcome.etag, outcome.lastModified)
                }
                val articles = outcome.articles
                if (articles.isEmpty()) continue

                // A DB failure for one feed's batch degrades to a failed feed,
                // like a fetch failure — it must not abort the whole pass.
                try {
                    val rowIds = dao.insertArticles(articles)
                    val existingIds = rowIds.withIndex().filter { it.value == -1L }.map { articles[it.index].id }
                    // Chunked: a single IN(...) over a huge batch would blow
                    // SQLite's 999 bound-variable limit on older devices.
                    val existingById = existingIds.chunked(500)
                        .flatMap { dao.articlesByIds(it) }
                        .associateBy { it.id }

                    val resyncs = mutableListOf<ArticleResync>()
                    rowIds.forEachIndexed { index, rowId ->
                        val built = articles[index]
                        if (rowId != -1L) {
                            inserted++
                            if (built.matchedRules.isNotEmpty()) newAlerts += built
                        } else {
                            // Already stored — pick up richer text a feed may now offer
                            // (e.g. this parser used to prefer a short excerpt over the
                            // full <content:encoded> body) without touching read/saved
                            // state, the owning feed, or timestamps.
                            val existing = existingById[built.id] ?: return@forEachIndexed
                            // Same story syndicated by another of the user's feeds: leave
                            // the original owner's copy alone.
                            if (existing.feedId != built.feedId) return@forEachIndexed
                            val summary = built.summary.ifBlank { existing.summary }
                            // Never blank out a stored image just because a later feed
                            // payload carries none. (The page-scraped image lives in its
                            // own column and isn't touched by a resync at all.)
                            val imageUrl = built.imageUrl.ifBlank { existing.imageUrl }
                            if (summary != existing.summary || imageUrl != existing.imageUrl) {
                                // Classify from what will actually be stored (kept title,
                                // merged summary) — built's classification may have run
                                // over a blank fetched summary, and writing that would
                                // wipe a legitimate alert match off the retained text.
                                val classification = Classifier.classify(
                                    existing.title,
                                    summary,
                                    Classifier.splitStoredCategories(existing.rssCategories),
                                    categories,
                                    rules,
                                )
                                resyncs += ArticleResync(
                                    id = built.id,
                                    summary = summary,
                                    imageUrl = imageUrl,
                                    categoryIds = classification.categoryIds,
                                    matchedRules = classification.ruleIds,
                                    matchedLabels = classification.ruleLabels,
                                )
                            }
                        }
                    }
                    if (resyncs.isNotEmpty()) dao.resyncArticles(resyncs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failedFeeds += feed.name
                }
            }

            dao.pruneOlderThan(cutoff)
            // Rule matches live longer than the timeline, but not forever: a broad rule
            // would otherwise grow the database without bound.
            dao.pruneAlertsOlderThan(alertCutoff)
            // Drop cached bodies whose article just aged out, so the content table
            // doesn't outgrow the article list it mirrors.
            dao.pruneOrphanContent()

            if (notify && newAlerts.isNotEmpty()) {
                Notifier.notifyMatches(context, newAlerts)
            }
            // Stamped for every completed pass, not just ones that found new
            // articles — this is what Settings' "last refreshed" reflects, so a
            // quiet feed shouldn't make background refresh look like it stopped.
            Prefs.get(context).setLastRefreshAt(now)
            RefreshResult(added = inserted, attempted = feeds.size, failedFeeds = failedFeeds)
        }
    }

    /**
     * A feed's fetch result: its ready-to-insert articles, the format detected while
     * parsing, and the validators to send next time. [notModified] marks a 304: the
     * body was never sent, so [articles] is empty and the other fields are the feed's
     * stored values.
     */
    private data class FetchOutcome(
        val articles: List<Article>,
        val isAtom: Boolean,
        val etag: String = "",
        val lastModified: String = "",
        val notModified: Boolean = false,
    )

    /**
     * Fetches and parses one feed into ready-to-insert articles.
     * Returns null on any fetch/parse failure so the caller can report the feed,
     * and pre-drops items already older than their prune cutoff (they would be
     * inserted and immediately re-pruned on every refresh otherwise). Rule matches
     * get the longer [alertCutoff], mirroring the two prune windows exactly, so a
     * pruned alert still listed in the feed can't come back as "new" and re-fire.
     */
    private suspend fun fetchFeed(
        feed: Feed,
        categories: List<Category>,
        rules: List<KeywordRule>,
        now: Long,
        cutoff: Long,
        alertCutoff: Long,
    ): FetchOutcome? = try {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .apply {
                // Conditional request: with the validators from the last fetch, an
                // unchanged feed answers 304 and sends no body. Most of a polling
                // reader's traffic is feeds that haven't changed.
                if (feed.etag.isNotBlank()) header("If-None-Match", feed.etag)
                if (feed.lastModified.isNotBlank()) header("If-Modified-Since", feed.lastModified)
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_MODIFIED) {
                return@use FetchOutcome(emptyList(), feed.isAtom, feed.etag, feed.lastModified, notModified = true)
            }
            val body = response.body
            if (!response.isSuccessful || body == null) return@use null

            val parsed = RssParser.parse(LimitedInputStream(body.byteStream(), MAX_FEED_BYTES))
            val articles = parsed.items.map { item ->
                // Generous cap, not a display truncation — just a guard against
                // pathological feeds; typical articles land well under this.
                val summary = TextUtil.clean(item.description).take(8_000)
                val title = TextUtil.clean(item.title)
                // Classify from the same stored-string split used by reclassifyAll so
                // both paths see an identical haystack.
                val rssCategories = item.categories.joinToString(",")
                val classification = Classifier.classify(
                    title, summary, Classifier.splitStoredCategories(rssCategories), categories, rules,
                )
                Article(
                    id = TextUtil.md5(item.guid),
                    feedId = feed.id,
                    feedName = feed.name,
                    feedColor = feed.color,
                    title = title,
                    summary = summary,
                    link = item.link.trim(),
                    imageUrl = item.imageUrl,
                    // Clamp slightly-future dates so ordering stays sane.
                    publishedAt = min(TextUtil.parseDate(item.pubDateRaw), now + 60_000),
                    fetchedAt = now,
                    rssCategories = rssCategories,
                    categoryIds = classification.categoryIds,
                    matchedRules = classification.ruleIds,
                    matchedLabels = classification.ruleLabels,
                )
            }
                .filter { it.title.isNotBlank() }
                .filter { it.publishedAt >= cutoff || (it.matchedRules.isNotEmpty() && it.publishedAt >= alertCutoff) }
            FetchOutcome(
                articles,
                parsed.isAtom,
                etag = response.header("ETag").orEmpty(),
                lastModified = response.header("Last-Modified").orEmpty(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // One bad feed shouldn't break the whole refresh.
        null
    }

    /**
     * Re-runs classification over stored articles — called after the user
     * edits keyword rules or categories so existing articles pick up the
     * change (pins appear/disappear without waiting for a new fetch).
     * Only rows whose classification actually changed are written, and only
     * their classification columns — concurrent read/save actions are safe.
     */
    suspend fun reclassifyAll() = withContext(Dispatchers.IO) {
        val categories = dao.categoriesOnce()
        val rules = dao.enabledRules()
        val updates = dao.articlesOnce().mapNotNull { article ->
            val classification = Classifier.classify(
                article.title,
                article.summary,
                Classifier.splitStoredCategories(article.rssCategories),
                categories,
                rules,
            )
            if (classification.categoryIds == article.categoryIds &&
                classification.ruleIds == article.matchedRules &&
                classification.ruleLabels == article.matchedLabels
            ) {
                null
            } else {
                ClassificationUpdate(
                    id = article.id,
                    categoryIds = classification.categoryIds,
                    matchedRules = classification.ruleIds,
                    matchedLabels = classification.ruleLabels,
                )
            }
        }
        if (updates.isNotEmpty()) dao.updateClassifications(updates)
    }

    /**
     * Reads the image an article's own page advertises to social sharers — the
     * full-size one, where a feed's own payload is often a thumbnail or (as with
     * EconomyNext) missing entirely. Called lazily when the user opens an article;
     * fetches just enough of the page to read its og:image meta tag, without
     * downloading the full page.
     */
    suspend fun fetchArticleImage(url: String, deadlineMs: Long? = null): String? =
        withContext(Dispatchers.IO) {
            val http = if (deadlineMs == null) client else Http.bounded(deadlineMs)
            try {
                val request = Request.Builder()
                    .url(Http.secure(url))
                    .header("User-Agent", Http.USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val source = response.body?.source() ?: return@use null
                    source.request(HTML_HEAD_PEEK_BYTES)
                    // Resolve against the final (post-redirect) page URL so relative
                    // og:image values work.
                    extractOgImage(source.buffer.readUtf8(), response.request.url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /** The cached full-text body for an article, or null if none has been extracted yet. */
    suspend fun cachedArticleText(id: String): String? = withContext(Dispatchers.IO) {
        dao.articleContent(id)?.text
    }

    /**
     * Returns the article's readable body, extracting and caching it on first use.
     * A cached body is returned verbatim (works offline); a fresh extraction is
     * persisted so re-opens — and saved articles — don't refetch.
     */
    suspend fun loadArticleText(article: Article): ArticleExtractor.Result = withContext(Dispatchers.IO) {
        dao.articleContent(article.id)?.let { return@withContext ArticleExtractor.Result.Success(it.text) }
        val result = ArticleExtractor.extract(article.link)
        if (result is ArticleExtractor.Result.Success) {
            dao.upsertArticleContent(ArticleContent(article.id, result.text, System.currentTimeMillis()))
        }
        result
    }

    private fun extractOgImage(html: String, baseUrl: HttpUrl): String? {
        for (match in metaTag.findAll(html)) {
            val tag = match.value
            if (ogImageProperty.containsMatchIn(tag)) {
                val content = contentAttr.find(tag)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")?.replace("&#038;", "&")?.replace("&#38;", "&")
                if (!content.isNullOrBlank()) {
                    // resolve() only ever yields http/https URLs; anything else
                    // (javascript:, data:, ...) comes back null and is skipped.
                    return baseUrl.resolve(content)?.toString() ?: continue
                }
            }
        }
        return null
    }

    private companion object {
        const val KEEP_DAYS = 14

        /** Unsaved keyword-rule matches are kept this long; saved articles are never pruned. */
        const val ALERT_KEEP_DAYS = 90
        const val HTML_HEAD_PEEK_BYTES = 65_536L
        const val HTTP_NOT_MODIFIED = 304
        const val MAX_FEED_BYTES = 5 * 1024 * 1024
        val metaTag = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
        val ogImageProperty = Regex("""property=["']og:image["']""", RegexOption.IGNORE_CASE)
        val contentAttr = Regex("""content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)

        /** Serializes overlapping refresh triggers (manual button vs periodic worker). */
        val refreshMutex = Mutex()

        /** Caps concurrent feed fetches — parallel enough to hide slow hosts, polite to the network. */
        val fetchSemaphore = Semaphore(6)
    }
}
