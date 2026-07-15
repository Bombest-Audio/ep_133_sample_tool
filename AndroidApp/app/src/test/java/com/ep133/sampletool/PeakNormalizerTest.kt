package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.PeakNormalizer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build interleaved s16 LE bytes from sample values. */
internal fun s16le(vararg samples: Int): ByteArray {
    val out = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, s ->
        out[i * 2] = (s and 0xFF).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}

/** Read sample [i] from interleaved s16 LE bytes. */
internal fun sampleAt(pcm: ByteArray, i: Int): Int =
    (pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)

class PeakNormalizerTest {

    private fun targetLinear(dbfs: Double) = 10.0.pow(dbfs / 20.0) * Short.MAX_VALUE

    @Test
    fun `quiet signal is boosted to the default -0_3 dBFS target peak`() {
        val pcm = s16le(1000, -2000, 500, 0)
        val out = PeakNormalizer.normalize(pcm)
        val expectedPeak = targetLinear(-0.3).roundToInt()
        val peak = (0 until 4).maxOf { abs(sampleAt(out, it)) }
        assertEquals(expectedPeak, peak)
        // Relative levels preserved: sample 0 is half of the (absolute) peak sample.
        assertEquals(expectedPeak / 2, sampleAt(out, 0))
    }

    @Test
    fun `hot signal is attenuated down to the target`() {
        val pcm = s16le(32767, -16000)
        val out = PeakNormalizer.normalize(pcm, targetDbfs = -6.0)
        val expectedPeak = (targetLinear(-6.0)).roundToInt()
        assertEquals(expectedPeak, sampleAt(out, 0))
        assertTrue(abs(sampleAt(out, 1)) < 16000)
    }

    @Test
    fun `custom target dBFS parameter is honored`() {
        val pcm = s16le(8000)
        val out = PeakNormalizer.normalize(pcm, targetDbfs = -12.0)
        assertEquals(targetLinear(-12.0).roundToInt(), sampleAt(out, 0))
    }

    @Test
    fun `digital silence passes through unchanged`() {
        val pcm = s16le(0, 0, 0)
        val out = PeakNormalizer.normalize(pcm)
        assertArrayEquals(pcm, out)
        assertNotSame(pcm, out)   // still a defensive copy
    }

    @Test
    fun `empty input returns empty output`() {
        assertEquals(0, PeakNormalizer.normalize(ByteArray(0)).size)
    }

    @Test
    fun `negative full-scale peak does not overflow`() {
        // abs(-32768) exceeds Short.MAX_VALUE; scaling must clamp into s16 range.
        val pcm = s16le(-32768, 100)
        val out = PeakNormalizer.normalize(pcm, targetDbfs = 0.0)
        assertTrue(sampleAt(out, 0) >= Short.MIN_VALUE.toInt())
        assertTrue(sampleAt(out, 0) <= Short.MAX_VALUE.toInt())
    }

    @Test
    fun `stereo interleaving is preserved (uniform gain per sample)`() {
        val pcm = s16le(1000, -1000, 2000, -2000)   // L R L R
        val out = PeakNormalizer.normalize(pcm, targetDbfs = 0.0)
        // Peak (2000) maps to 32767; every sample scales by the same factor.
        assertEquals(32767, sampleAt(out, 2))
        assertEquals(-32767, sampleAt(out, 3))
        // 1000 * 32767/2000 is ~16383.5 (the double representation sits a hair below
        // the exact tie), so the positive sample rounds up and the negative one down.
        assertEquals(16384, sampleAt(out, 0))
        assertEquals(-16384, sampleAt(out, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `positive target dBFS is rejected`() {
        PeakNormalizer.normalize(s16le(1), targetDbfs = 1.0)
    }
}
