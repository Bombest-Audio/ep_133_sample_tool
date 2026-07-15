package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.voice.PlaceholderInstrument
import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import com.ep133.sampletool.domain.audio.voice.SampledInstrument
import com.ep133.sampletool.domain.audio.voice.SampledInstrumentVoice
import com.ep133.sampletool.domain.audio.voice.WavIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * SFZ loading + sampled playback. WAV fixtures are generated in test code
 * (no binaries committed).
 */
class SampledInstrumentVoiceTest {

    // ── Fixture helpers ──────────────────────────────────────────────────────

    /** Minimal PCM16 mono WAV writer for fixtures. */
    private fun wavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(1)                       // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)            // byte rate
        header.putShort(2)                       // block align
        header.putShort(16)                      // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        out.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach {
            pcm.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.write(pcm.array())
        return out.toByteArray()
    }

    private fun sine(freq: Double, seconds: Double, sampleRate: Int, amp: Float = 0.5f) =
        FloatArray((sampleRate * seconds).toInt()) { i ->
            (amp * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }

    private val sr = 46875

    private fun fixtureInstrument(): SampledInstrument {
        val sfz = """
            <region> sample=c3.wav lokey=36 hikey=59 pitch_keycenter=48
            <region> sample=c5.wav lokey=60 hikey=96 pitch_keycenter=72 volume=-6
        """.trimIndent()
        val wavs = mapOf(
            "c3.wav" to wavBytes(sine(130.81, 1.0, sr), sr),
            "c5.wav" to wavBytes(sine(523.25, 1.0, sr), sr),
        )
        return SampledInstrument.fromSfz(sfz) { wavs.getValue(it) }
    }

    // ── WAV reader ───────────────────────────────────────────────────────────

    @Test
    fun `wav round trip preserves rate and content`() {
        val original = sine(440.0, 0.1, sr)

        val decoded = WavIo.read(wavBytes(original, sr))

        assertEquals(sr, decoded.sampleRate)
        assertEquals(original.size, decoded.samples.size)
        // 16-bit quantization error only
        for (i in original.indices) {
            assertTrue(abs(original[i] - decoded.samples[i]) < 1e-3f)
        }
    }

    // ── Zone selection ───────────────────────────────────────────────────────

    @Test
    fun `zoneFor picks the containing zone and falls back to nearest keycenter`() {
        val inst = fixtureInstrument()

        assertEquals(48, inst.zoneFor(note = 50, velocity = 90).pitchKeyCenter)
        assertEquals(72, inst.zoneFor(note = 65, velocity = 90).pitchKeyCenter)
        // 100 is above every hikey - nearest keycenter wins
        assertEquals(72, inst.zoneFor(note = 100, velocity = 90).pitchKeyCenter)
        // 20 is below every lokey
        assertEquals(48, inst.zoneFor(note = 20, velocity = 90).pitchKeyCenter)
    }

    @Test
    fun `zone gain reflects sfz volume`() {
        val inst = fixtureInstrument()

        // -6 dB -> ~0.501 linear
        assertEquals(0.501f, inst.zoneFor(note = 72, velocity = 90).gain, 0.001f)
        assertEquals(1.0f, inst.zoneFor(note = 48, velocity = 90).gain, 0.001f)
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    /** Zero-crossing frequency estimate over the sustained middle of the note. */
    private fun estimateFreq(pcm: FloatArray, from: Int, to: Int, sampleRate: Int): Double {
        var crossings = 0
        for (i in (from + 1) until to) {
            if (pcm[i - 1] <= 0f && pcm[i] > 0f) crossings++
        }
        return crossings * sampleRate.toDouble() / (to - from)
    }

    @Test
    fun `pitch shifts by semitone distance from keycenter`() {
        val voice = SampledInstrumentVoice(fixtureInstrument())

        // C3 zone keycenter 48 played at 55 = +7 semitones -> 130.81 * 1.4983 = ~196 Hz
        val pcm = voice.render(chords = listOf(listOf(55)), bpm = 120, sampleRate = sr)

        val freq = estimateFreq(pcm, from = sr / 10, to = sr / 2, sampleRate = sr)
        assertEquals(196.0, freq, 4.0)
    }

    @Test
    fun `render lays out one bar per chord and stays below full scale`() {
        val voice = SampledInstrumentVoice(fixtureInstrument())
        val chords = listOf(listOf(48, 52, 55), listOf(50, 53, 57))

        val pcm = voice.render(chords, bpm = 120, sampleRate = sr)

        assertEquals(2 * RenderableVoice.framesPerBar(120, sr), pcm.size)
        assertTrue("expected audible output", pcm.any { abs(it) > 0.01f })
        assertTrue("must not clip", pcm.all { abs(it) <= 1f })
    }

    @Test
    fun `placeholder instrument renders the densest chord without clipping`() {
        // Densest quality in ChordProgressions is DOM13 (6 notes)
        val voice = SampledInstrumentVoice(PlaceholderInstrument.generate(sr))
        val dom13 = listOf(60, 64, 67, 70, 74, 81)

        val pcm = voice.render(chords = listOf(dom13), bpm = 90, sampleRate = sr)

        val peak = pcm.maxOf { abs(it) }
        assertTrue("audible output expected", peak > 0.05f)
        assertTrue("peak $peak must be <= 0 dBFS", peak <= 1f)
    }
}
