package com.kasun.newslab.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs every migration from version 1 to the current version and checks the result
 * against the exported schema (app/schemas), which is what Room builds from the entities.
 * Only the current version's schema is exported, so the v1 database is created by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDbMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesFromVersion1ToCurrentAndMatchesTheEntitySchema() {
        val name = "migration-v1.db"
        createVersion1(name)

        val db = helper.runMigrationsAndValidate(name, 16, true, *AppDb.ALL_MIGRATIONS)

        // Seeded v1 rows survive, with the columns added since then at their defaults.
        db.query("SELECT name, url, color, enabled, isAtom, etag, lastModified, lastFetchedAt, lastError, lastErrorAt FROM feeds WHERE id = 7").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("Daily FT", it.getString(0))
            assertEquals("https://example.com/rss", it.getString(1))
            assertEquals(0xFF336699L, it.getLong(2))
            assertEquals(1, it.getInt(3))
            assertEquals(0, it.getInt(4))
            assertEquals("", it.getString(5))
            assertEquals("", it.getString(6))
            assertEquals(0L, it.getLong(7))
            assertEquals("", it.getString(8))
            assertEquals(0L, it.getLong(9))
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM categories WHERE name = 'Economy' AND keywords = 'gdp,imf' AND position = 0"))
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM keyword_rules WHERE label = 'IMF' AND query = 'imf' AND enabled = 1"))
        db.query("SELECT feedId, title, imageUrl, pageImageUrl, isRead, isSaved FROM articles WHERE id = 'a1'").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(7L, it.getLong(0))
            assertEquals("Rates held", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals("", it.getString(3))
            assertEquals(0, it.getInt(4))
            assertEquals(1, it.getInt(5))
        }
        // The v1 feed had no group, so 3->4 carried no membership across.
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM feed_group_members"))

        // 10->11 seeds the Default list with a pinned id; 9->10 seeded three links into it.
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM quick_link_lists WHERE id = 1 AND name = 'Default' AND position = 0"))
        assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM quick_links WHERE listId = 1"))
        assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM quick_links"))

        // Tables added and later dropped are gone.
        assertFalse(tableExists(db, "macro_indicators"))
        assertFalse(tableExists(db, "cse_announcements"))
        db.close()
    }

    @Test
    fun freshInstallSchemaIsSelfConsistent() {
        // Runs the exported schema's own CREATE statements; throws if the JSON is missing or bad.
        val db = helper.createDatabase("fresh-v16.db", 16)
        for (table in listOf(
            "feeds", "categories", "keyword_rules", "articles", "feed_groups",
            "feed_group_members", "article_content", "quick_links", "quick_link_lists",
        )) {
            assertTrue("missing table $table", tableExists(db, table))
        }
        assertEquals(
            2L,
            scalar(
                db,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name IN ('index_articles_publishedAt', 'index_articles_feedId')",
            ),
        )
        db.close()
    }

    /**
     * The version-1 schema: today's entities minus every column and table a migration
     * added since (imageUrl, pageImageUrl, isAtom, etag, lastModified, lastFetchedAt,
     * lastError, lastErrorAt, the indexes, and the group, content and link tables).
     */
    private fun createVersion1(name: String) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE feeds (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "name TEXT NOT NULL, url TEXT NOT NULL, color INTEGER NOT NULL, enabled INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "name TEXT NOT NULL, keywords TEXT NOT NULL, position INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE keyword_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "label TEXT NOT NULL, query TEXT NOT NULL, enabled INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE articles (id TEXT NOT NULL, feedId INTEGER NOT NULL, " +
                            "feedName TEXT NOT NULL, feedColor INTEGER NOT NULL, title TEXT NOT NULL, " +
                            "summary TEXT NOT NULL, link TEXT NOT NULL, publishedAt INTEGER NOT NULL, " +
                            "fetchedAt INTEGER NOT NULL, rssCategories TEXT NOT NULL, categoryIds TEXT NOT NULL, " +
                            "matchedRules TEXT NOT NULL, matchedLabels TEXT NOT NULL, isRead INTEGER NOT NULL, " +
                            "isSaved INTEGER NOT NULL, PRIMARY KEY(id))"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { open ->
            val db = open.writableDatabase
            db.execSQL("INSERT INTO feeds (id, name, url, color, enabled) VALUES (7, 'Daily FT', 'https://example.com/rss', ${0xFF336699L}, 1)")
            db.execSQL("INSERT INTO categories (name, keywords, position) VALUES ('Economy', 'gdp,imf', 0)")
            db.execSQL("INSERT INTO keyword_rules (label, query, enabled) VALUES ('IMF', 'imf', 1)")
            db.execSQL(
                "INSERT INTO articles (id, feedId, feedName, feedColor, title, summary, link, publishedAt, " +
                    "fetchedAt, rssCategories, categoryIds, matchedRules, matchedLabels, isRead, isSaved) VALUES " +
                    "('a1', 7, 'Daily FT', ${0xFF336699L}, 'Rates held', 'CBSL holds rates', 'https://example.com/a1', " +
                    "1700000000000, 1700000100000, 'economy', ',1,', ',1,', 'IMF', 0, 1)"
            )
        }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'") > 0
}
