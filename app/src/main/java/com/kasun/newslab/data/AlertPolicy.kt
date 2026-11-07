package com.kasun.newslab.data

/**
 * Decides which pending keyword alerts become notifications, and in what shape. Pure, so
 * the rules about muting, quiet hours and digests can be tested without a database or a
 * notification manager; Repository.refresh applies the answers.
 */
object AlertPolicy {

    /** How many article titles a digest lists before folding the rest into "+N more". */
    const val DIGEST_LINES = 5

    /**
     * Whether [nowMinutes] (minutes after local midnight) falls inside the quiet window
     * [startMinutes, endMinutes). A start after the end is an overnight window, e.g.
     * 22:00 to 07:00. Equal ends are an empty window rather than a whole day: silencing
     * alerts around the clock is what the master switch is for.
     */
    fun inQuietHours(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean = when {
        startMinutes == endMinutes -> false
        startMinutes < endMinutes -> nowMinutes in startMinutes until endMinutes
        else -> nowMinutes >= startMinutes || nowMinutes < endMinutes
    }

    /**
     * A pending alert notifies only if one of the rules it matched still wants
     * notifications and its source isn't muted. A rule that has since been switched off
     * or deleted is missing from [notifyingRuleIds], so it no longer speaks for the match.
     */
    fun shouldNotify(article: Article, notifyingRuleIds: Set<Long>, mutedFeedIds: Set<Long>): Boolean =
        article.feedId !in mutedFeedIds && ruleIds(article.matchedRules).any { it in notifyingRuleIds }

    /** What to do with one batch of pending alerts. */
    data class Plan(
        /** To be posted now; marked notified once they have been. */
        val post: List<Article>,
        /** Will never notify under the current rules and mutes; marked notified straight away. */
        val suppressed: List<Article>,
    )

    fun plan(pending: List<Article>, rules: List<KeywordRule>, feeds: List<Feed>): Plan {
        val notifying = rules.filter { it.enabled && it.notify }.mapTo(HashSet()) { it.id }
        val muted = feeds.filter { it.muted }.mapTo(HashSet()) { it.id }
        val (post, suppressed) = pending.partition { shouldNotify(it, notifying, muted) }
        return Plan(post, suppressed)
    }

    /**
     * One summary in place of a run of notifications, but only when there is a run: a
     * lone match keeps its normal notification, which opens the article itself.
     */
    fun useDigest(digestEnabled: Boolean, count: Int): Boolean = digestEnabled && count > 1

    /** "5 new matches: IMF, Interest rates", labels in the order they first appear. */
    fun digestTitle(articles: List<Article>): String {
        val labels = articles.flatMap { Classifier.splitLabels(it.matchedLabels) }.distinct()
        val count = "${articles.size} new ${if (articles.size == 1) "match" else "matches"}"
        return if (labels.isEmpty()) count else "$count: ${labels.joinToString(", ")}"
    }

    /** "22:00" for 1320: the 24-hour clock the quiet-hours rows and picker use. */
    fun clockLabel(minutes: Int): String =
        "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    /** Rule ids out of the stored ",2,5," form. */
    private fun ruleIds(stored: String): List<Long> = stored.split(',').mapNotNull { it.toLongOrNull() }
}
