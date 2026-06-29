package com.ep133.sampletool.domain.audio

/**
 * Splits interleaved s16 PCM into equal-length slices on FRAME boundaries.
 *
 * A "frame" is one sample per channel (e.g. stereo frame = 2 shorts). Slicing
 * on frame boundaries ensures stereo interleaving is never broken across a slice
 * boundary — left/right channel pairs stay together.
 *
 * Usage:
 *   val slices = LoopSlicer.equalSlices(pcm, channels = 2, count = 4)
 *
 * Pure, no Android dependencies — fully unit-testable on the JVM.
 */
object LoopSlicer {

    /**
     * Split [pcm] into [count] equal slices, each on a FRAME boundary.
     *
     * Rules:
     * - [count] <= 1 → returns the whole array as a single-element list.
     * - [count] > total frames → clamped to total frames (one frame per slice).
     * - Remainder frames are folded into the last slice so no audio is lost.
     * - Empty [pcm] → returns a single empty-array list.
     *
     * @param pcm      Interleaved signed 16-bit PCM samples.
     * @param channels Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
     * @param count    Desired number of slices. Values <= 1 are treated as 1.
     * @return         List of [count] (or fewer, if clamped) ShortArray slices.
     */
    fun equalSlices(pcm: ShortArray, channels: Int, count: Int): List<ShortArray> {
        require(channels >= 1) { "channels must be >= 1, was $channels" }

        // Empty or degenerate input — return one (possibly empty) slice.
        if (pcm.isEmpty() || count <= 1) return listOf(pcm.copyOf())

        val totalFrames = pcm.size / channels
        if (totalFrames == 0) return listOf(pcm.copyOf())

        // Clamp count to at most the total number of frames.
        val sliceCount = count.coerceAtMost(totalFrames)
        val baseFrames = totalFrames / sliceCount   // frames per slice before remainder
        val remainder  = totalFrames % sliceCount   // extra frames folded into last slice

        val result = ArrayList<ShortArray>(sliceCount)
        var frameOffset = 0
        for (i in 0 until sliceCount) {
            val framesThisSlice = if (i == sliceCount - 1) baseFrames + remainder else baseFrames
            val sampleStart = frameOffset * channels
            val sampleEnd   = sampleStart + framesThisSlice * channels
            result.add(pcm.copyOfRange(sampleStart, sampleEnd))
            frameOffset += framesThisSlice
        }
        return result
    }
}
