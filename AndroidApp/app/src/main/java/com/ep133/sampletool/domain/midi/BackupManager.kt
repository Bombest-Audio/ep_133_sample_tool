package com.ep133.sampletool.domain.midi

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "EP133BACKUP"

/**
 * Progress events emitted by [BackupManager.createBackup].
 */
sealed class BackupProgress {
    /** Backup in progress: `current` files downloaded of `total`. */
    data class Progress(val current: Int, val total: Int) : BackupProgress()
    /** Backup complete: [pakBytes] is the ZIP archive ready to write. */
    data class Done(val pakBytes: ByteArray) : BackupProgress()
    /** Backup failed: [message] describes the error. */
    data class Error(val message: String) : BackupProgress()
}

/**
 * Progress events emitted by [BackupManager.restore].
 */
sealed class RestoreProgress {
    /** Restore in progress: `current` files uploaded of `total`. */
    data class Progress(val current: Int, val total: Int) : RestoreProgress()
    /** Restore complete. */
    object Done : RestoreProgress()
    /** Restore failed: [message] describes the error. */
    data class Error(val message: String) : RestoreProgress()
}

/**
 * Orchestrates EP-133 backup and restore using the TE file-transfer protocol.
 *
 * **Backup** (createBackup): enumerates /sounds via FILE_LIST, downloads each WAV via FILE_GET,
 * assembles a ZIP (.pak) archive with WAV files and metadata.json.
 *
 * **Restore** (restore): validates the ZIP, then uploads each entry via FILE_PUT.
 *
 * Phase 2 implementation note: The full chunk-streaming protocol (STATUS_SPECIFIC_SUCCESS_START
 * continuation chunks) is implemented as a simplified single-response-per-file model. Phase 4
 * will add robust multi-chunk handling for large files.
 */
class BackupManager(private val midi: MIDIRepository) {

    /**
     * Generate the suggested backup filename: `EP133-YYYY-MM-DD-HHmm.pak`
     */
    fun suggestedBackupFilename(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
        return "EP133-${fmt.format(Date())}.pak"
    }

    /**
     * Create a backup of the EP-133's /sounds directory as a .pak (ZIP) archive.
     *
     * Emits [BackupProgress.Progress] per file downloaded, [BackupProgress.Done] with
     * the completed ZIP bytes, or [BackupProgress.Error] on failure.
     */
    fun createBackup(deviceId: Int): Flow<BackupProgress> = flow {
        emit(BackupProgress.Progress(0, 0))

        // Collect file list entries via FILE_LIST, waiting up to 5 seconds
        val entries = mutableListOf<MIDIRepository.FileListEntry>()
        val portId = midi.deviceState.value.outputPortId
        if (portId == null) {
            emit(BackupProgress.Error("No EP-133 connected"))
            return@flow
        }

        // Issue FILE_LIST on /sounds
        val listFrame = SysExProtocol.buildFileListFrame(deviceId, "/sounds", requestId = 10)
        midi.sendMidiDirect(portId, listFrame)

        // Collect entries for 5 seconds (STATUS_OK signals end of list)
        withTimeoutOrNull(5_000) {
            midi.fileListEntries.collect { entry ->
                entries.add(entry)
            }
        }

        if (entries.isEmpty()) {
            emit(BackupProgress.Error("No files found on device (or device did not respond)"))
            return@flow
        }

        val totalFiles = entries.size
        val fileData = mutableMapOf<String, ByteArray>()

        entries.forEachIndexed { index, entry ->
            emit(BackupProgress.Progress(index, totalFiles))

            if (entry.path.isBlank()) return@forEachIndexed

            // Issue FILE_GET for each file with a unique request id we can correlate on.
            val requestId = 20 + index
            val getFrame = SysExProtocol.buildFileGetFrame(
                deviceId, entry.path, chunkIndex = 0, requestId = requestId,
            )
            midi.sendMidiDirect(portId, getFrame)

            // Wait for THIS file's chunk, matched by the echoed request id so a late/stray
            // response for another entry can't be mis-bound. Use first { } (not collect {}):
            // collect on a SharedFlow never completes, so the previous version always hit the
            // timeout and returned null — every chunk was dropped and backups contained only
            // metadata.json.
            val chunk = withTimeoutOrNull(3_000) {
                midi.fileChunks.first { it.first == requestId }
            }

            if (chunk != null) {
                val filename = entry.path.substringAfterLast('/')
                fileData[filename] = chunk.second
            } else {
                Log.w(TAG, "Timeout waiting for FILE_GET response for ${entry.path}")
            }
        }

        // Assemble ZIP archive
        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            // Add WAV files
            fileData.forEach { (name, data) ->
                val entryName = if (name.endsWith(".wav", ignoreCase = true)) name else "$name.wav"
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(data)
                zip.closeEntry()
            }

            // Add metadata.json with device info
            val metadataJson = buildMetadataJson(
                fileCount = fileData.size,
                deviceState = midi.deviceState.value,
            )
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadataJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        emit(BackupProgress.Progress(totalFiles, totalFiles))
        emit(BackupProgress.Done(zipBytes.toByteArray()))
    }

    /**
     * Restore a .pak backup to the EP-133.
     *
     * Validates the ZIP magic bytes, then sends each entry as FILE_PUT frames.
     *
     * Emits [RestoreProgress.Progress] per file uploaded, [RestoreProgress.Done] on completion,
     * or [RestoreProgress.Error] on invalid file or connection error.
     */
    fun restore(pakBytes: ByteArray, deviceId: Int): Flow<RestoreProgress> = flow {
        // Validate ZIP magic: PK header [0x50, 0x4B, 0x03, 0x04]
        if (pakBytes.size < 4 ||
            pakBytes[0] != 0x50.toByte() ||
            pakBytes[1] != 0x4B.toByte() ||
            pakBytes[2] != 0x03.toByte() ||
            pakBytes[3] != 0x04.toByte()
        ) {
            emit(RestoreProgress.Error("Invalid PAK file — not a ZIP archive"))
            return@flow
        }

        val portId = midi.deviceState.value.outputPortId
        if (portId == null) {
            emit(RestoreProgress.Error("No EP-133 connected"))
            return@flow
        }

        // Parse ZIP entries
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(pakBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name != "metadata.json") {
                    val data = zip.readBytes()
                    entries.add(entry.name to data)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val totalFiles = entries.size
        entries.forEachIndexed { index, (name, data) ->
            emit(RestoreProgress.Progress(index, totalFiles))

            val path = "/sounds/$name"
            val putFrame = SysExProtocol.buildFilePutFrame(
                deviceId, path, data, chunkIndex = 0, requestId = 30 + index,
            )
            midi.sendMidiDirect(portId, putFrame)

            // Brief delay between file puts to avoid overwhelming the device
            kotlinx.coroutines.delay(50)
        }

        emit(RestoreProgress.Progress(totalFiles, totalFiles))
        emit(RestoreProgress.Done)
    }

    private fun buildMetadataJson(fileCount: Int, deviceState: com.ep133.sampletool.domain.model.DeviceState): String {
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

/** Internal helper: send raw MIDI bytes directly via the MIDI port. */
private fun MIDIRepository.sendMidiDirect(portId: String, data: ByteArray) {
    // sendRawBytes already looks up outputPortId internally, but we have portId here.
    // Use sendRawBytes for simplicity — it re-checks outputPortId which is fine.
    sendRawBytes(data)
}
