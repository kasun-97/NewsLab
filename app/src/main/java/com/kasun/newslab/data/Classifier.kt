package com.kasun.newslab.data

/**
 * Keyword-rule matching.
 *
 * A rule's query is split into tokens; every token must appear in the
 * article text (AND semantics). Tokens wrapped in double quotes must appear
 * as an exact contiguous phrase. A token prefixed with `-` (`-imf`,
 * `-"world cup"`) is an exclusion: the rule fails if it appears. Matching is
 * case-insensitive and substring-based ("rate" also matches "rates"), for
 * exclusions too, so `-rate` also rules out "rates".
 */
object RuleMatcher {

    private val tokenRegex = Regex("(-?)\"([^\"]+)\"|(\\S+)")

    /** A query split into the terms that must appear and the terms that must not. */
    private class Terms(val required: List<String>, val excluded: List<String>)

    private fun parse(query: String): Terms {
        val required = mutableListOf<String>()
        val excluded = mutableListOf<String>()
        for (match in tokenRegex.findAll(query.trim().lowercase())) {
            val phrase = match.groupValues[2].trim()
            if (phrase.isNotEmpty()) {
                if (match.groupValues[1].isEmpty()) required += phrase else excluded += phrase
                continue
            }
            val raw = match.groupValues[3]
            val negated = raw.startsWith('-')
            // An unclosed quote isn't a phrase; drop the stray quote mark rather than
            // keep a token that can only match text containing one. A lone "-" trims to
            // nothing and is dropped, so a stray dash never excludes everything.
            val word = raw.removePrefix("-").trim().trim('"')
            if (word.isEmpty()) continue
            if (negated) excluded += word else required += word
        }
        return Terms(required, excluded)
    }

    /** The terms that must all appear, in query order. Exclusions are not included. */
    fun tokenize(query: String): List<String> = parse(query).required

    /** The `-` terms, without the dash, any of which stops the rule from matching. */
    fun exclusions(query: String): List<String> = parse(query).excluded

    /**
     * True when every required term is in [haystack] and no excluded one is. A query
     * of only exclusions never matches: "everything except X" would pin and notify
     * almost the whole feed, which is never what an alert is for.
     */
    fun matches(query: String, haystack: String): Boolean {
        val terms = parse(query)
        return terms.required.isNotEmpty() &&
            terms.required.all { haystack.contains(it) } &&
            terms.excluded.none { haystack.contains(it) }
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
