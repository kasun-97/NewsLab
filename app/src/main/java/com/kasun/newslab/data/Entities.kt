package com.kasun.newslab.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An RSS source the app polls. */
@Entity(tableName = "feeds")
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    /** ARGB color used for the source accent bar in the timeline. */
    val color: Long,
    val enabled: Boolean = true,
    /** Detected from the feed's root element (<feed> vs <rss>/<rdf>) on the first successful fetch. */
    val isAtom: Boolean = false,
    /**
     * Validators from the last successful fetch, sent back as If-None-Match and
     * If-Modified-Since so an unchanged feed answers 304 with no body. Blank when the
     * server sent none; reset when the URL changes.
     */
    val etag: String = "",
    val lastModified: String = "",
    /** When the feed was last fetched successfully (a 304 counts); 0 if never. */
    val lastFetchedAt: Long = 0,
    /** Why the last refresh of this feed failed, in a few words; blank once a fetch succeeds again. */
    val lastError: String = "",
    val lastErrorAt: Long = 0,
    /** Still fetched and shown as usual, but its keyword matches never post a notification. */
    @ColumnInfo(defaultValue = "0")
    val muted: Boolean = false,
)

/** A user-defined folder for organizing sources on the Feeds screen (e.g. "Local", "Markets"). */
@Entity(tableName = "feed_groups")
data class FeedGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

/** Many-to-many: a source can be filed under any number of groups (or none). */
@Entity(tableName = "feed_group_members", primaryKeys = ["feedId", "groupId"])
data class FeedGroupMember(
    val feedId: Long,
    val groupId: Long,
)

/**
 * A custom category (Economy, Market News, Politics, ...).
 * [keywords] is a comma-separated list of match terms; an article is tagged
 * with this category when its title/summary/publisher-tags contain any term
 * (or the category name itself).
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keywords: String,
    val position: Int = 0,
)

/**
 * A keyword alert rule. [query] is a set of words that must ALL appear in an
 * article (title or summary) for the rule to fire; "quoted words" must appear
 * as an exact contiguous phrase. Matching articles are pinned to the top of
 * the inbox and can trigger notifications.
 */
@Entity(tableName = "keyword_rules")
data class KeywordRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val query: String,
    val enabled: Boolean = true,
    /** False keeps pinning this rule's matches but never notifies about them. */
    @ColumnInfo(defaultValue = "1")
    val notify: Boolean = true,
)

/**
 * A named group of quick links, drawn as one foldable section on the Links tab.
 * [position] is the section's order on screen, rewritten when sections are dragged.
 */
@Entity(tableName = "quick_link_lists")
data class QuickLinkList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
) {
    companion object {
        /** The list every install starts with, and where a new link lands unless told otherwise. */
        const val DEFAULT_ID = 1L
    }
}

/** A web link the user keeps on the Links tab, opened in a browser tab. */
@Entity(tableName = "quick_links")
data class QuickLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val position: Int = 0,
    // The SQL default matches what migration 10->11 backfills, so Room's schema check
    // sees the same column either way, and a seeded link needs no explicit list.
    @ColumnInfo(defaultValue = "1")
    val listId: Long = QuickLinkList.DEFAULT_ID,
)

/**
 * Full readable article body extracted from the source page, cached so a re-open
 * (or an offline read of a saved article) needs no network. Keyed by [Article.id];
 * orphaned rows are pruned alongside their article.
 */
@Entity(tableName = "article_content")
data class ArticleContent(
    @PrimaryKey val id: String,
    /** Extracted paragraphs, separated by blank lines. */
    val text: String,
    val fetchedAt: Long,
)

/** A fetched article, classified at insert time. */
@Entity(tableName = "articles", indices = [Index("publishedAt"), Index("feedId")])
data class Article(
    /** Stable hash of the item guid (or link). */
    @PrimaryKey val id: String,
    val feedId: Long,
    val feedName: String,
    val feedColor: Long,
    val title: String,
    val summary: String,
    val link: String,
    /** First image found in the article body, if any (empty when none). */
    val imageUrl: String = "",
    /**
     * Full-size image read from the article's own page (og:image), filled in lazily
     * when the article is opened. Kept apart from [imageUrl] — which for many feeds
     * is a small syndication thumbnail — so a refresh resync can rewrite the feed's
     * own image without discarding this one.
     */
    val pageImageUrl: String = "",
    val publishedAt: Long,
    val fetchedAt: Long,
    /** Raw publisher <category> tags, comma-joined. */
    val rssCategories: String,
    /** Matched category ids in ",1,3," form (empty when none). */
    val categoryIds: String,
    /** Matched keyword-rule ids in ",2," form (empty when none). */
    val matchedRules: String,
    /** Display labels of matched keyword rules. */
    val matchedLabels: String,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
    /**
     * Whether this match's alert has been posted (or deliberately never will be).
     * Kept on the row rather than in memory so an alert held back, e.g. by quiet hours,
     * survives the process dying before it is posted. The SQL default of 1 is what the
     * 16->17 migration backfills: rows from before it were notified, if ever, under the
     * old rules and must not fire again.
     */
    @ColumnInfo(defaultValue = "1")
    val alertNotified: Boolean = false,
) {
    /** The image to render: the article page's own, falling back to whatever the feed carried. */
    val displayImageUrl: String get() = pageImageUrl.ifBlank { imageUrl }
}
