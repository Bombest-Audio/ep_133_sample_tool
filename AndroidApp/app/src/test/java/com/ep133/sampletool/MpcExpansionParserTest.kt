package com.ep133.sampletool

import com.ep133.sampletool.domain.pack.MpcExpansionParser
import com.ep133.sampletool.domain.pack.MpcProgramType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Parser tests against the assumed community schema (docs/mpc-xpm-schema-notes.md).
 * Fixture XML strings are written from those notes - no real .xpm files exist
 * on this machine yet; re-validate against real files per the doc's checklist.
 */
class MpcExpansionParserTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun keygroupXpm() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPCVObject>
          <Version><File_Version>2.1</File_Version></Version>
          <Program type="Keygroup">
            <ProgramName>Dojo Keys</ProgramName>
            <SomeUnknownBlock><Nested>ignored</Nested></SomeUnknownBlock>
            <Instruments>
              <Instrument number="0">
                <LowNote>36</LowNote>
                <HighNote>59</HighNote>
                <UnknownField>7</UnknownField>
                <Layers>
                  <Layer number="1">
                    <SampleName>KeysC3</SampleName>
                    <RootNote>48</RootNote>
                    <VelStart>0</VelStart>
                    <VelEnd>127</VelEnd>
                    <Volume>0.5</Volume>
                  </Layer>
                  <Layer number="2">
                    <SampleName>KeysC3_hard</SampleName>
                    <RootNote>48</RootNote>
                  </Layer>
                </Layers>
              </Instrument>
              <Instrument number="1">
                <LowNote>60</LowNote>
                <HighNote>96</HighNote>
                <Layers>
                  <Layer number="1">
                    <SampleFile>KeysC5.WAV</SampleFile>
                    <RootNote>72</RootNote>
                  </Layer>
                </Layers>
              </Instrument>
            </Instruments>
          </Program>
        </MPCVObject>
    """.trimIndent()

    private fun drumXpm() = """
        <MPCVObject>
          <Program type="Drum">
            <ProgramName>Dojo Kit</ProgramName>
            <Instruments>
              <Instrument number="0">
                <Layers><Layer number="1"><SampleName>Kick01</SampleName></Layer></Layers>
              </Instrument>
              <Instrument number="1">
                <Layers><Layer number="1"><SampleFile>Snare01.wav</SampleFile></Layer></Layers>
              </Instrument>
              <Instrument number="2">
                <Layers><Layer number="1"><SampleName></SampleName></Layer></Layers>
              </Instrument>
            </Instruments>
          </Program>
        </MPCVObject>
    """.trimIndent()

    // ── KEYGROUP parsing ─────────────────────────────────────────────────────

    @Test
    fun `keygroup program parses zones with key ranges roots and gain`() {
        val result = MpcExpansionParser.parse(keygroupXpm())

        assertNotNull(result.program)
        val p = result.program!!
        assertEquals(MpcProgramType.KEYGROUP, p.type)
        assertEquals("Dojo Keys", p.name)
        assertEquals(2, p.zones.size)

        val z0 = p.zones[0]
        assertEquals("KeysC3.wav", z0.sampleFile)  // SampleName + .wav
        assertEquals(36, z0.loKey)
        assertEquals(59, z0.hiKey)
        assertEquals(48, z0.rootNote)
        assertEquals(0.5f, z0.gain, 1e-6f)

        val z1 = p.zones[1]
        assertEquals("KeysC5.WAV", z1.sampleFile)  // SampleFile verbatim
        assertEquals(72, z1.rootNote)
        assertEquals(1f, z1.gain, 1e-6f)           // Volume absent -> unity
    }

    @Test
    fun `only the first velocity layer is used`() {
        val p = MpcExpansionParser.parse(keygroupXpm()).program!!

        // Instrument 0 has two layers; layer 2 (KeysC3_hard) must be dropped.
        assertTrue(p.zones.none { it.sampleFile.contains("hard") })
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val xml = """
            <MPCVObject><Program type="keygroup">
              <Instruments><Instrument>
                <Layers><Layer><SampleName>Solo</SampleName></Layer></Layers>
              </Instrument></Instruments>
            </Program></MPCVObject>
        """.trimIndent()

        val result = MpcExpansionParser.parse(xml, fallbackName = "solo_prog")

        val p = result.program!!
        assertEquals("solo_prog", p.name)          // no ProgramName -> fallback
        val z = p.zones.single()
        assertEquals(0, z.loKey)
        assertEquals(127, z.hiKey)
        assertEquals(63, z.rootNote)               // midpoint of 0..127
        assertTrue(result.warnings.any { it.contains("RootNote") })
    }

    // ── DRUM parsing ─────────────────────────────────────────────────────────

    @Test
    fun `drum program routes samples to the one-shot list with warnings for empty pads`() {
        val result = MpcExpansionParser.parse(drumXpm())

        val p = result.program!!
        assertEquals(MpcProgramType.DRUM, p.type)
        assertEquals("Dojo Kit", p.name)
        assertEquals(listOf("Kick01.wav", "Snare01.wav"), p.drumSamples.map { it.sampleFile })
        assertTrue(p.zones.isEmpty())
        assertTrue(result.warnings.any { it.contains("pad entry 3") })
    }

    // ── Tolerance: malformed input warns, never throws ───────────────────────

    @Test
    fun `malformed xml returns warnings not a crash`() {
        val result = MpcExpansionParser.parse("this is <not really > xml <<<")

        assertNull(result.program)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `unknown program type is skipped with a warning`() {
        val xml = """<MPCVObject><Program type="Clip"><ProgramName>x</ProgramName></Program></MPCVObject>"""

        val result = MpcExpansionParser.parse(xml)

        assertNull(result.program)
        assertTrue(result.warnings.any { it.contains("Clip") })
    }

    @Test
    fun `program with no instruments is skipped with a warning`() {
        val xml = """<MPCVObject><Program type="Drum"><ProgramName>Empty</ProgramName></Program></MPCVObject>"""

        val result = MpcExpansionParser.parse(xml)

        assertNull(result.program)
        assertTrue(result.warnings.any { it.contains("Empty") })
    }

    @Test
    fun `keygroup instrument without any sample layer is skipped, others survive`() {
        val xml = """
            <MPCVObject><Program type="Keygroup">
              <Instruments>
                <Instrument><LowNote>0</LowNote><HighNote>63</HighNote><Layers><Layer/></Layers></Instrument>
                <Instrument><LowNote>64</LowNote><HighNote>127</HighNote>
                  <Layers><Layer><SampleName>Hi</SampleName><RootNote>96</RootNote></Layer></Layers>
                </Instrument>
              </Instruments>
            </Program></MPCVObject>
        """.trimIndent()

        val result = MpcExpansionParser.parse(xml)

        assertEquals(1, result.program!!.zones.size)
        assertEquals("Hi.wav", result.program!!.zones[0].sampleFile)
        assertTrue(result.warnings.any { it.contains("entry 1") })
    }

    // ── Keygroup -> SampledInstrument conversion ─────────────────────────────

    /** Minimal PCM16 mono WAV writer (same fixture approach as SampledInstrumentVoiceTest). */
    private fun wavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
        out.write(pcm.array())
        return out.toByteArray()
    }

    private val sr = 46875

    private fun sine(freq: Double, seconds: Double) =
        FloatArray((sr * seconds).toInt()) { i -> (0.5f * sin(2.0 * PI * freq * i / sr)).toFloat() }

    @Test
    fun `keygroup converts to a SampledInstrument with matching zone map`() {
        val program = MpcExpansionParser.parse(keygroupXpm()).program!!
        val wavs = mapOf(
            "KeysC3.wav" to wavBytes(sine(130.81, 0.5), sr),
            "KeysC5.WAV" to wavBytes(sine(523.25, 0.5), sr),
        )

        val instrument = MpcExpansionParser.toSampledInstrument(program, { wavs.getValue(it) })

        assertEquals(2, instrument.zones.size)
        // Zone mapping: note 50 lands in the 36..59 zone (keycenter 48)
        assertEquals(48, instrument.zoneFor(note = 50, velocity = 90).pitchKeyCenter)
        assertEquals(72, instrument.zoneFor(note = 70, velocity = 90).pitchKeyCenter)
        // Layer Volume 0.5 carried into zone gain
        assertEquals(0.5f, instrument.zoneFor(note = 50, velocity = 90).gain, 1e-6f)
    }

    @Test
    fun `conversion skips unresolvable zones with a warning and keeps the rest`() {
        val program = MpcExpansionParser.parse(keygroupXpm()).program!!
        val wavs = mapOf("KeysC5.WAV" to wavBytes(sine(523.25, 0.2), sr))
        val warnings = mutableListOf<String>()

        val instrument = MpcExpansionParser.toSampledInstrument(
            program,
            { wavs[it] ?: throw IllegalArgumentException("missing") },
            warnings,
        )

        assertEquals(1, instrument.zones.size)
        assertEquals(72, instrument.zones[0].pitchKeyCenter)
        assertTrue(warnings.any { it.contains("KeysC3.wav") })
    }
}
