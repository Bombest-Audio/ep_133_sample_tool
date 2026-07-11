package com.ep133.sampletool.domain.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val TAG = "EP133APP"

/**
 * Decodes an arbitrary audio file to raw 16-bit PCM via [MediaExtractor] + [MediaCodec].
 *
 * Supports every format MediaCodec handles at API 29+: WAV, MP3, AAC, FLAC, OGG. These
 * cover all common Splice export formats (05-RESEARCH A5).
 *
 * // HARDWARE/INSTRUMENTATION-VERIFY (UAT-DECODE): MediaCodec cannot be exercised in
 * // pure-JVM unit tests — it requires the Android runtime on a real device or an
 * // instrumented-test environment. The WavEncoder + Resampler unit tests cover the
 * // conversion math; this class carries the hardware-bound decode step. See UAT-DECODE
 * // in 05-HUMAN-UAT.md for the manual verification checklist.
 *
 * Threat mitigations:
 * - T-05-03-01 (DoS — huge file): accumulated PCM is bounded at [MAX_PCM_BYTES]; exceeding
 *   this cap throws [IOException] rather than OOMing. The decode loop runs under
 *   [Dispatchers.IO] so a slow/huge file cannot block the main thread.
 * - T-05-03-04 (SAF URI expiry): bytes are read inside [decode], which must be called
 *   during the picker-callback grant (Landmine 7). The caller must NOT persist the URI.
 *
 * No new dependencies — [MediaCodec] and [MediaExtractor] are platform APIs (API 29+).
 */
object AudioDecoder {

    /**
     * Decoded PCM result from a content:// audio URI.
     *
     * @param pcm        Raw signed 16-bit PCM samples, interleaved for stereo.
     * @param sampleRate Original source sample rate in Hz (e.g. 44100, 48000).
     * @param channels   Number of audio channels (1 = mono, 2 = stereo).
     */
    data class DecodedPcm(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

    /**
     * Maximum accumulated PCM bytes before aborting (T-05-03-01 DoS cap).
     * 2 min stereo at 48 kHz × 16-bit ≈ 46 MB; 64 MB is a generous ceiling that
     * still prevents an OOM from a malformed/huge file.
     */
    private const val MAX_PCM_BYTES = 64 * 1024 * 1024   // 64 MB

    /**
     * Decode the audio file at [uri] to raw 16-bit PCM.
     *
     * Must be called while the SAF picker grant for [uri] is still active (Landmine 7).
     * Runs under [Dispatchers.IO] — safe to call from any coroutine scope.
     *
     * PCM encoding: MediaCodec output is NOT guaranteed to be 16-bit at API 29+. FLAC and
     * HD-audio decoders can emit ENCODING_PCM_FLOAT or ENCODING_PCM_24BIT_PACKED. The output
     * format is read on INFO_OUTPUT_FORMAT_CHANGED and tracked across the drain loop; all
     * output bytes are converted to s16 via [pcmBytesToShorts] at the end.
     *
     * @param context Android [Context] to access the content resolver.
     * @param uri     SAF content:// URI from [ActivityResultContracts.OpenMultipleDocuments].
     * @return        [DecodedPcm] containing the raw PCM samples, source rate, and channel count.
     * @throws IOException on decode failure, oversized PCM accumulation, or unsupported encoding.
     * @throws CancellationException if the coroutine is cancelled — always rethrown.
     */
    suspend fun decode(context: Context, uri: Uri): DecodedPcm = withContext(Dispatchers.IO) {
        // Open inside the grant — FileDescriptor must be consumed before returning (Landmine 7).
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open content URI: $uri")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return@withContext try {
            extractor.setDataSource(pfd.fileDescriptor)

            // Select the first audio track.
            val (trackIndex, format) = selectAudioTrack(extractor)
                ?: throw IOException("No audio track found in URI: $uri")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type")
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "AudioDecoder: mime=$mime srcRate=$srcRate channels=$channels")

            // Request 16-bit output where supported (a hint, not a guarantee — FLAC may ignore it).
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            // Read the initial output PCM encoding from the input format (may change later).
            var pcmEncoding = readPcmEncoding(format)

            // Configure and start the decoder.
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val (pcmBytes, finalEncoding) = drainDecoder(codec, extractor, pcmEncoding)
            val pcm = pcmBytesToShorts(pcmBytes, finalEncoding)

            DecodedPcm(pcm, srcRate, channels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "AudioDecoder failed for $uri: ${e.message ?: e}"
            Log.e(TAG, msg, e)
            throw IOException(msg, e)
        } finally {
            // Each cleanup call is isolated: MediaCodec.stop() can throw in several
            // documented error states, and a throw here must not skip release()/close()
            // on the very error path this block exists to guard.
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { pfd.close() }
        }
    }

    /**
     * Scan all tracks in [extractor] and return the first audio track's index + format.
     * Returns null if no audio track is found.
     */
    private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to format
        }
        return null
    }

    /**
     * Read the PCM encoding from a [MediaFormat]. Returns [AudioFormat.ENCODING_PCM_16BIT]
     * if the key is absent (API 29 guarantee for standard formats like MP3/AAC).
     */
    private fun readPcmEncoding(fmt: MediaFormat): Int =
        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else
            AudioFormat.ENCODING_PCM_16BIT

    /**
     * Drain [codec] using [extractor] for input until [MediaCodec.BUFFER_FLAG_END_OF_STREAM].
     *
     * Accumulates raw PCM output bytes into a [ByteArrayOutputStream] bounded by
     * [MAX_PCM_BYTES] (T-05-03-01). Tracks [INFO_OUTPUT_FORMAT_CHANGED] events and updates
     * [currentEncoding] to reflect the actual output encoding (FLAC/HD decoders may emit
     * float or 24-bit rather than 16-bit even when 16-bit was requested).
     *
     * Returns a pair of (raw PCM bytes, final encoding constant).
     *
     * @throws IOException if the accumulated PCM size exceeds [MAX_PCM_BYTES].
     */
    private fun drainDecoder(
        codec: MediaCodec,
        extractor: MediaExtractor,
        initialEncoding: Int,
    ): Pair<ByteArray, Int> {
        val TIMEOUT_US = 10_000L   // 10 ms per buffer operation
        val info = MediaCodec.BufferInfo()
        val out = ByteArrayOutputStream(64 * 1024)
        var inputDone = false
        var currentEncoding = initialEncoding

        while (true) {
            // Feed input buffers while the extractor has data.
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inputBuf: ByteBuffer = codec.getInputBuffer(inIdx)
                        ?: throw IOException("Null input buffer at index $inIdx")
                    inputBuf.clear()
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        // End of stream — signal the codec.
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output buffers.
            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    val outputBuf: ByteBuffer = codec.getOutputBuffer(outIdx)
                        ?: throw IOException("Null output buffer at index $outIdx")
                    val size = info.size
                    if (size > 0) {
                        // Bounds check before accumulation (T-05-03-01 DoS cap).
                        if (out.size() + size > MAX_PCM_BYTES) {
                            codec.releaseOutputBuffer(outIdx, false)
                            throw IOException(
                                "AudioDecoder: decoded PCM exceeds ${MAX_PCM_BYTES / (1024 * 1024)} MB cap — aborting"
                            )
                        }
                        val chunk = ByteArray(size)
                        outputBuf.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return out.toByteArray() to currentEncoding
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Re-read PCM encoding from the actual output format (FLAC/HD may differ).
                    currentEncoding = readPcmEncoding(codec.outputFormat)
                    Log.d(TAG, "AudioDecoder: output format changed, pcmEncoding=$currentEncoding")
                }
                // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED — just loop.
            }
        }
    }

}

/**
 * Convert raw PCM bytes from MediaCodec output to a [ShortArray] of signed 16-bit samples.
 *
 * Supported encodings:
 * - [AudioFormat.ENCODING_PCM_16BIT]: LE Int16 — read directly.
 * - [AudioFormat.ENCODING_PCM_FLOAT]: LE Float32 — scale to [-32767, 32767] with ±1.0 clamping.
 * - [AudioFormat.ENCODING_PCM_24BIT_PACKED]: 3-byte LE — downshift to 16-bit (drop low byte).
 * - [AudioFormat.ENCODING_PCM_32BIT]: 4-byte LE — downshift to 16-bit (drop low 2 bytes).
 *
 * Pure, JVM-testable function: no Android runtime dependencies beyond the [AudioFormat]
 * constants (which are plain Int values). Top-level function in this package — call it
 * directly as `pcmBytesToShorts(...)` (import it by name), including from test code.
 *
 * @param bytes    Raw PCM bytes from MediaCodec output.
 * @param encoding One of the [AudioFormat].ENCODING_PCM_* constants.
 * @throws IOException for any unrecognized encoding constant.
 */
fun pcmBytesToShorts(bytes: ByteArray, encoding: Int): ShortArray {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> {
            val shorts = ShortArray(bytes.size / 2)
            buf.asShortBuffer().get(shorts)
            shorts
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val count = bytes.size / 4
            val floatBuf = buf.asFloatBuffer()
            ShortArray(count) {
                (floatBuf.get().coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            // 3 bytes per sample, LE: [lo, mid, hi]. Shift right 8 bits → Int16.
            val count = bytes.size / 3
            ShortArray(count) { i ->
                val lo  = bytes[i * 3].toInt() and 0xFF
                val mid = bytes[i * 3 + 1].toInt() and 0xFF
                val hi  = bytes[i * 3 + 2].toInt()    // signed for sign extension
                val s32 = (hi shl 16) or (mid shl 8) or lo
                (s32 shr 8).toShort()
            }
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            // 4 bytes per sample, LE Int32. Shift right 16 bits → Int16.
            val count = bytes.size / 4
            val intBuf = buf.asIntBuffer()
            ShortArray(count) { (intBuf.get() shr 16).toShort() }
        }
        else -> throw IOException("unsupported PCM encoding: $encoding")
    }
}
