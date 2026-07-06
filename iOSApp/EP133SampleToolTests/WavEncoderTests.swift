import XCTest
@testable import EP133SampleTool

/// Port of AndroidApp/app/src/test/java/com/ep133/sampletool/WavEncoderTest.kt.
///
/// Sample-rate guard: hard-coded literal 46875 — a wrong sample rate (e.g. 44100) fails here.
/// Byte-order guard: explicit little-endian reads — a byte-order slip yields wrong numeric values.
final class WavEncoderTests: XCTestCase {

    private let samplePCM: [Int16] = [0, 1, -1, 32767, -32768]

    // ── Helper: read a little-endian int16 from a byte offset ──
    private func leInt16(_ data: Data, _ offset: Int) -> Int {
        Int(data[offset]) | (Int(data[offset + 1]) << 8)
    }

    // ── Helper: read a little-endian int32 from a byte offset ──
    private func leInt32(_ data: Data, _ offset: Int) -> Int {
        let u = UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
        return Int(Int32(bitPattern: u))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test A: RIFF header carries 46875 Hz, s16, mono
    // ──────────────────────────────────────────────────────────────────────────

    func testRiffHeaderCarries46875S16Channels() {
        let wav = WavEncoder.encodeWav(samplePCM, sampleRate: 46875, channels: 1)

        // Total length: 44-byte header + PCM data
        XCTAssertEqual(44 + samplePCM.count * 2, wav.count, "Total WAV length")

        // RIFF chunk descriptor
        XCTAssertEqual(UInt8(ascii: "R"), wav[0], "RIFF[0]")
        XCTAssertEqual(UInt8(ascii: "I"), wav[1], "RIFF[1]")
        XCTAssertEqual(UInt8(ascii: "F"), wav[2], "RIFF[2]")
        XCTAssertEqual(UInt8(ascii: "F"), wav[3], "RIFF[3]")

        // WAVE marker (offset 8)
        XCTAssertEqual(UInt8(ascii: "W"), wav[8], "WAVE[0]")
        XCTAssertEqual(UInt8(ascii: "A"), wav[9], "WAVE[1]")
        XCTAssertEqual(UInt8(ascii: "V"), wav[10], "WAVE[2]")
        XCTAssertEqual(UInt8(ascii: "E"), wav[11], "WAVE[3]")

        // fmt sub-chunk marker (offset 12)
        XCTAssertEqual(UInt8(ascii: "f"), wav[12], "fmt [0]")
        XCTAssertEqual(UInt8(ascii: "m"), wav[13], "fmt [1]")
        XCTAssertEqual(UInt8(ascii: "t"), wav[14], "fmt [2]")
        XCTAssertEqual(UInt8(ascii: " "), wav[15], "fmt [3]")

        // data sub-chunk marker (offset 36)
        XCTAssertEqual(UInt8(ascii: "d"), wav[36], "data[0]")
        XCTAssertEqual(UInt8(ascii: "a"), wav[37], "data[1]")
        XCTAssertEqual(UInt8(ascii: "t"), wav[38], "data[2]")
        XCTAssertEqual(UInt8(ascii: "a"), wav[39], "data[3]")

        // fmt fields (all little-endian)
        let audioFormat = leInt16(wav, 20)
        XCTAssertEqual(1, audioFormat, "audioFormat must be 1 (PCM)")

        let channels = leInt16(wav, 22)
        XCTAssertEqual(1, channels, "channels must be 1 (mono)")

        // Assert the exact device rate — 46875, not 44100 or 48000
        let sampleRate = leInt32(wav, 24)
        XCTAssertEqual(46875, sampleRate, "sampleRate must be 46875 (EP-133 device rate)")

        // Assert 16-bit depth (not 32-bit float, not 24-bit)
        let bitsPerSample = leInt16(wav, 34)
        XCTAssertEqual(16, bitsPerSample, "bitsPerSample must be 16")

        // data sub-chunk size == pcm.count * 2 (Int16 = 2 bytes per sample)
        let dataSize = leInt32(wav, 40)
        XCTAssertEqual(samplePCM.count * 2, dataSize, "dataSize must be pcm.count * 2")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test B: stereo — channels, byteRate, blockAlign
    // ──────────────────────────────────────────────────────────────────────────

    func testStereoChannelsAndByteRate() {
        let wav = WavEncoder.encodeWav(samplePCM, sampleRate: 46875, channels: 2)

        let channels = leInt16(wav, 22)
        XCTAssertEqual(2, channels, "channels must be 2 (stereo)")

        // byteRate = sampleRate * channels * bytesPerSample = 46875 * 2 * 2
        let byteRate = leInt32(wav, 28)
        XCTAssertEqual(46875 * 2 * 2, byteRate, "byteRate must be 46875*2*2")

        // blockAlign = channels * bytesPerSample = 2 * 2
        let blockAlign = leInt16(wav, 32)
        XCTAssertEqual(4, blockAlign, "blockAlign must be 4 (channels=2 * bytesPerSample=2)")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test C: pass-through predicate (isAlreadyDeviceFormat)
    // ──────────────────────────────────────────────────────────────────────────

    func testPassThroughIdentityWhenAlready46875() {
        // A WAV built by WavEncoder itself must be recognized as device-format
        let alreadyDeviceFmt = WavEncoder.encodeWav(samplePCM, sampleRate: 46875, channels: 1)
        XCTAssertTrue(
            WavEncoder.isAlreadyDeviceFormat(alreadyDeviceFmt),
            "A 46875 Hz / s16 / mono WAV must be recognized as already device format"
        )

        // A WAV at 44100 Hz (the common Splice export rate) must NOT be recognized
        let spliceFmt = WavEncoder.encodeWav(samplePCM, sampleRate: 44100, channels: 1)
        XCTAssertFalse(
            WavEncoder.isAlreadyDeviceFormat(spliceFmt),
            "A 44100 Hz WAV must NOT be recognized as device format"
        )
    }
}
