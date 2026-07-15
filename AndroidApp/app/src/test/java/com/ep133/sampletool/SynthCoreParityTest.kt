package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.voice.KotlinSynthVoice
import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import com.ep133.sampletool.domain.audio.voice.SynthCore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 8 parity + quality gates for the synth voice.
 *
 * The live path (Oboe callback) and the offline bake both drive
 * SynthCore::renderBlock; the loop carries all state per sample, so its output
 * must be identical no matter how the frame stream is chunked. These tests
 * prove that property on the pure-Kotlin replica of the native core
 * (domain/audio/voice/SynthCore.kt, kept in sync with synth_core.h).
 * Bit-exact native verification requires an instrumented run on device -
 * documented Phase 8 gap.
 */
class SynthCoreParityTest {

    private val sr = RenderableVoice.DEVICE_SAMPLE_RATE // 46875

    /** Densest chord quality in ChordProgressions: DOM13, 6 notes on C4. */
    private val dom13 = listOf(60, 64, 67, 70, 74, 81)

    private fun renderChunked(chords: List<List<Int>>, frames: Int, chunk: Int): FloatArray {
        // Simulates the live path: the same note sequence, rendered through
        // renderBlock in audio-callback-sized bursts.
        val core = SynthCore(sr)
        val tail = (sr * KotlinSynthVoice.RELEASE_TAIL_SECONDS).toInt()
        val out = FloatArray(frames * chords.size + tail)
        var pos = 0

        fun renderSpan(total: Int) {
            var remaining = total
            while (remaining > 0) {
                val n = minOf(chunk, remaining)
                core.renderBlock(out, pos, n)
                pos += n
                remaining -= n
            }
        }

        for (chord in chords) {
            chord.forEach { core.noteOn(it, 90) }
            renderSpan(frames)
            chord.forEach { core.noteOff(it) }
        }
        renderSpan(tail)
        return out
    }

    @Test
    fun `offline render equals live-style burst render sample for sample`() {
        // Arrange: a fixed progression (golden-vector style note sequence)
        val chords = listOf(
            listOf(60, 64, 67),          // C major
            listOf(57, 60, 64, 67),      // Am7
            dom13,                       // densest voicing
        )
        val frames = RenderableVoice.framesPerBar(bpm = 120, sampleRate = sr)

        // Act: one offline pass vs Oboe-burst-sized chunks (192 frames)
        val offline = KotlinSynthVoice().render(chords, bpm = 120, sampleRate = sr)
        val live = renderChunked(chords, frames, chunk = 192)

        // Assert: bit-identical - same voice loop, chunking cannot matter
        assertEquals(live.size, offline.size)
        assertArrayEquals(live, offline, 0f)
    }

    @Test
    fun `chunk size does not change the output`() {
        val chords = listOf(listOf(48, 55, 64))
        val frames = RenderableVoice.framesPerBar(bpm = 90, sampleRate = sr)

        val burst64 = renderChunked(chords, frames, chunk = 64)
        val burst941 = renderChunked(chords, frames, chunk = 941) // odd, non-divisor

        assertArrayEquals(burst64, burst941, 0f)
    }

    @Test
    fun `densest chord stays below 0 dBFS with negligible DC offset`() {
        val pcm = KotlinSynthVoice().render(listOf(dom13), bpm = 60, sampleRate = sr)

        val peak = pcm.maxOf { abs(it) }
        val dc = pcm.map { it.toDouble() }.average()

        assertTrue("audible output expected, peak was $peak", peak > 0.1f)
        assertTrue("peak $peak must be <= 0 dBFS", peak <= 1f)
        // 12-bit quantization floor is ~4.9e-4; DC must sit well inside it
        assertTrue("DC offset $dc too large", abs(dc) < 1e-3)
    }

    @Test
    fun `render appends a release tail and ends at silence`() {
        val chords = listOf(listOf(60, 64, 67))
        val frames = RenderableVoice.framesPerBar(bpm = 120, sampleRate = sr)

        val pcm = KotlinSynthVoice().render(chords, bpm = 120, sampleRate = sr)

        assertEquals(frames + (sr * KotlinSynthVoice.RELEASE_TAIL_SECONDS).toInt(), pcm.size)
        // 150 ms release into a 250 ms tail: the final samples must be silent
        val lastChunk = pcm.copyOfRange(pcm.size - 100, pcm.size)
        assertTrue(lastChunk.all { it == 0f })
    }

    @Test
    fun `framesPerBar matches ChordPlayer bar timing`() {
        // ChordPlayer: msPerBar = 60000/bpm * 4
        assertEquals(2 * sr, RenderableVoice.framesPerBar(bpm = 120, sampleRate = sr))
        assertEquals((sr * 60.0 / 90 * 4).toInt(), RenderableVoice.framesPerBar(90, sr))
    }
}
