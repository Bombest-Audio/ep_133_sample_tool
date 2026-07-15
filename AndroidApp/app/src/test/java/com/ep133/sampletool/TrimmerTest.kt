package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.Trimmer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimmerTest {

    @Test
    fun `mono window is extracted by frame index`() {
        val pcm = s16le(1, 2, 3, 4, 5)
        assertArrayEquals(s16le(2, 3, 4), Trimmer.trimFrames(pcm, 1, startFrame = 1, endFrame = 4))
    }

    @Test
    fun `stereo window keeps interleaved pairs together`() {
        val pcm = s16le(1, -1, 2, -2, 3, -3)
        assertArrayEquals(s16le(2, -2), Trimmer.trimFrames(pcm, 2, startFrame = 1, endFrame = 2))
    }

    @Test
    fun `bounds are clamped to the available frames`() {
        val pcm = s16le(1, 2, 3)
        assertArrayEquals(pcm, Trimmer.trimFrames(pcm, 1, startFrame = -5, endFrame = 99))
    }

    @Test
    fun `empty window returns an empty array`() {
        val pcm = s16le(1, 2, 3)
        assertEquals(0, Trimmer.trimFrames(pcm, 1, startFrame = 2, endFrame = 2).size)
        assertEquals(0, Trimmer.trimFrames(pcm, 1, startFrame = 3, endFrame = 1).size)
    }
}
