package com.ep133.sampletool.domain.midi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Offline MIDI synthesizer using AudioTrack.
 *
 * Produces a soft pad tone from three sine harmonics (fundamental + 2nd + 3rd) with a
 * simple attack→decay→sustain envelope. Used as a fallback when no EP-133 is connected.
 *
 * Each note gets its own [AudioTrack] coroutine so multiple notes play simultaneously
 * for polyphonic chord preview. Note: one AudioTrack per note is intentional for chord
 * preview (3–4 simultaneous notes). Not suitable for rapid-fire arpeggios — AudioTrack
 * creation latency (~5ms) would be audible.
 */
class LocalSynth : SynthEngine {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    // ConcurrentHashMap guards against concurrent noteOn/noteOff from different threads.
    private val activeJobs = ConcurrentHashMap<Int, Job>()

    private val SAMPLE_RATE = 44100

    /** Start playing a note. Stops any previous playback on the same pitch first. */
    override fun noteOn(note: Int, velocity: Int) {
        noteOff(note)
        val freq = 440.0 * 2.0.pow((note - 69) / 12.0)
        val amp = (velocity / 127.0) * 0.6
        activeJobs[note] = scope.launch { playSustained(freq, amp) }
    }

    /** Stop a specific note. */
    override fun noteOff(note: Int) {
        activeJobs.remove(note)?.cancel()
    }

    /** Stop all currently playing notes immediately. */
    override fun allNotesOff() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private suspend fun playSustained(freq: Double, amp: Double) {
        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .build()

        try {
            track.play()
            val buf = ShortArray(bufSize / 2)
            var phase = 0.0
            var totalSamples = 0L
            val attackSamples = (SAMPLE_RATE * 0.01).toLong()   // 10ms attack
            val decaySamples = (SAMPLE_RATE * 2.0).toLong()     // 2s decay to sustain level

            while (currentCoroutineContext().isActive) {
                for (i in buf.indices) {
                    val t = totalSamples + i
                    // Envelope: attack → decay → sustain at 0.5 amplitude
                    val envAmp = when {
                        t < attackSamples -> t.toDouble() / attackSamples
                        t < attackSamples + decaySamples -> {
                            val decayProgress = (t - attackSamples).toDouble() / decaySamples
                            1.0 - decayProgress * 0.5
                        }
                        else -> 0.5
                    }
                    // Additive synthesis: fundamental + soft 2nd + 3rd harmonic (organ-like pad)
                    val sample = amp * envAmp * (
                        0.6 * sin(phase) +
                        0.25 * sin(phase * 2) +
                        0.1 * sin(phase * 3)
                    )
                    buf[i] = (sample * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    phase += 2.0 * PI * freq / SAMPLE_RATE
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
                track.write(buf, 0, buf.size)
                totalSamples += buf.size
            }
        } catch (_: CancellationException) {
            // Expected when noteOff() cancels this coroutine
        } finally {
            track.stop()
            track.release()
        }
    }

    /** Release all resources. Call when the synth is no longer needed. */
    override fun close() {
        allNotesOff()
        scope.cancel()
    }
}
