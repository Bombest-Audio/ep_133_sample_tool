package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.WavEncoder
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RED (Wave 0): Asserts the WavEncoder public contract before implementation exists.
 *
 * Landmine 2 guard: hard-coded literal 46875 — a wrong sample rate (e.g. 44100) fails here.
 * Landmine 3 guard: explicit LITTLE_ENDIAN reads — byte-order slip yields wrong numeric values.
 *
 * These tests compile-fail on `WavEncoder` until Wave 1 creates
 * `domain/audio/WavEncoder.kt`. No changes to these tests should be needed
 * to turn them GREEN — the production implementation must match this contract.
 */
class WavEncoderTest {

    private val samplePcm = shortArrayOf(0, 1, -1, 32767, -32768)

    // ── Helper: read a little-endian int16 from a byte offset ──
    private fun ByteArray.leInt16(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    // ── Helper: read a little-endian int32 from a byte offset ──
    private fun ByteArray.leInt32(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    // ──────────────────────────────────────────────────────────────────────────
    // Test A: RIFF header carries 46875 Hz, s16, mono (Landmine 2 + 3 guards)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun riffHeader_carries46875_s16_channels() {
        val wav = WavEncoder.encodeWav(samplePcm, sampleRate = 46875, channels = 1)

        // Total length: 44-byte header + PCM data
        assertEquals("Total WAV length", 44 + samplePcm.size * 2, wav.size)

        // RIFF chunk descriptor
        assertEquals("RIFF[0]", 'R'.code.toByte(), wav[0])
        assertEquals("RIFF[1]", 'I'.code.toByte(), wav[1])
        assertEquals("RIFF[2]", 'F'.code.toByte(), wav[2])
        assertEquals("RIFF[3]", 'F'.code.toByte(), wav[3])

        // WAVE marker (offset 8)
        assertEquals("WAVE[0]", 'W'.code.toByte(), wav[8])
        assertEquals("WAVE[1]", 'A'.code.toByte(), wav[9])
        assertEquals("WAVE[2]", 'V'.code.toByte(), wav[10])
        assertEquals("WAVE[3]", 'E'.code.toByte(), wav[11])

        // fmt sub-chunk marker (offset 12)
        assertEquals("fmt [0]", 'f'.code.toByte(), wav[12])
        assertEquals("fmt [1]", 'm'.code.toByte(), wav[13])
        assertEquals("fmt [2]", 't'.code.toByte(), wav[14])
        assertEquals("fmt [3]", ' '.code.toByte(), wav[15])

        // data sub-chunk marker (offset 36)
        assertEquals("data[0]", 'd'.code.toByte(), wav[36])
        assertEquals("data[1]", 'a'.code.toByte(), wav[37])
        assertEquals("data[2]", 't'.code.toByte(), wav[38])
        assertEquals("data[3]", 'a'.code.toByte(), wav[39])

        // fmt fields (all little-endian)
        val audioFormat = wav.leInt16(20)
        assertEquals("audioFormat must be 1 (PCM)", 1, audioFormat)

        val channels = wav.leInt16(22)
        assertEquals("channels must be 1 (mono)", 1, channels)

        // Landmine 2: assert the exact device rate — 46875, not 44100 or 48000
        val sampleRate = wav.leInt32(24)
        assertEquals("sampleRate must be 46875 (EP-133 device rate)", 46875, sampleRate)

        // Landmine 3: assert 16-bit depth (not 32-bit float, not 24-bit)
        val bitsPerSample = wav.leInt16(34)
        assertEquals("bitsPerSample must be 16", 16, bitsPerSample)

        // data sub-chunk size == pcm.size * 2 (Int16 = 2 bytes per sample)
        val dataSize = wav.leInt32(40)
        assertEquals("dataSize must be pcm.size * 2", samplePcm.size * 2, dataSize)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test B: stereo — channels, byteRate, blockAlign
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun stereo_channelsAndByteRate() {
        val wav = WavEncoder.encodeWav(samplePcm, sampleRate = 46875, channels = 2)

        val channels = wav.leInt16(22)
        assertEquals("channels must be 2 (stereo)", 2, channels)

        // byteRate = sampleRate * channels * bytesPerSample = 46875 * 2 * 2
        val byteRate = wav.leInt32(28)
        assertEquals("byteRate must be 46875*2*2", 46875 * 2 * 2, byteRate)

        // blockAlign = channels * bytesPerSample = 2 * 2
        val blockAlign = wav.leInt16(32)
        assertEquals("blockAlign must be 4 (channels=2 * bytesPerSample=2)", 4, blockAlign)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test C: pass-through predicate (isAlreadyDeviceFormat)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun passThrough_identity_whenAlready46875() {
        // A WAV built by WavEncoder itself must be recognized as device-format
        val alreadyDeviceFmt = WavEncoder.encodeWav(samplePcm, sampleRate = 46875, channels = 1)
        assertTrue(
            "A 46875 Hz / s16 / mono WAV must be recognized as already device format",
            WavEncoder.isAlreadyDeviceFormat(alreadyDeviceFmt),
        )

        // A WAV at 44100 Hz (the common Splice export rate) must NOT be recognized
        val spliceFmt = WavEncoder.encodeWav(samplePcm, sampleRate = 44100, channels = 1)
        assertFalse(
            "A 44100 Hz WAV must NOT be recognized as device format (Landmine 2)",
            WavEncoder.isAlreadyDeviceFormat(spliceFmt),
        )
    }
}
