import Foundation

/// Thrown by `Resampler.toRate` on invalid arguments (mirrors Kotlin's
/// IllegalArgumentException from `require`).
enum ResamplerError: Error, Equatable {
    case invalidArgument(String)
}

/// Per-channel linear interpolation resampler targeting the EP-133 K.O. II's 46875 Hz rate.
///
/// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/Resampler.kt.
/// Pure math — output is sample-for-sample identical to the Kotlin implementation.
///
/// Cap rule: the device never upsamples beyond 46875 Hz, so the effective target is
/// clamped to the device rate — passing a higher `dstRate` cannot drive upsampling past
/// it. If the source rate is already at the target, the array is returned unchanged.
/// The `dstRate` parameter always means 46875 in production; it is kept as a parameter
/// for testability only.
enum Resampler {

    /// Resample `pcm` from `srcRate` to `dstRate` (default 46875, the device rate).
    ///
    /// Fast-path: if `srcRate` == effective target, returns `pcm` unchanged (no copy,
    /// no computation). This prevents any resample artifact when the source is already
    /// at the device rate.
    ///
    /// For `srcRate` != target: deinterleaves by `channels`, linearly resamples each
    /// channel to the target rate independently, then re-interleaves the results.
    ///
    /// Linear interpolation per output sample i:
    ///   srcPos = i * srcRate / dstRate
    ///   lo     = floor(srcPos)
    ///   frac   = srcPos - lo
    ///   out[i] = pcm[lo] * (1 - frac) + pcm[lo+1] * frac
    ///   (lo+1 clamped to last index at the tail to avoid out-of-bounds read)
    ///
    /// Output is clamped to Int16 range before conversion. Rounding is half-up toward
    /// positive infinity to match Kotlin's `roundToInt()` (Java Math.round semantics).
    ///
    /// - Parameters:
    ///   - pcm: Raw signed 16-bit PCM samples, interleaved for stereo.
    ///   - srcRate: Source sample rate in Hz.
    ///   - dstRate: Target sample rate in Hz (default 46875 — the device rate).
    ///   - channels: Number of audio channels: 1 (mono) or 2 (stereo).
    /// - Returns: Resampled samples at the target rate, interleaved for stereo.
    /// - Throws: `ResamplerError.invalidArgument` if `channels` < 1, `srcRate` <= 0,
    ///   or `dstRate` <= 0.
    static func toRate(
        _ pcm: [Int16],
        srcRate: Int,
        dstRate: Int = EP133Device.sampleRate,
        channels: Int = 1
    ) throws -> [Int16] {
        // Input guards — prevent division by zero on channels or rates.
        guard channels >= 1 else {
            throw ResamplerError.invalidArgument("channels must be >= 1, was \(channels)")
        }
        guard srcRate > 0 else {
            throw ResamplerError.invalidArgument("srcRate must be > 0, was \(srcRate)")
        }
        guard dstRate > 0 else {
            throw ResamplerError.invalidArgument("dstRate must be > 0, was \(dstRate)")
        }

        // Clamp the target: the device never upsamples beyond its native rate, so a
        // dstRate above the device rate is capped rather than honored.
        let targetRate = min(dstRate, EP133Device.sampleRate)

        // Fast-path: no-op at the target rate (no resample artifact)
        if srcRate == targetRate { return pcm }

        // Number of frames (samples per channel) in the source
        let srcFrames = pcm.count / channels

        // Compute output frame count: round(srcFrames * targetRate / srcRate)
        let dstFrames = roundHalfUp(Double(srcFrames) * Double(targetRate) / Double(srcRate))

        // Allocate interleaved output
        var out = [Int16](repeating: 0, count: dstFrames * channels)

        // Resample each channel independently, then re-interleave
        for ch in 0..<channels {
            // Deinterleave: extract channel `ch` samples at stride `channels`
            var src = [Int16](repeating: 0, count: srcFrames)
            for frame in 0..<srcFrames {
                src[frame] = pcm[frame * channels + ch]
            }

            // Linear interpolate to dstFrames
            for i in 0..<dstFrames {
                let srcPos = Double(i) * Double(srcRate) / Double(targetRate)
                let lo = Int(srcPos)
                let frac = srcPos - Double(lo)
                let hiIdx = lo + 1 < src.count ? lo + 1 : lo  // clamp at tail

                let interp = Double(src[lo]) * (1.0 - frac) + Double(src[hiIdx]) * frac

                // Clamp to Int16 range and re-interleave
                let rounded = roundHalfUp(interp)
                out[i * channels + ch] = Int16(max(Int(Int16.min), min(Int(Int16.max), rounded)))
            }
        }

        return out
    }

    /// Round half-up toward positive infinity — Kotlin `Double.roundToInt()` /
    /// Java `Math.round(double)` semantics: floor(x + 0.5).
    private static func roundHalfUp(_ x: Double) -> Int {
        Int((x + 0.5).rounded(.down))
    }
}
