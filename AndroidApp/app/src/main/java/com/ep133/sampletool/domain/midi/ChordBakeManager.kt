package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.domain.audio.voice.KotlinSynthVoice
import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "EP133APP"

/**
 * Progress events emitted by [ChordBakeManager.bake].
 *
 * Mirrors [SampleImportProgress]: staged progress, then Done on success or Error
 * on any failure. Each bake is one atomic unit.
 */
sealed class ChordBakeProgress {
    /** Offline render of the progression is running. */
    object Rendering : ChordBakeProgress()
    /** Render finished; PCM upload to /sounds is in flight. */
    object Uploading : ChordBakeProgress()
    /** Bake complete: sample saved on the device as [name]. */
    data class Done(val name: String) : ChordBakeProgress()
    /** Bake failed: [message] describes the error. */
    data class Error(val message: String) : ChordBakeProgress()
}

/**
 * Bake-to-sample orchestration: render a chord progression offline with a
 * [RenderableVoice] and upload the result to the EP-133's /sounds as a real sample.
 *
 * Mirrors [SampleImportManager]'s shape: a [Flow] of [ChordBakeProgress] per bake,
 * device-connection guard first, name sanitization before any device write, duration
 * and storage pre-flights, and CancellationException rethrown before generic catch.
 *
 * Pipeline: [RenderableVoice.render] (float PCM at 46875 Hz) -> peak guard -> s16 LE
 * bytes -> [MIDIRepository.putSampleFile]. The upload path is unchanged from sample
 * import; a cancelled upload is terminated cleanly by [FileTransferClient]'s existing
 * contract - no wedge handling is duplicated here.
 *
 * Gain safety: float-domain summing is already bounded by the voice's tanh saturator,
 * but a true-peak-ish guard runs anyway - if the rendered peak exceeds 0 dBFS the
 * buffer is normalized down (and logged) before the 16-bit encode, so the encode can
 * never wrap.
 */
class ChordBakeManager(private val midi: MIDIRepository) {

    /**
     * Bake [chords] (per-chord MIDI note lists, one bar each at [bpm]) to a sample
     * named after [rawName] on the connected EP-133.
     *
     * Steps: device guard -> name sanitize -> duration pre-flight (20 s device cap,
     * including the voice's release tail) -> offline render (Default dispatcher) ->
     * peak guard -> 16-bit encode -> storage pre-flight -> upload.
     *
     * @param rawName Desired sample name (not yet sanitized), e.g. the progression name.
     * @param chords  Per-chord MIDI notes, e.g. from [RenderableVoice.chordsOf].
     * @param bpm     Tempo; each chord is one 4/4 bar.
     * @param voice   The voice to render with - the SAME code path as live preview.
     */
    fun bake(
        rawName: String,
        chords: List<List<Int>>,
        bpm: Int,
        voice: RenderableVoice,
    ): Flow<ChordBakeProgress> = flow {
        if (midi.deviceState.value.outputPortId == null) {
            emit(ChordBakeProgress.Error("No EP-133 connected"))
            return@flow
        }

        val safeName = SampleImportManager.sanitizeName(rawName)
        if (safeName == null) {
            emit(ChordBakeProgress.Error("Invalid sample name: $rawName"))
            return@flow
        }

        if (chords.isEmpty()) {
            emit(ChordBakeProgress.Error("Nothing to bake - progression is empty"))
            return@flow
        }

        // Duration pre-flight: bars * (4 beats * 60/bpm) + the voice's release tail
        // must fit the device's 20 s single-sample ceiling. Blocks before rendering.
        val estimatedSec = estimateDurationSec(chords.size, bpm)
        if (estimatedSec > MAX_SAMPLE_SECONDS) {
            emit(ChordBakeProgress.Error(
                "Too long to bake: %.1fs at $bpm BPM (device max is ${MAX_SAMPLE_SECONDS.toInt()}s) - raise the BPM or shorten the progression".format(estimatedSec)
            ))
            return@flow
        }

        emit(ChordBakeProgress.Rendering)

        val pcm = try {
            withContext(Dispatchers.Default) {
                voice.render(chords, bpm, RenderableVoice.DEVICE_SAMPLE_RATE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ChordBakeManager: render failed for $safeName", e)
            emit(ChordBakeProgress.Error("Render failed: ${e.message ?: e}"))
            return@flow
        }

        // Belt-and-suspenders: the estimate above already blocked over-length bakes,
        // but never upload a buffer the device would reject.
        val actualSec = pcm.size.toDouble() / RenderableVoice.DEVICE_SAMPLE_RATE
        if (actualSec > MAX_SAMPLE_SECONDS) {
            emit(ChordBakeProgress.Error(
                "Rendered audio is %.1fs - over the ${MAX_SAMPLE_SECONDS.toInt()}s device max".format(actualSec)
            ))
            return@flow
        }

        // True-peak-ish guard: assert peak <= 0 dBFS post-render; normalize down if over.
        val safePcm = normalizeIfOverFullScale(pcm, safeName)

        val pcmBytes = floatToPcm16Le(safePcm)

        if (!preflightStorage(pcmBytes.size)) {
            emit(ChordBakeProgress.Error("Not enough space on EP-133 for $safeName"))
            return@flow
        }

        emit(ChordBakeProgress.Uploading)

        val nodeId = try {
            midi.putSampleFile(safeName, pcmBytes, CHANNELS, RenderableVoice.DEVICE_SAMPLE_RATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ChordBakeManager: upload failed for $safeName", e)
            emit(ChordBakeProgress.Error("Upload failed: ${e.message ?: e}"))
            return@flow
        }

        if (nodeId != null) {
            emit(ChordBakeProgress.Done(safeName))
        } else {
            emit(ChordBakeProgress.Error(
                "Upload not confirmed by EP-133 (no STATUS_OK) - reconnect and retry $safeName"
            ))
        }
    }

    /**
     * If any sample in [pcm] exceeds full scale (|x| > 1.0, i.e. over 0 dBFS),
     * return a copy normalized so the peak lands exactly at full scale, and log
     * the correction. Otherwise return [pcm] unchanged.
     *
     * Pure and unit-testable without Android deps (Log is stubbed on the JVM runner).
     */
    internal fun normalizeIfOverFullScale(pcm: FloatArray, name: String): FloatArray {
        var peak = 0f
        for (s in pcm) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        if (peak <= 1f) return pcm
        val scale = 1f / peak
        Log.w(TAG, "ChordBakeManager: $name rendered %.2f dB over full scale - normalizing down"
            .format(20.0 * Math.log10(peak.toDouble())))
        return FloatArray(pcm.size) { pcm[it] * scale }
    }

    /**
     * Convert float PCM in [-1, 1] to interleaved little-endian signed 16-bit bytes
     * (no RIFF header - [MIDIRepository.putSampleFile] takes raw PCM). Values are
     * clamped defensively; the peak guard has already bounded the buffer.
     */
    internal fun floatToPcm16Le(pcm: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            val clamped = s.coerceIn(-1f, 1f)
            buf.putShort((clamped * Short.MAX_VALUE).roundToInt().toShort())
        }
        return buf.array()
    }

    /**
     * Pre-flight: check that [pcmSize] bytes fit in the remaining /sounds storage.
     * Same pattern as [SampleImportManager]: unknown storage info allows the upload
     * (best-effort - the device rejects if full).
     */
    private fun preflightStorage(pcmSize: Int): Boolean {
        val state = midi.deviceState.value
        val used = state.storageUsedBytes ?: return true
        val total = state.storageTotalBytes ?: return true
        return total - used >= pcmSize
    }

    companion object {
        /** EP-133 single-sample duration ceiling (seconds). */
        const val MAX_SAMPLE_SECONDS = 20.0

        /** Bakes are mono - every [RenderableVoice] renders mono float PCM. */
        private const val CHANNELS = 1

        /**
         * Estimated bake duration in seconds: [bars] 4/4 bars at [bpm] plus the
         * voice release tail appended by [RenderableVoice.render].
         */
        fun estimateDurationSec(bars: Int, bpm: Int): Double {
            require(bpm > 0) { "bpm must be > 0, was $bpm" }
            return bars * 4.0 * 60.0 / bpm + KotlinSynthVoice.RELEASE_TAIL_SECONDS
        }
    }
}
