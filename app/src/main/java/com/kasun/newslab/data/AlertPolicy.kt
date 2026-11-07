package com.kasun.newslab.data

/**
 * Decides which pending keyword alerts become notifications, and in what shape. Pure, so
 * the rules about muting, quiet hours and digests can be tested without a database or a
 * notification manager; Repository.refresh applies the answers.
 */
object AlertPolicy {

    /** How many article titles a digest lists before folding the rest into "+N more". */
    const val DIGEST_LINES = 5

    /** How many matches get a notification of their own before the rest share one summary. */
    const val MAX_SINGLE_NOTIFICATIONS = 5

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
        /**
         * Will never notify under the current rules and mutes, or was read while it sat
         * pending; marked notified straight away.
         */
        val suppressed: List<Article>,
    )

    fun plan(pending: List<Article>, rules: List<KeywordRule>, feeds: List<Feed>): Plan {
        val notifying = rules.filter { it.enabled && it.notify }.mapTo(HashSet()) { it.id }
        val muted = feeds.filter { it.muted }.mapTo(HashSet()) { it.id }
        // A held alert can outlive the pass that found it (quiet hours, a pass that
        // died), and an article read in the meantime has nothing left to tell anyone.
        val (post, suppressed) = pending.partition { !it.isRead && shouldNotify(it, notifying, muted) }
        return Plan(post, suppressed)
    }

    /**
     * Which pending alerts a finished pass marks notified. A held pass marks nothing. A
     * notifying pass, or any pass with the master switch off, marks the whole batch: it
     * was posted, deliberately suppressed, or is never meant to alert. An in-app refresh
     * with notifications on marks only what it inserted itself, which the user is
     * looking at; older rows (held through quiet hours, or left by a pass that died)
     * stay pending for the next background pass to post.
     */
    fun idsToMark(
        pending: List<Article>,
        insertedIds: Collection<String>,
        notify: Boolean,
        notificationsEnabled: Boolean,
        held: Boolean,
    ): List<String> = when {
        held -> emptyList()
        notify || !notificationsEnabled -> pending.map { it.id }
        else -> {
            val inserted = insertedIds.toHashSet()
            pending.map { it.id }.filter { it in inserted }
        }
    }

    /**
     * Splits matches into the newest few that get a notification each and the rest,
     * which share one summary: past the cap, older matches must not crowd out newer
     * ones, and none may be marked notified without being posted somewhere.
     */
    fun singlesAndOverflow(articles: List<Article>): Pair<List<Article>, List<Article>> {
        val newestFirst = articles.sortedByDescending { it.publishedAt }
        return newestFirst.take(MAX_SINGLE_NOTIFICATIONS) to newestFirst.drop(MAX_SINGLE_NOTIFICATIONS)
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
