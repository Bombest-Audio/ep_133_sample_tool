import XCTest
@testable import EP133SampleTool

/// Port of AndroidApp/app/src/test/java/com/ep133/sampletool/LoopSlicerTest.kt.
///
/// `equalSlices` covers:
///  - Mono even split
///  - Stereo: interleave preserved + even frame boundaries
///  - Remainder folded into the last slice
///  - count=1 returns the whole array
///  - count > total frames is clamped to total frames
///
/// `slicePcmBytes` covers:
///  - Mono even split produces correct byte arrays
///  - Slices tile exactly (concatenated = original)
///  - Stereo frame boundaries respected (byte count multiple of channels*2)
///  - count=1 returns whole array
///  - count > totalFrames clamped
///  - Empty input → single empty slice
final class LoopSlicerTests: XCTestCase {

    // ── Test A: Mono even split ─────────────────────────────────────────────────

    func testMonoEvenSplitReturnsEqualSlices() {
        // 8 mono frames, split into 4 → each slice = 2 samples
        let pcm = (0..<8).map { Int16($0) }   // [0,1,2,3,4,5,6,7]
        let slices = LoopSlicer.equalSlices(pcm, channels: 1, count: 4)

        XCTAssertEqual(4, slices.count, "slice count")
        XCTAssertEqual([0, 1], slices[0], "slice 0")
        XCTAssertEqual([2, 3], slices[1], "slice 1")
        XCTAssertEqual([4, 5], slices[2], "slice 2")
        XCTAssertEqual([6, 7], slices[3], "slice 3")
    }

    // ── Test B: Stereo interleave preserved + even frame boundaries ─────────────

    func testStereoEvenSplitPreservesInterleaving() {
        // 4 stereo frames (L0,R0, L1,R1, L2,R2, L3,R3), split into 2
        let pcm: [Int16] = [10, -10, 20, -20, 30, -30, 40, -40]
        let slices = LoopSlicer.equalSlices(pcm, channels: 2, count: 2)

        XCTAssertEqual(2, slices.count, "slice count")
        // Each slice = 2 frames = 4 samples
        XCTAssertEqual([10, -10, 20, -20], slices[0], "slice 0")
        XCTAssertEqual([30, -30, 40, -40], slices[1], "slice 1")
    }

    func testStereoOddFramesNeverBreaksFrame() {
        // 6 stereo frames (12 samples), split into 4
        // baseFrames = 6/4 = 1, remainder = 2 → slices: 1,1,1,3 frames
        let pcm = (0..<12).map { Int16($0) }
        let slices = LoopSlicer.equalSlices(pcm, channels: 2, count: 4)

        XCTAssertEqual(4, slices.count, "slice count")
        // Every slice has an even number of samples (frame boundary check)
        for (i, s) in slices.enumerated() {
            XCTAssertEqual(0, s.count % 2, "slice \(i) sample count is even")
        }
        // Last slice absorbs remainder: 1 base + 2 remainder = 3 frames = 6 samples
        XCTAssertEqual(6, slices[3].count, "last slice size")
        // Total samples preserved
        let total = slices.reduce(0) { $0 + $1.count }
        XCTAssertEqual(12, total, "total samples")
    }

    // ── Test C: Remainder folded into last slice ────────────────────────────────

    func testRemainderFoldedIntoLastSlice() {
        // 10 mono frames, split into 3 → base=3, remainder=1 → slices: 3,3,4
        let pcm = (0..<10).map { Int16($0) }
        let slices = LoopSlicer.equalSlices(pcm, channels: 1, count: 3)

        XCTAssertEqual(3, slices.count, "slice count")
        XCTAssertEqual(3, slices[0].count, "slice 0 size")
        XCTAssertEqual(3, slices[1].count, "slice 1 size")
        XCTAssertEqual(4, slices[2].count, "slice 2 size (with remainder)")

        // Content check: all original samples present
        let reassembled = slices.flatMap { $0 }
        XCTAssertEqual(pcm, reassembled, "samples preserved")
    }

    // ── Test D: count=1 returns the whole array ─────────────────────────────────

    func testCountOneReturnsWholeArray() {
        let pcm: [Int16] = [1, 2, 3, 4, 5]
        let slices = LoopSlicer.equalSlices(pcm, channels: 1, count: 1)

        XCTAssertEqual(1, slices.count, "one slice")
        XCTAssertEqual(pcm, slices[0], "content unchanged")
    }

    func testCountZeroTreatedAsOne() {
        let pcm: [Int16] = [7, 8, 9]
        let slices = LoopSlicer.equalSlices(pcm, channels: 1, count: 0)

        XCTAssertEqual(1, slices.count, "one slice")
        XCTAssertEqual(pcm, slices[0], "content unchanged")
    }

    // ── Test E: count > total frames is clamped ─────────────────────────────────

    func testCountExceedsFramesClampedToFrameCount() {
        // 3 mono frames, requesting 100 slices → clamped to 3
        let pcm: [Int16] = [10, 20, 30]
        let slices = LoopSlicer.equalSlices(pcm, channels: 1, count: 100)

        XCTAssertEqual(3, slices.count, "clamped to 3 slices")
        XCTAssertEqual([10], slices[0], "slice 0")
        XCTAssertEqual([20], slices[1], "slice 1")
        XCTAssertEqual([30], slices[2], "slice 2")
    }

    // ── Test F: empty PCM ────────────────────────────────────────────────────────

    func testEmptyPcmReturnsSingleEmptySlice() {
        let slices = LoopSlicer.equalSlices([], channels: 1, count: 4)

        XCTAssertEqual(1, slices.count, "one slice")
        XCTAssertEqual(0, slices[0].count, "empty slice")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // slicePcmBytes tests
    // ─────────────────────────────────────────────────────────────────────────

    // ── Test G: mono even split produces correct byte arrays ─────────────────

    func testSlicePcmBytesMonoEvenSplitCorrectBytes() {
        // 8 mono frames = 16 bytes (2 bytes/frame), split into 4 → each slice = 2 frames = 4 bytes.
        let pcm = Data((0..<16).map { UInt8($0) })
        let slices = LoopSlicer.slicePcmBytes(pcm, channels: 1, count: 4)

        XCTAssertEqual(4, slices.count, "slice count")
        XCTAssertEqual(Data([0, 1, 2, 3]), slices[0], "slice 0")
        XCTAssertEqual(Data([4, 5, 6, 7]), slices[1], "slice 1")
        XCTAssertEqual(Data([8, 9, 10, 11]), slices[2], "slice 2")
        XCTAssertEqual(Data([12, 13, 14, 15]), slices[3], "slice 3")
    }

    // ── Test H: slices tile exactly — concatenation equals original ───────────

    func testSlicePcmBytesTilesExactlyNoDroppedBytes() {
        // 10 mono frames = 20 bytes, split into 3 → stepped: 0..3, 3..6, 6..10 (remainder in last)
        let pcm = Data((0..<20).map { UInt8(truncatingIfNeeded: $0 * 3) })
        let slices = LoopSlicer.slicePcmBytes(pcm, channels: 1, count: 3)

        XCTAssertEqual(3, slices.count, "slice count")
        let reassembled = slices.reduce(Data()) { $0 + $1 }
        XCTAssertEqual(pcm, reassembled, "concatenation must equal original PCM")
    }

    // ── Test I: stereo frame boundaries respected ─────────────────────────────

    func testSlicePcmBytesStereoFramesBoundariesRespected() {
        // 6 stereo frames (12 samples = 24 bytes), split into 3 → each slice = 2 frames = 8 bytes.
        let pcm = Data((0..<24).map { UInt8($0) })
        let slices = LoopSlicer.slicePcmBytes(pcm, channels: 2, count: 3)

        XCTAssertEqual(3, slices.count, "slice count")
        let bytesPerFrame = 4  // channels=2, 2 bytes each
        for (i, s) in slices.enumerated() {
            XCTAssertEqual(0, s.count % bytesPerFrame, "slice \(i) byte count is multiple of bytesPerFrame")
        }
        // Total bytes preserved.
        let total = slices.reduce(0) { $0 + $1.count }
        XCTAssertEqual(24, total, "total bytes")
    }

    // ── Test J: count=1 returns the whole array ───────────────────────────────

    func testSlicePcmBytesCountOneReturnsWholeArray() {
        let pcm = Data([1, 2, 3, 4])
        let slices = LoopSlicer.slicePcmBytes(pcm, channels: 1, count: 1)

        XCTAssertEqual(1, slices.count, "one slice")
        XCTAssertEqual(pcm, slices[0], "content unchanged")
    }

    // ── Test K: count > totalFrames is clamped ────────────────────────────────

    func testSlicePcmBytesCountExceedsFramesClampedToFrameCount() {
        // 3 mono frames = 6 bytes, requesting 100 slices → clamped to 3.
        let pcm = Data([10, 11, 20, 21, 30, 31])
        let slices = LoopSlicer.slicePcmBytes(pcm, channels: 1, count: 100)

        XCTAssertEqual(3, slices.count, "clamped to 3 slices")
        XCTAssertEqual(Data([10, 11]), slices[0], "slice 0")
        XCTAssertEqual(Data([20, 21]), slices[1], "slice 1")
        XCTAssertEqual(Data([30, 31]), slices[2], "slice 2")
    }

    // ── Test L: empty PCM → single empty slice ────────────────────────────────

    func testSlicePcmBytesEmptyPcmReturnsSingleEmptySlice() {
        let slices = LoopSlicer.slicePcmBytes(Data(), channels: 1, count: 4)

        XCTAssertEqual(1, slices.count, "one slice")
        XCTAssertEqual(0, slices[0].count, "empty slice")
    }

    // ── downmixStereoToMono ───────────────────────────────────────────────────

    private func leShort(_ v: Int) -> [UInt8] {
        [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)]
    }

    private func stereo(_ lr: Int...) -> Data {
        var out = [UInt8]()
        for v in lr { out.append(contentsOf: leShort(v)) }
        return Data(out)
    }

    func testDownmixAveragesChannelsHalvesSize() {
        // frame0 L=1000 R=2000 → 1500 ; frame1 L=-1000 R=-2000 → -1500
        let pcm = stereo(1000, 2000, -1000, -2000)
        let mono = LoopSlicer.downmixStereoToMono(pcm)

        XCTAssertEqual(pcm.count / 2, mono.count, "mono is half the byte size")
        let bytes = [UInt8](mono)
        XCTAssertEqual(leShort(1500), Array(bytes[0..<2]), "frame0 avg 1500")
        XCTAssertEqual(leShort(-1500), Array(bytes[2..<4]), "frame1 avg -1500")
    }

    func testDownmixTrailingPartialFrameIgnored() {
        // 6 bytes = one full stereo frame (4B) + 2 dangling bytes → one mono frame out.
        let pcm = Data([0, 0, 0, 0, 99, 99])
        let mono = LoopSlicer.downmixStereoToMono(pcm)
        XCTAssertEqual(2, mono.count, "only the full frame is emitted")
    }
}
