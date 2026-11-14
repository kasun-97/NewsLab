package com.kasun.newslab.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Carries the app's settings inside a backup's database file, in a table of their own.
 *
 * Settings live in DataStore, outside the database, so a backup used to restore the
 * library and leave every choice (theme, quiet hours, mute words) at its default.
 * Putting them in the database copy rather than beside it means both kinds of backup
 * carry them, the plain file as well as the zip, and the plain one is still a single
 * SQLite file. The table exists only in backups: it is written into the copy being
 * exported and taken out of the copy being restored, so the live database never has it.
 */
internal object SettingsBackup {

    private const val TABLE = "backup_settings"

    /** Writes [settings] into the database copy at [file], replacing any carried before. */
    fun write(file: File, settings: Map<String, String>) = open(file).use { db ->
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            db.execSQL("CREATE TABLE $TABLE (name TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            settings.forEach { (name, value) ->
                db.execSQL("INSERT INTO $TABLE (name, value) VALUES (?, ?)", arrayOf(name, value))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        settle(db)
    }

    /**
     * Reads the settings the database copy at [file] carries and removes them from it,
     * leaving a database the app can open as its own. Null when it carries none: a
     * backup made before settings were included, whose restore leaves them alone.
     */
    fun take(file: File): Map<String, String>? = open(file).use { db ->
        val carried = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(TABLE),
        ).use { it.moveToFirst() }
        if (!carried) return@use null
        val settings = db.rawQuery("SELECT name, value FROM $TABLE", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        db.execSQL("DROP TABLE $TABLE")
        settle(db)
        settings
    }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    /**
     * Folds the change into the main file. The caller copies that one file onward, and
     * on a device that opens databases in WAL mode the change would otherwise still be
     * sitting in the side file. A no-op in any other journal mode.
     */
    private fun settle(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }
}
