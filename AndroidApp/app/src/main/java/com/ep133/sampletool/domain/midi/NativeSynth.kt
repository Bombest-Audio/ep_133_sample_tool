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
        System.loadLibrary("nativesynth")
        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48000
        ptr = nativeCreate(sampleRate)
        if (ptr == 0L) Log.e(TAG, "Native synth failed to open - audio will be silent")
    }

    override fun noteOn(note: Int, velocity: Int) = nativeNoteOn(ptr, note, velocity)
    override fun noteOff(note: Int)                = nativeNoteOff(ptr, note)
    override fun allNotesOff()                     = nativeAllNotesOff(ptr)
    override fun close()                           = nativeClose(ptr)

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeNoteOn(ptr: Long, note: Int, velocity: Int)
    private external fun nativeNoteOff(ptr: Long, note: Int)
    private external fun nativeAllNotesOff(ptr: Long)
    private external fun nativeClose(ptr: Long)

    companion object {
        private const val TAG = "EP133NATIVE"
    }
}
