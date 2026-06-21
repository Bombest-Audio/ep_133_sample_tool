package com.ep133.sampletool.domain.audio

import kotlin.math.roundToInt

/**
 * Per-channel linear interpolation resampler targeting the EP-133 K.O. II's 46875 Hz rate.
 *
 * Algorithm choice: linear interpolation (~40 lines, O(n) time, O(n) space).
 * Sinc/windowed-sinc would give slightly better frequency response at the transition band
 * but is overkill for the EP-133's 8-12 kHz effective audio bandwidth. Linear interp is
 * audibly indistinguishable for this use case (05-RESEARCH: "audibly fine for EP-133").
 *
 * Cap rule (05-RESEARCH A2): the device never upsamples beyond 46875 Hz. If the source
 * rate is already 46875, the array is returned unchanged. The [dstRate] parameter always
 * means 46875 in production; it is kept as a parameter for testability only.
 *
 * No Android imports — pure JVM; fully unit-testable on the JVM test runner.
 *
 * SAMPLE-02: pure resampling half. The device-bound decode half lands in Wave 2.
 */
object Resampler {

    /** The EP-133 K.O. II native sample rate — output is always capped here. */
    private const val DEVICE_SAMPLE_RATE = 46875

    /**
     * Resample [pcm] from [srcRate] to [dstRate] (default [DEVICE_SAMPLE_RATE]).
     *
     * Fast-path: if [srcRate] == [dstRate], returns [pcm] unchanged (referential identity,
     * no copy, no computation — Test D guard). This prevents any resample artifact when
     * the source is already at the device rate (Landmine 2).
     *
     * For [srcRate] != [dstRate]: deinterleaves by [channels], linearly resamples each
     * channel to the target rate independently, then re-interleaves the results.
     *
     * Linear interpolation per output sample i:
     *   srcPos = i * srcRate / dstRate
     *   lo     = floor(srcPos)
     *   frac   = srcPos - lo
     *   out[i] = pcm[lo] * (1 - frac) + pcm[lo+1] * frac
     *   (lo+1 clamped to last index at the tail to avoid out-of-bounds read)
     *
     * Output is clamped to [Short.MIN_VALUE]..[Short.MAX_VALUE] before conversion.
     *
     * @param pcm       Raw signed 16-bit PCM samples, interleaved for stereo.
     * @param srcRate   Source sample rate in Hz.
     * @param dstRate   Target sample rate in Hz (default 46875 — the device rate).
     * @param channels  Number of audio channels: 1 (mono) or 2 (stereo).
     * @return          Resampled samples at [dstRate], interleaved for stereo.
     * @throws IllegalArgumentException if [channels] < 1, [srcRate] <= 0, or [dstRate] <= 0.
     */
    fun toRate(
        pcm: ShortArray,
        srcRate: Int,
        dstRate: Int = DEVICE_SAMPLE_RATE,
        channels: Int = 1
    ): ShortArray {
        // Input guards — prevent ArithmeticException (div-by-zero on channels or rates).
        require(channels >= 1) { "channels must be >= 1, was $channels" }
        require(srcRate > 0) { "srcRate must be > 0, was $srcRate" }
        require(dstRate > 0) { "dstRate must be > 0, was $dstRate" }

        // Fast-path: no-op at the device rate (Landmine 2 — no resample artifact)
        if (srcRate == dstRate) return pcm

        // Number of frames (samples per channel) in the source
        val srcFrames = pcm.size / channels

        // Compute output frame count: round(srcFrames * dstRate / srcRate)
        val dstFrames = (srcFrames * dstRate.toDouble() / srcRate).roundToInt()

        // Allocate interleaved output
        val out = ShortArray(dstFrames * channels)

        // Resample each channel independently, then re-interleave
        for (ch in 0 until channels) {
            // Deinterleave: extract channel `ch` samples at stride `channels`
            val src = ShortArray(srcFrames) { frame -> pcm[frame * channels + ch] }

            // Linear interpolate to dstFrames
            for (i in 0 until dstFrames) {
                val srcPos = i * srcRate.toDouble() / dstRate
                val lo = srcPos.toInt()
                val frac = srcPos - lo
                val hiIdx = if (lo + 1 < src.size) lo + 1 else lo  // clamp at tail

                val interp = src[lo] * (1.0 - frac) + src[hiIdx] * frac

                // Clamp to Short range and re-interleave
                out[i * channels + ch] = interp
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }

        return out
    }
}
