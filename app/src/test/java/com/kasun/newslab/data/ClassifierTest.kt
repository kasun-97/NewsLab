package com.kasun.newslab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {

    @Test
    fun tokenizeSplitsWordsAndQuotedPhrasesAndLowercases() {
        assertEquals(
            listOf("imf", "interest  rates", "cut"),
            RuleMatcher.tokenize("  IMF \"Interest  Rates\" Cut "),
        )
    }

    @Test
    fun tokenizeEmptyOrBlankGivesNoTokens() {
        assertEquals(emptyList<String>(), RuleMatcher.tokenize(""))
        assertEquals(emptyList<String>(), RuleMatcher.tokenize("   "))
        // A quoted phrase of only spaces trims to nothing and is dropped.
        assertEquals(listOf("imf"), RuleMatcher.tokenize("\"  \" imf"))
    }

    @Test
    fun tokenizeKeepsQuoteCharOnUnbalancedPhrase() {
        // Current behaviour: an unclosed quote is not a phrase, so the quote stays
        // glued to the first word and that token only matches text containing a quote.
        assertEquals(listOf("\"interest", "rates"), RuleMatcher.tokenize("\"interest rates"))
    }

    @Test
    fun matchesRequiresEveryToken() {
        assertTrue(RuleMatcher.matches("IMF loan", "the imf approved a loan tranche"))
        assertFalse(RuleMatcher.matches("IMF bond", "the imf approved a loan tranche"))
    }

    @Test
    fun matchesIsSubstringBased() {
        assertTrue(RuleMatcher.matches("rate", "policy rates held"))
    }

    @Test
    fun quotedPhraseMustBeContiguous() {
        assertTrue(RuleMatcher.matches("\"interest rates\"", "interest rates rise again"))
        assertFalse(RuleMatcher.matches("\"interest rates\"", "rates of interest rise"))
        // Unquoted, the same words match in any order.
        assertTrue(RuleMatcher.matches("interest rates", "rates of interest rise"))
    }

    @Test
    fun emptyQueryNeverMatches() {
        assertFalse(RuleMatcher.matches("", "anything at all"))
        assertFalse(RuleMatcher.matches("   ", "anything at all"))
    }

    @Test
    fun haystackIsExpectedLowercaseByCaller() {
        // The query is lowercased but the haystack is not; Classifier lowercases it
        // before calling, and is the only production caller.
        assertFalse(RuleMatcher.matches("imf", "IMF deal"))
    }
}

class ClassifierTest {

    private val economy = Category(id = 1, name = "Economy", keywords = "gdp, inflation")
    private val politics = Category(id = 3, name = "Politics", keywords = "parliament,cabinet")
    private val eu = Category(id = 5, name = "EU", keywords = "")
    private val markets = Category(id = 7, name = "Markets", keywords = "x, aspi")

    private fun classify(
        title: String,
        summary: String = "",
        tags: List<String> = emptyList(),
        rules: List<KeywordRule> = emptyList(),
    ) = Classifier.classify(title, summary, tags, listOf(economy, politics, eu, markets), rules)

    @Test
    fun categoryMatchesByNameOrKeyword() {
        assertEquals(",1,", classify("Economy grows").categoryIds)
        assertEquals(",1,", classify("Q2 GDP beats forecast").categoryIds)
        assertEquals(",1,3,", classify("Cabinet approves inflation target").categoryIds)
    }

    @Test
    fun categoryNameShorterThanThreeCharsIsIgnored() {
        assertEquals("", classify("EU tariffs bite").categoryIds)
    }

    @Test
    fun keywordShorterThanTwoCharsIsIgnored() {
        // "x" would otherwise match almost anything; "aspi" still works.
        assertEquals("", classify("Exports expand").categoryIds)
        assertEquals(",7,", classify("ASPI ends higher").categoryIds)
    }

    @Test
    fun rssCategoriesAndSummaryAreSearched() {
        assertEquals(",3,", classify("Untitled", tags = listOf("Politics")).categoryIds)
        assertEquals(",1,", classify("Untitled", summary = "Inflation eased").categoryIds)
    }

    @Test
    fun noMatchGivesEmptyStrings() {
        val c = classify("Cricket score")
        assertEquals("", c.categoryIds)
        assertEquals("", c.ruleIds)
        assertEquals("", c.ruleLabels)
    }

    @Test
    fun enabledRulesMatchAndLabelsAreJoined() {
        val rules = listOf(
            KeywordRule(id = 2, label = "IMF", query = "imf"),
            KeywordRule(id = 4, label = "Rates", query = "\"interest rates\""),
            KeywordRule(id = 6, label = "Off", query = "imf", enabled = false),
            KeywordRule(id = 8, label = "Bonds", query = "bond"),
        )
        val c = classify("IMF warns on interest rates", rules = rules)
        assertEquals(",2,4,", c.ruleIds)
        assertEquals("IMF" + Classifier.LABEL_SEPARATOR + "Rates", c.ruleLabels)
        assertEquals(listOf("IMF", "Rates"), Classifier.splitLabels(c.ruleLabels))
    }

    @Test
    fun rulesSeeRssCategories() {
        val rules = listOf(KeywordRule(id = 9, label = "Tourism", query = "tourism"))
        assertEquals(",9,", classify("Arrivals up", tags = listOf("Tourism"), rules = rules).ruleIds)
    }

    @Test
    fun splitLabelsHandlesEmptyAndSingle() {
        assertEquals(emptyList<String>(), Classifier.splitLabels(""))
        assertEquals(listOf("IMF"), Classifier.splitLabels("IMF"))
        assertEquals(listOf("A", "B"), Classifier.splitLabels(" A " + Classifier.LABEL_SEPARATOR + " B "))
    }

    @Test
    fun splitStoredCategoriesTrimsAndDropsBlanks() {
        assertEquals(listOf("Business", "Economy"), Classifier.splitStoredCategories(" Business, ,Economy ,"))
        assertEquals(emptyList<String>(), Classifier.splitStoredCategories(""))
    }
}
