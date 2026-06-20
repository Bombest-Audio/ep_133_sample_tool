package com.ep133.sampletool

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Wave 0 scaffold for the backup-library enumeration (PROJ-03).
 *
 * Covers (when filled by Wave 2):
 *  - enumerate `.tar` files in the backups dir, sorted by lastModified() descending
 *
 * This is hardware-free and pure, so the test is real NOW: it creates two temp .tar
 * files with distinct mtimes and asserts descending sort order against an inline
 * listFiles + sortedByDescending expression. Wave 2 swaps the inline expression for
 * the real ProjectBackupManager enumeration helper without changing the assertion.
 *
 * TODO(04-project-management-03): replace the inline enumeration expression with the
 * ProjectBackupManager backup-library helper once it lands.
 */
class BackupLibraryTest {

    @Test
    fun enumeratesTarFiles_sortedByMtimeDescending() {
        val dir = File.createTempFile("ep133-backups", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            val older = File(dir, "EP133-P00-old.tar").apply {
                writeBytes(ByteArray(0))
                setLastModified(1_000_000_000_000L)
            }
            val newer = File(dir, "EP133-P01-new.tar").apply {
                writeBytes(ByteArray(0))
                setLastModified(2_000_000_000_000L)
            }
            // Decoy non-.tar file that must be excluded.
            File(dir, "notes.txt").apply { writeBytes(ByteArray(0)) }

            // TODO(04-project-management-03): replace with ProjectBackupManager enumeration helper
            val names = dir.listFiles { f -> f.extension == "tar" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name }
                ?: emptyList()

            assertEquals(listOf(newer.name, older.name), names)
        } finally {
            dir.deleteRecursively()
        }
    }
}
