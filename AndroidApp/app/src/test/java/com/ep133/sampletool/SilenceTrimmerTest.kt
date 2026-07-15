package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.SilenceTrimmer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceTrimmerTest {

    // sampleRate 1000 keeps the padding math readable: 5 ms = 5 frames.
    private val rate = 1000

    @Test
    fun `leading and trailing silence trimmed with zero padding`() {
        val pcm = s16le(0, 0, 0, 10000, 20000, 10000, 0, 0)
        val out = SilenceTrimmer.trim(pcm, channels = 1, sampleRate = rate, paddingMs = 0)
        assertArrayEquals(s16le(10000, 20000, 10000), out)
    }

    @Test
    fun `padding keeps original audio around the loud region`() {
        val pcm = s16le(0, 0, 0, 0, 0, 0, 10000, 0, 0, 0, 0, 0, 0)
        // 2 ms at 1000 Hz = 2 frames of padding each side.
        val out = SilenceTrimmer.trim(pcm, channels = 1, sampleRate = rate, paddingMs = 2)
        assertArrayEquals(s16le(0, 0, 10000, 0, 0), out)
    }

    @Test
    fun `padding clamps at the array bounds`() {
        val pcm = s16le(10000, 0)
        val out = SilenceTrimmer.trim(pcm, channels = 1, sampleRate = rate, paddingMs = 50)
        assertArrayEquals(pcm, out)
    }

    @Test
    fun `threshold decides what counts as silence`() {
        // -20 dBFS of 32767 is about 3277: the 3000 samples read as silence, 4000 does not.
        val pcm = s16le(3000, 4000, 3000)
        val out = SilenceTrimmer.trim(
            pcm, channels = 1, sampleRate = rate, thresholdDbfs = -20.0, paddingMs = 0,
        )
        assertArrayEquals(s16le(4000), out)
    }

    @Test
    fun `all-silence input passes through unchanged`() {
        val pcm = s16le(0, 1, -1, 0)
        val out = SilenceTrimmer.trim(pcm, channels = 1, sampleRate = rate)
        assertArrayEquals(pcm, out)
    }

    @Test
    fun `no silence to trim returns the input unchanged`() {
        val pcm = s16le(10000, 20000, 10000)
        val out = SilenceTrimmer.trim(pcm, channels = 1, sampleRate = rate, paddingMs = 0)
        assertArrayEquals(pcm, out)
    }

    @Test
    fun `empty input returns empty output`() {
        assertEquals(0, SilenceTrimmer.trim(ByteArray(0), channels = 1, sampleRate = rate).size)
    }

    @Test
    fun `stereo frames trim on frame boundaries and either channel keeps a frame`() {
        // Frames: (0,0) (0,10000) (10000,0) (0,0) - a loud R channel keeps frame 1.
        val pcm = s16le(0, 0, 0, 10000, 10000, 0, 0, 0)
        val out = SilenceTrimmer.trim(pcm, channels = 2, sampleRate = rate, paddingMs = 0)
        assertArrayEquals(s16le(0, 10000, 10000, 0), out)
    }
}
