package com.kasun.newslab.data

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A restore replaces the live database and restarts onto it, so a file Room can't open must be refused first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreCheckTest {

    private val context = RuntimeEnvironment.getApplication()
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { SQLiteDatabase.deleteDatabase(it) }
        AppDb.close()
        context.deleteDatabase("newslab.db")
    }

    private fun newFile(name: String) = File(context.cacheDir, name).also { files += it }

    /** A database with NewsLab's first-version tables at [version]. */
    private fun backupAt(version: Int, tables: List<String> = listOf("feeds", "categories", "keyword_rules", "articles")): File {
        val file = newFile("backup-v$version-${tables.size}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            tables.forEach { db.execSQL("CREATE TABLE $it (id INTEGER PRIMARY KEY)") }
            db.version = version
        }
        return file
    }

    @Test
    fun acceptsACopyOfTheLiveDatabase() {
        // What export writes: the Room database, checkpointed, copied as a single file.
        val live = AppDb.get(context)
        live.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { it.moveToFirst() }
        val copy = newFile("copy.db")
        context.getDatabasePath("newslab.db").copyTo(copy, overwrite = true)

        assertNull(BackupManager.restoreProblem(copy))
        // The check must leave the file restorable: still a database at the same version.
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(DB_VERSION, it.version)
        }
    }

    @Test
    fun acceptsBackupsFromThisAndOlderVersions() {
        assertNull(BackupManager.restoreProblem(backupAt(DB_VERSION)))
        assertNull(BackupManager.restoreProblem(backupAt(1)))
    }

    @Test
    fun refusesABackupFromANewerVersion() {
        val problem = BackupManager.restoreProblem(backupAt(DB_VERSION + 1))
        assertNotNull(problem)
        assertTrue(problem!!.contains("newer version"))
    }

    @Test
    fun refusesADatabaseThatIsNotNewsLabs() {
        val problem = "That file is a database, but not a NewsLab backup."
        assertEquals(problem, BackupManager.restoreProblem(backupAt(DB_VERSION, tables = listOf("notes"))))
        assertEquals(problem, BackupManager.restoreProblem(backupAt(DB_VERSION + 5, tables = listOf("feeds", "articles"))))
        // Never versioned by Room, even with the right table names.
        assertEquals(problem, BackupManager.restoreProblem(backupAt(0)))
    }

    @Test
    fun reportsAFileThatOnlyStartsLikeADatabase() {
        val file = newFile("garbage.db")
        file.writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(4096) { 0x5A })
        assertEquals("That backup's database looks corrupted.", BackupManager.restoreProblem(file))
    }
}
