package com.kasun.newslab.data

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A group of articles judged to be the same story. Single-article groups are
 * ordinary timeline items; groups of two or more are shown as one card with the
 * contributing sources. [members] are ordered newest-first, so [representative]
 * is the freshest take and [sources] lists the distinct feeds covering it.
 */
data class StoryCluster(val members: List<Article>) {
    val representative: Article get() = members.first()
    val size: Int get() = members.size
    val sources: List<Article> get() = members.distinctBy { it.feedId }
}

/**
 * Groups near-duplicate stories across sources, on-device and dependency-free.
 * Two articles merge when their headlines are near-identical (SimHash, catching
 * syndicated wire copy) or share enough rare, discriminative words (IDF-weighted
 * cosine with a shared-rare-token guard, catching independently written coverage
 * of the same event) — and they fall within a time window. Clustering is a pure
 * function of the input list, so it can be derived on read like the day grouping.
 */
object StoryClusterer {

    private const val WINDOW_MS = 48L * 3_600_000L
    private const val SIMHASH_MAX_HAMMING = 4
    private const val COSINE_MIN = 0.30

    /** A shared token counts as "strong" (identifying) when it's long or numeric. */
    private const val STRONG_TOKEN_LEN = 5

    /** Groups [articles] (assumed newest-first) into clusters, preserving timeline order. */
    fun cluster(articles: List<Article>): List<StoryCluster> {
        val n = articles.size
        if (n < 2) return articles.map { StoryCluster(listOf(it)) }

        val docs = Array(n) { tokenize(articles[it].title) }
        val df = HashMap<String, Int>()
        for (doc in docs) for (token in doc.keys) df[token] = (df[token] ?: 0) + 1
        val idf = HashMap<String, Double>()
        for ((token, count) in df) idf[token] = ln(1.0 + n.toDouble() / count)

        val norms = DoubleArray(n)
        val fingerprints = LongArray(n)
        for (i in 0 until n) {
            var sumSq = 0.0
            for ((token, tf) in docs[i]) {
                val w = (idf[token] ?: 0.0) * tf
                sumSq += w * w
            }
            norms[i] = sqrt(sumSq)
            fingerprints[i] = simhash(docs[i], idf)
        }

        val uf = UnionFind(n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                // Sorted newest-first, so once j is older than the window from i, so is everything after it.
                if (articles[i].publishedAt - articles[j].publishedAt > WINDOW_MS) break
                if (sameStory(i, j, docs, idf, norms, fingerprints)) uf.union(i, j)
            }
        }

        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byRoot.getOrPut(uf.find(i)) { mutableListOf() }.add(i)
        return byRoot.values
            .map { indices -> StoryCluster(indices.map { articles[it] }.sortedByDescending { it.publishedAt }) }
            .sortedByDescending { it.representative.publishedAt }
    }

    private fun sameStory(
        i: Int,
        j: Int,
        docs: Array<Map<String, Int>>,
        idf: Map<String, Double>,
        norms: DoubleArray,
        fingerprints: LongArray,
    ): Boolean {
        // Near-identical text (syndicated copy) — decided on the fingerprint alone.
        if (hamming(fingerprints[i], fingerprints[j]) <= SIMHASH_MAX_HAMMING) return true
        if (norms[i] == 0.0 || norms[j] == 0.0) return false

        // Iterate the smaller doc, accumulating the IDF-weighted dot product and
        // counting shared "strong" tokens (long or numeric) that actually identify
        // the story — headlines can share scaffolding ("Sri Lanka … in June") while
        // being different stories, so the identifying-token count guards against that.
        val small = if (docs[i].size <= docs[j].size) docs[i] else docs[j]
        val large = if (small === docs[i]) docs[j] else docs[i]
        var dot = 0.0
        var strongShared = 0
        for ((token, tfSmall) in small) {
            val tfLarge = large[token] ?: continue
            val w = idf[token] ?: 0.0
            dot += (w * tfSmall) * (w * tfLarge)
            if (token.length >= STRONG_TOKEN_LEN || token.any(Char::isDigit)) strongShared++
        }
        val cosine = dot / (norms[i] * norms[j])
        return cosine >= COSINE_MIN && strongShared >= 2
    }

    private fun tokenize(title: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (raw in title.lowercase().split(TOKEN_SPLIT)) {
            if (raw.length < 2 || raw in STOPWORDS) continue
            val token = stem(raw)
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    /** Strips a single plural "s" from longer words so tariff/tariffs, rate/rates align. */
    private fun stem(word: String): String =
        if (word.length >= 4 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    /** 64-bit IDF-weighted SimHash of a headline's tokens. */
    private fun simhash(doc: Map<String, Int>, idf: Map<String, Double>): Long {
        val columns = DoubleArray(64)
        for ((token, tf) in doc) {
            val h = hash64(token)
            val w = (idf[token] ?: 0.0) * tf
            for (b in 0 until 64) {
                if ((h ushr b) and 1L == 1L) columns[b] += w else columns[b] -= w
            }
        }
        var fingerprint = 0L
        for (b in 0 until 64) if (columns[b] > 0) fingerprint = fingerprint or (1L shl b)
        return fingerprint
    }

    /** FNV-1a 64-bit, for a stable per-token hash independent of String.hashCode. */
    private fun hash64(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037 (FNV offset basis)
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L // FNV prime
        }
        return h
    }

    private fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
    }

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

    // Conservative function-word list — deliberately excludes news-signal words like
    // "us", "new", numbers, and proper nouns.
    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "for", "at", "by",
        "with", "from", "as", "is", "are", "was", "were", "be", "been", "it", "its", "that",
        "this", "after", "over", "amid", "say", "says", "said", "will", "would", "can",
        "could", "has", "have", "had", "not", "than", "then", "so", "such", "into", "out",
        "about", "more", "most", "up", "down", "off", "per", "via", "vs", "if", "his", "her",
    )
}
