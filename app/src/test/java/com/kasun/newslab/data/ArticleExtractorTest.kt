package com.kasun.newslab.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {

    private val first = "The central bank held its policy rate steady on Tuesday, citing easing inflation."
    private val second = "Reserves rose for a fourth straight month, the monetary authority said in a statement."
    private val third = "Analysts had expected a cut, and bond yields edged up after the announcement."
    private val prose = "<p>$first</p><p>$second</p><p>$third</p>"

    private fun extract(html: String): String? =
        ArticleExtractor.extractReadable(Jsoup.parse(html, "https://example.com/"))

    private fun assertWholeArticle(html: String) {
        assertEquals(listOf(first, second, third).joinToString("\n\n"), extract(html))
    }

    @Test
    fun keepsAnArticleCarryingWordPressTagAndCategoryClasses() {
        // post_class() output: the tag and category slugs describe the post, not the element.
        assertWholeArticle(
            """<body class="single single-post">
               <article class="post-1 post type-post hentry category-social-media tag-imf tag-trending">$prose</article>
               </body>""",
        )
    }

    @Test
    fun keepsThePageWhenBodyClassesMentionASidebarOrHeader() {
        for (bodyClass in listOf("has-sidebar", "no-sidebar", "has-header-image", "right-sidebar", "page_with_sidebar")) {
            assertWholeArticle("""<body class="single $bodyClass"><div class="entry-content">$prose</div></body>""")
        }
    }

    @Test
    fun keepsAnArticleInsideAWrapperNamedAfterTheLayout() {
        assertWholeArticle(
            """<body><div class="content-sidebar-wrap"><main><article>$prose</article></main>
               <div class="sidebar"><p>Subscribe to the newsletter for a daily round-up of the news.</p></div></div></body>""",
        )
        assertWholeArticle("""<body><div id="page-nav-and-content"><div class="story">$prose</div></div></body>""")
    }

    @Test
    fun stillStripsCommentsShareBarsAndRelatedLinks() {
        val text = extract(
            """<body><article class="post tag-imf">$prose
               <div class="post-share"><p>Share this story with your friends on every network.</p></div>
               <div class="related-posts"><p>Related: the same bank did something else last month.</p></div>
               <ul class="post-tags"><li>A long enough tag list entry that would otherwise be kept</li></ul>
               <div id="comments"><p>First! This comment is long enough to pass the block filter.</p></div>
               </article></body>""",
        )
        assertNotNull(text)
        assertTrue(text!!.contains(first) && text.contains(third))
        for (noise in listOf("Share this story", "Related:", "tag list entry", "First!")) {
            assertFalse("kept: $noise", text.contains(noise))
        }
    }

    @Test
    fun aTeaserStripAheadOfTheArticleDoesNotBecomeTheRoot() {
        // The strip has its own <article> cards; it must go before the first <article> is chosen.
        assertWholeArticle(
            """<body><div class="trending"><article><p>A teaser for some other story, long enough to count.</p></article></div>
               <article class="post">$prose</article></body>""",
        )
    }

    @Test
    fun aPageWithNoProseExtractsNothing() {
        assertEquals(null, extract("<body><div class=\"menu\"><a href=\"/\">Home</a></div></body>"))
    }
}
