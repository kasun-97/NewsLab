package com.kasun.newslab.data

/**
 * Global mute words: articles that mention any of them are kept out of the inbox
 * timeline. Saved articles and keyword-alert matches are never hidden, since saving
 * or writing a rule is a more specific signal than a blanket mute.
 *
 * Terms are comma-separated, so a term can be a phrase ("world cup"). They match
 * case-insensitively at the start of a word, like a whole-words keyword rule: muting
 * "sport" hides "sports" but not "transport".
 */
object MuteFilter {

    /** The stored comma-separated list as lowercase terms; terms under two characters are dropped. */
    fun parse(raw: String): List<String> =
        raw.split(',').map { it.trim().lowercase() }.filter { it.length >= 2 }.distinct()

    /** True when [article] should be hidden from the timeline for mentioning one of [terms]. */
    fun isMuted(article: Article, terms: List<String>): Boolean {
        if (terms.isEmpty() || article.isSaved || article.matchedRules.isNotEmpty()) return false
        val haystack = buildString {
            append(article.title.lowercase()).append(' ')
            append(article.summary.lowercase()).append(' ')
            append(article.rssCategories.lowercase())
        }
        return terms.any { RuleMatcher.containsAtWordStart(haystack, it) }
    }
}
