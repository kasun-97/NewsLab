package com.kasun.newslab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric because RssParser builds its parser with android.util.Xml.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RssParserTest {

    private fun parse(xml: String) = RssParser.parse(xml.trimIndent().byteInputStream(Charsets.UTF_8))

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:media="http://search.yahoo.com/mrss/">
        <channel>
          <title>Channel title</title>
          <link>https://example.com</link>
          <item>
            <title><![CDATA[Rupee <b>firms</b> against dollar]]></title>
            <link>https://example.com/rupee</link>
            <guid isPermaLink="false">post-101</guid>
            <description><![CDATA[<p>Short excerpt</p><img src="https://example.com/excerpt.jpg">]]></description>
            <content:encoded><![CDATA[<p>Full body</p><img src="https://example.com/body.jpg">]]></content:encoded>
            <category><![CDATA[Business]]></category>
            <category>Economy</category>
            <pubDate>Mon, 20 Jul 2026 10:30:00 +0000</pubDate>
            <media:thumbnail url="https://example.com/thumb.jpg"/>
          </item>
          <item>
            <title>Enclosure story</title>
            <link>https://example.com/second</link>
            <description>Plain text</description>
            <enclosure url="https://example.com/audio.mp3" type="audio/mpeg" length="1"/>
            <enclosure url="https://example.com/photo.jpg" type="image/jpeg" length="1"/>
          </item>
          <item>
            <title>Inline image story</title>
            <link>https://example.com/third</link>
            <description><![CDATA[<p>Hi</p><img class="x" src="https://example.com/a.jpg?w=1&amp;h=2" />]]></description>
          </item>
          <item>
            <title>Protocol-relative image</title>
            <link>https://example.com/fourth</link>
            <description><![CDATA[<img src="//cdn.example.com/p.jpg">]]></description>
          </item>
          <item>
            <title>Local file image</title>
            <link>https://example.com/fifth</link>
            <media:thumbnail url="file:///data/data/com.kasun.newslab/x.jpg"/>
            <description><![CDATA[<img src="content://evil/x.jpg">]]></description>
          </item>
          <item>
            <title>Media group image</title>
            <link>https://example.com/sixth</link>
            <media:group>
              <media:content url="https://example.com/video.mp4" medium="video"/>
              <media:content url="https://example.com/still.jpg" medium="image"/>
            </media:group>
          </item>
          <item>
            <title>No link, dropped</title>
          </item>
          <item>
            <link>https://example.com/untitled</link>
          </item>
        </channel>
        </rss>
    """

    @Test
    fun rssItemFields() {
        val result = parse(rss)
        assertFalse(result.isAtom)
        assertEquals(6, result.items.size)

        val first = result.items[0]
        assertEquals("Rupee <b>firms</b> against dollar", first.title)
        assertEquals("https://example.com/rupee", first.link)
        assertEquals("post-101", first.guid)
        // <content:encoded> is preferred over the <description> excerpt.
        assertEquals("<p>Full body</p><img src=\"https://example.com/body.jpg\">", first.description)
        assertEquals(listOf("Business", "Economy"), first.categories)
        assertEquals("Mon, 20 Jul 2026 10:30:00 +0000", first.pubDateRaw)
        // media:thumbnail beats an <img> in the body.
        assertEquals("https://example.com/thumb.jpg", first.imageUrl)
    }

    @Test
    fun rssGuidFallsBackToLinkAndImageEnclosureIsUsed() {
        val second = parse(rss).items[1]
        assertEquals("https://example.com/second", second.guid)
        assertEquals("Plain text", second.description)
        assertEquals("", second.pubDateRaw)
        assertEquals(emptyList<String>(), second.categories)
        assertEquals("https://example.com/photo.jpg", second.imageUrl)
    }

    @Test
    fun rssImageFallbacksAreSanitised() {
        val items = parse(rss).items
        // <img> from HTML, with the CDATA-literal &amp; decoded.
        assertEquals("https://example.com/a.jpg?w=1&h=2", items[2].imageUrl)
        assertEquals("https://cdn.example.com/p.jpg", items[3].imageUrl)
        // file:// and content:// are not web schemes and are dropped.
        assertEquals("", items[4].imageUrl)
        // media:content nested in media:group, image medium only.
        assertEquals("https://example.com/still.jpg", items[5].imageUrl)
    }

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Atom feed</title>
          <entry>
            <title type="html">Atom &lt;em&gt;entry&lt;/em&gt;</title>
            <link rel="self" href="https://example.org/api/1"/>
            <link rel="alternate" type="text/html" href="https://example.org/posts/1"/>
            <link rel="replies" href="https://example.org/posts/1#comments"/>
            <link rel="enclosure" type="image/png" href="https://example.org/img/1.png"/>
            <id>tag:example.org,2026:1</id>
            <published>2026-07-20T16:00:00+05:30</published>
            <updated>2026-07-21T00:00:00Z</updated>
            <category term="Markets"/>
            <category term="  "/>
            <category scheme="x" term="Bonds"></category>
            <summary>Summary text</summary>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>First <b>bold</b> para</p> <p>Second</p></div></content>
          </entry>
          <entry>
            <title>Bare link entry</title>
            <link href="https://example.org/posts/2"/>
            <summary>Only a summary</summary>
          </entry>
        </feed>
    """

    @Test
    fun atomEntryFields() {
        val result = parse(atom)
        assertTrue(result.isAtom)
        assertEquals(2, result.items.size)

        val entry = result.items[0]
        assertEquals("Atom <em>entry</em>", entry.title)
        // rel="alternate" wins over self (seen first) and replies (seen after).
        assertEquals("https://example.org/posts/1", entry.link)
        assertEquals("tag:example.org,2026:1", entry.guid)
        // First date seen wins; <updated> does not overwrite <published>.
        assertEquals("2026-07-20T16:00:00+05:30", entry.pubDateRaw)
        assertEquals(listOf("Markets", "Bonds"), entry.categories)
        assertEquals("https://example.org/img/1.png", entry.imageUrl)
        // Nested xhtml text is kept, not reset by the inner tags; block tags are kept
        // as markup so the later clean-up can turn them into paragraph breaks, while
        // inline tags (<b>) are dropped.
        assertEquals("<div><p>First bold para</p> <p>Second</p></div>", entry.description)
        assertEquals("First bold para\n\nSecond", TextUtil.clean(entry.description))

        val bare = result.items[1]
        assertEquals("https://example.org/posts/2", bare.link)
        assertEquals("https://example.org/posts/2", bare.guid)
        assertEquals("Only a summary", bare.description)
    }

    @Test
    fun compactXhtmlContentKeepsBlockBoundariesAndImages() {
        // Adjacent block elements with no whitespace between them used to run together
        // ("OneTwo"); the blocks now survive as markup, and so does an image.
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom"><entry>
              <title>T</title><link href="https://example.org/3"/>
              <content type="xhtml"><div><p>One</p><p>Two<br/>Three</p><img src="https://example.org/i.png" alt=""/></div></content>
            </entry></feed>
        """
        val item = parse(xml).items.single()
        assertEquals("<div><p>One</p><p>Two<br>Three</p><img src=\"https://example.org/i.png\"></div>", item.description)
        // <br> is a line wrap, which clean() collapses to a space; <p> is a paragraph.
        assertEquals("One\n\nTwo Three", TextUtil.clean(item.description))
        assertEquals("https://example.org/i.png", item.imageUrl)
    }

    @Test
    fun atomRootWithNoEntriesIsStillAtom() {
        val result = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Empty</title></feed>""")
        assertTrue(result.isAtom)
        assertEquals(0, result.items.size)
    }

    @Test
    fun truncatedDocumentKeepsParsedItems() {
        val truncated = """
            <rss version="2.0"><channel>
              <item><title>Complete</title><link>https://example.com/1</link></item>
              <item><title>Cut off</title><link>https://example.com/2
        """
        val result = parse(truncated)
        assertFalse(result.isAtom)
        assertEquals(listOf("Complete"), result.items.map { it.title })
    }

    @Test
    fun malformedTailKeepsParsedItems() {
        val broken = """
            <rss version="2.0"><channel>
              <item><title>Good</title><link>https://example.com/1</link></item>
              <item><title>Bad & broken</title><link>https://example.com/2</link></item>
            </channel></rss>
        """
        assertEquals(listOf("Good"), parse(broken).items.map { it.title })
    }

    @Test
    fun garbageInputGivesNoItems() {
        val result = parse("this is not xml")
        assertEquals(0, result.items.size)
        assertFalse(result.isAtom)
    }
}
