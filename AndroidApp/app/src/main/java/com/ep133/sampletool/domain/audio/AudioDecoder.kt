package com.ep133.sampletool.domain.audio

import android.content.Context
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
     * @param context Android [Context] to access the content resolver.
     * @param uri     SAF content:// URI from [ActivityResultContracts.OpenMultipleDocuments].
     * @return        [DecodedPcm] containing the raw PCM samples, source rate, and channel count.
     * @throws IOException on decode failure or oversized PCM accumulation.
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

            // Configure and start the decoder.
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmBytes = drainDecoder(codec, extractor)
            val pcm = bytesToShortArray(pcmBytes)

            DecodedPcm(pcm, srcRate, channels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "AudioDecoder failed for $uri: ${e.message ?: e}"
            Log.e(TAG, msg, e)
            throw IOException(msg, e)
        } finally {
            codec?.stop()
            codec?.release()
            extractor.release()
            pfd.close()
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
     * Drain [codec] using [extractor] for input until [MediaCodec.BUFFER_FLAG_END_OF_STREAM].
     *
     * Accumulates raw PCM output bytes into a [ByteArrayOutputStream] bounded by
     * [MAX_PCM_BYTES] (T-05-03-01). Returns the raw PCM bytes in the codec's native
     * format (should be 16-bit PCM for most platform decoders at API 29+).
     *
     * @throws IOException if the accumulated PCM size exceeds [MAX_PCM_BYTES].
     */
    private fun drainDecoder(codec: MediaCodec, extractor: MediaExtractor): ByteArray {
        val TIMEOUT_US = 10_000L   // 10 ms per buffer operation
        val info = MediaCodec.BufferInfo()
        val out = ByteArrayOutputStream(64 * 1024)
        var inputDone = false

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
                        return out.toByteArray()
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Format change notification — no action needed (format already read).
                    Log.d(TAG, "AudioDecoder: output format changed")
                }
                // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED — just loop.
            }
        }
    }

    /**
     * Convert raw PCM bytes (assumed 16-bit little-endian from MediaCodec) to a [ShortArray].
     * MediaCodec PCM output is always 16-bit LE at API 29+ for standard audio formats.
     */
    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}
