package com.kasun.newslab.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Readability-style full-text extraction: fetches an article's page and pulls the
 * main body out of the surrounding navigation, ads and boilerplate. Deliberately
 * lightweight — it prefers a semantic `<article>`/`<main>` when the page has one and
 * otherwise scores paragraph containers, rather than porting the full algorithm.
 */
object ArticleExtractor {

    sealed interface Result {
        /** The readable body, as paragraphs separated by blank lines. */
        data class Success(val text: String) : Result

        /** The page loaded but no article-like body could be found. */
        data object Empty : Result

        /** The page couldn't be fetched (network error, offline, non-HTML, ...). */
        data object Error : Result

        /** The site's robots.txt disallows fetching this page. */
        data object Blocked : Result
    }

    suspend fun extract(url: String): Result = withContext(Dispatchers.IO) {
        // Asked for over https whatever the feed said, or the platform refuses to send
        // it at all — see Http.secure.
        val secureUrl = Http.secure(url)
        val httpUrl = secureUrl.toHttpUrlOrNull() ?: return@withContext Result.Error
        try {
            if (!Robots.isAllowed(httpUrl)) return@withContext Result.Blocked
            val request = Request.Builder()
                .url(secureUrl)
                .header("User-Agent", Http.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body ?: return@use Result.Error
                if (!response.isSuccessful) return@use Result.Error
                val mediaType = body.contentType()
                // Only parse HTML; a PDF or image link has no readable body to pull.
                if (mediaType != null && mediaType.subtype.lowercase() !in HTML_SUBTYPES) {
                    return@use Result.Empty
                }
                // Cap the bytes first, then let jsoup detect the charset from the page's
                // own <meta>/BOM (falling back to UTF-8) rather than trusting the header.
                val bytes = readCapped(body.byteStream(), MAX_BYTES)
                val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, response.request.url.toString())
                val text = extractReadable(doc)
                if (text == null || text.length < MIN_ARTICLE_CHARS) Result.Empty else Result.Success(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.Error
        }
    }

    private fun extractReadable(doc: Document): String? {
        // Strip the obvious non-content first so it can't win the scoring.
        doc.select("script, style, noscript, iframe, svg, form, button, input, textarea, nav, aside, header, footer, figure figcaption").remove()
        doc.select("[class~=(?i)(^|[-_ ])(comment|share|social|related|promo|advert|ad|ads|sponsor|newsletter|subscribe|sidebar|footer|header|nav|menu|breadcrumb|byline|author-bio|cookie|popup|modal|paywall|widget|recommend|trending|tags?)($|[-_ ])]").remove()
        doc.select("[id~=(?i)(comment|share|social|related|promo|advert|sidebar|footer|nav|menu|newsletter|subscribe|cookie|popup|paywall|recommend|trending)]").remove()

        val root = doc.selectFirst("article")
            ?: doc.selectFirst("[role=main]")
            ?: doc.selectFirst("main")
            ?: bestCandidate(doc)
            ?: doc.body()
            ?: return null

        val blocks = mutableListOf<String>()
        var lastLower = ""
        for (element in root.select("p, h2, h3, h4, blockquote, pre, li")) {
            // Skip nested block-in-block (a <p> inside a <blockquote>, etc.) so its
            // text isn't emitted twice.
            if (element.parents().any { it !== root && it.tagName() in BLOCK_TAGS }) continue
            if (linkDensity(element) > MAX_LINK_DENSITY) continue

            val text = element.text().trim()
            val minLen = if (element.tagName() == "li") LIST_MIN_CHARS else BLOCK_MIN_CHARS
            if (text.length < minLen) continue

            // Some templates repeat the lede as both a standfirst and the first
            // paragraph; drop back-to-back duplicates.
            val lower = text.lowercase()
            if (lower == lastLower) continue
            lastLower = lower
            blocks += text
        }
        if (blocks.isEmpty()) return null
        return blocks.joinToString("\n\n")
    }

    /**
     * Picks the element most likely to hold the article: paragraph text flows up to
     * its parent (and, halved, its grandparent), so the container wrapping the most
     * real prose scores highest.
     */
    private fun bestCandidate(doc: Document): Element? {
        val scores = HashMap<Element, Double>()
        for (p in doc.select("p")) {
            val text = p.text()
            if (text.length < 25) continue
            val score = 1.0 + text.count { it == ',' } + min(text.length / 100.0, 3.0)
            p.parent()?.let { scores[it] = (scores[it] ?: 0.0) + score }
            p.parent()?.parent()?.let { scores[it] = (scores[it] ?: 0.0) + score / 2 }
        }
        return scores.maxByOrNull { it.value }?.key
    }

    /** Fraction of an element's text that sits inside links — high means it's navigation. */
    private fun linkDensity(element: Element): Double {
        val total = element.text().length
        if (total == 0) return 0.0
        val linkLen = element.select("a").sumOf { it.text().length }
        return linkLen.toDouble() / total
    }

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
    private const val MIN_ARTICLE_CHARS = 250
    private const val BLOCK_MIN_CHARS = 25
    private const val LIST_MIN_CHARS = 40
    private const val MAX_LINK_DENSITY = 0.5
    private val HTML_SUBTYPES = setOf("html", "xhtml+xml", "xml")
    private val BLOCK_TAGS = setOf("p", "h2", "h3", "h4", "blockquote", "pre", "li")
}
