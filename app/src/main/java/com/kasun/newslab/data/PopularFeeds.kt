package com.kasun.newslab.data

/** A suggested source offered in the first-run intro; [group] is the folder it's filed under. */
data class PopularFeed(
    val name: String,
    val url: String,
    val group: String,
    val recommended: Boolean = false,
)

/**
 * Curated feeds offered on the "add popular feeds" onboarding step, world desks first
 * and Sri Lankan titles below them. Only the three majors are pre-ticked, so a first
 * run starts with a manageable spread rather than every source at once.
 */
val POPULAR_FEEDS: List<PopularFeed> = listOf(
    PopularFeed("BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "World", recommended = true),
    PopularFeed(
        "Wall Street Journal",
        "https://feeds.content.dowjones.io/public/rss/RSSWorldNews",
        "World",
        recommended = true,
    ),
    PopularFeed(
        "CNBC",
        "https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114",
        "World",
        recommended = true,
    ),
    PopularFeed("Bloomberg", "https://feeds.bloomberg.com/markets/news.rss", "World"),
    PopularFeed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "World"),
    PopularFeed("EconomyNext", "https://economynext.com/feed/", "Sri Lanka"),
    PopularFeed("Daily FT", "https://www.ft.lk/rss/Front-Page/26", "Sri Lanka"),
    PopularFeed("Daily Mirror", "https://www.dailymirror.lk/rss/breaking_news/108", "Sri Lanka"),
    PopularFeed("Ada Derana", "https://www.adaderana.lk/rss.php", "Sri Lanka"),
    PopularFeed("Lanka Business Online", "https://www.lankabusinessonline.com/feed/", "Sri Lanka"),
    PopularFeed("Newswire", "https://www.newswire.lk/feed", "Sri Lanka"),
    PopularFeed("The Island", "https://island.lk/feed/", "Sri Lanka"),
)
