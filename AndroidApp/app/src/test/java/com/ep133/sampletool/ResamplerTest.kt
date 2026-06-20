package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.Resampler
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.roundToInt

/**
 * RED (Wave 0): Asserts the Resampler public contract before implementation exists.
 *
 * Landmine 2 guard (wrong sample rate): the identity test (Test D) ensures srcRate == 46875
 * returns the unchanged array, preventing a silent resample artifact at the device rate.
 *
 * Spec (from 05-RESEARCH A2 + data/index.js):
 *   - Never upsample beyond 46875 Hz; Math.min(src, 46875) semantics.
 *   - srcRate == 46875 → return input unchanged (no resample work at device rate).
 *   - srcRate != 46875 → resample to 46875 by linear interpolation per channel.
 *
 * These tests compile-fail on `Resampler` until Wave 1 creates
 * `domain/audio/Resampler.kt`. No changes to these tests should be needed
 * to turn them GREEN — the production implementation must match this contract.
 */
class ResamplerTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Test D: no-op when srcRate == 46875 (Landmine 2 guard — no resample at device rate)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun noOp_whenSrcEqualsDst() {
        val input = shortArrayOf(100, 200, -100, 32000, -32000)
        val result = Resampler.toRate(input, srcRate = 46875, dstRate = 46875, channels = 1)
        // Identity: the implementation MUST return contentEquals input (or the same array)
        // so no resample artifact is introduced at the device rate.
        assertArrayEquals(
            "toRate with srcRate==46875 must return the input unchanged (no resample artifact)",
            input,
            result,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test E: 44100 → 46875 output length ratio (the common Splice-file case)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun length_44100_to_46875() {
        // 44100 mono samples at 44100 Hz = 1 second of audio
        val input = ShortArray(44100) { (it % 1000).toShort() }
        val result = Resampler.toRate(input, srcRate = 44100, dstRate = 46875, channels = 1)

        // Expected output length: round(44100 * 46875.0 / 44100.0) == 46875
        val expectedLength = (input.size * 46875.0 / 44100.0).roundToInt()
        // Allow ±1 for rounding at boundaries
        assertTrue(
            "Resampled length must be within ±1 of expected $expectedLength, got ${result.size}",
            result.size in (expectedLength - 1)..(expectedLength + 1),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test F: downsample from 48000 → 46875 (device caps at 46875, never upsamples)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun downsample_neverUpsamplesBeyond46875() {
        // 48000 > 46875: the device's max. The resampler must downsample to 46875 (A2).
        val input = ShortArray(48000) { (it % 500).toShort() }
        val result = Resampler.toRate(input, srcRate = 48000, dstRate = 46875, channels = 1)

        // Expected output length: round(48000 * 46875.0 / 48000.0) == 46875
        val expectedLength = (input.size * 46875.0 / 48000.0).roundToInt()
        assertTrue(
            "48000→46875 resampled length must be within ±1 of expected $expectedLength, got ${result.size}",
            result.size in (expectedLength - 1)..(expectedLength + 1),
        )
        // Output must be shorter than input (downsampled, never upsampled beyond 46875)
        assertTrue(
            "48000→46875 output must be shorter than input (device never upsamples beyond 46875)",
            result.size < input.size,
        )
    }
}
