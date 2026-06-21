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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Concurrency: device uploads are serialized via [uploadMutex]. Concurrent batch imports
 * (e.g. N files launched in parallel from [onFilesPicked]) queue at the device-transfer
 * step rather than colliding with [MIDIRepository]'s single-in-flight-transfer guard.
 * Decode/convert still overlaps across files for throughput — only the [MIDIRepository.putSampleFile]
 * call is held under the mutex.
 *
 * Security (T-05-03):
 * - T-05-03-02: name is sanitized to a safe basename + ".wav" before any device write.
 * - T-05-03-03: converted size is pre-flighted against available /sounds storage.
 * - T-05-03-04: [importSample] reads bytes inside the caller's picker-callback grant (Landmine 7).
 */
class SampleImportManager(private val midi: MIDIRepository) {

    /**
     * Serializes device-upload calls so concurrent batch imports queue instead of
     * colliding with [MIDIRepository]'s single-in-flight-transfer guard.
     */
    private val uploadMutex = Mutex()

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
            uploadMutex.withLock { midi.putSampleFile(safeName, wavBytes) }
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
            emit(SampleImportProgress.Error(
                "Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry $safeName"
            ))
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
            uploadMutex.withLock { midi.putSampleFile(safeName, wavBytes) }
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
            emit(SampleImportProgress.Error(
                "Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry $safeName"
            ))
        }
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
     * Sanitize [rawName] to a device-safe basename + ".wav" for /sounds.
     *
     * Produces a pure-ASCII basename safe for `SysExProtocol.buildFilePutFrame`, which
     * encodes the destination path with `Charsets.US_ASCII`. Non-ASCII characters (e.g.
     * accented letters, emoji) would otherwise be lossily mangled into `?` bytes, corrupting
     * the on-device `/sounds/<name>` path (T-05-03-02 path-traversal + encoding mitigation).
     *
     * Steps:
     * 1. Extract the basename: strips any leading path components (`/` or `\` separated)
     *    then drops the file extension.
     * 2. Replace every character NOT in `[A-Za-z0-9 _-]` with `_` — makes the result pure
     *    ASCII and losslessly encodable. This subsumes the old `/`, `\`, `..`, control-char
     *    rejection; those characters all become `_`.
     * 3. Collapse runs of whitespace to a single space, then `trim()` leading/trailing
     *    whitespace and trim leading/trailing `.`, `_`, `-` for a clean device name.
     * 4. Truncate the basename to [MAX_BASENAME_LEN] characters, then re-trim trailing
     *    `_`, `-`, or space that may be exposed at the cut point.
     * 5. Return `null` if the result is empty (caller emits "Invalid sample name").
     * 6. Return `"$base.wav"`.
     *
     * Guaranteed: `sanitizeName("kick.wav") == "kick.wav"`, same for `"snare.wav"` and
     * `"hihat.wav"` (regression contract for existing tests).
     *
     * @param rawName Original filename as received from the SAF picker or caller.
     * @return        Sanitized `"<basename>.wav"` or `null` if no safe name can be derived.
     */
    fun sanitizeName(rawName: String): String? {
        // Step 1: extract the basename (strip path components, then drop extension).
        val withoutPath = rawName.substringAfterLast('/').substringAfterLast('\\')
        val stem = withoutPath.substringBeforeLast('.')

        // Step 2: replace every character outside [A-Za-z0-9 _-] with '_'.
        val replaced = stem.replace(Regex("[^A-Za-z0-9 _\\-]"), "_")

        // Step 3: collapse whitespace runs, trim leading/trailing whitespace and punctuation.
        val collapsed = replaced.replace(Regex("\\s+"), " ").trim()
        val trimmed = collapsed.trim('.', '_', '-').trim()

        // Step 4: cap length, then re-trim any punctuation exposed at the cut point.
        val capped = if (trimmed.length > MAX_BASENAME_LEN) {
            trimmed.substring(0, MAX_BASENAME_LEN).trimEnd('_', '-', ' ')
        } else {
            trimmed
        }

        // Step 5: reject empty result.
        if (capped.isEmpty()) return null

        // Step 6: append device extension.
        return "$capped.wav"
    }

    companion object {
        /** Maximum device-safe basename length (excludes the ".wav" extension). */
        const val MAX_BASENAME_LEN = 32
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
