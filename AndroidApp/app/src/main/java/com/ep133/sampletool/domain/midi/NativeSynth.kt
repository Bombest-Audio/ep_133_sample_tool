package com.ep133.sampletool.domain.midi

import android.media.AudioManager
import android.util.Log

/**
 * Oboe-backed polyphonic synthesizer implementing [SynthEngine].
 *
 * Replaces [LocalSynth] with a low-latency native audio stream: single Oboe
 * output in LOW_LATENCY | EXCLUSIVE mode, 8 voices mixed in the callback on a
 * real-time native thread (no GC pauses, no JVM scheduling jitter).
 *
 * If the native library is missing for this ABI or the stream fails to open
 * (rare - emulators, unusual devices), the instance transparently delegates to
 * a [LocalSynth] fallback so chord preview stays audible; callers never need
 * to detect the failure.
 */
class NativeSynth(
    audioManager: AudioManager,
    private val fallbackFactory: () -> SynthEngine = { LocalSynth() },
) : SynthEngine {

    private val ptr: Long

    // Non-null exactly when the native path is unavailable (missing .so or failed
    // stream open); all playback then delegates here so preview stays audible.
    private val fallback: SynthEngine?

    init {
        ptr = try {
            System.loadLibrary("nativesynth")
            val sampleRate = audioManager
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull() ?: 48000
            nativeCreate(sampleRate)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "nativesynth library unavailable for this ABI", e)
            0L
        }
        fallback = if (ptr == 0L) {
            Log.e(TAG, "Native synth unavailable - falling back to LocalSynth (AudioTrack)")
            fallbackFactory()
        } else {
            null
        }
    }

    override fun noteOn(note: Int, velocity: Int) {
        if (ptr != 0L) nativeNoteOn(ptr, note, velocity) else fallback?.noteOn(note, velocity)
    }

    override fun noteOff(note: Int) {
        if (ptr != 0L) nativeNoteOff(ptr, note) else fallback?.noteOff(note)
    }

    override fun allNotesOff() {
        if (ptr != 0L) nativeAllNotesOff(ptr) else fallback?.allNotesOff()
    }

    override fun close() {
        if (ptr != 0L) nativeClose(ptr) else fallback?.close()
    }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeNoteOn(ptr: Long, note: Int, velocity: Int)
    private external fun nativeNoteOff(ptr: Long, note: Int)
    private external fun nativeAllNotesOff(ptr: Long)
    private external fun nativeClose(ptr: Long)

    companion object {
        private const val TAG = "EP133NATIVE"
    }
}
