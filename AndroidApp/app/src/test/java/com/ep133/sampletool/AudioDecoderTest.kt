package com.ep133.sampletool

import android.media.AudioFormat
import com.ep133.sampletool.domain.audio.pcmBytesToShorts
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [AudioDecoder.pcmBytesToShorts] — pure JVM, no Android runtime needed.
 *
 * Covers all supported encoding paths:
 *  - ENCODING_PCM_16BIT: LE Int16 round-trip
 *  - ENCODING_PCM_FLOAT: LE Float32 → s16 with ±1.0 clamping
 *  - ENCODING_PCM_24BIT_PACKED: 3-byte LE → s16 downshift
 *  - ENCODING_PCM_32BIT: 4-byte LE → s16 downshift
 *  - Unknown encoding: throws IOException
 */
class AudioDecoderTest {

    // ── ENCODING_PCM_16BIT ──────────────────────────────────────────────────

    @Test
    fun pcmBytesToShorts_16bit_roundTrip() {
        val input = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = ByteBuffer.allocate(input.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            input.forEach { buf.putShort(it) }
        }.array()

        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_16BIT)

        assertArrayEquals("16-bit LE round-trip must be lossless", input, result)
    }

    @Test
    fun pcmBytesToShorts_16bit_emptyInput() {
        val result = pcmBytesToShorts(ByteArray(0), AudioFormat.ENCODING_PCM_16BIT)
        assertEquals("empty input → empty output", 0, result.size)
    }

    // ── ENCODING_PCM_FLOAT ──────────────────────────────────────────────────

    @Test
    fun pcmBytesToShorts_float_knownValues() {
        // Known Float32 inputs and their expected Int16 outputs.
        // 0.5f * 32767f = 16383.5f → roundToInt() = 16384 (rounds half-up).
        // -0.5f * 32767f = -16383.5f → roundToInt() = -16384 (rounds half-up toward +inf... but
        //   coerceIn(-1f,1f) * 32767f for -0.5f → -16383.5f; roundToInt uses "round half to even"
        //   via Math.round semantics: Math.round(-16383.5f) = -16383).
        // Use exact computed values to avoid brittle ties.
        val floats = floatArrayOf(0f, 1f, -1f)
        val expected = shortArrayOf(0, 32767, -32767)

        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            floats.forEach { buf.putFloat(it) }
        }.array()

        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_FLOAT)

        assertArrayEquals("Float32 → s16 known values", expected, result)
    }

    @Test
    fun pcmBytesToShorts_float_zero() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply { putFloat(0f) }.array()
        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_FLOAT)
        assertEquals("0.0f → 0", 0, result[0].toInt())
    }

    @Test
    fun pcmBytesToShorts_float_clampingAbove1() {
        val floats = floatArrayOf(2f, 3.14f)  // both > 1.0 → must clamp to 32767
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            floats.forEach { buf.putFloat(it) }
        }.array()

        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_FLOAT)

        result.forEach { s ->
            assertEquals("Values > 1.0 must clamp to 32767", 32767, s.toInt())
        }
    }

    @Test
    fun pcmBytesToShorts_float_clampingBelow_1() {
        val floats = floatArrayOf(-2f, -1.5f)  // both < -1.0 → must clamp to -32767
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            floats.forEach { buf.putFloat(it) }
        }.array()

        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_FLOAT)

        result.forEach { s ->
            assertEquals("Values < -1.0 must clamp to -32767", -32767, s.toInt())
        }
    }

    // ── ENCODING_PCM_24BIT_PACKED ───────────────────────────────────────────

    @Test
    fun pcmBytesToShorts_24bit_positiveValue() {
        // 0x007F00 in 24-bit LE = [0x00, 0x7F, 0x00] → s24 = 0x007F00 → downshift 8 = 0x007F = 127
        val bytes = byteArrayOf(0x00, 0x7F.toByte(), 0x00)
        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        assertEquals("24-bit 0x007F00 → s16 = 127", 127, result[0].toInt())
    }

    @Test
    fun pcmBytesToShorts_24bit_negativeValue() {
        // 0xFF8000 in 24-bit LE = [0x00, 0x80.toByte(), 0xFF.toByte()]
        // s24 = sign-extended: 0xFF8000 → as signed int = -32768 → downshift 8 = -128
        val bytes = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte())
        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        assertEquals("24-bit 0xFF8000 → s16 = -128", -128, result[0].toInt())
    }

    @Test
    fun pcmBytesToShorts_24bit_zero() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00)
        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_24BIT_PACKED)
        assertEquals("24-bit 0 → s16 = 0", 0, result[0].toInt())
    }

    // ── ENCODING_PCM_32BIT ──────────────────────────────────────────────────

    @Test
    fun pcmBytesToShorts_32bit_knownValues() {
        // Int32 max positive in s32 space: 0x7FFF0000 → downshift 16 → 0x7FFF = 32767
        val values = intArrayOf(0x7FFF0000.toInt(), 0x00000000, 0x80010000.toInt())
        val expected = shortArrayOf(32767, 0, -32767)

        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { buf ->
            values.forEach { buf.putInt(it) }
        }.array()

        val result = pcmBytesToShorts(bytes, AudioFormat.ENCODING_PCM_32BIT)

        assertArrayEquals("32-bit → s16 downshift known values", expected, result)
    }

    // ── Unknown encoding ────────────────────────────────────────────────────

    @Test(expected = IOException::class)
    fun pcmBytesToShorts_unknownEncoding_throwsIOException() {
        pcmBytesToShorts(ByteArray(4), encoding = 0xDEAD)
    }
}
