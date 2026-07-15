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
 * If the native stream fails to open (rare - emulators, unusual devices),
 * all methods become no-ops. Swap back to [LocalSynth] in that scenario.
 */
class NativeSynth(audioManager: AudioManager) : SynthEngine {

    private val ptr: Long

    init {
        ptr = try {
            System.loadLibrary("nativesynth")
            val sampleRate = audioManager
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull() ?: 48000
            nativeCreate(sampleRate)
        } catch (e: UnsatisfiedLinkError) {
            // Missing .so for this ABI: degrade to the documented no-op behavior
            // instead of crashing the app at startup.
            Log.e(TAG, "nativesynth library unavailable - audio will be silent", e)
            0L
        }
        if (ptr == 0L) Log.e(TAG, "Native synth failed to open - audio will be silent")
    }

    override fun noteOn(note: Int, velocity: Int) { if (ptr != 0L) nativeNoteOn(ptr, note, velocity) }
    override fun noteOff(note: Int) { if (ptr != 0L) nativeNoteOff(ptr, note) }
    override fun allNotesOff() { if (ptr != 0L) nativeAllNotesOff(ptr) }
    override fun close() { if (ptr != 0L) nativeClose(ptr) }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeNoteOn(ptr: Long, note: Int, velocity: Int)
    private external fun nativeNoteOff(ptr: Long, note: Int)
    private external fun nativeAllNotesOff(ptr: Long)
    private external fun nativeClose(ptr: Long)

    companion object {
        private const val TAG = "EP133NATIVE"
    }
}
