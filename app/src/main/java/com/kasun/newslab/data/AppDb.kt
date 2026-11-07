package com.kasun.newslab.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Feed::class, Category::class, KeywordRule::class, Article::class,
        FeedGroup::class, FeedGroupMember::class,
        ArticleContent::class, QuickLink::class,
        QuickLinkList::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {

    abstract fun dao(): NewsDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN imageUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS feed_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("ALTER TABLE feeds ADD COLUMN groupId INTEGER DEFAULT NULL")
            }
        }

        /**
         * Replaces the single feeds.groupId column with a feed_group_members join table
         * so a source can belong to more than one group. Table-recreate rather than
         * ALTER TABLE ... DROP COLUMN, since that needs SQLite 3.35+ and isn't reliably
         * available across all supported Android versions.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS feed_group_members (" +
                        "feedId INTEGER NOT NULL, groupId INTEGER NOT NULL, " +
                        "PRIMARY KEY(feedId, groupId))"
                )
                db.execSQL(
                    "INSERT INTO feed_group_members (feedId, groupId) " +
                        "SELECT id, groupId FROM feeds WHERE groupId IS NOT NULL"
                )
                db.execSQL(
                    "CREATE TABLE feeds_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, url TEXT NOT NULL, " +
                        "color INTEGER NOT NULL, enabled INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO feeds_new (id, name, url, color, enabled) " +
                        "SELECT id, name, url, color, enabled FROM feeds"
                )
                db.execSQL("DROP TABLE feeds")
                db.execSQL("ALTER TABLE feeds_new RENAME TO feeds")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS macro_indicators (" +
                        "indicatorKey TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, " +
                        "currentValue REAL NOT NULL, currentPeriod TEXT NOT NULL, " +
                        "previousValue REAL, previousPeriod TEXT, " +
                        "format TEXT NOT NULL, deltaFormat TEXT NOT NULL, fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        /** Indexes for the hot article queries (timeline sort, per-feed counts/deletes). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_articles_publishedAt ON articles (publishedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_articles_feedId ON articles (feedId)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN isAtom INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cse_announcements (" +
                        "id INTEGER NOT NULL PRIMARY KEY, announcementId INTEGER NOT NULL, " +
                        "company TEXT NOT NULL, category TEXT NOT NULL, dateText TEXT NOT NULL, " +
                        "createdDate INTEGER NOT NULL, logoUrl TEXT NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS article_content (" +
                        "id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        private const val CREATE_QUICK_LINKS =
            "CREATE TABLE IF NOT EXISTS quick_links (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                "url TEXT NOT NULL, position INTEGER NOT NULL)"

        /**
         * Country hubs the links feature originally shipped with. Kept only because
         * migration 9->10 ran with them; a fresh install now starts with no links.
         * An applied migration must keep doing what it did, or upgrading and installing
         * clean would leave two different databases.
         */
        private const val SEED_QUICK_LINKS =
            "INSERT INTO quick_links (title, url, position) VALUES " +
                "('IMF - Sri Lanka', 'https://www.imf.org/en/countries/lka#whatsnew', 0), " +
                "('World Bank - Sri Lanka', 'https://www.worldbank.org/ext/en/country/srilanka', 1), " +
                "('ADB - Sri Lanka', 'https://www.adb.org/where-we-work/sri-lanka', 2)"

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_QUICK_LINKS)
                db.execSQL(SEED_QUICK_LINKS)
            }
        }

        private const val CREATE_QUICK_LINK_LISTS =
            "CREATE TABLE IF NOT EXISTS quick_link_lists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "position INTEGER NOT NULL)"

        /** The list every install starts with; its id is pinned so links can default to it. */
        private const val SEED_DEFAULT_LIST =
            "INSERT INTO quick_link_lists (id, name, position) VALUES (1, 'Default', 0)"

        /**
         * Groups links into foldable sections. Existing links keep their order and all land
         * in the seeded Default list, so an upgrade looks unchanged until lists are added.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_QUICK_LINK_LISTS)
                db.execSQL(SEED_DEFAULT_LIST)
                db.execSQL("ALTER TABLE quick_links ADD COLUMN listId INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Drops the Macro tab's table. The section is out of the app until its data
         * sources are worth shipping; the migration that created it (4->5) is left as
         * it was, since an applied migration has to keep doing what it did.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS macro_indicators")
            }
        }

        /**
         * Adds the page-scraped image alongside the feed's own, so the two can't
         * overwrite each other. Existing rows start blank and fill in as articles
         * are opened.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN pageImageUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Drops the Colombo Stock Exchange announcements table. The section was removed
         * from the app; its watchlist and notification settings in DataStore are simply
         * never read again.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS cse_announcements")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "newslab.db",
            )
                .addCallback(Seed)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                )
                .build()
                .also { instance = it }
        }

        /** Releases the underlying file handles so the database file can be safely replaced (import). */
        fun close() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }

    /** Seeds default feeds, categories and example keyword rules on first run. */
    private object Seed : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // No feeds are seeded — the first-run intro adds the sources the user picks.
            // Categories and keyword rules are still seeded so classification and alerts
            // work the moment those chosen feeds start fetching.
            db.execSQL(
                "INSERT INTO categories (name, keywords, position) VALUES " +
                    "('Economy', 'economy,inflation,gdp,imf,central bank,cbsl,rupee,treasury,fiscal,tax,exports,imports,reserves,tariff,world bank,debt', 0), " +
                    "('Market News', 'market,stocks,shares,cse,aspi,bourse,ipo,dividend,earnings,profit,investors,bond,forex,trading,listed', 1), " +
                    "('Politics', 'president,parliament,minister,election,cabinet,government,opposition,party,npp,sjb,slpp,gazette,policy', 2)"
            )
            db.execSQL(
                "INSERT INTO keyword_rules (label, query, enabled) VALUES " +
                    "('IMF', 'imf', 1), " +
                    "('Interest rates', 'interest rate', 1)"
            )
            // The empty Default list is seeded, but no links go in it — the country hubs
            // that used to ship here are one reader's bookmarks, not a sensible default
            // for everyone. The list itself has to exist, since links file into it.
            db.execSQL(SEED_DEFAULT_LIST)
        }
    }
}
