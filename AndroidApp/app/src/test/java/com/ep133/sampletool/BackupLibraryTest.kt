package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.ProjectBackupManager
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Backup-library enumeration (PROJ-03) against the real ProjectBackupManager helper.
 *
 * Hardware-free and pure: creates temp .tar files with distinct mtimes and asserts the
 * Context-free [ProjectBackupManager.enumerateBackups] helper returns them newest-first,
 * excluding non-.tar files.
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

            val items = ProjectBackupManager.enumerateBackups(dir)

            assertEquals(listOf(newer.name, older.name), items.map { it.name })
            assertEquals(2_000_000_000_000L, items[0].timestamp)
            assertEquals(newer.absolutePath, items[0].file.absolutePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun enumerateBackups_flagsManifestPresence() {
        val dir = File.createTempFile("ep133-manifests", "").let { it.delete(); it.mkdirs(); it }
        try {
            val withManifest = File(dir, "EP133-P01-a.tar").apply { writeBytes(ByteArray(0)) }
            File(dir, "EP133-P02-b.tar").apply { writeBytes(ByteArray(0)) }
            // Sidecar dir + manifest.json only for the first backup (999.10).
            File(dir, "EP133-P01-a.manifest").apply { mkdirs() }
                .resolve("manifest.json").writeText("{}")

            val byName = ProjectBackupManager.enumerateBackups(dir).associateBy { it.name }

            assertEquals(2, byName.size)
            assertTrue("sidecar present → hasManifest", byName[withManifest.name]!!.hasManifest)
            assertFalse("no sidecar → !hasManifest", byName["EP133-P02-b.tar"]!!.hasManifest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun enumerateBackups_emptyDirReturnsEmptyList() {
        val dir = File.createTempFile("ep133-empty", "").let { it.delete(); it.mkdirs(); it }
        try {
            assertTrue(ProjectBackupManager.enumerateBackups(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
