package com.kasun.newslab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteFilterTest {

    private fun article(
        title: String,
        summary: String = "",
        tags: String = "",
        saved: Boolean = false,
        matchedRules: String = "",
    ) = Article(
        id = title, feedId = 1, feedName = "Feed", feedColor = 0, title = title, summary = summary,
        link = "https://example.com", publishedAt = 0, fetchedAt = 0, rssCategories = tags,
        categoryIds = "", matchedRules = matchedRules, matchedLabels = "", isSaved = saved,
    )

    @Test
    fun parseTrimsLowercasesAndDropsShortAndRepeatedTerms() {
        assertEquals(
            listOf("cricket", "world cup"),
            MuteFilter.parse(" Cricket , World Cup,, x, cricket "),
        )
        assertEquals(emptyList<String>(), MuteFilter.parse(""))
    }

    @Test
    fun hidesArticlesMentioningATermAtAWordStart() {
        val terms = MuteFilter.parse("sport, world cup")
        assertTrue(MuteFilter.isMuted(article("Sports roundup"), terms))
        assertTrue(MuteFilter.isMuted(article("Rupee steady", summary = "Ahead of the World Cup final"), terms))
        assertTrue(MuteFilter.isMuted(article("Late result", tags = "Sport"), terms))
        assertFalse(MuteFilter.isMuted(article("Transport fares cut"), terms))
        assertFalse(MuteFilter.isMuted(article("Rupee steady"), terms))
    }

    @Test
    fun neverHidesSavedArticlesOrAlertMatches() {
        val terms = MuteFilter.parse("cricket")
        assertFalse(MuteFilter.isMuted(article("Cricket board budget", saved = true), terms))
        assertFalse(MuteFilter.isMuted(article("Cricket board budget", matchedRules = ",2,"), terms))
    }

    @Test
    fun noTermsHidesNothing() {
        assertFalse(MuteFilter.isMuted(article("Cricket"), emptyList()))
    }
}
