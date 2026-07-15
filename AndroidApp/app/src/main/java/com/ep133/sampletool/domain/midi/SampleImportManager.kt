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
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "EP133APP"

/** Target device sample rate — must match WavEncoder.DEVICE_SAMPLE_RATE. */
private const val DEVICE_SAMPLE_RATE = 46875

/**
 * Result of [SampleImportManager.convert]: raw s16 LE PCM bytes ready for upload,
 * plus the format parameters needed for the PUT INIT metadata JSON.
 *
 * [pcm] is interleaved little-endian signed 16-bit PCM — NO RIFF/WAV header.
 * [channels] is 1 (mono) or 2 (stereo).
 * [sampleRate] is always [DEVICE_SAMPLE_RATE] (46875) after conversion.
 */
data class ConvertedSample(
    val pcm: ByteArray,
    val channels: Int,
    val sampleRate: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConvertedSample) return false
        return channels == other.channels && sampleRate == other.sampleRate &&
            pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + channels
        result = 31 * result + sampleRate
        return result
    }
}

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
 * Entry points:
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
 * - T-05-03-04: [importSample] reads bytes inside the caller's picker-callback grant (content://
 *   URI read permission is only valid for the lifetime of that callback — never defer the read).
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
     * Reads must happen inside the picker-callback grant — never defer (the content:// read
     * permission is only valid for the callback's lifetime).
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

        val converted = try {
            convert(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: convert failed for $safeName", e)
            emit(SampleImportProgress.Error("Convert failed: ${e.message ?: e}"))
            return@flow
        }

        if (!preflightStorage(converted.pcm.size)) {
            emit(SampleImportProgress.Error("Not enough space on EP-133 for $safeName"))
            return@flow
        }

        val nodeId = try {
            uploadMutex.withLock {
                midi.putSampleFile(safeName, converted.pcm, converted.channels, converted.sampleRate)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: upload failed for $safeName", e)
            emit(SampleImportProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        if (nodeId != null) {
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
     * If [wavBytes] is a valid WAV container, the data chunk is sliced out and channels/sampleRate
     * are extracted from the fmt chunk. Otherwise the bytes are treated as raw PCM with
     * device-format defaults (46875 Hz, 1 channel) so test doubles remain simple.
     *
     * @param rawName  Filename to sanitize + upload under (e.g. "kick.wav").
     * @param wavBytes Pre-read WAV bytes; format is sniffed via [WavEncoder.isAlreadyDeviceFormat].
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

        // Extract raw PCM + format parameters from the byte array.
        // If it looks like a WAV, slice out the data chunk. Otherwise treat as raw PCM.
        val converted: ConvertedSample = if (WavEncoder.isAlreadyDeviceFormat(wavBytes)) {
            sliceWavData(wavBytes) ?: ConvertedSample(wavBytes, 1, DEVICE_SAMPLE_RATE)
        } else {
            // Non-WAV or invalid: pass raw bytes as-is with device defaults (test doubles use this).
            ConvertedSample(wavBytes, 1, DEVICE_SAMPLE_RATE)
        }

        if (!preflightStorage(converted.pcm.size)) {
            emit(SampleImportProgress.Error("Not enough space on EP-133 for $safeName"))
            return@flow
        }

        val nodeId = try {
            uploadMutex.withLock {
                midi.putSampleFile(safeName, converted.pcm, converted.channels, converted.sampleRate)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SampleImportManager: upload failed for $safeName", e)
            emit(SampleImportProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        if (nodeId != null) {
            emit(SampleImportProgress.Progress(1, 1))
            emit(SampleImportProgress.Done(safeName))
        } else {
            emit(SampleImportProgress.Error(
                "Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry $safeName"
            ))
        }
    }

    /**
     * Convert a content:// audio URI to raw s16 LE PCM bytes ready for device upload.
     *
     * Must be called inside the picker-callback grant for [uri]. Runs the full convert pipeline:
     *  - Fast path: if the file is already WAV/s16/46875/(1|2)ch → slice out the data chunk
     *    (strip the RIFF header) and return the raw PCM bytes + fmt parameters.
     *  - Slow path: [AudioDecoder.decode] → [Resampler.toRate] → convert ShortArray to
     *    little-endian bytes; channels and sampleRate are read from the decoded result.
     *
     * The returned [ConvertedSample.pcm] contains NO RIFF/WAV header — only interleaved
     * s16 LE PCM samples. [ConvertedSample.sampleRate] is always [DEVICE_SAMPLE_RATE] (46875).
     *
     * Runs under [Dispatchers.IO] for the initial byte read; [AudioDecoder] switches to
     * its own IO context for the decode loop.
     *
     * @param enforceMaxLength when true (single-sample import), reject sources longer than the
     *   device's 20 s single-sample cap up front. The loop chopper passes **false**: it needs the
     *   full-length PCM to slice, and each resulting slice is size-checked against the device's
     *   per-sample byte budget before upload, so the whole-loop cap must not apply here.
     */
    suspend fun convert(
        context: Context,
        uri: Uri,
        enforceMaxLength: Boolean = true,
    ): ConvertedSample =
        withContext(Dispatchers.IO) {
            // Read raw bytes inside the grant (content:// URI read permission is callback-scoped).
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("Cannot read URI: $uri")

            if (WavEncoder.isAlreadyDeviceFormat(rawBytes)) {
                // Pass-through fast path: already 46875/s16 — slice header, keep raw PCM.
                val sliced = sliceWavData(rawBytes)
                    ?: throw java.io.IOException("Failed to slice WAV data chunk from device-format file")
                return@withContext sliced
            }

            // Slow path: decode → validate → resample → encode as raw PCM bytes.
            withContext(Dispatchers.Default) {
                val (pcm, srcRate, channels) = AudioDecoder.decode(context, uri)

                // Device input guards (verified from data/index.js):
                //   - source sample rate must be in [3000, 768000] Hz.
                //   - total duration must not exceed 20.0 seconds.
                // These throw IllegalArgumentException, which importSample's existing
                // catch(e: Exception) turns into an Error("Convert failed: …") row.
                if (srcRate < 3000 || srcRate > 768_000) {
                    throw IllegalArgumentException("invalid sample rate: $srcRate Hz (must be 3000–768000)")
                }
                val durationSec = (pcm.size / channels).toDouble() / srcRate
                if (enforceMaxLength && durationSec > 20.0) {
                    throw IllegalArgumentException(
                        "max sample length is 20 seconds (was %.1fs)".format(durationSec)
                    )
                }

                val resampled = Resampler.toRate(pcm, srcRate, DEVICE_SAMPLE_RATE, channels)
                ConvertedSample(
                    pcm = shortArrayToLeBytes(resampled),
                    channels = channels,
                    sampleRate = DEVICE_SAMPLE_RATE,
                )
            }
        }

    /**
     * Convert a [ShortArray] of s16 samples to interleaved little-endian PCM bytes.
     *
     * Each Short is written as 2 bytes, low byte first (little-endian, as required by the
     * EP-133). The output has no header — pure raw PCM.
     *
     * Pure and unit-testable without Android deps.
     */
    internal fun shortArrayToLeBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    /**
     * Locate the RIFF WAV `data` sub-chunk in [wavBytes] and return a [ConvertedSample]
     * containing only the raw PCM bytes (header stripped), plus channels and sampleRate
     * read from the `fmt ` chunk.
     *
     * This handles non-standard WAV files where the `data` sub-chunk is not at the canonical
     * offset 36 (e.g. files with LIST/INFO metadata inserted before the data chunk). If the
     * file passes [WavEncoder.isAlreadyDeviceFormat] the `fmt ` chunk is guaranteed to exist
     * at offset 12; we scan forward from there for the `data` four-CC.
     *
     * Returns `null` if the `data` chunk cannot be located (malformed header).
     *
     * Pure and unit-testable without Android deps.
     */
    internal fun sliceWavData(wavBytes: ByteArray): ConvertedSample? {
        // Minimum: RIFF(4) + size(4) + WAVE(4) + fmt (4) + fmtSize(4) + 16 bytes of fmt body = 36
        if (wavBytes.size < 36) return null
        return try {
            val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Skip RIFF(4) + chunkSize(4) + WAVE(4) = 12 bytes → now at fmt  sub-chunk
            buf.position(12)

            // Read fmt  four-CC
            val fmtTag = ByteArray(4).also { buf.get(it) }
            if (!fmtTag.contentEquals("fmt ".toByteArray(Charsets.US_ASCII))) return null

            val fmtSize = buf.int          // typically 16 for PCM
            val fmtStart = buf.position()  // start of fmt body
            // audioFormat (skip — already validated by isAlreadyDeviceFormat)
            buf.short
            val channels = buf.short.toInt() and 0xFFFF
            val sampleRate = buf.int

            // Skip past the rest of the fmt  sub-chunk to the next sub-chunk
            buf.position(fmtStart + fmtSize)

            // Scan forward for the `data` four-CC (handles extra sub-chunks like LIST/INFO)
            var dataOffset = -1
            var dataSize = 0
            while (buf.remaining() >= 8) {
                val tag = ByteArray(4).also { buf.get(it) }
                val chunkSize = buf.int
                if (tag.contentEquals("data".toByteArray(Charsets.US_ASCII))) {
                    dataOffset = buf.position()
                    dataSize = chunkSize
                    break
                }
                // Skip unknown sub-chunk (align to 2 bytes per RIFF spec)
                val skip = chunkSize + (chunkSize and 1)
                buf.position(buf.position() + skip)
            }

            if (dataOffset < 0 || dataSize <= 0) return null
            val end = minOf(dataOffset + dataSize, wavBytes.size)
            val pcm = wavBytes.copyOfRange(dataOffset, end)
            ConvertedSample(pcm = pcm, channels = channels, sampleRate = sampleRate)
        } catch (_: Exception) {
            null
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
    fun sanitizeName(rawName: String): String? = Companion.sanitizeName(rawName)

    companion object {
        /** Maximum device-safe basename length (excludes the ".wav" extension). */
        const val MAX_BASENAME_LEN = 32

        /** Static form of [SampleImportManager.sanitizeName] - same contract, no instance needed
         *  (shared with [ChordBakeManager] so baked names get the identical T-05-03-02 treatment). */
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
    }

    /**
     * Pre-flight: check that [wavSize] bytes fit in the remaining /sounds storage.
     *
     * Reads [MIDIRepository.deviceState] for storageUsedBytes / storageTotalBytes (populated
     * by [MIDIRepository.queryDeviceStats] via FILE_METADATA on /sounds). If storage info
     * is not yet available, allows the upload (best-effort — the device will reject if full).
     *
     * Never start a write that would overflow device storage.
     */
    private fun preflightStorage(wavSize: Int): Boolean {
        val state = midi.deviceState.value
        val used = state.storageUsedBytes ?: return true   // unknown → allow
        val total = state.storageTotalBytes ?: return true
        val available = total - used
        return available >= wavSize
    }
}
