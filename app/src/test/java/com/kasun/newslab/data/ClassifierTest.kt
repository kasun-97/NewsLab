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
    fun tokenizeDropsStrayQuoteOnUnbalancedPhrase() {
        // An unclosed quote is not a phrase: the words are plain tokens and the stray
        // quote mark is dropped instead of being glued to the first word.
        assertEquals(listOf("interest", "rates"), RuleMatcher.tokenize("\"interest rates"))
        assertEquals(listOf("rates"), RuleMatcher.tokenize("\" rates"))
        assertTrue(RuleMatcher.matches("\"interest rates", "interest rates held"))
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

    @Test
    fun dashedWordExcludes() {
        assertTrue(RuleMatcher.matches("loan -imf", "world bank approves a loan"))
        assertFalse(RuleMatcher.matches("loan -imf", "the imf approved a loan tranche"))
        // Exclusions are substring-based like everything else.
        assertFalse(RuleMatcher.matches("bank -rate", "central bank holds rates"))
    }

    @Test
    fun dashedQuotedPhraseExcludesOnlyTheWholePhrase() {
        assertFalse(RuleMatcher.matches("sri lanka -\"world cup\"", "sri lanka win world cup opener"))
        // The words on their own, apart, do not trip the phrase exclusion.
        assertTrue(RuleMatcher.matches("sri lanka -\"world cup\"", "sri lanka cup final, world reacts"))
    }

    @Test
    fun tokenizeAndExclusionsSplitAMixedQuery() {
        val query = "IMF -Argentina \"Rate Cut\" -\"World Cup\""
        assertEquals(listOf("imf", "rate cut"), RuleMatcher.tokenize(query))
        assertEquals(listOf("argentina", "world cup"), RuleMatcher.exclusions(query))
        assertTrue(RuleMatcher.matches(query, "imf backs rate cut in sri lanka"))
        assertFalse(RuleMatcher.matches(query, "imf backs rate cut in argentina"))
        assertFalse(RuleMatcher.matches(query, "imf rate cut talk overshadowed by world cup"))
        assertFalse(RuleMatcher.matches(query, "imf mission arrives"))
    }

    @Test
    fun onlyExclusionsNeverMatches() {
        assertFalse(RuleMatcher.matches("-imf", "cricket score"))
        assertFalse(RuleMatcher.matches("-imf -\"world cup\"", "cricket score"))
        assertEquals(emptyList<String>(), RuleMatcher.tokenize("-imf"))
    }

    @Test
    fun loneDashIsIgnored() {
        assertEquals(listOf("imf"), RuleMatcher.tokenize("imf -"))
        assertEquals(emptyList<String>(), RuleMatcher.exclusions("imf -"))
        assertTrue(RuleMatcher.matches("imf -", "imf deal"))
        assertFalse(RuleMatcher.matches("-", "anything at all"))
        // A dash only negates at the start of a token; hyphenated words stay positive.
        assertTrue(RuleMatcher.matches("co-op", "co-op bank results"))
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
    fun exclusionsApplyAgainstTheLowercasedHaystack() {
        // Title and tags are mixed case; classify lowercases them, so -"World Cup"
        // still rules the article out and a plain exclusion is checked in tags too.
        val rules = listOf(
            KeywordRule(id = 2, label = "Sri Lanka", query = "\"sri lanka\" -\"World Cup\""),
            KeywordRule(id = 4, label = "IMF", query = "imf -Sport"),
        )
        assertEquals("", classify("Sri Lanka in WORLD CUP final", rules = rules).ruleIds)
        assertEquals(",2,", classify("Sri Lanka signs IMF deal", tags = listOf("Sport"), rules = rules).ruleIds)
        assertEquals(",2,4,", classify("Sri Lanka signs IMF deal", rules = rules).ruleIds)
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
