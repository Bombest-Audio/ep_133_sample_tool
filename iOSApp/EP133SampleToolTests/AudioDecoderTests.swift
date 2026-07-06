import XCTest
@testable import EP133SampleTool

/// Port of AndroidApp/app/src/test/java/com/ep133/sampletool/AudioDecoderTest.kt.
///
/// Covers all supported `pcmBytesToShorts` encoding paths:
///  - encodingPCM16Bit: LE Int16 round-trip
///  - encodingPCMFloat: LE Float32 → s16 with ±1.0 clamping
///  - encodingPCM24BitPacked: 3-byte LE → s16 downshift
///  - encodingPCM32Bit: 4-byte LE → s16 downshift
///  - Unknown encoding: throws
///
/// The Kotlin decode() path is MediaCodec-bound and has no JVM unit tests; on iOS the
/// AVFoundation decode path IS testable in the simulator, so this suite adds decode
/// round-trip tests against WAV fixtures built by WavEncoder.
final class AudioDecoderTests: XCTestCase {

    // ── Fixture helpers ─────────────────────────────────────────────────────

    private func int16Bytes(_ values: [Int16]) -> Data {
        var out = [UInt8]()
        for v in values {
            let u = UInt16(bitPattern: v)
            out.append(UInt8(u & 0xFF))
            out.append(UInt8(u >> 8))
        }
        return Data(out)
    }

    private func floatBytes(_ values: [Float]) -> Data {
        var out = [UInt8]()
        for v in values {
            let u = v.bitPattern
            out.append(UInt8(u & 0xFF))
            out.append(UInt8((u >> 8) & 0xFF))
            out.append(UInt8((u >> 16) & 0xFF))
            out.append(UInt8(u >> 24))
        }
        return Data(out)
    }

    private func int32Bytes(_ values: [Int32]) -> Data {
        var out = [UInt8]()
        for v in values {
            let u = UInt32(bitPattern: v)
            out.append(UInt8(u & 0xFF))
            out.append(UInt8((u >> 8) & 0xFF))
            out.append(UInt8((u >> 16) & 0xFF))
            out.append(UInt8(u >> 24))
        }
        return Data(out)
    }

    // ── encodingPCM16Bit ────────────────────────────────────────────────────

    func testPcmBytesToShorts16BitRoundTrip() throws {
        let input: [Int16] = [0, 1000, -1000, Int16.max, Int16.min]
        let bytes = int16Bytes(input)

        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCM16Bit)

        XCTAssertEqual(input, result, "16-bit LE round-trip must be lossless")
    }

    func testPcmBytesToShorts16BitEmptyInput() throws {
        let result = try pcmBytesToShorts(Data(), encoding: AudioDecoder.encodingPCM16Bit)
        XCTAssertEqual(0, result.count, "empty input → empty output")
    }

    // ── encodingPCMFloat ────────────────────────────────────────────────────

    func testPcmBytesToShortsFloatKnownValues() throws {
        // Known Float32 inputs and their expected Int16 outputs.
        // Exact computed values only — ties are brittle across rounding modes.
        let floats: [Float] = [0, 1, -1]
        let expected: [Int16] = [0, 32767, -32767]

        let bytes = floatBytes(floats)

        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCMFloat)

        XCTAssertEqual(expected, result, "Float32 → s16 known values")
    }

    func testPcmBytesToShortsFloatZero() throws {
        let bytes = floatBytes([0])
        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCMFloat)
        XCTAssertEqual(0, Int(result[0]), "0.0f → 0")
    }

    func testPcmBytesToShortsFloatClampingAbove1() throws {
        let floats: [Float] = [2, 3.14]  // both > 1.0 → must clamp to 32767
        let bytes = floatBytes(floats)

        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCMFloat)

        for s in result {
            XCTAssertEqual(32767, Int(s), "Values > 1.0 must clamp to 32767")
        }
    }

    func testPcmBytesToShortsFloatClampingBelow1() throws {
        let floats: [Float] = [-2, -1.5]  // both < -1.0 → must clamp to -32767
        let bytes = floatBytes(floats)

        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCMFloat)

        for s in result {
            XCTAssertEqual(-32767, Int(s), "Values < -1.0 must clamp to -32767")
        }
    }

    // ── encodingPCM24BitPacked ──────────────────────────────────────────────

    func testPcmBytesToShorts24BitPositiveValue() throws {
        // 0x007F00 in 24-bit LE = [0x00, 0x7F, 0x00] → s24 = 0x007F00 → downshift 8 = 0x007F = 127
        let bytes = Data([0x00, 0x7F, 0x00])
        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCM24BitPacked)
        XCTAssertEqual(127, Int(result[0]), "24-bit 0x007F00 → s16 = 127")
    }

    func testPcmBytesToShorts24BitNegativeValue() throws {
        // 0xFF8000 in 24-bit LE = [0x00, 0x80, 0xFF]
        // s24 = sign-extended: 0xFF8000 → as signed int = -32768 → downshift 8 = -128
        let bytes = Data([0x00, 0x80, 0xFF])
        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCM24BitPacked)
        XCTAssertEqual(-128, Int(result[0]), "24-bit 0xFF8000 → s16 = -128")
    }

    func testPcmBytesToShorts24BitZero() throws {
        let bytes = Data([0x00, 0x00, 0x00])
        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCM24BitPacked)
        XCTAssertEqual(0, Int(result[0]), "24-bit 0 → s16 = 0")
    }

    // ── encodingPCM32Bit ────────────────────────────────────────────────────

    func testPcmBytesToShorts32BitKnownValues() throws {
        // Int32 max positive in s32 space: 0x7FFF0000 → downshift 16 → 0x7FFF = 32767
        let values: [Int32] = [0x7FFF0000, 0x00000000, Int32(bitPattern: 0x80010000)]
        let expected: [Int16] = [32767, 0, -32767]

        let bytes = int32Bytes(values)

        let result = try pcmBytesToShorts(bytes, encoding: AudioDecoder.encodingPCM32Bit)

        XCTAssertEqual(expected, result, "32-bit → s16 downshift known values")
    }

    // ── Unknown encoding ────────────────────────────────────────────────────

    func testPcmBytesToShortsUnknownEncodingThrowsIOException() {
        XCTAssertThrowsError(try pcmBytesToShorts(Data(count: 4), encoding: 0xDEAD)) { error in
            XCTAssertEqual(AudioDecoderError.unsupportedEncoding(0xDEAD), error as? AudioDecoderError)
        }
    }

    // ── decode() — AVFoundation path (no Kotlin counterpart; MediaCodec is  ──
    // ── untestable on the JVM, but AVAudioFile runs fine in the simulator)  ──

    private func writeTempWav(_ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("wav")
        try data.write(to: url)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return url
    }

    func testDecodeWavMonoRoundTrip() throws {
        let input: [Int16] = [0, 1000, -1000, 32767, -32768, 12345, -12345]
        let wav = WavEncoder.encodeWav(input, sampleRate: 44100, channels: 1)
        let url = try writeTempWav(wav)

        let decoded = try AudioDecoder.decode(url: url)

        XCTAssertEqual(44100, decoded.sampleRate, "source rate preserved (no resampling)")
        XCTAssertEqual(1, decoded.channels, "mono")
        XCTAssertEqual(input, decoded.pcm, "s16 WAV decode must be lossless")
    }

    func testDecodeWavStereoPreservesInterleaving() throws {
        // 3 stereo frames, distinct L/R values
        let input: [Int16] = [100, -100, 200, -200, 300, -300]
        let wav = WavEncoder.encodeWav(input, sampleRate: 48000, channels: 2)
        let url = try writeTempWav(wav)

        let decoded = try AudioDecoder.decode(url: url)

        XCTAssertEqual(48000, decoded.sampleRate, "source rate preserved (no resampling)")
        XCTAssertEqual(2, decoded.channels, "stereo")
        XCTAssertEqual(input, decoded.pcm, "interleaved stereo decode must be lossless")
    }
}
