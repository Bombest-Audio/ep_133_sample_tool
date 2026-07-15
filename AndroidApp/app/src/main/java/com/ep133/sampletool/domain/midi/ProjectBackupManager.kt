package com.ep133.sampletool.domain.midi

import android.content.Context
import android.util.Log
import com.ep133.sampletool.domain.backup.ProjectManifestLoader
import com.ep133.sampletool.domain.backup.ProjectManifestWriter
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

/**
 * A restore filename must be a project archive. Matches both the plain legacy form
 * (`[prefix]P{NN}.tar`) and the form [suggestedProjectFilename] actually writes
 * (`[{customName}-]EP133-P{NN}-{yyyy-MM-dd-HHmm}.tar`, e.g. `EP133-P03-2026-07-11-1230.tar`).
 * The old `\w*P(\d{2})\.tar` couldn't span the hyphens/date, so the app rejected its own
 * backups (issue #26). Anchored `^…$` and the character classes exclude `/`, `\`, and stray
 * `.`, so a traversal name can never match. Capture group 1 is the two-digit slot number.
 */
private val PROJECT_TAR_REGEX =
    Regex("""^(?:[A-Za-z0-9_]+-)?(?:EP133-)?P(\d{2})(?:-\d{4}-\d{2}-\d{2}-\d{4})?\.tar$""")

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

/**
 * One on-disk project backup in the library (PROJ-03). [hasManifest] is true when a sidecar
 * `<name>.manifest/manifest.json` exists next to the `.tar` (999.10), so the UI can badge
 * manifest-carrying backups.
 */
data class BackupItem(
    val file: File,
    val name: String,
    val timestamp: Long,
    val hasManifest: Boolean = false,
)

/**
 * Single-project archive backup / restore + local backup-library enumeration over the
 * paged transfer protocol.
 *
 * A project backup is an **opaque device blob**: download the `.tar` via the paged GET,
 * write it to app storage, done — no ZIP re-archiving and never inspect inside the tar
 * (contrast [BackupManager]'s `/sounds` `.pak` assembly). Restore is the inverse PUT.
 *
 * Reference: data/index.js `downloadProjectArchive` / `uploadProjectArchive`.
 */
class ProjectBackupManager(
    private val midi: MIDIRepository,
    private val manifestWriter: ProjectManifestWriter = ProjectManifestWriter(midi),
) {

    /**
     * Backup file name: `[{customName}-]EP133-P{NN}-{timestamp}.tar`. An optional app-side
     * [customName] (see ProjectNameStore) is sanitized to `[A-Za-z0-9_ ]`, trimmed to 40 chars,
     * spaces → underscores, and prefixed so the exported file carries the user's project name.
     */
    fun suggestedProjectFilename(slot: MIDIRepository.ProjectSlot, customName: String? = null): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
        val slotIndex = slotIndexOf(slot.name)
        val prefix = customName
            ?.replace(Regex("[^A-Za-z0-9_ ]"), "")
            ?.trim()
            ?.take(40)
            ?.replace(' ', '_')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$it-" }
            .orEmpty()
        return "${prefix}EP133-P%02d-%s.tar".format(slotIndex, fmt.format(Date()))
    }

    /**
     * Back up a single project slot to app-specific external storage as an opaque `.tar`.
     *
     * Downloads the archive via the paged GET (no `ZipOutputStream`), then writes it under
     * `getExternalFilesDir("backups")` (falling back to internal `filesDir/backups` if external
     * storage is unavailable). Emits Progress → Done(file), or Error on failure.
     *
     * After the `.tar` lands, a sidecar manifest directory (`<name>.manifest/` with manifest.json
     * + exported sample WAVs) is written best-effort via [ProjectManifestWriter] (999.10); its
     * failure never fails the backup.
     */
    fun backupProject(
        slot: MIDIRepository.ProjectSlot,
        context: Context,
        customName: String? = null,
    ): Flow<ProjectBackupProgress> = flow {
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
                val out = File(dir, suggestedProjectFilename(slot, customName))
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

        // Sidecar manifest (999.10) — strictly additive and best-effort: the .tar above is
        // already on disk and stays the authoritative restore artifact. A manifest failure is
        // logged (and partial skips are recorded inside manifest.json), never surfaced as Error.
        try {
            manifestWriter.writeManifest(slot, file, slotIndexOf(slot.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Manifest write failed for ${file.name} — .tar backup is still valid", e)
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
     * Validates the filename against [PROJECT_TAR_REGEX] before any device write (threat T-04-06):
     * the anchored regex also rejects path separators, so a traversal name ("../") can never match.
     * Reads the bytes under [Dispatchers.IO], then uploads via the paged PUT.
     *
     * Restore is wired, validated, and enabled in the UI (999.10) behind the restore-confirm
     * AlertDialog — the destructive PUT requires that confirmation before invocation. The
     * hardware backup→restore round-trip UAT is still pending (Open Q2 / UAT-3).
     */
    fun restoreProject(file: File, context: Context): Flow<ProjectBackupProgress> = flow {
        val slotIndex = tarSlotIndex(file.name)
        if (slotIndex == null) {
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

        // Device project slots are named 01..09, so valid indices are 1..9. The old 0..8 bound
        // rejected a legitimate P09 restore and admitted a nonexistent P00 (issue #26).
        if (slotIndex !in 1..9) {
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
     * storage is unavailable. The directory is created if missing.
     */
    private fun backupsDir(context: Context): File =
        (context.getExternalFilesDir(BACKUPS_DIR)
            ?: context.filesDir.resolve(BACKUPS_DIR)).also { it.mkdirs() }

    /**
     * Extract the numeric slot index from a project name. The device names slots "01".."09"
     * (no "P" prefix — confirmed from the /projects/NN device paths), so match digits anywhere;
     * "P03" still yields 3. Fall back to 0 so a backup never ends up named "P-1".
     */
    private fun slotIndexOf(name: String): Int =
        Regex("""(\d{1,2})""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    companion object {
        /**
         * Full-match [name] against [PROJECT_TAR_REGEX] and return the captured two-digit slot
         * number, or null if it is not a valid project-archive name. The valid-slot range check
         * (1..9) is the caller's — this only decodes the name. Internal so the restore filename
         * validation (issue #26) is directly unit-testable without a Context or device.
         */
        internal fun tarSlotIndex(name: String): Int? =
            PROJECT_TAR_REGEX.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * Pure backup-library enumeration: `.tar` files in [dir], newest first. Hardware-free
         * and Context-free so it is directly unit-testable against a temp directory (PROJ-03).
         */
        fun enumerateBackups(dir: File): List<BackupItem> =
            dir.listFiles { f -> f.extension == "tar" }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    BackupItem(
                        file = it,
                        name = it.name,
                        timestamp = it.lastModified(),
                        hasManifest = ProjectManifestLoader.hasManifest(it),
                    )
                }
                ?: emptyList()
    }
}
