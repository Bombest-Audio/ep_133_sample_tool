package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.midi.SamplePrep
import com.ep133.sampletool.domain.midi.SamplePrepOptions
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplePrepTest {

    private val rate = 46875

    private fun mono(vararg samples: Int) = ConvertedSample(s16le(*samples), 1, rate)

    @Test
    fun `defaults-off options are the identity (same instance, no copy)`() {
        val sample = mono(1, 2, 3)
        assertSame(sample, SamplePrep.apply(sample, SamplePrepOptions()))
    }

    @Test
    fun `normalize alone hits the target peak`() {
        val out = SamplePrep.apply(mono(1000, -500), SamplePrepOptions(normalize = true))
        val peak = maxOf(abs(sampleAt(out.pcm, 0)), abs(sampleAt(out.pcm, 1)))
        // Default target is -0.3 dBFS of 32767 (about 31655).
        assertTrue(peak in 31600..32767)
        assertEquals(1, out.channels)
        assertEquals(rate, out.sampleRate)
    }

    @Test
    fun `toMono downmixes stereo and halves the byte count`() {
        val stereo = ConvertedSample(s16le(1000, 3000, -1000, -3000), 2, rate)
        val out = SamplePrep.apply(stereo, SamplePrepOptions(toMono = true))
        assertEquals(1, out.channels)
        assertEquals(stereo.pcm.size / 2, out.pcm.size)
        assertEquals(2000, sampleAt(out.pcm, 0))
        assertEquals(-2000, sampleAt(out.pcm, 1))
    }

    @Test
    fun `toMono on already-mono input is a no-op`() {
        val sample = mono(1, 2)
        val out = SamplePrep.apply(sample, SamplePrepOptions(toMono = true))
        assertEquals(1, out.channels)
        assertEquals(sample, out)
    }

    @Test
    fun `mono downmix runs BEFORE normalize so the delivered peak is exact`() {
        // L/R cancel to a lower mono peak: 20000 and 0 average to 10000. If normalize
        // ran first the downmix would drop the peak below target afterwards.
        val stereo = ConvertedSample(s16le(20000, 0), 2, rate)
        val out = SamplePrep.apply(
            stereo, SamplePrepOptions(toMono = true, normalize = true, targetDbfs = 0.0),
        )
        assertEquals(1, out.channels)
        assertEquals(32767, sampleAt(out.pcm, 0))
    }

    @Test
    fun `trimSilence runs before normalize and strips quiet edges`() {
        val sample = mono(0, 0, 8000, 0, 0)
        val out = SamplePrep.apply(
            sample,
            SamplePrepOptions(trimSilence = true, normalize = true, silencePaddingMs = 0, targetDbfs = 0.0),
        )
        assertEquals(2, out.pcm.size)               // one frame survives
        assertEquals(32767, sampleAt(out.pcm, 0))   // and lands exactly on target
    }
}
