package com.kasun.newslab.data

/**
 * Keyword-rule matching.
 *
 * A rule's query is split into tokens; every token must appear in the
 * article text (AND semantics). Tokens wrapped in double quotes must appear
 * as an exact contiguous phrase. Matching is case-insensitive and
 * substring-based ("rate" also matches "rates").
 */
object RuleMatcher {

    private val tokenRegex = Regex("\"([^\"]+)\"|(\\S+)")

    fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        for (match in tokenRegex.findAll(query.trim().lowercase())) {
            val phrase = match.groupValues[1].trim()
            val word = match.groupValues[2].trim()
            when {
                phrase.isNotEmpty() -> tokens += phrase
                word.isNotEmpty() -> tokens += word
            }
        }
        return tokens
    }

    fun matches(query: String, haystack: String): Boolean {
        val tokens = tokenize(query)
        return tokens.isNotEmpty() && tokens.all { haystack.contains(it) }
    }
}

data class Classification(
    val categoryIds: String,
    val ruleIds: String,
    val ruleLabels: String,
)

/** Assigns categories and keyword-rule matches to an article at insert time. */
object Classifier {

    /** Joins the labels of multiple matched rules in Article.matchedLabels. */
    const val LABEL_SEPARATOR = "  ·  "

    /** Individual rule labels from a joined Article.matchedLabels string. */
    fun splitLabels(joined: String): List<String> =
        joined.split(LABEL_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Splits a stored Article.rssCategories string back into tag values. Insert-time
     * classification uses this on the same joined string it stores, so an article's
     * haystack is byte-identical whether built at insert or during reclassifyAll.
     */
    fun splitStoredCategories(joined: String): List<String> =
        joined.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun classify(
        title: String,
        summary: String,
        rssCategories: List<String>,
        categories: List<Category>,
        rules: List<KeywordRule>,
    ): Classification {
        val haystack = buildString {
            append(title.lowercase()).append(' ')
            append(summary.lowercase()).append(' ')
            rssCategories.forEach { append(it.lowercase()).append(' ') }
        }

        val categoryIds = categories.filter { category ->
            val name = category.name.trim().lowercase()
            val terms = category.keywords.split(',')
                .map { it.trim().lowercase() }
                .filter { it.length >= 2 }
            (name.length >= 3 && haystack.contains(name)) || terms.any { haystack.contains(it) }
        }.map { it.id }

        val hits = rules.filter { it.enabled && RuleMatcher.matches(it.query, haystack) }

        return Classification(
            categoryIds = if (categoryIds.isEmpty()) ""
            else categoryIds.joinToString(",", ",", ","),
            ruleIds = if (hits.isEmpty()) ""
            else hits.joinToString(",", ",", ",") { it.id.toString() },
            ruleLabels = hits.joinToString(LABEL_SEPARATOR) { it.label },
        )
    }
}
