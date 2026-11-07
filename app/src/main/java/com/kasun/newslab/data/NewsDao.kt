package com.kasun.newslab.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Per-source article tallies, shown on the Sources screen. */
data class FeedArticleCounts(val feedId: Long, val unread: Int, val readCount: Int)

/** Whole-library tallies, shown on the Settings statistics section. */
data class ArticleStats(val total: Int, val unread: Int, val saved: Int, val alertMatches: Int)

/** Refresh-time content update for an already-stored article; never touches read/saved/feed/timestamps. */
data class ArticleResync(
    val id: String,
    val summary: String,
    val imageUrl: String,
    val categoryIds: String,
    val matchedRules: String,
    val matchedLabels: String,
)

/** Classification-only update produced by reclassifyAll for rows whose match result changed. */
data class ClassificationUpdate(
    val id: String,
    val categoryIds: String,
    val matchedRules: String,
    val matchedLabels: String,
)

@Dao
interface NewsDao {

    // ---- Articles ----

    // The 500 cap keeps the timeline bounded, but saved and alert-matched rows are
    // retained past the prune window precisely so they stay reachable — exempt them
    // from the cap or they'd silently vanish from Saved/Alerts/search once 500 newer
    // articles exist.
    @Query(
        "SELECT * FROM articles WHERE isSaved = 1 OR matchedRules != '' " +
            "OR id IN (SELECT id FROM articles ORDER BY publishedAt DESC LIMIT 500) " +
            "ORDER BY publishedAt DESC"
    )
    fun articles(): Flow<List<Article>>

    @Query("SELECT * FROM articles")
    suspend fun articlesOnce(): List<Article>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun articleById(id: String): Article?

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    suspend fun articlesByIds(ids: List<String>): List<Article>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END) AS unread, " +
            "SUM(CASE WHEN isSaved = 1 THEN 1 ELSE 0 END) AS saved, " +
            "SUM(CASE WHEN matchedRules != '' THEN 1 ELSE 0 END) AS alertMatches " +
            "FROM articles"
    )
    fun articleStats(): Flow<ArticleStats>

    /** Returns one rowId per input; -1 for rows that already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(items: List<Article>): List<Long>

    @Update
    suspend fun updateArticles(items: List<Article>)

    // Targeted single-column write for the lazy og:image fetch. A full-row @Update
    // here would write back the stale pre-open snapshot after the network fetch,
    // reverting isRead/isSaved set in the meantime.
    @Query("UPDATE articles SET pageImageUrl = :url WHERE id = :id")
    suspend fun setPageImageUrl(id: String, url: String)

    @Query(
        "UPDATE articles SET summary = :summary, imageUrl = :imageUrl, " +
            "categoryIds = :categoryIds, matchedRules = :matchedRules, matchedLabels = :matchedLabels " +
            "WHERE id = :id"
    )
    suspend fun resyncArticle(
        id: String,
        summary: String,
        imageUrl: String,
        categoryIds: String,
        matchedRules: String,
        matchedLabels: String,
    )

    @Transaction
    suspend fun resyncArticles(items: List<ArticleResync>) {
        items.forEach {
            resyncArticle(it.id, it.summary, it.imageUrl, it.categoryIds, it.matchedRules, it.matchedLabels)
        }
    }

    @Query(
        "UPDATE articles SET categoryIds = :categoryIds, " +
            "matchedRules = :matchedRules, matchedLabels = :matchedLabels WHERE id = :id"
    )
    suspend fun updateClassification(
        id: String,
        categoryIds: String,
        matchedRules: String,
        matchedLabels: String,
    )

    @Transaction
    suspend fun updateClassifications(items: List<ClassificationUpdate>) {
        items.forEach { updateClassification(it.id, it.categoryIds, it.matchedRules, it.matchedLabels) }
    }

    @Query("UPDATE articles SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("UPDATE articles SET isRead = 0 WHERE isRead = 1")
    suspend fun markAllUnread()

    @Query("UPDATE articles SET isRead = :read WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE articles SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun deleteArticle(id: String)

    // Alert-matched articles are exempt, same as saved ones: pruning one and having its
    // still-listed feed item reinserted as "new" on a later fetch would re-fire the alert.
    @Query("DELETE FROM articles WHERE isSaved = 0 AND matchedRules = '' AND publishedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM articles WHERE feedId = :feedId AND isSaved = 0")
    suspend fun deleteArticlesByFeed(feedId: Long)

    @Query("UPDATE articles SET feedName = :name WHERE feedId = :feedId")
    suspend fun renameFeedArticles(feedId: Long, name: String)

    @Query(
        "SELECT feedId, " +
            "SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END) AS unread, " +
            "SUM(CASE WHEN isRead = 1 THEN 1 ELSE 0 END) AS readCount " +
            "FROM articles GROUP BY feedId"
    )
    fun articleCountsByFeed(): Flow<List<FeedArticleCounts>>

    // ---- Article content (extracted full text) ----

    @Query("SELECT * FROM article_content WHERE id = :id")
    suspend fun articleContent(id: String): ArticleContent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticleContent(content: ArticleContent)

    @Query("DELETE FROM article_content WHERE id = :id")
    suspend fun deleteArticleContent(id: String)

    /** Drops cached bodies whose article has been pruned or deleted. */
    @Query("DELETE FROM article_content WHERE id NOT IN (SELECT id FROM articles)")
    suspend fun pruneOrphanContent()

    // ---- Quick links ----

    @Query("SELECT * FROM quick_links ORDER BY position, id")
    fun quickLinks(): Flow<List<QuickLink>>

    @Insert
    suspend fun insertQuickLink(link: QuickLink): Long

    @Update
    suspend fun updateQuickLink(link: QuickLink)

    @Query("DELETE FROM quick_links WHERE id = :id")
    suspend fun deleteQuickLink(id: Long)

    /** Next free sort position within a list, so a new link lands at that section's end. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM quick_links WHERE listId = :listId")
    suspend fun nextQuickLinkPosition(listId: Long): Int

    /** Moves every link out of a list about to be deleted, appending them to [targetId]. */
    @Query(
        "UPDATE quick_links SET listId = :targetId, " +
            "position = position + (SELECT COALESCE(MAX(position), -1) + 1 FROM quick_links WHERE listId = :targetId) " +
            "WHERE listId = :listId"
    )
    suspend fun moveQuickLinksToList(listId: Long, targetId: Long)

    // ---- Quick-link lists ----

    @Query("SELECT * FROM quick_link_lists ORDER BY position, id")
    fun quickLinkLists(): Flow<List<QuickLinkList>>

    @Insert
    suspend fun insertQuickLinkList(list: QuickLinkList): Long

    @Update
    suspend fun updateQuickLinkList(list: QuickLinkList)

    @Query("DELETE FROM quick_link_lists WHERE id = :id")
    suspend fun deleteQuickLinkList(id: Long)

    @Query("UPDATE quick_link_lists SET position = :position WHERE id = :id")
    suspend fun setQuickLinkListPosition(id: Long, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM quick_link_lists")
    suspend fun nextQuickLinkListPosition(): Int

    // ---- Feeds ----

    @Query("SELECT * FROM feeds ORDER BY id")
    fun feeds(): Flow<List<Feed>>

    @Query("SELECT * FROM feeds WHERE enabled = 1")
    suspend fun enabledFeeds(): List<Feed>

    @Insert
    suspend fun insertFeed(feed: Feed): Long

    @Update
    suspend fun updateFeed(feed: Feed)

    // Targeted so a background refresh detecting the format doesn't clobber
    // anything else on the row (name/url edits happening concurrently, etc).
    @Query("UPDATE feeds SET isAtom = :isAtom WHERE id = :id")
    suspend fun updateFeedIsAtom(id: Long, isAtom: Boolean)

    @Delete
    suspend fun deleteFeed(feed: Feed)

    /** Feed removal plus its dependent rows, atomically — a crash mid-way can't strand orphans. */
    @Transaction
    suspend fun deleteFeedCompletely(feed: Feed) {
        deleteArticlesByFeed(feed.id)
        clearFeedGroups(feed.id)
        deleteFeed(feed)
    }

    /** Feed edit plus the article rename and group re-membership it implies, atomically. */
    @Transaction
    suspend fun updateFeedWithGroups(feed: Feed, renameArticles: Boolean, groupIds: Set<Long>) {
        updateFeed(feed)
        if (renameArticles) renameFeedArticles(feed.id, feed.name)
        setFeedGroups(feed.id, groupIds)
    }

    // ---- Feed groups ----

    @Query("SELECT * FROM feed_groups ORDER BY id")
    fun feedGroups(): Flow<List<FeedGroup>>

    @Insert
    suspend fun insertFeedGroup(group: FeedGroup): Long

    @Update
    suspend fun updateFeedGroup(group: FeedGroup)

    @Delete
    suspend fun deleteFeedGroup(group: FeedGroup)

    /** A source can belong to any number of groups; this is the full membership set. */
    @Query("SELECT * FROM feed_group_members")
    fun feedGroupMembers(): Flow<List<FeedGroupMember>>

    @Query("DELETE FROM feed_group_members WHERE feedId = :feedId")
    suspend fun clearFeedGroups(feedId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedGroupMembers(members: List<FeedGroupMember>)

    @Query("DELETE FROM feed_group_members WHERE groupId = :groupId")
    suspend fun deleteFeedGroupMemberships(groupId: Long)

    @Transaction
    suspend fun deleteFeedGroupCompletely(group: FeedGroup) {
        deleteFeedGroupMemberships(group.id)
        deleteFeedGroup(group)
    }

    /** Replaces a source's full group membership with exactly [groupIds]. */
    @Transaction
    suspend fun setFeedGroups(feedId: Long, groupIds: Set<Long>) {
        clearFeedGroups(feedId)
        if (groupIds.isNotEmpty()) {
            insertFeedGroupMembers(groupIds.map { FeedGroupMember(feedId, it) })
        }
    }

    // ---- Categories ----

    @Query("SELECT * FROM categories ORDER BY position, id")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY position, id")
    suspend fun categoriesOnce(): List<Category>

    @Insert
    suspend fun insertCategory(category: Category): Long

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    // ---- Keyword rules ----

    @Query("SELECT * FROM keyword_rules ORDER BY id")
    fun rules(): Flow<List<KeywordRule>>

    @Query("SELECT * FROM keyword_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<KeywordRule>

    @Insert
    suspend fun insertRule(rule: KeywordRule): Long

    @Update
    suspend fun updateRule(rule: KeywordRule)

    @Delete
    suspend fun deleteRule(rule: KeywordRule)
}
