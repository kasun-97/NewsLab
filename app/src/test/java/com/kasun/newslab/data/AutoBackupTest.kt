package com.kasun.newslab.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pruning deletes files from a folder the reader owns, so what counts as "ours" is pinned down here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBackupTest {

    private val colombo = ZoneId.of("Asia/Colombo")

    private fun nameAt(year: Int, month: Int, day: Int, hour: Int = 9): String =
        AutoBackup.fileName(ZonedDateTime.of(year, month, day, hour, 5, 7, 0, colombo).toInstant().toEpochMilli(), colombo)

    @Test
    fun fileNameCarriesTheLocalTimeAndSortsByAge() {
        assertEquals("newslab-auto-2026-11-14-090507.db", nameAt(2026, 11, 14))
        val names = listOf(nameAt(2026, 11, 14), nameAt(2026, 2, 3), nameAt(2025, 12, 28), nameAt(2026, 11, 14, hour = 21))
        assertEquals(
            listOf(nameAt(2025, 12, 28), nameAt(2026, 2, 3), nameAt(2026, 11, 14), nameAt(2026, 11, 14, hour = 21)),
            names.sorted(),
        )
    }

    @Test
    fun keepsTheNewestFourAndNamesTheRestForDeletion() {
        val weekly = (1..6).map { nameAt(2026, 10, it * 4) }
        assertEquals(listOf(weekly[1], weekly[0]), AutoBackup.surplus(weekly.shuffled()))
        assertEquals(emptyList<String>(), AutoBackup.surplus(weekly.take(4)))
        assertEquals(emptyList<String>(), AutoBackup.surplus(emptyList()))
    }

    @Test
    fun neverNamesAFileItDidNotWrite() {
        val ours = (1..6).map { nameAt(2026, 10, it * 4) }
        val theirs = listOf(
            "newslab-backup-2026-01-01-090507.db", // a manual export
            "newslab-backup-2026-01-01-090507.zip",
            "newslab-auto-notes.db",
            "newslab-auto-2026-10-04-090507.db.bak",
            "my-newslab-auto-2026-10-04-090507.db",
            "holiday.jpg",
        )
        val surplus = AutoBackup.surplus(ours + theirs)
        assertEquals(2, surplus.size)
        assertTrue(ours.containsAll(surplus))
    }

    @Test
    fun aCopyTheProviderRenamedStillCountsAsOurs() {
        val renamed = "newslab-auto-2026-10-04-090507 (1).db"
        val newer = (2..5).map { nameAt(2026, 10, it * 4) }
        assertEquals(listOf(renamed), AutoBackup.surplus(newer + renamed))
    }

    @Test
    fun folderLabelIsThePathPartOfTheTreeId() {
        assertEquals(
            "Documents/NewsLab",
            AutoBackup.folderLabel("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNewsLab"),
        )
        assertEquals("the chosen folder", AutoBackup.folderLabel("not a tree uri"))
    }

    @Test
    fun aFailureKeepsTheLastSuccessTimeAndASuccessClearsTheError() = runBlocking {
        val prefs = Prefs.get(RuntimeEnvironment.getApplication())
        prefs.setAutoBackupFolder("content://example/tree/a")
        try {
            prefs.recordAutoBackup(time = 1_000, error = "")
            prefs.recordAutoBackup(time = 2_000, error = "Disk full")
            assertEquals(1_000L, prefs.autoBackupLastAt.first())
            assertEquals("Disk full", prefs.autoBackupLastError.first())

            prefs.recordAutoBackup(time = 3_000, error = "")
            assertEquals(3_000L, prefs.autoBackupLastAt.first())
            assertEquals("", prefs.autoBackupLastError.first())

            // Another folder starts with no history, and neither folder nor history is carried in a backup.
            prefs.setAutoBackupFolder("content://example/tree/b")
            assertEquals(0L, prefs.autoBackupLastAt.first())
            assertEquals(emptyMap<String, String>(), prefs.exportSettings())
        } finally {
            prefs.setAutoBackupFolder("")
        }
    }

    @Test
    fun withNoFolderChosenARunFailsAndSaysSo() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val result = AutoBackup.run(context)
        assertEquals(ImportResult.Failure("No backup folder chosen."), result)
        assertEquals("No backup folder chosen.", Prefs.get(context).autoBackupLastError.first())
        Prefs.get(context).setAutoBackupFolder("")
    }
}
