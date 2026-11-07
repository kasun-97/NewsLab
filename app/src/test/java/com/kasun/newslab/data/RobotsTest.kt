package com.kasun.newslab.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotsTest {

    private fun rules(txt: String) = Robots.parse(txt.trimIndent())

    @Test
    fun specificGroupBeatsWildcard() {
        val r = rules(
            """
            User-agent: *
            Disallow: /

            User-agent: newslab
            Disallow: /private
            """,
        )
        assertTrue(r.allows("/news/story"))
        assertFalse(r.allows("/private/page"))
    }

    @Test
    fun wildcardGroupAppliesWhenNoSpecificGroup() {
        val r = rules(
            """
            User-agent: googlebot
            Disallow: /

            User-agent: *
            Disallow: /private
            """,
        )
        assertTrue(r.allows("/news"))
        assertFalse(r.allows("/private"))
    }

    @Test
    fun agentMatchIsCaseInsensitiveAndLongestPrefixWins() {
        // Current behaviour: an agent that is a prefix of our token ("news") also
        // names us; the longest such agent is the one used.
        val r = rules(
            """
            User-agent: News
            Disallow: /a

            User-agent: NewsLab
            Disallow: /b
            """,
        )
        assertTrue(r.allows("/a"))
        assertFalse(r.allows("/b"))
        assertFalse(rules("User-agent: news\nDisallow: /a").allows("/a"))
    }

    @Test
    fun longestMatchWins() {
        val r = rules(
            """
            User-agent: *
            Disallow: /a
            Allow: /a/b
            """,
        )
        assertTrue(r.allows("/a/b/c"))
        assertFalse(r.allows("/a/c"))
        assertTrue(r.allows("/other"))
    }

    @Test
    fun allowBeatsDisallowOnEqualLength() {
        assertTrue(rules("User-agent: *\nDisallow: /page\nAllow: /page").allows("/page"))
        assertTrue(rules("User-agent: *\nAllow: /page\nDisallow: /page").allows("/page"))
    }

    @Test
    fun starWildcardAndDollarAnchor() {
        val r = rules(
            """
            User-agent: *
            Disallow: /*.pdf
            Disallow: /*.php$
            """,
        )
        assertFalse(r.allows("/docs/file.pdf"))
        assertFalse(r.allows("/docs/file.pdf?dl=1")) // no anchor: prefix match
        assertTrue(r.allows("/docs/file.html"))
        assertFalse(r.allows("/index.php"))
        assertTrue(r.allows("/index.php?x=1"))
    }

    @Test
    fun regexMetacharactersInPatternsAreLiteral() {
        val r = rules("User-agent: *\nDisallow: /a.b")
        assertFalse(r.allows("/a.b"))
        assertTrue(r.allows("/axb"))
    }

    @Test
    fun commentsAreIgnored() {
        val r = rules(
            """
            # whole-line comment
            User-agent: * # trailing comment
            Disallow: /secret # internal only
            """,
        )
        assertFalse(r.allows("/secret"))
        assertTrue(r.allows("/public"))
    }

    @Test
    fun consecutiveUserAgentLinesShareRules() {
        val r = rules(
            """
            User-agent: googlebot
            User-agent: newslab
            Disallow: /x

            User-agent: *
            Disallow: /
            """,
        )
        assertFalse(r.allows("/x"))
        assertTrue(r.allows("/y"))
    }

    @Test
    fun userAgentAfterRulesStartsNewGroup() {
        val r = rules(
            """
            User-agent: newslab
            Disallow: /x
            User-agent: otherbot
            Disallow: /y
            """,
        )
        assertFalse(r.allows("/x"))
        assertTrue(r.allows("/y"))
    }

    @Test
    fun noGroupsOrEmptyRulesAllowEverything() {
        assertTrue(rules("").allows("/anything"))
        assertTrue(rules("Sitemap: https://example.com/sitemap.xml").allows("/anything"))
        // Rules before any User-agent line belong to no group.
        assertTrue(rules("Disallow: /").allows("/anything"))
        // An empty Disallow value disallows nothing.
        assertTrue(rules("User-agent: *\nDisallow:").allows("/anything"))
    }

    @Test
    fun emptyUserAgentValueIsTreatedAsNamingUs() {
        // Current behaviour, documented: a blank "User-agent:" value is a prefix of
        // every token, so its group is picked over the wildcard group.
        val r = rules(
            """
            User-agent: *
            Allow: /

            User-agent:
            Disallow: /
            """,
        )
        assertFalse(r.allows("/news"))
    }
}
