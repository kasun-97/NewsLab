package com.kasun.newslab.data

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Settings live outside the database; a backup has to carry them or a restore loses every choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupTest {

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = Prefs.get(context)
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { SQLiteDatabase.deleteDatabase(it) }
        AppDb.close()
        context.deleteDatabase("newslab.db")
        // The DataStore is one per process; leave it as the next test expects to find it.
        runBlocking { prefs.importSettings(emptyMap()) }
    }

    private fun newFile(name: String) = File(context.cacheDir, name).also { files += it }

    private fun hasSettingsTable(file: File): Boolean =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE name = 'backup_settings'", null).use { it.moveToFirst() }
        }

    @Test
    fun carriesOnlyWhatWasChangedAndPutsItBack() = runBlocking {
        assertEquals(emptyMap<String, String>(), prefs.exportSettings())

        prefs.setMuteWords("cricket, world cup")
        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setQuietHoursEnabled(true)
        prefs.setQuietHoursStartMinutes(21 * 60 + 30)
        prefs.setRefreshInterval(120)
        val carried = prefs.exportSettings()
        assertEquals(
            mapOf(
                "mute_words" to "cricket, world cup",
                "theme_mode" to "DARK",
                "quiet_hours_enabled" to "true",
                "quiet_hours_start" to "1290",
                "refresh_interval_minutes" to "120",
            ),
            carried,
        )

        // Changed after the backup was made; the restore must undo all of it.
        prefs.setMuteWords("")
        prefs.setThemeMode(ThemeMode.LIGHT)
        prefs.setQuietHoursStartMinutes(60)
        prefs.setHideRead(true)

        prefs.importSettings(carried)
        assertEquals("cricket, world cup", prefs.muteWords.first())
        assertEquals(ThemeMode.DARK, prefs.themeMode.first())
        assertTrue(prefs.quietHoursEnabled.first())
        assertEquals(1290, prefs.quietHoursStartMinutes.first())
        assertEquals(120L, prefs.refreshIntervalMinutes.first())
        // Not in the backup, so back to its default rather than left as it was.
        assertFalse(prefs.hideRead.first())
    }

    @Test
    fun stateThatBelongsToTheDeviceIsNotCarried() = runBlocking {
        prefs.setLastRefreshAt(123_456L)
        assertEquals(emptyMap<String, String>(), prefs.exportSettings())
        // And a restore doesn't touch it.
        prefs.importSettings(mapOf("mute_words" to "golf"))
        assertEquals(123_456L, prefs.lastRefreshAt.first())
        prefs.setLastRefreshAt(0)
    }

    @Test
    fun unknownAndUnreadableValuesAreIgnored() = runBlocking {
        prefs.importSettings(
            mapOf(
                "from_a_later_version" to "x",
                "quiet_hours_start" to "half past nine",
                "notifications_enabled" to "yes",
                "mute_words" to "golf",
            ),
        )
        assertEquals(mapOf("mute_words" to "golf"), prefs.exportSettings())
    }

    @Test
    fun settingsRideInsideTheDatabaseFileAndComeOutAgain() {
        val file = newFile("carrier.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE TABLE feeds (id INTEGER PRIMARY KEY)") }

        // A backup made before settings were carried has nothing to take.
        assertNull(SettingsBackup.take(file))

        val settings = mapOf("mute_words" to "cricket, \"world cup\"", "theme_mode" to "DARK")
        SettingsBackup.write(file, mapOf("stale" to "1"))
        SettingsBackup.write(file, settings)
        assertTrue(hasSettingsTable(file))

        assertEquals(settings, SettingsBackup.take(file))
        // Taken out, so the restored database is the app's own schema and nothing more.
        assertFalse(hasSettingsTable(file))
        assertNull(SettingsBackup.take(file))
    }

    @Test
    fun aBackupWithNoSettingsChangedStillRecordsThatFact() {
        val file = newFile("defaults.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).close()
        SettingsBackup.write(file, emptyMap())
        // Empty, not null: restoring it resets the settings, unlike a backup that predates them.
        assertEquals(emptyMap<String, String>(), SettingsBackup.take(file))
    }

    @Test
    fun exportWritesARestorableDatabaseCarryingTheSettings() = runBlocking {
        AppDb.get(context).dao().insertFeed(Feed(name = "Daily FT", url = "https://example.com/rss", color = 0))
        prefs.setMuteWords("cricket")

        val exported = newFile("exported.db")
        assertEquals(ImportResult.Success, BackupManager.export(context, Uri.fromFile(exported), includeImages = false))

        assertNull(BackupManager.restoreProblem(exported))
        assertEquals(mapOf("mute_words" to "cricket"), SettingsBackup.take(exported))
        SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM feeds", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("Daily FT", it.getString(0))
            }
        }
        // The live database was only read: it never gains the carrier table.
        assertFalse(hasSettingsTable(context.getDatabasePath("newslab.db")))
    }
}
