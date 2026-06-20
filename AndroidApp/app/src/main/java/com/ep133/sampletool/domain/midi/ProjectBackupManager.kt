package com.ep133.sampletool.domain.midi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "EP133APP"

/** Subdirectory under app-specific external storage holding project `.tar` backups. */
private const val BACKUPS_DIR = "backups"

/** A restore filename must be a project archive: optional prefix, then `P{NN}.tar`. */
private val PROJECT_TAR_REGEX = Regex("""\w*P(\d{2})\.tar""")

/**
 * Progress events emitted by [ProjectBackupManager.backupProject] / [restoreProject].
 *
 * Mirrors [BackupProgress]; a project is a single opaque `.tar` device archive, so
 * `total`/`current` track bytes rather than a file count.
 */
sealed class ProjectBackupProgress {
    /** Transfer in progress: `current` bytes of `total` moved. */
    data class Progress(val current: Int, val total: Int) : ProjectBackupProgress()
    /** Backup/restore complete. [file] is the written archive (backup) or source (restore). */
    data class Done(val file: File) : ProjectBackupProgress()
    /** Failed: [message] describes the error. */
    data class Error(val message: String) : ProjectBackupProgress()
}

/** One on-disk project backup in the library (PROJ-03). */
data class BackupItem(val file: File, val name: String, val timestamp: Long)

/**
 * Single-project archive backup / restore + local backup-library enumeration over the
 * Wave 1 paged transfer protocol.
 *
 * A project backup is an **opaque device blob**: download the `.tar` via the paged GET,
 * write it to app storage, done — no ZIP re-archiving and never inspect inside the tar
 * (contrast [BackupManager]'s `/sounds` `.pak` assembly). Restore is the inverse PUT.
 *
 * Reference: data/index.js `downloadProjectArchive` / `uploadProjectArchive`.
 */
class ProjectBackupManager(private val midi: MIDIRepository) {

    /** Backup file name: `EP133-P{NN}-{timestamp}.tar` (reuses BackupManager's date format). */
    fun suggestedProjectFilename(slot: MIDIRepository.ProjectSlot): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
        val slotIndex = slotIndexOf(slot.name)
        return "EP133-P%02d-%s.tar".format(slotIndex, fmt.format(Date()))
    }

    /**
     * Back up a single project slot to app-specific external storage as an opaque `.tar`.
     *
     * Downloads the archive via the paged GET (no `ZipOutputStream`), then writes it under
     * `getExternalFilesDir("backups")` (falling back to internal `filesDir/backups` if external
     * storage is unavailable — Pitfall 6). Emits Progress → Done(file), or Error on failure.
     */
    fun backupProject(slot: MIDIRepository.ProjectSlot, context: Context): Flow<ProjectBackupProgress> = flow {
        if (midi.deviceState.value.outputPortId == null) {
            emit(ProjectBackupProgress.Error("No EP-133 connected"))
            return@flow
        }
        emit(ProjectBackupProgress.Progress(0, slot.sizeBytes.toInt()))

        val archive = try {
            midi.getProjectArchive(slot.nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Project backup download failed for ${slot.name}", e)
            emit(ProjectBackupProgress.Error("Download failed: ${e.message ?: e}"))
            return@flow
        }

        val file = try {
            withContext(Dispatchers.IO) {
                val dir = backupsDir(context)
                val out = File(dir, suggestedProjectFilename(slot))
                out.writeBytes(archive)   // opaque blob — written as-is
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Project backup write failed for ${slot.name}", e)
            emit(ProjectBackupProgress.Error("Write failed: ${e.message ?: e}"))
            return@flow
        }

        emit(ProjectBackupProgress.Progress(archive.size, archive.size))
        emit(ProjectBackupProgress.Done(file))
    }

    /**
     * Enumerate the backup library: `.tar` files in the backups dir, newest first (PROJ-03).
     * Pure enough to test against a temp dir.
     */
    fun listBackups(context: Context): List<BackupItem> = enumerateBackups(backupsDir(context))

    /**
     * Restore a single project archive to the device (destructive PUT).
     *
     * Validates the filename against `P(\d{2})\.tar` before any device write (threat T-04-06):
     * the regex also rejects path separators, so a traversal name ("../") can never match.
     * Reads the bytes under [Dispatchers.IO], then uploads via the paged PUT.
     *
     * HARDWARE-GATE (Open Q2): restore is wired and validated, but the user-facing button is
     * gated on a hardware backup→restore round-trip pass; default = ship behind the Wave 3
     * restore-confirm AlertDialog. The destructive PUT requires that confirmation before invocation.
     */
    fun restoreProject(file: File, context: Context): Flow<ProjectBackupProgress> = flow {
        if (!PROJECT_TAR_REGEX.matches(file.name)) {
            emit(ProjectBackupProgress.Error("Invalid backup filename: ${file.name} (expected P{NN}.tar)"))
            return@flow
        }
        // Constrain the source to the app-owned backups dir — defends against a restore
        // whose path escapes the library (T-04-08); the file must live under backupsDir.
        val dir = backupsDir(context)
        if (file.parentFile?.canonicalPath != dir.canonicalPath) {
            emit(ProjectBackupProgress.Error("Backup must be in the app library"))
            return@flow
        }
        if (midi.deviceState.value.outputPortId == null) {
            emit(ProjectBackupProgress.Error("No EP-133 connected"))
            return@flow
        }

        val slotIndex = PROJECT_TAR_REGEX.find(file.name)?.groupValues?.get(1)?.toIntOrNull()
        if (slotIndex == null || slotIndex !in 0..8) {
            emit(ProjectBackupProgress.Error("Backup filename slot out of range: ${file.name}"))
            return@flow
        }

        val bytes = try {
            withContext(Dispatchers.IO) { file.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Project restore read failed for ${file.name}", e)
            emit(ProjectBackupProgress.Error("Read failed: ${e.message ?: e}"))
            return@flow
        }
        emit(ProjectBackupProgress.Progress(0, bytes.size))

        // Resolve the target slot node by re-enumerating /projects and matching the slot index.
        val slot = midi.listProjects().firstOrNull { slotIndexOf(it.name) == slotIndex }
        if (slot == null) {
            emit(ProjectBackupProgress.Error("Target slot P%02d not found on device".format(slotIndex)))
            return@flow
        }

        val ok = try {
            midi.putProjectArchive(slot.nodeId, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Project restore upload failed for ${file.name}", e)
            emit(ProjectBackupProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        if (ok) {
            emit(ProjectBackupProgress.Progress(bytes.size, bytes.size))
            emit(ProjectBackupProgress.Done(file))
        } else {
            emit(ProjectBackupProgress.Error("Device did not acknowledge the restore"))
        }
    }

    /**
     * App-specific external backups dir, with a fallback to internal storage when external
     * storage is unavailable (Pitfall 6). The directory is created if missing.
     */
    private fun backupsDir(context: Context): File =
        (context.getExternalFilesDir(BACKUPS_DIR)
            ?: context.filesDir.resolve(BACKUPS_DIR)).also { it.mkdirs() }

    /** Extract the numeric slot index from a name like "P03" → 3; -1 if not a P{NN} name. */
    private fun slotIndexOf(name: String): Int =
        Regex("""P(\d{2})""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    companion object {
        /**
         * Pure backup-library enumeration: `.tar` files in [dir], newest first. Hardware-free
         * and Context-free so it is directly unit-testable against a temp directory (PROJ-03).
         */
        fun enumerateBackups(dir: File): List<BackupItem> =
            dir.listFiles { f -> f.extension == "tar" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { BackupItem(it, it.name, it.lastModified()) }
                ?: emptyList()
    }
}
