package com.kasun.newslab.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed class ImportResult {
    object Success : ImportResult()
    data class Failure(val message: String) : ImportResult()
}

/**
 * Backup/restore for the whole local library. The database alone (feeds,
 * categories, rules, articles) is a plain SQLite file copy; the "+ images"
 * variant additionally bundles Coil's cached thumbnails into a zip so a
 * restored library doesn't need to re-download everything.
 */
object BackupManager {

    /** The live database's filename, and the entry a zip backup stores it under. */
    private const val DB_FILE_NAME = "newslab.db"
    /** The pre-rename entry name, still accepted on import so backups from the old app restore. */
    private const val LEGACY_DB_ENTRY_NAME = "newsdesk.db"
    private const val IMAGES_ENTRY_PREFIX = "images/"

    fun exportFileName(includeImages: Boolean): String {
        val stamp = android.text.format.DateFormat.format("yyyy-MM-dd-HHmmss", System.currentTimeMillis())
        return if (includeImages) "newslab-backup-$stamp.zip" else "newslab-backup-$stamp.db"
    }

    suspend fun export(context: Context, destination: Uri, includeImages: Boolean): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                checkpointWal(context)
                val dbFile = context.getDatabasePath(DB_FILE_NAME)
                val out = context.contentResolver.openOutputStream(destination)
                    ?: return@withContext ImportResult.Failure("Couldn't open the destination file.")
                out.use { stream ->
                    if (includeImages) {
                        ZipOutputStream(stream).use { zip ->
                            addFileToZip(zip, dbFile, DB_FILE_NAME)
                            val imageCacheDir = File(context.cacheDir, "image_cache")
                            if (imageCacheDir.exists()) {
                                imageCacheDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                    val relative = file.relativeTo(imageCacheDir).path.replace(File.separatorChar, '/')
                                    addFileToZip(zip, file, IMAGES_ENTRY_PREFIX + relative)
                                }
                            }
                        }
                    } else {
                        dbFile.inputStream().use { it.copyTo(stream) }
                    }
                }
                ImportResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult.Failure(e.message ?: "Export failed.")
            }
        }

    /**
     * Replaces the live database — and the image cache, if [source] is a zip that
     * has one — with the contents of [source]. Whether it's a plain database file
     * or a zip is detected from the file's own content, not the caller. The
     * current database is kept as a `.before-import.bak` sidecar in case the
     * import turns out to be bad. Callers must restart the app immediately after
     * a [ImportResult.Success] — every existing DB connection (ViewModels, the
     * repository) was opened against the file this just replaced.
     */
    suspend fun import(context: Context, source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val downloaded = File.createTempFile("import", ".tmp", context.cacheDir)
        val extractedDb = File(context.cacheDir, "import-extracted.db")
        val tempImages = File(context.cacheDir, "image_cache_import_tmp")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext ImportResult.Failure("Couldn't open the selected file.")
            input.use { stream -> downloaded.outputStream().use { stream.copyTo(it) } }

            val dbFile: File
            var hasImages = false

            when {
                looksLikeSqlite(downloaded) -> dbFile = downloaded
                looksLikeZip(downloaded) -> {
                    tempImages.deleteRecursively()
                    var foundDb = false
                    ZipInputStream(downloaded.inputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when {
                                entry.name == DB_FILE_NAME || entry.name == LEGACY_DB_ENTRY_NAME -> {
                                    foundDb = true
                                    extractedDb.outputStream().use { zip.copyTo(it) }
                                }
                                entry.name.startsWith(IMAGES_ENTRY_PREFIX) && !entry.isDirectory -> {
                                    val target = File(tempImages, entry.name.removePrefix(IMAGES_ENTRY_PREFIX))
                                    // A crafted entry name ("images/../../x") must not escape the staging directory.
                                    if (!target.canonicalPath.startsWith(tempImages.canonicalPath + File.separator)) {
                                        return@withContext ImportResult.Failure("That backup contains an invalid file path.")
                                    }
                                    hasImages = true
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zip.copyTo(it) }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    if (!foundDb) {
                        return@withContext ImportResult.Failure("That backup doesn't contain a database.")
                    }
                    if (!looksLikeSqlite(extractedDb)) {
                        return@withContext ImportResult.Failure("That backup's database looks corrupted.")
                    }
                    dbFile = extractedDb
                }
                else -> return@withContext ImportResult.Failure("That file doesn't look like a NewsLab backup.")
            }

            AppDb.close()
            val liveDbFile = context.getDatabasePath(DB_FILE_NAME)
            File(liveDbFile.path + "-wal").delete()
            File(liveDbFile.path + "-shm").delete()
            if (liveDbFile.exists()) {
                liveDbFile.copyTo(File(liveDbFile.path + ".before-import.bak"), overwrite = true)
            }
            dbFile.copyTo(liveDbFile, overwrite = true)

            // The image cache always ends up matching the backup, not whatever was
            // cached before — a database-only backup means no images, full stop.
            val imageCacheDir = File(context.cacheDir, "image_cache")
            imageCacheDir.deleteRecursively()
            if (hasImages) {
                tempImages.renameTo(imageCacheDir)
            }
            ImportResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "Import failed.")
        } finally {
            downloaded.delete()
            extractedDb.delete()
            tempImages.deleteRecursively()
        }
    }

    /**
     * Erases everything and returns the app to its fresh-install state: the database
     * (so Room's seed callback re-inserts the default feeds/categories/rules on next
     * open), the preferences (layout, notification and last-refresh state),
     * and the cached images. Callers must [restartApp] immediately on success — the
     * files this deletes are the ones every live connection was opened against.
     */
    suspend fun reset(context: Context): ImportResult = withContext(Dispatchers.IO) {
        try {
            AppDb.close()
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            dbFile.delete()
            // DataStore ("settings") lives under filesDir/datastore; drop the whole
            // directory so every preference reverts to its default.
            File(context.filesDir, "datastore").deleteRecursively()
            File(context.cacheDir, "image_cache").deleteRecursively()
            ImportResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "Reset failed.")
        }
    }

    /** Restarts the app in a fresh process so every DB connection reopens against the new file. */
    fun restartApp(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launchIntent.component))
        Runtime.getRuntime().exit(0)
    }

    private fun checkpointWal(context: Context) {
        // Room defaults to WAL; folding it back into the main file before copying
        // means a plain file copy is a complete, self-contained snapshot.
        AppDb.get(context).openHelper.writableDatabase
            .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            .use { it.moveToFirst() }
    }

    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun looksLikeSqlite(file: File): Boolean {
        if (file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return header.decodeToString(0, 15) == "SQLite format 3"
    }

    private fun looksLikeZip(file: File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { it.read(header) }
        // ZIP local file header signature ("PK\x03\x04").
        return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
    }
}
