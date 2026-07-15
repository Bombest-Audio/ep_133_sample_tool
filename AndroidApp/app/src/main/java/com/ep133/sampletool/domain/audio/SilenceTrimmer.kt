package com.ep133.sampletool.domain.audio

import kotlin.math.abs

/**
 * Trims leading and trailing silence from interleaved s16 LE PCM bytes.
 *
 * "Silence" is any FRAME whose loudest channel sample is below a dBFS threshold. Trimming
 * happens on frame boundaries (like [LoopSlicer]) so stereo interleaving is never broken,
 * and a configurable padding of audio is kept on both sides of the detected content so
 * transients are not clipped.
 *
 * Pure, no Android dependencies - fully unit-testable on the JVM.
 */
object SilenceTrimmer {

    /** Default silence threshold: -60 dBFS. */
    const val DEFAULT_THRESHOLD_DBFS = -60.0

    /** Default padding kept around the detected content, in milliseconds. */
    const val DEFAULT_PADDING_MS = 5

    /**
     * Remove leading and trailing frames quieter than [thresholdDbfs], keeping
     * [paddingMs] of the original audio on each side of the loud region.
     *
     * Rules:
     * - Empty input returns an empty array.
     * - ALL-silent input returns an unmodified copy: trimming a sample to zero length
     *   would break the upload pipeline, so a fully quiet file passes through untouched.
     * - Padding is clamped to the array bounds; a trailing incomplete frame is ignored.
     *
     * @param pcm           Interleaved s16 LE PCM bytes (no RIFF/WAV header).
     * @param channels      Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
     * @param sampleRate    Sample rate in Hz, used to convert [paddingMs] to frames.
     * @param thresholdDbfs Frames whose peak is below this level count as silence.
     * @param paddingMs     Milliseconds of original audio preserved before/after content.
     * @return              New byte array containing only the kept frames.
     */
    fun trim(
        pcm: ByteArray,
        channels: Int,
        sampleRate: Int,
        thresholdDbfs: Double = DEFAULT_THRESHOLD_DBFS,
        paddingMs: Int = DEFAULT_PADDING_MS,
    ): ByteArray {
        require(channels >= 1) { "channels must be >= 1, was $channels" }
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        require(paddingMs >= 0) { "paddingMs must be >= 0, was $paddingMs" }

        val bytesPerFrame = channels * 2
        val totalFrames = pcm.size / bytesPerFrame
        if (totalFrames == 0) return pcm.copyOf()

        val thresholdLinear = Math.pow(10.0, thresholdDbfs / 20.0) * Short.MAX_VALUE

        fun frameIsLoud(frame: Int): Boolean {
            val base = frame * bytesPerFrame
            for (ch in 0 until channels) {
                val o = base + ch * 2
                val s = (pcm[o + 1].toInt() shl 8) or (pcm[o].toInt() and 0xFF)
                if (abs(s) >= thresholdLinear) return true
            }
            return false
        }

        var first = -1
        for (f in 0 until totalFrames) {
            if (frameIsLoud(f)) { first = f; break }
        }
        // All-silence: pass through unchanged (see rule above).
        if (first < 0) return pcm.copyOf()

        var last = first
        for (f in totalFrames - 1 downTo first) {
            if (frameIsLoud(f)) { last = f; break }
        }

        val padFrames = (paddingMs.toLong() * sampleRate / 1000L).toInt()
        val startFrame = (first - padFrames).coerceAtLeast(0)
        val endFrame = (last + 1 + padFrames).coerceAtMost(totalFrames)
        return pcm.copyOfRange(startFrame * bytesPerFrame, endFrame * bytesPerFrame)
    }
}
