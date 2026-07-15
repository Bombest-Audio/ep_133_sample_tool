package com.ep133.sampletool.domain.audio

/**
 * Cuts a frame range out of interleaved s16 LE PCM bytes.
 *
 * The frame-boundary counterpart to [LoopSlicer]'s equal slicing: where LoopSlicer tiles
 * a loop into N pieces, [trimFrames] extracts one arbitrary [startFrame, endFrame) window,
 * used by the prep pipeline for explicit start/end trims.
 *
 * Pure, no Android dependencies - fully unit-testable on the JVM.
 */
object Trimmer {

    /**
     * Return frames [startFrame] (inclusive) to [endFrame] (exclusive) of [pcm].
     *
     * Rules:
     * - Both bounds are clamped to [0, totalFrames]; a trailing incomplete frame is ignored.
     * - An empty window after clamping (start >= end) returns an empty array.
     *
     * @param pcm        Interleaved s16 LE PCM bytes (no RIFF/WAV header).
     * @param channels   Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
     * @param startFrame First frame to keep (clamped to array bounds).
     * @param endFrame   One past the last frame to keep (clamped to array bounds).
     * @return           New byte array containing only the requested frames.
     */
    fun trimFrames(pcm: ByteArray, channels: Int, startFrame: Int, endFrame: Int): ByteArray {
        require(channels >= 1) { "channels must be >= 1, was $channels" }

        val bytesPerFrame = channels * 2
        val totalFrames = pcm.size / bytesPerFrame
        val start = startFrame.coerceIn(0, totalFrames)
        val end = endFrame.coerceIn(0, totalFrames)
        if (start >= end) return ByteArray(0)
        return pcm.copyOfRange(start * bytesPerFrame, end * bytesPerFrame)
    }
}
