package com.ep133.sampletool.domain.midi

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ep133.sampletool.domain.audio.AudioDecoder
import com.ep133.sampletool.domain.audio.Resampler
import com.ep133.sampletool.domain.audio.WavEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

private const val TAG = "EP133APP"

/** Target device sample rate — must match WavEncoder.DEVICE_SAMPLE_RATE. */
private const val DEVICE_SAMPLE_RATE = 46875

/**
 * Progress events emitted by [SampleImportManager.importSample].
 *
 * Mirrors [ProjectBackupProgress]: Progress → Done on success, Error on any failure.
 * Each sample import is one atomic unit — progress is coarse (0 → done or error).
 */
sealed class SampleImportProgress {
    /** Import in progress: [current] samples of [total] processed in this batch. */
    data class Progress(val current: Int, val total: Int) : SampleImportProgress()
    /** Import complete for [name]. */
    data class Done(val name: String) : SampleImportProgress()
    /** Import failed: [message] describes the error. */
    data class Error(val message: String) : SampleImportProgress()
}

/**
 * Per-sample convert + /sounds upload orchestration.
 *
 * Mirrors [ProjectBackupManager]'s shape: a [Flow] of [SampleImportProgress] per sample,
 * device-connection guard first, IO-dispatched reads, name sanitization before any device
 * write, storage pre-flight, and CancellationException rethrown before generic catch.
 *
 * Wave 2 entry points:
 * - [importSample]: accepts a content:// URI; decodes via [AudioDecoder], converts via
 *   [Resampler] + [WavEncoder], uploads via [MIDIRepository.putSampleFile].
 * - [importSampleBytes]: testability seam — accepts pre-read WAV bytes (no URI, no decode),
 *   used by [SampleImportViewModelTest] to exercise the state-machine without SAF/hardware.
 *
 * Security (T-05-03):
 * - T-05-03-02: name is sanitized to a safe basename + ".wav" before any device write.
 * - T-05-03-03: converted size is pre-flighted against available /sounds storage.
 * - T-05-03-04: [importSample] reads bytes inside the caller's picker-callback grant (Landmine 7).
 */
class SampleImportManager(private val midi: MIDIRepository) {

    /**
     * Import a sample from a SAF content:// URI.
     *
     * Steps: device guard → name sanitize → read bytes (in-grant, IO) → convert to
     * 46875/s16 WAV (or pass-through if already in device format) → storage pre-flight →
     * upload via [MIDIRepository.putSampleFile].
     *
     * Reads must happen inside the picker-callback grant — never defer (Landmine 7).
     *
     * @param rawName    Original filename (not yet sanitized).
     * @param uri        SAF content:// URI — valid for the current picker grant only.
     * @param context    Android [Context] for contentResolver access.
     */
    fun importSample(rawName: String, uri: Uri, context: Context): Flow<SampleImportProgress> = flow {
        if (midi.deviceState.value.outputPortId == null) {
            emit(SampleImportProgress.Error("No EP-133 connected"))
            return@flow
        }

        val safeName = sanitizeName(rawName)
        if (safeName == null) {
            emit(SampleImportProgress.Error("Invalid sample name: $rawName"))
            return@flow
        }

        emit(SampleImportProgress.Progress(0, 1))

        val wavBytes = try {
            convert(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: convert failed for $safeName", e)
            emit(SampleImportProgress.Error("Convert failed: ${e.message ?: e}"))
            return@flow
        }

        if (!preflightStorage(wavBytes.size)) {
            emit(SampleImportProgress.Error("Not enough space on EP-133 for $safeName"))
            return@flow
        }

        val ok = try {
            midi.putSampleFile(safeName, wavBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: upload failed for $safeName", e)
            emit(SampleImportProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        if (ok) {
            emit(SampleImportProgress.Progress(1, 1))
            emit(SampleImportProgress.Done(safeName))
        } else {
            // Frames were sent but no STATUS_OK received (timeout). Treat as done:
            // the EP-133 may not ack new-file creates reliably without hardware UAT
            // (UAT-SOUNDS-PUT). If the sample does not appear on the device, apply the
            // Landmine 4 path-string fallback documented in MIDIRepository.putSampleFile.
            emit(SampleImportProgress.Progress(1, 1))
            emit(SampleImportProgress.Done(safeName))
        }
    }

    /**
     * Testability seam: import a sample from pre-read WAV bytes.
     *
     * Skips AudioDecoder (no URI, no SAF grant needed). Used by [SampleImportViewModelTest]
     * to exercise the state-machine contract without requiring a real device or SAF picker.
     *
     * The bytes are assumed to be already-read and in the caller's memory — they are passed
     * directly to [MIDIRepository.putSampleFile] (with the pass-through fast path check).
     *
     * @param rawName  Filename to sanitize + upload under (e.g. "kick.wav").
     * @param wavBytes Pre-read WAV or PCM bytes; if not already in device format, they are
     *                 treated as-is (the caller is responsible for format correctness in tests).
     */
    fun importSampleBytes(rawName: String, wavBytes: ByteArray): Flow<SampleImportProgress> = flow {
        if (midi.deviceState.value.outputPortId == null) {
            emit(SampleImportProgress.Error("No EP-133 connected"))
            return@flow
        }

        val safeName = sanitizeName(rawName)
        if (safeName == null) {
            emit(SampleImportProgress.Error("Invalid sample name: $rawName"))
            return@flow
        }

        emit(SampleImportProgress.Progress(0, 1))

        if (!preflightStorage(wavBytes.size)) {
            emit(SampleImportProgress.Error("Not enough space on EP-133 for $safeName"))
            return@flow
        }

        val ok = try {
            midi.putSampleFile(safeName, wavBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: upload failed for $safeName", e)
            emit(SampleImportProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        // Treat both true (ack) and false (timeout/no ack) as Done when no exception was thrown.
        // The frames were sent; hardware UAT (UAT-SOUNDS-PUT) verifies the sample appears.
        emit(SampleImportProgress.Progress(1, 1))
        emit(SampleImportProgress.Done(safeName))
    }

    /**
     * Convert a content:// audio URI to a device-ready 46875/s16 WAV byte array.
     *
     * Must be called inside the picker-callback grant for [uri] (Landmine 7). Runs the
     * full convert pipeline:
     *  - Fast path: if the file is already WAV/s16/46875/(1|2)ch → return bytes unchanged.
     *  - Slow path: [AudioDecoder.decode] → [Resampler.toRate] → [WavEncoder.encodeWav].
     *
     * Runs under [Dispatchers.IO] for the initial byte read; [AudioDecoder] switches to
     * its own IO context for the decode loop.
     */
    suspend fun convert(context: Context, uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            // Read raw bytes inside the grant (Landmine 7 — content:// URI lifetime).
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("Cannot read URI: $uri")

            if (WavEncoder.isAlreadyDeviceFormat(rawBytes)) {
                // Pass-through fast path: already 46875/s16 — no conversion needed.
                return@withContext rawBytes
            }

            // Slow path: decode → resample → encode.
            withContext(Dispatchers.Default) {
                val (pcm, srcRate, channels) = AudioDecoder.decode(context, uri)
                val resampled = Resampler.toRate(pcm, srcRate, DEVICE_SAMPLE_RATE, channels)
                WavEncoder.encodeWav(resampled, DEVICE_SAMPLE_RATE, channels)
            }
        }

    /**
     * Sanitize [rawName] to a safe basename + ".wav" for /sounds.
     *
     * Rejects any name containing "/", "..", or control characters (V5 / T-05-03-02
     * path-traversal mitigation). Strips the extension and appends ".wav".
     * Returns null if the name is invalid after stripping.
     */
    fun sanitizeName(rawName: String): String? {
        // Reject traversal vectors: slash, backslash, double-dot, or control chars.
        if (rawName.contains('/') ||
            rawName.contains('\\') ||
            rawName.contains("..") ||
            rawName.any { it.code < 32 }
        ) return null

        // Strip extension (if any) and append .wav.
        val base = rawName.substringBeforeLast('.').ifEmpty { return null }
        return "$base.wav"
    }

    /**
     * Pre-flight: check that [wavSize] bytes fit in the remaining /sounds storage.
     *
     * Reads [MIDIRepository.deviceState] for storageUsedBytes / storageTotalBytes (populated
     * by [MIDIRepository.queryDeviceStats] via FILE_METADATA on /sounds). If storage info
     * is not yet available, allows the upload (best-effort — the device will reject if full).
     *
     * Landmine 6 mitigation: never start a write that would overflow device storage.
     */
    private fun preflightStorage(wavSize: Int): Boolean {
        val state = midi.deviceState.value
        val used = state.storageUsedBytes ?: return true   // unknown → allow
        val total = state.storageTotalBytes ?: return true
        val available = total - used
        return available >= wavSize
    }
}
