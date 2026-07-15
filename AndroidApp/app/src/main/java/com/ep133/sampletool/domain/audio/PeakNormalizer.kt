package com.ep133.sampletool.domain.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Peak-normalizes interleaved s16 LE PCM bytes to a target dBFS peak level.
 *
 * Channel-agnostic: peak detection and gain scaling operate per sample, so mono and
 * interleaved stereo both work without a channels parameter (interleaving is preserved
 * because every sample is scaled by the same gain).
 *
 * Pure, no Android dependencies - fully unit-testable on the JVM. Operates on the same
 * s16 LE byte layout as [LoopSlicer] and ConvertedSample.pcm.
 */
object PeakNormalizer {

    /** Default target peak: -0.3 dBFS, a conventional safety margin below full scale. */
    const val DEFAULT_TARGET_DBFS = -0.3

    /**
     * Scale [pcm] so its peak sample lands at [targetDbfs] (relative to s16 full scale).
     *
     * Rules:
     * - Digital silence (all-zero) or empty input returns an unmodified copy - there is
     *   no peak to normalize and amplifying silence only raises the noise floor.
     * - Gain is applied uniformly to every sample; results are rounded and clamped to
     *   the s16 range so downward AND upward normalization are both safe.
     * - Any trailing incomplete sample (odd byte count) is ignored.
     *
     * @param pcm        Interleaved s16 LE PCM bytes (no RIFF/WAV header).
     * @param targetDbfs Target peak in dBFS; must be <= 0.0.
     * @return           New byte array with the gain applied.
     */
    fun normalize(pcm: ByteArray, targetDbfs: Double = DEFAULT_TARGET_DBFS): ByteArray {
        require(targetDbfs <= 0.0) { "targetDbfs must be <= 0.0, was $targetDbfs" }

        val samples = pcm.size / 2
        var peak = 0
        var i = 0
        repeat(samples) {
            val s = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            val a = abs(s)
            if (a > peak) peak = a
            i += 2
        }
        if (peak == 0) return pcm.copyOf()

        val targetLinear = Math.pow(10.0, targetDbfs / 20.0) * Short.MAX_VALUE
        val gain = targetLinear / peak

        val out = ByteArray(samples * 2)
        i = 0
        repeat(samples) {
            val s = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            val scaled = (s * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (scaled and 0xFF).toByte()
            out[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
