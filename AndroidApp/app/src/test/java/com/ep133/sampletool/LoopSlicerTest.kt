package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.LoopSlicer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LoopSlicer.equalSlices] and [LoopSlicer.slicePcmBytes].
 *
 * [equalSlices] covers:
 *  - Mono even split
 *  - Stereo: interleave preserved + even frame boundaries
 *  - Remainder folded into the last slice
 *  - count=1 returns the whole array
 *  - count > total frames is clamped to total frames
 *
 * [slicePcmBytes] covers:
 *  - Mono even split produces correct byte arrays
 *  - Slices tile exactly (concatenated = original)
 *  - Stereo frame boundaries respected (byte count multiple of channels*2)
 *  - count=1 returns whole array
 *  - count > totalFrames clamped
 *  - Empty input → single empty slice
 */
class LoopSlicerTest {

    // ── Test A: Mono even split ─────────────────────────────────────────────────

    @Test
    fun monoEvenSplit_returnsEqualSlices() {
        // 8 mono frames, split into 4 → each slice = 2 samples
        val pcm = ShortArray(8) { it.toShort() }   // [0,1,2,3,4,5,6,7]
        val slices = LoopSlicer.equalSlices(pcm, channels = 1, count = 4)

        assertEquals("slice count", 4, slices.size)
        assertArrayEquals("slice 0", shortArrayOf(0, 1), slices[0])
        assertArrayEquals("slice 1", shortArrayOf(2, 3), slices[1])
        assertArrayEquals("slice 2", shortArrayOf(4, 5), slices[2])
        assertArrayEquals("slice 3", shortArrayOf(6, 7), slices[3])
    }

    // ── Test B: Stereo interleave preserved + even frame boundaries ─────────────

    @Test
    fun stereoEvenSplit_preservesInterleaving() {
        // 4 stereo frames (L0,R0, L1,R1, L2,R2, L3,R3), split into 2
        val pcm = shortArrayOf(10, -10, 20, -20, 30, -30, 40, -40)
        val slices = LoopSlicer.equalSlices(pcm, channels = 2, count = 2)

        assertEquals("slice count", 2, slices.size)
        // Each slice = 2 frames = 4 samples
        assertArrayEquals("slice 0", shortArrayOf(10, -10, 20, -20), slices[0])
        assertArrayEquals("slice 1", shortArrayOf(30, -30, 40, -40), slices[1])
    }

    @Test
    fun stereoOddFrames_neverBreaksFrame() {
        // 6 stereo frames (12 samples), split into 4
        // baseFrames = 6/4 = 1, remainder = 2 → slices: 1,1,1,3 frames
        val pcm = ShortArray(12) { it.toShort() }
        val slices = LoopSlicer.equalSlices(pcm, channels = 2, count = 4)

        assertEquals("slice count", 4, slices.size)
        // Every slice has an even number of samples (frame boundary check)
        for ((i, s) in slices.withIndex()) {
            assertEquals("slice $i sample count is even", 0, s.size % 2)
        }
        // Last slice absorbs remainder: 1 base + 2 remainder = 3 frames = 6 samples
        assertEquals("last slice size", 6, slices[3].size)
        // Total samples preserved
        val total = slices.sumOf { it.size }
        assertEquals("total samples", 12, total)
    }

    // ── Test C: Remainder folded into last slice ────────────────────────────────

    @Test
    fun remainderFoldedIntoLastSlice() {
        // 10 mono frames, split into 3 → base=3, remainder=1 → slices: 3,3,4
        val pcm = ShortArray(10) { it.toShort() }
        val slices = LoopSlicer.equalSlices(pcm, channels = 1, count = 3)

        assertEquals("slice count", 3, slices.size)
        assertEquals("slice 0 size", 3, slices[0].size)
        assertEquals("slice 1 size", 3, slices[1].size)
        assertEquals("slice 2 size (with remainder)", 4, slices[2].size)

        // Content check: all original samples present
        val reassembled = slices.flatMap { it.toList() }
        assertArrayEquals("samples preserved", pcm.toTypedArray(), reassembled.toTypedArray())
    }

    // ── Test D: count=1 returns the whole array ─────────────────────────────────

    @Test
    fun countOne_returnsWholeArray() {
        val pcm = shortArrayOf(1, 2, 3, 4, 5)
        val slices = LoopSlicer.equalSlices(pcm, channels = 1, count = 1)

        assertEquals("one slice", 1, slices.size)
        assertArrayEquals("content unchanged", pcm, slices[0])
    }

    @Test
    fun countZero_treatedAsOne() {
        val pcm = shortArrayOf(7, 8, 9)
        val slices = LoopSlicer.equalSlices(pcm, channels = 1, count = 0)

        assertEquals("one slice", 1, slices.size)
        assertArrayEquals("content unchanged", pcm, slices[0])
    }

    // ── Test E: count > total frames is clamped ─────────────────────────────────

    @Test
    fun countExceedsFrames_clampedToFrameCount() {
        // 3 mono frames, requesting 100 slices → clamped to 3
        val pcm = shortArrayOf(10, 20, 30)
        val slices = LoopSlicer.equalSlices(pcm, channels = 1, count = 100)

        assertEquals("clamped to 3 slices", 3, slices.size)
        assertArrayEquals("slice 0", shortArrayOf(10), slices[0])
        assertArrayEquals("slice 1", shortArrayOf(20), slices[1])
        assertArrayEquals("slice 2", shortArrayOf(30), slices[2])
    }

    // ── Test F: empty PCM ────────────────────────────────────────────────────────

    @Test
    fun emptyPcm_returnsSingleEmptySlice() {
        val slices = LoopSlicer.equalSlices(ShortArray(0), channels = 1, count = 4)

        assertEquals("one slice", 1, slices.size)
        assertEquals("empty slice", 0, slices[0].size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // slicePcmBytes tests
    // ─────────────────────────────────────────────────────────────────────────

    // ── Test G: mono even split produces correct byte arrays ─────────────────

    @Test
    fun slicePcmBytes_monoEvenSplit_correctBytes() {
        // 8 mono frames = 16 bytes (2 bytes/frame), split into 4 → each slice = 2 frames = 4 bytes.
        val pcm = ByteArray(16) { it.toByte() }
        val slices = LoopSlicer.slicePcmBytes(pcm, channels = 1, count = 4)

        assertEquals("slice count", 4, slices.size)
        assertArrayEquals("slice 0", byteArrayOf(0, 1, 2, 3), slices[0])
        assertArrayEquals("slice 1", byteArrayOf(4, 5, 6, 7), slices[1])
        assertArrayEquals("slice 2", byteArrayOf(8, 9, 10, 11), slices[2])
        assertArrayEquals("slice 3", byteArrayOf(12, 13, 14, 15), slices[3])
    }

    // ── Test H: slices tile exactly — concatenation equals original ───────────

    @Test
    fun slicePcmBytes_tilesExactly_noDroppedBytes() {
        // 10 mono frames = 20 bytes, split into 3 → stepped: 0..3, 3..6, 6..10 (remainder in last)
        val pcm = ByteArray(20) { (it * 3).toByte() }
        val slices = LoopSlicer.slicePcmBytes(pcm, channels = 1, count = 3)

        assertEquals("slice count", 3, slices.size)
        val reassembled = slices.flatMap { it.toList() }.toByteArray()
        assertArrayEquals("concatenation must equal original PCM", pcm, reassembled)
    }

    // ── Test I: stereo frame boundaries respected ─────────────────────────────

    @Test
    fun slicePcmBytes_stereo_framesBoundariesRespected() {
        // 6 stereo frames (12 samples = 24 bytes), split into 3 → each slice = 2 frames = 8 bytes.
        val pcm = ByteArray(24) { it.toByte() }
        val slices = LoopSlicer.slicePcmBytes(pcm, channels = 2, count = 3)

        assertEquals("slice count", 3, slices.size)
        val bytesPerFrame = 4  // channels=2, 2 bytes each
        for ((i, s) in slices.withIndex()) {
            assertEquals("slice $i byte count is multiple of bytesPerFrame", 0, s.size % bytesPerFrame)
        }
        // Total bytes preserved.
        val total = slices.sumOf { it.size }
        assertEquals("total bytes", 24, total)
    }

    // ── Test J: count=1 returns the whole array ───────────────────────────────

    @Test
    fun slicePcmBytes_countOne_returnsWholeArray() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val slices = LoopSlicer.slicePcmBytes(pcm, channels = 1, count = 1)

        assertEquals("one slice", 1, slices.size)
        assertArrayEquals("content unchanged", pcm, slices[0])
    }

    // ── Test K: count > totalFrames is clamped ────────────────────────────────

    @Test
    fun slicePcmBytes_countExceedsFrames_clampedToFrameCount() {
        // 3 mono frames = 6 bytes, requesting 100 slices → clamped to 3.
        val pcm = byteArrayOf(10, 11, 20, 21, 30, 31)
        val slices = LoopSlicer.slicePcmBytes(pcm, channels = 1, count = 100)

        assertEquals("clamped to 3 slices", 3, slices.size)
        assertArrayEquals("slice 0", byteArrayOf(10, 11), slices[0])
        assertArrayEquals("slice 1", byteArrayOf(20, 21), slices[1])
        assertArrayEquals("slice 2", byteArrayOf(30, 31), slices[2])
    }

    // ── Test L: empty PCM → single empty slice ────────────────────────────────

    @Test
    fun slicePcmBytes_emptyPcm_returnsSingleEmptySlice() {
        val slices = LoopSlicer.slicePcmBytes(ByteArray(0), channels = 1, count = 4)

        assertEquals("one slice", 1, slices.size)
        assertEquals("empty slice", 0, slices[0].size)
    }

    // ── downmixStereoToMono ───────────────────────────────────────────────────

    private fun leShort(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun stereo(vararg lr: Int): ByteArray {
        val out = ByteArray(lr.size * 2)
        lr.forEachIndexed { i, v -> leShort(v).copyInto(out, i * 2) }
        return out
    }

    @Test
    fun downmix_averagesChannels_halvesSize() {
        // frame0 L=1000 R=2000 → 1500 ; frame1 L=-1000 R=-2000 → -1500
        val pcm = stereo(1000, 2000, -1000, -2000)
        val mono = LoopSlicer.downmixStereoToMono(pcm)

        assertEquals("mono is half the byte size", pcm.size / 2, mono.size)
        assertArrayEquals("frame0 avg 1500", leShort(1500), mono.copyOfRange(0, 2))
        assertArrayEquals("frame1 avg -1500", leShort(-1500), mono.copyOfRange(2, 4))
    }

    @Test
    fun downmix_trailingPartialFrame_ignored() {
        // 6 bytes = one full stereo frame (4B) + 2 dangling bytes → one mono frame out.
        val pcm = byteArrayOf(0, 0, 0, 0, 99, 99)
        val mono = LoopSlicer.downmixStereoToMono(pcm)
        assertEquals("only the full frame is emitted", 2, mono.size)
    }
}
