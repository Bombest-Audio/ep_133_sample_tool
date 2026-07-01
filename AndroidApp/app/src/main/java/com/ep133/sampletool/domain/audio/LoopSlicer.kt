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
 *   val byteSlices = LoopSlicer.slicePcmBytes(pcm, channels = 1, count = 4)
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

    /**
     * Split raw s16 LE PCM bytes into [count] equal slices, each on a FRAME boundary.
     *
     * Each frame is [channels] samples × 2 bytes per sample = [channels] * 2 bytes.
     * Slice boundaries are computed with the stepped formula `start = i*frames/N,
     * end = (i+1)*frames/N` (integer arithmetic) so that all N slices tile exactly
     * with no dropped or duplicated samples — the last slice absorbs any remainder.
     *
     * This is the byte-array counterpart to [equalSlices] and is used by [KitViewModel]
     * to extract per-slice PCM for independent device uploads (per-slice upload respects
     * the EP-133's 20 s single-sample cap).
     *
     * Rules:
     * - [count] <= 1 → returns the whole array as a single-element list.
     * - [count] > total frames → clamped to total frames (one frame per slice).
     * - Empty [pcm] → returns a single empty-array list.
     *
     * @param pcm      Interleaved s16 LE PCM bytes (no RIFF/WAV header). Size must be
     *                 a multiple of (channels * 2); any trailing incomplete frame is ignored.
     * @param channels Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
     * @param count    Desired number of slices. Values <= 1 are treated as 1.
     * @return         List of byte-array slices. Each entry's frame count is
     *                 `((i+1)*totalFrames/count) - (i*totalFrames/count)`.
     */
    fun slicePcmBytes(pcm: ByteArray, channels: Int, count: Int): List<ByteArray> {
        require(channels >= 1) { "channels must be >= 1, was $channels" }

        val bytesPerFrame = channels * 2
        val totalFrames = pcm.size / bytesPerFrame

        // Empty or degenerate input — return one (possibly empty) slice.
        if (totalFrames == 0 || count <= 1) return listOf(pcm.copyOf())

        // Clamp count to at most the total number of frames.
        val sliceCount = count.coerceAtMost(totalFrames)

        val result = ArrayList<ByteArray>(sliceCount)
        for (i in 0 until sliceCount) {
            val startFrame = (i.toLong() * totalFrames / sliceCount).toInt()
            val endFrame   = ((i + 1).toLong() * totalFrames / sliceCount).toInt()
            val byteStart  = startFrame * bytesPerFrame
            val byteEnd    = endFrame * bytesPerFrame
            result.add(pcm.copyOfRange(byteStart, byteEnd))
        }
        return result
    }

    /**
     * Downmix interleaved stereo s16 LE PCM bytes to mono by averaging the two channels
     * per frame. Output is exactly half the input size (mono s16 LE, same sample rate).
     *
     * Used by the loop chopper so chopped slices upload as mono — a drum-kit slice rarely
     * needs stereo, and mono halves the EP-133 sample-memory footprint (the device rejects
     * uploads with "not enough space" once /sounds fills). The whole-loop import path keeps
     * the source channel count; only chop downmixes.
     *
     * @param pcm Interleaved stereo s16 LE bytes (4 bytes/frame). Any trailing incomplete
     *            frame (size not a multiple of 4) is ignored.
     * @return    Mono s16 LE bytes (2 bytes/frame).
     */
    fun downmixStereoToMono(pcm: ByteArray): ByteArray {
        val frames = pcm.size / 4
        val out = ByteArray(frames * 2)
        var si = 0
        var di = 0
        repeat(frames) {
            // s16 LE read: low byte unsigned, high byte sign-extended (Byte.toInt() sign-extends).
            val l = (pcm[si + 1].toInt() shl 8) or (pcm[si].toInt() and 0xFF)
            val r = (pcm[si + 3].toInt() shl 8) or (pcm[si + 2].toInt() and 0xFF)
            val m = (l + r) shr 1   // arithmetic average of two signed 16-bit samples; stays in range
            out[di] = (m and 0xFF).toByte()
            out[di + 1] = ((m shr 8) and 0xFF).toByte()
            si += 4
            di += 2
        }
        return out
    }
}
