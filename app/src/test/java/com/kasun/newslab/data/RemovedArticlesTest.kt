package com.kasun.newslab.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A removed article must stay removed while its feed keeps listing it, and the record
 * of it must go once the feed has moved on. Run against a real (in-memory) database,
 * since the behaviour is in the SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemovedArticlesTest {

    private lateinit var db: AppDb
    private lateinit var dao: NewsDao

    private val day = 86_400_000L
    private val grace = 7 * day

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun close() = db.close()

    private fun article(
        id: String,
        feedId: Long = 1,
        publishedAt: Long = 0,
        matched: Boolean = false,
        saved: Boolean = false,
    ) = Article(
        id = id,
        feedId = feedId,
        feedName = "Feed $feedId",
        feedColor = 0,
        title = "Title $id",
        summary = "",
        link = "https://example.com/$id",
        publishedAt = publishedAt,
        fetchedAt = publishedAt,
        rssCategories = "",
        categoryIds = "",
        matchedRules = if (matched) ",1," else "",
        matchedLabels = if (matched) "IMF" else "",
        isSaved = saved,
    )

    private fun storedIds(): Set<String> = runBlocking { dao.articlesOnce().mapTo(HashSet()) { it.id } }

    /** One refresh of a feed listing [listed]: what the insert step would add, given what was removed. */
    private fun refresh(feedId: Long, listed: List<Article>, now: Long) = runBlocking {
        val removed = dao.stillRemoved(feedId, listed.map { it.id }, now, grace)
        dao.insertArticles(listed.filterNot { it.id in removed })
    }

    @Test
    fun aDeletedArticleStaysDeletedWhileTheFeedListsIt() = runBlocking {
        val listing = listOf(article("a"), article("b"))
        refresh(1, listing, now = 0)
        dao.upsertArticleContent(ArticleContent("a", "body", 0))

        dao.removeArticle("a", now = 10)
        assertNull(dao.articleContent("a"))

        // Months of refreshes, each further apart than the margin: still listed, still out.
        for (pass in 1..6) refresh(1, listing, now = pass * 30 * day)
        assertEquals(setOf("b"), storedIds())
    }

    @Test
    fun anItemPrunedForAgeIsNotReinsertedAsNew() = runBlocking {
        // What a dateless item looks like: stamped with the time it was first fetched.
        dao.insertArticles(listOf(article("undated", publishedAt = 0), article("alert", publishedAt = 0, matched = true)))

        dao.prune(cutoff = 14 * day, alertCutoff = -1, now = 15 * day)
        assertEquals(setOf("alert"), storedIds())

        // The feed still lists it, and the parser would stamp it "now" again.
        refresh(1, listOf(article("undated", publishedAt = 15 * day)), now = 15 * day)
        assertEquals(setOf("alert"), storedIds())

        // Rule matches go the same way on their own, longer window.
        dao.prune(cutoff = 14 * day, alertCutoff = 90 * day, now = 91 * day)
        refresh(1, listOf(article("alert", publishedAt = 91 * day, matched = true)), now = 91 * day)
        assertEquals(emptySet<String>(), storedIds())
    }

    @Test
    fun pruningLeavesSavedArticlesAndRemembersNothingForThem() = runBlocking {
        dao.insertArticles(listOf(article("kept", saved = true), article("kept-alert", matched = true, saved = true)))
        dao.prune(cutoff = 14 * day, alertCutoff = 90 * day, now = 100 * day)
        assertEquals(setOf("kept", "kept-alert"), storedIds())
        assertEquals(emptyList<String>(), dao.removedAmong(listOf("kept", "kept-alert")))
    }

    @Test
    fun aRecordIsForgottenOnceTheFeedHasNotListedTheItemForTheMargin() = runBlocking {
        dao.insertArticles(listOf(article("gone"), article("listed")))
        dao.removeArticle("gone", now = 0)
        dao.removeArticle("listed", now = 0)

        // Inside the margin nothing is forgotten, listed or not.
        dao.stillRemoved(1, listOf("listed"), now = 3 * day, graceMs = grace)
        assertEquals(setOf("gone", "listed"), dao.removedAmong(listOf("gone", "listed")).toSet())

        // Past it, only what the feed still lists is kept.
        assertEquals(setOf("listed"), dao.stillRemoved(1, listOf("listed"), now = 8 * day, graceMs = grace))
        assertEquals(listOf("listed"), dao.removedAmong(listOf("gone", "listed")))
    }

    @Test
    fun aQuietFeedDoesNotOutliveItsRecords() = runBlocking {
        dao.insertArticles(listOf(article("old")))
        dao.removeArticle("old", now = 0)
        // Unreachable or unchanged (304) for a year, then a fresh listing that still has the item.
        refresh(1, listOf(article("old"), article("new")), now = 365 * day)
        assertEquals(setOf("new"), storedIds())
    }

    @Test
    fun anotherFeedsFetchNeitherForgetsNorRevivesARecord() = runBlocking {
        dao.insertArticles(listOf(article("a", feedId = 1)))
        dao.removeArticle("a", now = 0)

        // Feed 2 is fetched long after; it can't age out feed 1's record.
        dao.stillRemoved(2, listOf("x"), now = 30 * day, graceMs = grace)
        assertEquals(listOf("a"), dao.removedAmong(listOf("a")))

        // The same story syndicated in feed 2 is held back too, and counts as a sighting.
        refresh(2, listOf(article("a", feedId = 2)), now = 30 * day)
        assertEquals(emptySet<String>(), storedIds())
        dao.stillRemoved(1, listOf("other"), now = 31 * day, graceMs = grace)
        assertEquals(listOf("a"), dao.removedAmong(listOf("a")))
    }

    @Test
    fun deletingASourceForgetsItsRecordsSoReAddingBringsArticlesBack() = runBlocking {
        val feedId = dao.insertFeed(Feed(name = "Feed", url = "https://example.com/rss", color = 0))
        dao.insertArticles(listOf(article("a", feedId = feedId)))
        dao.removeArticle("a", now = 0)

        dao.deleteFeedCompletely(dao.enabledFeeds().single())
        assertEquals(emptyList<String>(), dao.removedAmong(listOf("a")))
    }
}
