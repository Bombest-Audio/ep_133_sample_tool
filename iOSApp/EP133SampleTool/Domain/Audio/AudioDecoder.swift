import AVFoundation
import Foundation

/// Thrown by `AudioDecoder.decode` and `pcmBytesToShorts` (mirrors Kotlin's IOException).
enum AudioDecoderError: Error, Equatable {
    case cannotOpen(String)
    case decodeFailed(String)
    case pcmCapExceeded(String)
    case unsupportedEncoding(Int)
}

/// Decodes an arbitrary audio file to raw 16-bit PCM via AVFoundation.
///
/// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/audio/AudioDecoder.kt.
/// The Android version drives MediaExtractor + MediaCodec; here AVAudioFile does the
/// container parsing and sample-format conversion. The public contract is the same:
/// decode a file URL to interleaved s16 PCM plus the source rate and channel count.
/// Supports every format Core Audio handles: WAV, AIFF, MP3, AAC/M4A, FLAC — covering
/// all common Splice export formats.
///
/// DoS cap: accumulated PCM is bounded at `maxPCMBytes`; exceeding it throws rather
/// than exhausting memory on a malformed/huge file.
enum AudioDecoder {

    /// Decoded PCM result from an audio file URL.
    ///
    /// - `pcm`: Raw signed 16-bit PCM samples, interleaved for stereo.
    /// - `sampleRate`: Original source sample rate in Hz (e.g. 44100, 48000).
    /// - `channels`: Number of audio channels (1 = mono, 2 = stereo).
    struct DecodedPCM {
        let pcm: [Int16]
        let sampleRate: Int
        let channels: Int
    }

    /// Maximum decoded PCM bytes before aborting (DoS cap).
    /// 2 min stereo at 48 kHz × 16-bit ≈ 46 MB; 64 MB is a generous ceiling that
    /// still prevents memory exhaustion from a malformed/huge file.
    static let maxPCMBytes = 64 * 1024 * 1024   // 64 MB

    // Android AudioFormat.ENCODING_PCM_* constants, mirrored so `pcmBytesToShorts`
    // keeps the exact Kotlin contract (Int-typed encoding selector).
    static let encodingPCM16Bit = 2        // AudioFormat.ENCODING_PCM_16BIT
    static let encodingPCMFloat = 4        // AudioFormat.ENCODING_PCM_FLOAT
    static let encodingPCM24BitPacked = 21 // AudioFormat.ENCODING_PCM_24BIT_PACKED
    static let encodingPCM32Bit = 22       // AudioFormat.ENCODING_PCM_32BIT

    /// Decode the audio file at `url` to raw 16-bit PCM.
    ///
    /// Must be called while security-scoped access to `url` is active when the URL
    /// comes from the document picker (the counterpart of Android's SAF-grant rule).
    ///
    /// AVAudioFile is opened with a 16-bit interleaved processing format, so Core
    /// Audio converts float/24-bit/32-bit sources to s16 during the read — the
    /// equivalent of the Kotlin drain loop plus `pcmBytesToShorts`. AVAudioFile never
    /// resamples: the returned rate is the source rate.
    ///
    /// - Parameter url: File URL of the audio to decode.
    /// - Returns: `DecodedPCM` containing the raw PCM samples, source rate, and channel count.
    /// - Throws: `AudioDecoderError` on open/decode failure or oversized PCM.
    static func decode(url: URL) throws -> DecodedPCM {
        let file: AVAudioFile
        do {
            file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatInt16, interleaved: true)
        } catch {
            throw AudioDecoderError.cannotOpen("AudioDecoder failed for \(url.lastPathComponent): \(error.localizedDescription)")
        }

        let sampleRate = Int(file.fileFormat.sampleRate)
        let channels = Int(file.processingFormat.channelCount)
        let frames = Int(file.length)

        // Bounds check before allocation (DoS cap).
        if frames * channels * 2 > maxPCMBytes {
            throw AudioDecoderError.pcmCapExceeded(
                "AudioDecoder: decoded PCM exceeds \(maxPCMBytes / (1024 * 1024)) MB cap — aborting"
            )
        }

        if frames == 0 {
            return DecodedPCM(pcm: [], sampleRate: sampleRate, channels: channels)
        }

        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: file.processingFormat,
            frameCapacity: AVAudioFrameCount(frames)
        ) else {
            throw AudioDecoderError.decodeFailed("AudioDecoder: cannot allocate PCM buffer (\(frames) frames)")
        }

        do {
            try file.read(into: buffer)
        } catch {
            throw AudioDecoderError.decodeFailed("AudioDecoder failed for \(url.lastPathComponent): \(error.localizedDescription)")
        }

        guard let channelData = buffer.int16ChannelData else {
            throw AudioDecoderError.decodeFailed("AudioDecoder: buffer has no int16 channel data")
        }

        // Interleaved format: channelData[0] holds frameLength * channels samples.
        let sampleCount = Int(buffer.frameLength) * channels
        let pcm = Array(UnsafeBufferPointer(start: channelData[0], count: sampleCount))

        return DecodedPCM(pcm: pcm, sampleRate: sampleRate, channels: channels)
    }
}

/// Convert raw PCM bytes to signed 16-bit samples.
///
/// Mirror of the Kotlin top-level `pcmBytesToShorts` in AudioDecoder.kt. On Android
/// this converts MediaCodec output; here it stands alone as a byte-level utility (the
/// iOS decode path converts via AVAudioFile), kept for contract and test parity.
///
/// Supported encodings (Android AudioFormat constant values):
/// - `AudioDecoder.encodingPCM16Bit`: LE Int16 — read directly.
/// - `AudioDecoder.encodingPCMFloat`: LE Float32 — scale to [-32767, 32767] with ±1.0 clamping.
/// - `AudioDecoder.encodingPCM24BitPacked`: 3-byte LE — downshift to 16-bit (drop low byte).
/// - `AudioDecoder.encodingPCM32Bit`: 4-byte LE — downshift to 16-bit (drop low 2 bytes).
///
/// - Parameters:
///   - bytes: Raw PCM bytes.
///   - encoding: One of the `AudioDecoder.encodingPCM*` constants.
/// - Throws: `AudioDecoderError.unsupportedEncoding` for any unrecognized encoding constant.
func pcmBytesToShorts(_ bytes: Data, encoding: Int) throws -> [Int16] {
    let raw = [UInt8](bytes)
    switch encoding {
    case AudioDecoder.encodingPCM16Bit:
        let count = raw.count / 2
        var shorts = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let u = UInt16(raw[i * 2]) | (UInt16(raw[i * 2 + 1]) << 8)
            shorts[i] = Int16(bitPattern: u)
        }
        return shorts
    case AudioDecoder.encodingPCMFloat:
        let count = raw.count / 4
        var shorts = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let u = UInt32(raw[i * 4])
                | (UInt32(raw[i * 4 + 1]) << 8)
                | (UInt32(raw[i * 4 + 2]) << 16)
                | (UInt32(raw[i * 4 + 3]) << 24)
            let f = Float(bitPattern: u)
            let scaled = min(max(f, -1.0), 1.0) * 32767.0
            // Half-up rounding toward +inf — Kotlin Float.roundToInt() / Math.round(float).
            shorts[i] = Int16(truncatingIfNeeded: Int((scaled + 0.5).rounded(.down)))
        }
        return shorts
    case AudioDecoder.encodingPCM24BitPacked:
        // 3 bytes per sample, LE: [lo, mid, hi]. Shift right 8 bits → Int16.
        let count = raw.count / 3
        var shorts = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let lo = Int(raw[i * 3])
            let mid = Int(raw[i * 3 + 1])
            let hi = Int(Int8(bitPattern: raw[i * 3 + 2]))   // signed for sign extension
            let s32 = (hi << 16) | (mid << 8) | lo
            shorts[i] = Int16(truncatingIfNeeded: s32 >> 8)
        }
        return shorts
    case AudioDecoder.encodingPCM32Bit:
        // 4 bytes per sample, LE Int32. Shift right 16 bits → Int16.
        let count = raw.count / 4
        var shorts = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let u = UInt32(raw[i * 4])
                | (UInt32(raw[i * 4 + 1]) << 8)
                | (UInt32(raw[i * 4 + 2]) << 16)
                | (UInt32(raw[i * 4 + 3]) << 24)
            shorts[i] = Int16(truncatingIfNeeded: Int32(bitPattern: u) >> 16)
        }
        return shorts
    default:
        throw AudioDecoderError.unsupportedEncoding(encoding)
    }
}
