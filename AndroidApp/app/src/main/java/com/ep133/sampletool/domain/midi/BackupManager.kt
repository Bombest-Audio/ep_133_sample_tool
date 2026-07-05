package com.ep133.sampletool.domain.midi

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "EP133BACKUP"

/** Progress events emitted by [BackupManager.createBackup]. */
sealed class BackupProgress {
    /** Backup in progress: `current` files downloaded of `total`. */
    data class Progress(val current: Int, val total: Int) : BackupProgress()
    /** Backup complete: [pakBytes] is the ZIP archive ready to write. */
    data class Done(val pakBytes: ByteArray) : BackupProgress()
    /** Backup failed: [message] describes the error. */
    data class Error(val message: String) : BackupProgress()
}

/** Progress events emitted by [BackupManager.restore]. */
sealed class RestoreProgress {
    /** Restore in progress: `current` files uploaded of `total`. */
    data class Progress(val current: Int, val total: Int) : RestoreProgress()
    /** Restore complete. */
    object Done : RestoreProgress()
    /** Restore failed: [message] describes the error. */
    data class Error(val message: String) : RestoreProgress()
}

/**
 * Orchestrates EP-133 /sounds backup and restore on top of [MIDIRepository]'s reqId-correlated
 * file ops.
 *
 * **Backup** ([createBackup]): enumerates /sounds via [MIDIRepository.listSoundEntries], downloads
 * each file with the multi-chunk [MIDIRepository.getFileBytes], and assembles a ZIP (.pak) of the
 * files plus a metadata.json. (The old single-response-per-file model truncated anything bigger
 * than one chunk — real samples exceed one chunk, so backups used to contain only metadata.json.)
 *
 * **Restore** ([restore]): validates the ZIP, then re-creates each file in /sounds via the
 * paged, per-page-acked [MIDIRepository.putSampleFile] (no more fire-and-forget). Restore is gated
 * off in the UI pending hardware UAT; per-file channel/samplerate metadata is not yet captured, so
 * restored files default to the device-native mono / 46875 Hz.
 */
class BackupManager(private val midi: MIDIRepository) {

    /** Suggested backup filename: `EP133-YYYY-MM-DD-HHmm.pak`. */
    fun suggestedBackupFilename(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
        return "EP133-${fmt.format(Date())}.pak"
    }

    /**
     * Create a backup of the EP-133's /sounds directory as a .pak (ZIP) archive.
     *
     * Emits [BackupProgress.Progress] per file downloaded, [BackupProgress.Done] with the completed
     * ZIP bytes, or [BackupProgress.Error] on failure.
     */
    fun createBackup(): Flow<BackupProgress> = flow {
        emit(BackupProgress.Progress(0, 0))

        if (midi.deviceState.value.outputPortId == null) {
            emit(BackupProgress.Error("No EP-133 connected"))
            return@flow
        }

        // Enumerate /sounds via the reqId-correlated listing (replaces the old 5s collect window).
        val entries = midi.listSoundEntries()
        if (entries.isEmpty()) {
            emit(BackupProgress.Error("No files found on device (or it did not respond)"))
            return@flow
        }

        val total = entries.size
        // LinkedHashMap → preserve device order in the archive.
        val fileData = LinkedHashMap<String, ByteArray>()
        entries.forEachIndexed { index, entry ->
            emit(BackupProgress.Progress(index, total))
            // Multi-chunk download — assembles the whole file, no truncation.
            val bytes = midi.getFileBytes(entry.nodeId)
            if (bytes != null && bytes.isNotEmpty()) {
                fileData[entry.name] = bytes
            } else {
                Log.w(TAG, "skip '${entry.name}' (node ${entry.nodeId}): GET returned no data")
            }
        }

        if (fileData.isEmpty()) {
            emit(BackupProgress.Error("Downloaded 0 of $total files — the device returned no data"))
            return@flow
        }

        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            fileData.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            val metadataJson = buildMetadataJson(fileData.size, midi.deviceState.value)
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadataJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        emit(BackupProgress.Progress(total, total))
        emit(BackupProgress.Done(zipBytes.toByteArray()))
    }

    /**
     * Restore a .pak backup to the EP-133's /sounds directory.
     *
     * Validates the ZIP, then re-creates each entry via the paged, per-page-acked
     * [MIDIRepository.putSampleFile]. Emits [RestoreProgress.Progress] per file, [RestoreProgress.Done]
     * on completion, or [RestoreProgress.Error] on an invalid archive, no connection, or a failed page.
     */
    fun restore(pakBytes: ByteArray): Flow<RestoreProgress> = flow {
        // Validate ZIP magic: PK header [0x50, 0x4B, 0x03, 0x04].
        if (pakBytes.size < 4 ||
            pakBytes[0] != 0x50.toByte() ||
            pakBytes[1] != 0x4B.toByte() ||
            pakBytes[2] != 0x03.toByte() ||
            pakBytes[3] != 0x04.toByte()
        ) {
            emit(RestoreProgress.Error("Invalid PAK file — not a ZIP archive"))
            return@flow
        }

        if (midi.deviceState.value.outputPortId == null) {
            emit(RestoreProgress.Error("No EP-133 connected"))
            return@flow
        }

        // Pass 1: count restorable entries — names only, no file bytes held.
        var total = 0
        ZipInputStream(pakBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name != "metadata.json") total++
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (total == 0) {
            emit(RestoreProgress.Error("Backup contains no sound files"))
            return@flow
        }

        // Pass 2: decompress and upload ONE entry at a time, so only a single file's
        // bytes are in memory at once (PCM blobs can be several MB each).
        var index = 0
        ZipInputStream(pakBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name != "metadata.json") {
                    val name = entry.name
                    emit(RestoreProgress.Progress(index, total))
                    val data = zip.readBytes()
                    val ok = try {
                        // Paged create-PUT with a per-page ack and a closing terminator.
                        midi.putSampleFile(name = name, pcmBytes = data)
                    } catch (e: Exception) {
                        Log.w(TAG, "restore failed on '$name'", e)
                        false
                    }
                    if (!ok) {
                        emit(RestoreProgress.Error("Restore failed on '$name'"))
                        return@flow
                    }
                    index++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        emit(RestoreProgress.Progress(total, total))
        emit(RestoreProgress.Done)
    }

    private fun buildMetadataJson(
        fileCount: Int,
        deviceState: com.ep133.sampletool.domain.model.DeviceState,
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        return """
            {
              "backup_date": "${fmt.format(Date())}",
              "firmware_version": "${deviceState.firmwareVersion ?: "unknown"}",
              "device_name": "${deviceState.deviceName}",
              "file_count": $fileCount
            }
        """.trimIndent()
    }
}
