import XCTest
@testable import EP133SampleTool

/// Port of AndroidApp/app/src/test/java/com/ep133/sampletool/ResamplerTest.kt.
///
/// The identity test ensures srcRate == 46875 returns the unchanged array, preventing
/// a silent resample artifact at the device rate.
///
/// Spec:
///   - Never upsample beyond 46875 Hz; min(src, 46875) semantics.
///   - srcRate == 46875 → return input unchanged (no resample work at device rate).
///   - srcRate != 46875 → resample to 46875 by linear interpolation per channel.
final class ResamplerTests: XCTestCase {

    // ──────────────────────────────────────────────────────────────────────────
    // Test D: no-op when srcRate == 46875 (no resample at device rate)
    // ──────────────────────────────────────────────────────────────────────────

    func testNoOpWhenSrcEqualsDst() throws {
        let input: [Int16] = [100, 200, -100, 32000, -32000]
        let result = try Resampler.toRate(input, srcRate: 46875, dstRate: 46875, channels: 1)
        // Identity: the implementation MUST return the input unchanged
        // so no resample artifact is introduced at the device rate.
        XCTAssertEqual(
            input, result,
            "toRate with srcRate==46875 must return the input unchanged (no resample artifact)"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test E: 44100 → 46875 output length ratio (the common Splice-file case)
    // ──────────────────────────────────────────────────────────────────────────

    func testLength44100To46875() throws {
        // 44100 mono samples at 44100 Hz = 1 second of audio
        let input = (0..<44100).map { Int16($0 % 1000) }
        let result = try Resampler.toRate(input, srcRate: 44100, dstRate: 46875, channels: 1)

        // Expected output length: round(44100 * 46875.0 / 44100.0) == 46875
        let expectedLength = Int((Double(input.count) * 46875.0 / 44100.0).rounded())
        // Allow ±1 for rounding at boundaries
        XCTAssertTrue(
            (expectedLength - 1...expectedLength + 1).contains(result.count),
            "Resampled length must be within ±1 of expected \(expectedLength), got \(result.count)"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test F: downsample from 48000 → 46875 (device caps at 46875, never upsamples)
    // ──────────────────────────────────────────────────────────────────────────

    func testDownsampleNeverUpsamplesBeyond46875() throws {
        // 48000 > 46875: the device's max. The resampler must downsample to 46875.
        let input = (0..<48000).map { Int16($0 % 500) }
        let result = try Resampler.toRate(input, srcRate: 48000, dstRate: 46875, channels: 1)

        // Expected output length: round(48000 * 46875.0 / 48000.0) == 46875
        let expectedLength = Int((Double(input.count) * 46875.0 / 48000.0).rounded())
        XCTAssertTrue(
            (expectedLength - 1...expectedLength + 1).contains(result.count),
            "48000→46875 resampled length must be within ±1 of expected \(expectedLength), got \(result.count)"
        )
        // Output must be shorter than input (downsampled, never upsampled beyond 46875)
        XCTAssertLessThan(
            result.count, input.count,
            "48000→46875 output must be shorter than input (device never upsamples beyond 46875)"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Input guard tests — channels == 0, srcRate == 0, dstRate == 0
    // ──────────────────────────────────────────────────────────────────────────

    func testThrowsWhenChannelsIsZero() {
        XCTAssertThrowsError(
            try Resampler.toRate([1, 2, 3, 4], srcRate: 44100, dstRate: 46875, channels: 0)
        ) { error in
            XCTAssertTrue(error is ResamplerError)
        }
    }

    func testThrowsWhenSrcRateIsZero() {
        XCTAssertThrowsError(
            try Resampler.toRate([1, 2], srcRate: 0, dstRate: 46875, channels: 1)
        ) { error in
            XCTAssertTrue(error is ResamplerError)
        }
    }

    func testThrowsWhenDstRateIsZero() {
        XCTAssertThrowsError(
            try Resampler.toRate([1, 2], srcRate: 44100, dstRate: 0, channels: 1)
        ) { error in
            XCTAssertTrue(error is ResamplerError)
        }
    }
}
