package com.ep133.sampletool

import android.media.AudioManager
import com.ep133.sampletool.domain.midi.NativeSynth
import com.ep133.sampletool.domain.midi.SynthEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On the JVM the nativesynth JNI library can never load, which is exactly the
 * ABI-missing failure path on device: NativeSynth must delegate to its fallback
 * SynthEngine instead of crashing or going silent.
 */
class NativeSynthFallbackTest {

    private class CountingSynth : SynthEngine {
        var noteOns = 0
        var noteOffs = 0
        var allOffs = 0
        var closes = 0
        override fun noteOn(note: Int, velocity: Int) { noteOns++ }
        override fun noteOff(note: Int) { noteOffs++ }
        override fun allNotesOff() { allOffs++ }
        override fun close() { closes++ }
    }

    // AudioManager's no-arg constructor is package-private in the unit-test stubs;
    // reflection gets us an instance (it is never touched on the load-failure path).
    private fun stubAudioManager(): AudioManager =
        AudioManager::class.java.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()

    @Test
    fun loadFailure_delegatesAllCallsToFallback() {
        val spy = CountingSynth()
        val synth = NativeSynth(stubAudioManager(), fallbackFactory = { spy })

        synth.noteOn(60, 90)
        synth.noteOn(64, 90)
        synth.noteOff(60)
        synth.allNotesOff()
        synth.close()

        assertEquals(2, spy.noteOns)
        assertEquals(1, spy.noteOffs)
        assertEquals(1, spy.allOffs)
        assertEquals(1, spy.closes)
    }
}
