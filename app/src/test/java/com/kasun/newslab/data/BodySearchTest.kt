package com.kasun.newslab.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The search bar's text is untrusted FTS input: the expression builder must never let
 * it through as query syntax. The last tests run real queries against the index, so
 * the expressions are checked against SQLite itself and not only against strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BodySearchTest {

    @Test
    fun eachWordIsQuotedAndPrefixMatched() {
        assertEquals("\"imf*\"", BodySearch.matchExpression("imf"))
        assertEquals("\"interest*\" \"rates*\"", BodySearch.matchExpression("  interest   rates "))
    }

    @Test
    fun caseIsLeftForTheTokenizer() {
        assertEquals("\"IMF*\" \"Loan*\"", BodySearch.matchExpression("IMF Loan"))
    }

    @Test
    fun quotesAndPunctuationNeverReachTheExpression() {
        assertEquals("\"world*\" \"cup*\"", BodySearch.matchExpression("\"world cup\""))
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("imf* ^loan"))
        assertEquals("\"text*\" \"rates*\"", BodySearch.matchExpression("text:rates"))
        assertEquals("\"don*\" \"t*\"", BodySearch.matchExpression("don't"))
    }

    @Test
    fun exclusionMinusIsDroppedButTheWordKept() {
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("imf -loan"))
        assertNull(BodySearch.matchExpression("-"))
        assertNull(BodySearch.matchExpression("- -"))
    }

    @Test
    fun operatorsAreDroppedOnlyInCapitals() {
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("imf OR loan"))
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("NOT imf AND loan"))
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("imf NEAR loan"))
        assertEquals("\"imf*\" \"loan*\"", BodySearch.matchExpression("imf NEAR/3 loan"))
        // In lower case FTS reads them as words, so they stay search terms.
        assertEquals("\"rates*\" \"near*\" \"record*\"", BodySearch.matchExpression("rates near record"))
    }

    @Test
    fun digitsAndNonLatinScriptsAreWords() {
        assertEquals("\"2026*\" \"budget*\"", BodySearch.matchExpression("2026 budget"))
        // Sinhala vowel signs are combining marks; the word must stay whole.
        assertEquals("\"බදු*\"", BodySearch.matchExpression("බදු"))
        assertEquals("\"வரி*\"", BodySearch.matchExpression("வரி."))
    }

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(BodySearch.matchExpression(""))
        assertNull(BodySearch.matchExpression("   "))
        assertNull(BodySearch.matchExpression("*\"-^:"))
        assertNull(BodySearch.matchExpression("OR AND NOT NEAR"))
    }

    @Test
    fun searchBodiesFindsEveryWordByPrefixInExtractedBodies() = withRepository { repo ->
        runBlocking {
            repo.dao.upsertArticleContent(ArticleContent("a", "The central bank held policy rates steady.", 1))
            repo.dao.upsertArticleContent(ArticleContent("b", "Treasury bill yields fell at the weekly auction.", 2))
            repo.dao.upsertArticleContent(ArticleContent("c", "Bank shares rallied on the bourse.", 3))

            assertEquals(setOf("a", "c"), repo.searchBodies("bank"))
            assertEquals(setOf("a"), repo.searchBodies("Bank rate"))
            assertEquals(setOf("b"), repo.searchBodies("yield auction"))
            assertEquals(emptySet<String>(), repo.searchBodies("bank auction"))
            assertEquals(emptySet<String>(), repo.searchBodies("   "))
        }
    }

    @Test
    fun searchBodiesTreatsHostileInputAsWords() = withRepository { repo ->
        runBlocking {
            repo.dao.upsertArticleContent(ArticleContent("a", "IMF approves the next loan tranche.", 1))
            repo.dao.upsertArticleContent(ArticleContent("b", "Rupee steadies against the dollar.", 2))

            // Raw, each of these is an FTS syntax error or a different query entirely.
            assertEquals(setOf("a"), repo.searchBodies("\"imf"))
            assertEquals(setOf("a"), repo.searchBodies("IMF OR loan"))
            // OR is dropped, not honoured, so both words are still required.
            assertEquals(emptySet<String>(), repo.searchBodies("imf OR rupee"))
            assertEquals(setOf("a"), repo.searchBodies("imf -loan"))
            assertEquals(setOf("a"), repo.searchBodies("NEAR/2 tranche*"))
            assertEquals(setOf("b"), repo.searchBodies("rupee:dollar"))
        }
    }

    /** A repository over a fresh database, which is deleted again afterwards. */
    private fun withRepository(block: (Repository) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        try {
            block(Repository(context))
        } finally {
            AppDb.close()
            context.deleteDatabase("newslab.db")
        }
    }
}
