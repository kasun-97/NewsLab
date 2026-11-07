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

        // validateDroppedTables is off because it rejects any table Room has no entity for,
        // and the full-text index is deliberately raw SQL. The same check is made below
        // with that index and its FTS4 shadow tables allowed.
        val db = helper.runMigrationsAndValidate(name, 17, false, *AppDb.ALL_MIGRATIONS)

        // Seeded v1 rows survive, with the columns added since then at their defaults.
        db.query("SELECT name, url, color, enabled, isAtom, etag, lastModified, lastFetchedAt, lastError, lastErrorAt, muted FROM feeds WHERE id = 7").use {
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
            assertEquals(0, it.getInt(10))
        }
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM categories WHERE name = 'Economy' AND keywords = 'gdp,imf' AND position = 0"))
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM keyword_rules WHERE label = 'IMF' AND query = 'imf' AND enabled = 1 AND notify = 1"))
        // An existing match counts as already notified, so the upgrade can't re-fire it.
        db.query("SELECT feedId, title, imageUrl, pageImageUrl, isRead, isSaved, alertNotified FROM articles WHERE id = 'a1'").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(7L, it.getLong(0))
            assertEquals("Rates held", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals("", it.getString(3))
            assertEquals(0, it.getInt(4))
            assertEquals(1, it.getInt(5))
            assertEquals(1, it.getInt(6))
        }
        // The v1 feed had no group, so 3->4 carried no membership across.
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM feed_group_members"))

        // 10->11 seeds the Default list with a pinned id; 9->10 seeded three links into it.
        assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM quick_link_lists WHERE id = 1 AND name = 'Default' AND position = 0"))
        assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM quick_links WHERE listId = 1"))
        assertEquals(3L, scalar(db, "SELECT COUNT(*) FROM quick_links"))

        // Tables added and later dropped are gone, and nothing else is left over.
        assertFalse(tableExists(db, "macro_indicators"))
        assertFalse(tableExists(db, "cse_announcements"))
        val expectedTables = setOf(
            "feeds", "categories", "keyword_rules", "articles", "feed_groups",
            "feed_group_members", "article_content", "quick_links", "quick_link_lists",
            "room_master_table", "android_metadata", "sqlite_sequence",
            "article_content_fts", "article_content_fts_segments", "article_content_fts_segdir",
            "article_content_fts_docsize", "article_content_fts_stat",
        )
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(emptySet<String>(), tables - expectedTables)

        // 16->17 adds the full-text index over cached bodies, which Room doesn't validate.
        assertContentFtsInstalled(db)
        db.close()
    }

    @Test
    fun freshInstallCreatesTheContentIndexAndKeepsItInStep() {
        // Room builds a fresh install from the entities alone; the index comes from Seed.
        val context = RuntimeEnvironment.getApplication()
        try {
            val db = AppDb.get(context).openHelper.writableDatabase
            assertContentFtsInstalled(db)

            // The body cache writes with INSERT OR REPLACE (Room's REPLACE strategy).
            db.execSQL("INSERT OR REPLACE INTO article_content (id, text, fetchedAt) VALUES ('a', 'central bank holds rates', 1)")
            db.execSQL("INSERT OR REPLACE INTO article_content (id, text, fetchedAt) VALUES ('a', 'treasury bill auction', 2)")
            assertEquals(emptySet<String>(), bodyMatches(db, "rates"))
            assertEquals(setOf("a"), bodyMatches(db, "auction"))

            db.execSQL("UPDATE article_content SET text = 'rupee steadies' WHERE id = 'a'")
            assertEquals(emptySet<String>(), bodyMatches(db, "auction"))
            assertEquals(setOf("a"), bodyMatches(db, "rupee"))

            // No earlier body's terms may attach to whichever row takes a freed rowid next.
            db.execSQL("DELETE FROM article_content WHERE id = 'a'")
            db.execSQL("INSERT OR REPLACE INTO article_content (id, text, fetchedAt) VALUES ('b', 'tea exports rise', 3)")
            for (stale in listOf("rates", "auction", "rupee")) {
                assertEquals(stale, emptySet<String>(), bodyMatches(db, stale))
            }
            assertEquals(setOf("b"), bodyMatches(db, "tea"))

            // Throws if the index disagrees with the content table.
            db.execSQL("INSERT INTO article_content_fts(article_content_fts) VALUES ('integrity-check')")
        } finally {
            AppDb.close()
            context.deleteDatabase("newslab.db")
        }
    }

    @Test
    fun freshInstallSchemaIsSelfConsistent() {
        // Runs the exported schema's own CREATE statements; throws if the JSON is missing or bad.
        val db = helper.createDatabase("fresh-v17.db", 17)
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

    private fun assertContentFtsInstalled(db: SupportSQLiteDatabase) {
        assertTrue(tableExists(db, "article_content_fts"))
        for (trigger in listOf(
            "article_content_fts_bi", "article_content_fts_ai", "article_content_fts_bd",
            "article_content_fts_bu", "article_content_fts_au",
        )) {
            assertEquals(
                "missing trigger $trigger",
                1L,
                scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '$trigger'"),
            )
        }
    }

    private fun bodyMatches(db: SupportSQLiteDatabase, match: String): Set<String> =
        db.query(
            "SELECT c.id FROM article_content c WHERE c.rowid IN " +
                "(SELECT docid FROM article_content_fts WHERE article_content_fts MATCH ?)",
            arrayOf(match),
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'") > 0
}
