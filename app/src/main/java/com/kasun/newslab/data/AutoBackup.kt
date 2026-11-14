package com.kasun.newslab.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The weekly automatic backup: a copy of the database (settings included, images not)
 * written into a folder the reader picked, with only the newest few kept. The folder is
 * reached through the Storage Access Framework, so it can be on the device, an SD card
 * or a cloud drive's app, and the app needs no storage permission.
 *
 * Talks to the folder through DocumentsContract directly rather than the DocumentFile
 * helper: it needs three calls, which doesn't justify another dependency.
 */
object AutoBackup {

    /** How many automatic backups are kept; older ones are deleted after each new one. */
    const val KEEP = 4

    const val INTERVAL_DAYS = 7L

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ENGLISH)

    // A provider may alter a name it considers taken ("... (1).db"), hence the loose middle.
    private val NAME = Regex("""newslab-auto-\d{4}-\d{2}-\d{2}-\d{6}.*\.db""")

    /** Distinct from a manual export's name, so pruning can never touch a backup made by hand. */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "newslab-auto-${STAMP.format(Instant.ofEpochMilli(now).atZone(zone))}.db"

    /**
     * Of a folder's file [names], the automatic backups beyond the newest [keep]. The
     * stamp sorts as text, so the name order is the age order. Everything else in the
     * folder (manual exports, the reader's own files) is never a candidate.
     */
    fun surplus(names: Collection<String>, keep: Int = KEEP): List<String> =
        names.filter(NAME::matches).sortedDescending().drop(keep)

    /** The folder as a person would recognise it: "Documents/NewsLab" for "primary:Documents/NewsLab". */
    fun folderLabel(folder: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(folder)).substringAfter(':').ifBlank { "the chosen folder" }
    }.getOrDefault("the chosen folder")

    /**
     * Writes one backup into the chosen folder, prunes the old ones and records the
     * outcome for Settings to show. A failure here must be visible afterwards: nobody
     * is watching when this runs, and a backup that silently stopped is worse than none.
     */
    suspend fun run(context: Context): ImportResult = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val now = System.currentTimeMillis()
        val result = try {
            write(context, prefs.autoBackupFolder.first(), now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            // The grant was revoked, or the folder's storage was removed.
            ImportResult.Failure("NewsLab no longer has access to the backup folder. Choose it again.")
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "Backup failed.")
        }
        prefs.recordAutoBackup(now, (result as? ImportResult.Failure)?.message.orEmpty())
        result
    }

    private suspend fun write(context: Context, folder: String, now: Long): ImportResult {
        if (folder.isBlank()) return ImportResult.Failure("No backup folder chosen.")
        val tree = Uri.parse(folder)
        val resolver = context.contentResolver
        val directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val file = DocumentsContract.createDocument(resolver, directory, "application/octet-stream", fileName(now))
            ?: return ImportResult.Failure("Couldn't create a file in the backup folder.")
        val exported = BackupManager.export(context, file, includeImages = false)
        if (exported is ImportResult.Failure) {
            // Don't leave an empty or half-written file that looks like a backup.
            runCatching { DocumentsContract.deleteDocument(resolver, file) }
            return exported
        }
        // The new backup is safely written; failing to tidy up older ones doesn't undo that.
        runCatching { prune(resolver, tree) }
        return ImportResult.Success
    }

    private fun prune(resolver: ContentResolver, tree: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val idsByName = HashMap<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) idsByName[cursor.getString(0)] = cursor.getString(1)
        }
        for (name in surplus(idsByName.keys)) {
            val id = idsByName[name] ?: continue
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        }
    }
}
