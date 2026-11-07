package com.kasun.newslab.data

import com.kasun.newslab.work.RefreshWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchTest {

    private fun article(id: String, publishedAt: Long) = Article(
        id = id,
        feedId = 1,
        feedName = "Feed",
        feedColor = 0,
        title = "Title $id",
        summary = "",
        link = "https://example.com/$id",
        publishedAt = publishedAt,
        fetchedAt = publishedAt,
        rssCategories = "",
        categoryIds = "",
        matchedRules = ",1,",
        matchedLabels = "IMF",
    )

    @Test
    fun picksNewestFirst() {
        val picked = Repository.Prefetch.pick(
            listOf(article("old", 100), article("new", 300), article("mid", 200)),
        )
        assertEquals(listOf("new", "mid", "old"), picked.map { it.id })
    }

    @Test
    fun capsAtTwentyPerRun() {
        val candidates = (1..25).map { article("a$it", it.toLong()) }
        val picked = Repository.Prefetch.pick(candidates)
        assertEquals(20, picked.size)
        // The five oldest are the ones left for a later run (or never).
        assertEquals("a25", picked.first().id)
        assertEquals("a6", picked.last().id)
    }

    @Test
    fun pickOfNothingIsNothing() {
        assertTrue(Repository.Prefetch.pick(emptyList()).isEmpty())
    }

    @Test
    fun prefetchesOnlyWhenEnabledUnmeteredAndThereIsSomethingNew() {
        val ids = listOf("a")
        assertTrue(RefreshWorker.shouldPrefetch(enabled = true, metered = false, newAlertIds = ids))
        assertFalse(RefreshWorker.shouldPrefetch(enabled = false, metered = false, newAlertIds = ids))
        assertFalse(RefreshWorker.shouldPrefetch(enabled = true, metered = true, newAlertIds = ids))
        assertFalse(RefreshWorker.shouldPrefetch(enabled = true, metered = false, newAlertIds = emptyList()))
    }
}
