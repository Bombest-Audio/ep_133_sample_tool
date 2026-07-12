import Foundation

/// Splits interleaved s16 PCM into equal-length slices on FRAME boundaries.
///
/// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/LoopSlicer.kt.
/// Pure math — identical slicing behavior to the Kotlin implementation.
///
/// A "frame" is one sample per channel (e.g. stereo frame = 2 samples). Slicing
/// on frame boundaries ensures stereo interleaving is never broken across a slice
/// boundary — left/right channel pairs stay together.
enum LoopSlicer {

    /// Split `pcm` into `count` equal slices, each on a FRAME boundary.
    ///
    /// Rules:
    /// - `count` <= 1 → returns the whole array as a single-element list.
    /// - `count` > total frames → clamped to total frames (one frame per slice).
    /// - Remainder frames are folded into the last slice so no audio is lost.
    /// - Empty `pcm` → returns a single empty-array list.
    ///
    /// - Parameters:
    ///   - pcm: Interleaved signed 16-bit PCM samples.
    ///   - channels: Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
    ///   - count: Desired number of slices. Values <= 1 are treated as 1.
    /// - Returns: List of `count` (or fewer, if clamped) slices.
    static func equalSlices(_ pcm: [Int16], channels: Int, count: Int) -> [[Int16]] {
        precondition(channels >= 1, "channels must be >= 1, was \(channels)")

        // Empty or degenerate input — return one (possibly empty) slice.
        if pcm.isEmpty || count <= 1 { return [pcm] }

        let totalFrames = pcm.count / channels
        if totalFrames == 0 { return [pcm] }

        // Clamp count to at most the total number of frames.
        let sliceCount = min(count, totalFrames)
        let baseFrames = totalFrames / sliceCount   // frames per slice before remainder
        let remainder = totalFrames % sliceCount    // extra frames folded into last slice

        var result = [[Int16]]()
        result.reserveCapacity(sliceCount)
        var frameOffset = 0
        for i in 0..<sliceCount {
            let framesThisSlice = i == sliceCount - 1 ? baseFrames + remainder : baseFrames
            let sampleStart = frameOffset * channels
            let sampleEnd = sampleStart + framesThisSlice * channels
            result.append(Array(pcm[sampleStart..<sampleEnd]))
            frameOffset += framesThisSlice
        }
        return result
    }

    /// Split raw s16 LE PCM bytes into `count` equal slices, each on a FRAME boundary.
    ///
    /// Each frame is `channels` samples × 2 bytes per sample. Slice boundaries are
    /// computed with the stepped formula `start = i*frames/N, end = (i+1)*frames/N`
    /// (integer arithmetic) so that all N slices tile exactly with no dropped or
    /// duplicated samples — the last slice absorbs any remainder.
    ///
    /// This is the byte-array counterpart to `equalSlices`, used to extract per-slice
    /// PCM for independent device uploads (per-slice upload respects the EP-133's
    /// 20 s single-sample cap).
    ///
    /// Rules:
    /// - `count` <= 1 → returns the whole array as a single-element list.
    /// - `count` > total frames → clamped to total frames (one frame per slice).
    /// - Empty `pcm` → returns a single empty-array list.
    ///
    /// - Parameters:
    ///   - pcm: Interleaved s16 LE PCM bytes (no RIFF/WAV header). Size must be a
    ///     multiple of (channels * 2); any trailing incomplete frame is ignored.
    ///   - channels: Number of audio channels (1 = mono, 2 = stereo). Must be >= 1.
    ///   - count: Desired number of slices. Values <= 1 are treated as 1.
    /// - Returns: List of byte-array slices. Each entry's frame count is
    ///   `((i+1)*totalFrames/count) - (i*totalFrames/count)`.
    static func slicePcmBytes(_ pcm: Data, channels: Int, count: Int) -> [Data] {
        precondition(channels >= 1, "channels must be >= 1, was \(channels)")

        let bytesPerFrame = channels * 2
        let totalFrames = pcm.count / bytesPerFrame

        // Empty or degenerate input — return one (possibly empty) slice.
        if totalFrames == 0 || count <= 1 { return [pcm] }

        // Clamp count to at most the total number of frames.
        let sliceCount = min(count, totalFrames)

        let bytes = [UInt8](pcm)
        var result = [Data]()
        result.reserveCapacity(sliceCount)
        for i in 0..<sliceCount {
            let startFrame = Int(Int64(i) * Int64(totalFrames) / Int64(sliceCount))
            let endFrame = Int(Int64(i + 1) * Int64(totalFrames) / Int64(sliceCount))
            let byteStart = startFrame * bytesPerFrame
            let byteEnd = endFrame * bytesPerFrame
            result.append(Data(bytes[byteStart..<byteEnd]))
        }
        return result
    }

    /// Downmix interleaved stereo s16 LE PCM bytes to mono by averaging the two channels
    /// per frame. Output is exactly half the input size (mono s16 LE, same sample rate).
    ///
    /// Used by the loop chopper so chopped slices upload as mono — a drum-kit slice
    /// rarely needs stereo, and mono halves the EP-133 sample-memory footprint (the
    /// device rejects uploads with "not enough space" once /sounds fills). The
    /// whole-loop import path keeps the source channel count; only chop downmixes.
    ///
    /// - Parameter pcm: Interleaved stereo s16 LE bytes (4 bytes/frame). Any trailing
    ///   incomplete frame (size not a multiple of 4) is ignored.
    /// - Returns: Mono s16 LE bytes (2 bytes/frame).
    static func downmixStereoToMono(_ pcm: Data) -> Data {
        let bytes = [UInt8](pcm)
        let frames = bytes.count / 4
        var out = [UInt8](repeating: 0, count: frames * 2)
        var si = 0
        var di = 0
        for _ in 0..<frames {
            // s16 LE read: low byte unsigned, high byte sign-extended.
            let l = (Int(Int8(bitPattern: bytes[si + 1])) << 8) | Int(bytes[si])
            let r = (Int(Int8(bitPattern: bytes[si + 3])) << 8) | Int(bytes[si + 2])
            let m = (l + r) >> 1   // arithmetic average of two signed 16-bit samples; stays in range
            out[di] = UInt8(m & 0xFF)
            out[di + 1] = UInt8((m >> 8) & 0xFF)
            si += 4
            di += 2
        }
        return Data(out)
    }
}
