package com.kasun.newslab.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryClustererTest {

    private val hour = 3_600_000L
    private val base = 1_784_543_400_000L // 2026-07-20T10:30Z

    private fun article(id: String, title: String, hoursAgo: Long, feedId: Long = 1) = Article(
        id = id,
        feedId = feedId,
        feedName = "Feed $feedId",
        feedColor = 0,
        title = title,
        summary = "",
        link = "https://example.com/$id",
        publishedAt = base - hoursAgo * hour,
        fetchedAt = base,
        rssCategories = "",
        categoryIds = "",
        matchedRules = "",
        matchedLabels = "",
    )

    private fun ids(clusters: List<StoryCluster>) = clusters.map { c -> c.members.map { it.id } }

    @Test
    fun emptyAndSingleInput() {
        assertEquals(emptyList<StoryCluster>(), StoryClusterer.cluster(emptyList()))
        val one = article("a", "Anything", 0)
        assertEquals(listOf(StoryCluster(listOf(one))), StoryClusterer.cluster(listOf(one)))
    }

    @Test
    fun identicalHeadlinesMerge() {
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Central Bank holds policy rates steady", 1, feedId = 1),
                article("b", "Central Bank holds policy rates steady", 2, feedId = 2),
                article("c", "Colombo stocks close higher on banking gains", 3),
            ),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c")), ids(clusters))
        assertEquals(2, clusters[0].sources.size)
    }

    @Test
    fun syndicatedCopyWithCasePunctuationAndStopwordChangesMergesOnSimHash() {
        // Short words only, so the cosine path's strong-token guard can't fire:
        // the merge has to come from the matching SimHash fingerprint.
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Fed cuts key rate", 1),
                article("b", "The FED cuts key rates!", 2, feedId = 2),
                article("c", "Colombo stocks close higher on banking gains", 3),
            ),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c")), ids(clusters))
    }

    @Test
    fun nearIdenticalCopyWithExtraWordsMerges() {
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Sri Lanka rupee closes at 298.50 against US dollar", 1),
                article("b", "Sri Lanka rupee closes at 298.50 against the US dollar: dealers", 2, feedId = 2),
                article("c", "Cabinet approves new tourism promotion plan", 3),
            ),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c")), ids(clusters))
    }

    @Test
    fun shortNearIdenticalHeadlinesWithOneExtraWordMergeViaJaccard() {
        // One extra word moves the IDF-weighted SimHash past the 4-bit threshold, and
        // with no long/numeric shared tokens the cosine guard can't fire; the token-set
        // overlap (9 of 10 words) catches it instead.
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Fed cuts key rate, oil and gold jump, yen dips", 1),
                article("b", "Fed cuts key rate, oil and gold jump, yen dips again", 2, feedId = 2),
                article("c", "Colombo stocks close higher on banking gains", 3),
            ),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c")), ids(clusters))
    }

    @Test
    fun shortHeadlinesSharingOnlyMostWordsStayApart() {
        // Seven of eleven distinct words shared is under the overlap threshold, and
        // with no long or numeric tokens the cosine path can't fire: different stories.
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Fed holds key rate, oil and gold dip, yen up", 1),
                article("b", "Fed cuts key rate, oil and gold jump, yen dips", 2, feedId = 2),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b")), ids(clusters))
    }

    @Test
    fun sameEventWithSharedRareTokensMergesViaCosine() {
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "IMF approves third review of Sri Lanka bailout", 1),
                article("b", "Sri Lanka tourist arrivals rise in June", 2),
                article("c", "Sri Lanka bailout: IMF board completes third review, releases 334 million", 3, feedId = 2),
                article("d", "Colombo stocks close higher on banking gains", 4),
                article("e", "Sri Lanka cricket squad named for England tour", 5),
            ),
        )
        assertEquals(listOf(listOf("a", "c"), listOf("b"), listOf("d"), listOf("e")), ids(clusters))
    }

    @Test
    fun headlinesSharingOnlyScaffoldingStayApart() {
        // Cosine clears 0.30 on "sri lanka ... june", but only one shared token is
        // strong ("lanka"), so the guard keeps them apart.
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Sri Lanka tourist arrivals rise in June", 1),
                article("b", "Sri Lanka inflation eases in June", 2),
                article("c", "Colombo stocks close higher on banking gains", 3),
                article("d", "Cabinet approves new tourism promotion plan", 4),
            ),
        )
        assertEquals(4, clusters.size)
    }

    @Test
    fun articlesOutsideTheWindowDoNotMerge() {
        val title = "Central Bank holds policy rates steady"
        val apart = StoryClusterer.cluster(listOf(article("a", title, 0), article("b", title, 49)))
        assertEquals(listOf(listOf("a"), listOf("b")), ids(apart))
        val inside = StoryClusterer.cluster(listOf(article("a", title, 0), article("b", title, 47)))
        assertEquals(listOf(listOf("a", "b")), ids(inside))
    }

    @Test
    fun outputKeepsNewestFirstOrderForClustersAndMembers() {
        val clusters = StoryClusterer.cluster(
            listOf(
                article("a", "Colombo stocks close higher on banking gains", 1),
                article("b", "Central Bank holds policy rates steady", 2, feedId = 1),
                article("c", "Cabinet approves new tourism promotion plan", 3),
                article("d", "Central Bank holds policy rates steady", 4, feedId = 2),
                article("e", "Central Bank holds policy rates steady", 5, feedId = 1),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b", "d", "e"), listOf("c")), ids(clusters))
        val story = clusters[1]
        assertEquals("b", story.representative.id)
        assertEquals(3, story.size)
        assertEquals(listOf("b", "d"), story.sources.map { it.id })
    }
}
