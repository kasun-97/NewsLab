package com.kasun.newslab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Plain JUnit: SharedLink is a regex plus OkHttp's URL parser, no Android APIs.
class SharedLinkTest {

    @Test
    fun bareUrlIsReturned() {
        assertEquals("https://example.com/story", SharedLink.extract("https://example.com/story", null))
    }

    @Test
    fun firstUrlInSurroundingTextWins() {
        val text = "Worth a read: https://news.example.lk/a/1 and also http://other.example/b"
        assertEquals("https://news.example.lk/a/1", SharedLink.extract(text, null))
    }

    @Test
    fun subjectIsTheFallbackWhenTextHasNoLink() {
        assertEquals(
            "https://example.com/from-subject",
            SharedLink.extract("CBSL holds rates", "https://example.com/from-subject"),
        )
    }

    @Test
    fun textBeatsSubject() {
        assertEquals(
            "https://example.com/text",
            SharedLink.extract("https://example.com/text", "https://example.com/subject"),
        )
    }

    @Test
    fun noLinkAnywhereIsNull() {
        assertNull(SharedLink.extract("just some words", "a headline"))
        assertNull(SharedLink.extract(null, null))
        assertNull(SharedLink.extract("   ", ""))
    }

    @Test
    fun nonWebSchemesAreIgnored() {
        assertNull(SharedLink.extract("ftp://example.com/file and mailto:me@example.com", null))
        assertEquals(
            "https://example.com/ok",
            SharedLink.extract("intent://evil#Intent;end then https://example.com/ok", null),
        )
    }

    @Test
    fun trailingSentencePunctuationIsDropped() {
        assertEquals("https://example.com/a", SharedLink.extract("Read https://example.com/a.", null))
        assertEquals("https://example.com/a", SharedLink.extract("See https://example.com/a, then", null))
        assertEquals("https://example.com/a", SharedLink.extract("\"https://example.com/a\"", null))
    }

    @Test
    fun unbalancedClosingBracketIsDroppedButBalancedOneKept() {
        assertEquals("https://example.com/a", SharedLink.extract("(see https://example.com/a)", null))
        assertEquals(
            "https://en.wikipedia.org/wiki/Mercury_(planet)",
            SharedLink.extract("https://en.wikipedia.org/wiki/Mercury_(planet)", null),
        )
    }

    @Test
    fun schemeAndHostAreCanonicalised() {
        // Upper-case schemes would slip past the add-feed path's startsWith("http").
        assertEquals("https://example.com/Path", SharedLink.extract("HTTPS://EXAMPLE.COM/Path", null))
    }

    @Test
    fun queryStringSurvives() {
        assertEquals(
            "https://example.com/a?id=5&utm_source=share",
            SharedLink.extract("https://example.com/a?id=5&utm_source=share", null),
        )
    }

    @Test
    fun hostLabelDropsWww() {
        assertEquals("economynext.com", SharedLink.hostLabel("https://www.economynext.com/some/story"))
        assertEquals("news.example.lk", SharedLink.hostLabel("https://news.example.lk/"))
    }

    @Test
    fun hostLabelOfUnparseableInputIsTheInput() {
        assertEquals("not a url", SharedLink.hostLabel("not a url"))
    }
}
