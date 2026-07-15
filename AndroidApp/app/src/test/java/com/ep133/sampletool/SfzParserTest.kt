package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.voice.SfzParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SfzParserTest {

    @Test
    fun `parses regions with explicit key range and keycenter`() {
        // Arrange
        val sfz = """
            // Tiny fixture instrument
            <region> sample=lo.wav lokey=36 hikey=59 pitch_keycenter=48
            <region> sample=hi.wav lokey=60 hikey=84 pitch_keycenter=72 volume=-6
        """.trimIndent()

        // Act
        val regions = SfzParser.parse(sfz)

        // Assert
        assertEquals(2, regions.size)
        assertEquals("lo.wav", regions[0].sample)
        assertEquals(36, regions[0].loKey)
        assertEquals(59, regions[0].hiKey)
        assertEquals(48, regions[0].pitchKeyCenter)
        assertEquals(0f, regions[0].volumeDb, 0f)
        assertEquals(-6f, regions[1].volumeDb, 0f)
    }

    @Test
    fun `key opcode sets lokey hikey and keycenter together`() {
        val regions = SfzParser.parse("<region> sample=c4.wav key=60")

        assertEquals(60, regions[0].loKey)
        assertEquals(60, regions[0].hiKey)
        assertEquals(60, regions[0].pitchKeyCenter)
    }

    @Test
    fun `group opcodes are inherited and region opcodes win`() {
        val sfz = """
            <group> lovel=0 hivel=63 volume=-3
            <region> sample=soft.wav key=60
            <region> sample=loud.wav key=60 lovel=64 hivel=127
        """.trimIndent()

        val regions = SfzParser.parse(sfz)

        assertEquals(2, regions.size)
        assertEquals(0, regions[0].loVel)
        assertEquals(63, regions[0].hiVel)
        assertEquals(-3f, regions[0].volumeDb, 0f)
        assertEquals(64, regions[1].loVel)
        assertEquals(127, regions[1].hiVel)
    }

    @Test
    fun `sample paths with spaces survive`() {
        val regions = SfzParser.parse("<region> sample=Donner B1 C4.wav key=60 volume=-1.5")

        assertEquals("Donner B1 C4.wav", regions[0].sample)
        assertEquals(-1.5f, regions[0].volumeDb, 0f)
    }

    @Test
    fun `regions without a sample and unsupported headers are skipped`() {
        val sfz = """
            <control> default_path=samples/
            <region> lokey=0 hikey=127
            <region> sample=ok.wav key=60 loop_mode=one_shot
        """.trimIndent()

        val regions = SfzParser.parse(sfz)

        assertEquals(1, regions.size)
        assertEquals("ok.wav", regions[0].sample)
    }

    @Test
    fun `missing key opcodes default to full range`() {
        val regions = SfzParser.parse("<region> sample=x.wav")

        assertEquals(0, regions[0].loKey)
        assertEquals(127, regions[0].hiKey)
        assertEquals(60, regions[0].pitchKeyCenter)
        assertEquals(0, regions[0].loVel)
        assertEquals(127, regions[0].hiVel)
    }
}
