import Foundation

/// RIFF/PCM-16 WAV encoder hard-locked to the EP-133's native format.
///
/// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/WavEncoder.kt.
/// Output is byte-exact with the Kotlin implementation.
///
/// The EP-133 K.O. II expects samples in /sounds at exactly:
///   - Sample rate: 46875 Hz
///   - Bit depth: 16-bit signed integer, little-endian
///   - Channels: 1 (mono) or 2 (stereo) — passed through unchanged; no downmix
///
/// RIFF byte layout (all fields little-endian):
///   offset  0  "RIFF"         (4-CC)
///   offset  4  chunkSize      (36 + dataSize, LE u32)
///   offset  8  "WAVE"         (4-CC)
///   offset 12  "fmt "         (4-CC)
///   offset 16  subchunk1Size  (16, LE u32 — fixed for PCM)
///   offset 20  audioFormat    (1 = PCM, LE u16)
///   offset 22  channels       (LE u16)
///   offset 24  sampleRate     (LE u32)
///   offset 28  byteRate       (sampleRate * channels * 2, LE u32)
///   offset 32  blockAlign     (channels * 2, LE u16)
///   offset 34  bitsPerSample  (16, LE u16)
///   offset 36  "data"         (4-CC)
///   offset 40  dataSize       (pcm.count * 2, LE u32)
///   offset 44  Int16 LE samples (interleaved for stereo)
enum WavEncoder {

    /// The EP-133 K.O. II native bit depth.
    private static let deviceBitDepth = 16

    /// Encode `pcm` samples as a RIFF/PCM-16 WAV at the given `sampleRate` and `channels`.
    ///
    /// In production use `sampleRate` is always 46875 Hz. The parameter is exposed so
    /// callers can build test fixtures at other rates for use with
    /// `isAlreadyDeviceFormat` (e.g. creating a 44100 Hz WAV to confirm it is rejected
    /// by the pass-through sniffer).
    ///
    /// Channel count is preserved as-is (mono → mono, stereo → stereo). No downmix
    /// is performed — the device accepts both channel counts.
    ///
    /// - Parameters:
    ///   - pcm: Raw signed 16-bit PCM samples, interleaved for stereo.
    ///   - sampleRate: Sample rate to embed in the header (default 46875).
    ///   - channels: Number of audio channels: 1 (mono) or 2 (stereo).
    /// - Returns: Complete RIFF WAV bytes including the 44-byte header.
    static func encodeWav(_ pcm: [Int16], sampleRate: Int = EP133Device.sampleRate, channels: Int = 1) -> Data {
        let dataSize = pcm.count * 2             // 2 bytes per Int16 sample
        let chunkSize = 36 + dataSize            // everything after the 8-byte RIFF descriptor
        let byteRate = sampleRate * channels * 2 // bytes per second
        let blockAlign = channels * 2            // bytes per sample frame

        var bytes = [UInt8]()
        bytes.reserveCapacity(44 + dataSize)

        // RIFF chunk descriptor
        bytes.append(contentsOf: Array("RIFF".utf8))    // offset 0
        appendUInt32LE(&bytes, UInt32(chunkSize))       // offset 4
        bytes.append(contentsOf: Array("WAVE".utf8))    // offset 8

        // fmt sub-chunk
        bytes.append(contentsOf: Array("fmt ".utf8))    // offset 12
        appendUInt32LE(&bytes, 16)                      // offset 16: subchunk1Size (fixed PCM)
        appendUInt16LE(&bytes, 1)                       // offset 20: audioFormat = 1 (PCM)
        appendUInt16LE(&bytes, UInt16(channels))        // offset 22: channels
        appendUInt32LE(&bytes, UInt32(sampleRate))      // offset 24: sampleRate
        appendUInt32LE(&bytes, UInt32(byteRate))        // offset 28: byteRate
        appendUInt16LE(&bytes, UInt16(blockAlign))      // offset 32: blockAlign
        appendUInt16LE(&bytes, UInt16(deviceBitDepth))  // offset 34: bitsPerSample = 16

        // data sub-chunk
        bytes.append(contentsOf: Array("data".utf8))    // offset 36
        appendUInt32LE(&bytes, UInt32(dataSize))        // offset 40: dataSize

        // PCM samples — Int16 LE, interleaved for stereo
        for sample in pcm {
            appendUInt16LE(&bytes, UInt16(bitPattern: sample))  // offset 44+
        }

        return Data(bytes)
    }

    /// Returns `true` iff `wavBytes` is already in the EP-133's required format:
    ///   - Valid RIFF/WAVE container
    ///   - audioFormat == 1 (PCM, uncompressed)
    ///   - bitsPerSample == 16
    ///   - sampleRate == 46875 Hz
    ///   - channels == 1 or 2
    ///
    /// Parsing is defensive: any buffer shorter than 44 bytes or with wrong
    /// RIFF/WAVE/fmt magic returns `false` rather than throwing. This is safe to call
    /// on untrusted file bytes arriving from the document picker.
    ///
    /// Fast-path: callers can skip re-encoding if this returns `true`.
    static func isAlreadyDeviceFormat(_ wavBytes: Data) -> Bool {
        // Need at least a complete 44-byte standard WAV header
        if wavBytes.count < 44 { return false }

        let bytes = [UInt8](wavBytes)

        // Verify RIFF magic (offset 0..3)
        if Array(bytes[0..<4]) != Array("RIFF".utf8) { return false }

        // chunkSize (offset 4..7) not validated

        // Verify WAVE magic (offset 8..11)
        if Array(bytes[8..<12]) != Array("WAVE".utf8) { return false }

        // Verify fmt  magic (offset 12..15)
        if Array(bytes[12..<16]) != Array("fmt ".utf8) { return false }

        // subchunk1Size (offset 16..19) not validated

        // Read fmt fields
        let audioFormat = readUInt16LE(bytes, at: 20)    // expect 1 (PCM)
        let channels = readUInt16LE(bytes, at: 22)       // 1 or 2
        let sampleRate = readInt32LE(bytes, at: 24)      // expect 46875
        // byteRate (offset 28) and blockAlign (offset 32) not checked (derived values)
        let bitsPerSample = readUInt16LE(bytes, at: 34)  // expect 16

        return audioFormat == 1 &&
            bitsPerSample == deviceBitDepth &&
            sampleRate == EP133Device.sampleRate &&
            (1...2).contains(channels)
    }

    // MARK: - Little-endian helpers

    private static func appendUInt16LE(_ bytes: inout [UInt8], _ value: UInt16) {
        bytes.append(UInt8(value & 0xFF))
        bytes.append(UInt8(value >> 8))
    }

    private static func appendUInt32LE(_ bytes: inout [UInt8], _ value: UInt32) {
        bytes.append(UInt8(value & 0xFF))
        bytes.append(UInt8((value >> 8) & 0xFF))
        bytes.append(UInt8((value >> 16) & 0xFF))
        bytes.append(UInt8(value >> 24))
    }

    private static func readUInt16LE(_ bytes: [UInt8], at offset: Int) -> Int {
        Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
    }

    private static func readInt32LE(_ bytes: [UInt8], at offset: Int) -> Int {
        let u = UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
        return Int(Int32(bitPattern: u))
    }
}
