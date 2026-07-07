import Foundation

/// Target device sample rate — must match WavEncoder's device rate.
private let DEVICE_SAMPLE_RATE = 46875

/// Errors thrown by `SampleImportManager.convert` (Kotlin counterparts:
/// `java.io.IOException` → `cannotRead` / `malformedWav`;
/// `IllegalArgumentException` → `invalidArgument`).
enum SampleImportError: Error, Equatable {
    case cannotRead(String)
    case malformedWav(String)
    case invalidArgument(String)
}

/// Result of `SampleImportManager.convert`: raw s16 LE PCM bytes ready for upload,
/// plus the format parameters needed for the PUT INIT metadata JSON.
///
/// Swift port of the Kotlin `ConvertedSample` in
/// AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt.
///
/// `pcm` is interleaved little-endian signed 16-bit PCM — NO RIFF/WAV header.
/// `channels` is 1 (mono) or 2 (stereo).
/// `sampleRate` is always `DEVICE_SAMPLE_RATE` (46875) after conversion.
struct ConvertedSample: Equatable {
    let pcm: [UInt8]
    let channels: Int
    let sampleRate: Int
}

/// Progress events emitted by `SampleImportManager.importSample`.
///
/// Swift port of the Kotlin sealed class `SampleImportProgress`.
/// Mirrors `ProjectBackupProgress`: Progress → Done on success, Error on any failure.
/// Each sample import is one atomic unit — progress is coarse (0 → done or error).
enum SampleImportProgress: Equatable {
    /// Import in progress: `current` samples of `total` processed in this batch.
    case progress(current: Int, total: Int)
    /// Import complete for `name`.
    case done(name: String)
    /// Import failed: `message` describes the error.
    case error(message: String)
}

/// Per-sample convert + /sounds upload orchestration.
///
/// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SampleImportManager.kt.
/// Kotlin `Flow<SampleImportProgress>` → `AsyncStream<SampleImportProgress>` (same event
/// sequence); Android `Context` + SAF `content://` `Uri` → file `URL` read via `Data`.
///
/// Entry points:
/// - `importSample`: accepts a file URL; decodes via `AudioDecoder`, converts via
///   `Resampler` + raw-PCM encoding, uploads via `MIDIRepository.putSampleFile`.
/// - `importSampleBytes`: testability seam — accepts pre-read WAV bytes (no URL, no decode),
///   used by view-model tests to exercise the state-machine without the picker/hardware.
///
/// Concurrency: device uploads are serialized via `uploadMutex`. Concurrent batch imports
/// (e.g. N files launched in parallel from the file picker) queue at the device-transfer
/// step rather than colliding with `MIDIRepository`'s single-in-flight-transfer guard.
/// Decode/convert still overlaps across files for throughput — only the
/// `MIDIRepository.putSampleFile` call is held under the mutex.
///
/// Security (T-05-03):
/// - T-05-03-02: name is sanitized to a safe basename + ".wav" before any device write.
///   An unsanitizable name is an ERROR ("Invalid sample name"), never a fallback.
/// - T-05-03-03: converted size is pre-flighted against available /sounds storage.
/// - T-05-03-04: `importSample` reads bytes inside the caller's security-scoped access
///   (the iOS counterpart of Android's picker-callback grant — Landmine 7).
@MainActor
final class SampleImportManager {

    private let midi: MIDIRepository

    /// Serializes device-upload calls so concurrent batch imports queue instead of
    /// colliding with `MIDIRepository`'s single-in-flight-transfer guard.
    private let uploadMutex = AsyncMutex()

    init(_ midi: MIDIRepository) {
        self.midi = midi
    }

    /// Maximum device-safe basename length (excludes the ".wav" extension).
    /// `nonisolated`: a plain constant read from the nonisolated convert/sanitize path. Without it
    /// the constant inherits the class's `@MainActor` isolation — a warning in Swift 5, an error in
    /// the Swift 6 language mode.
    nonisolated static let MAX_BASENAME_LEN = 32

    // ── Import flows ───────────────────────────────────────────────────────────

    /// Import a sample from a file URL (document picker / share sheet).
    ///
    /// Kotlin counterpart: `importSample(rawName, uri, context)`.
    ///
    /// Steps: device guard → name sanitize → read bytes (security-scoped) → convert to
    /// 46875/s16 raw PCM (or pass-through if already in device format) → storage
    /// pre-flight → upload via `MIDIRepository.putSampleFile`.
    ///
    /// - Parameters:
    ///   - rawName: Original filename (not yet sanitized).
    ///   - url: File URL — read immediately (never defer past the picker grant).
    func importSample(rawName: String, url: URL) -> AsyncStream<SampleImportProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor in
                await self.runImportSample(rawName: rawName, url: url, continuation: continuation)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runImportSample(
        rawName: String,
        url: URL,
        continuation: AsyncStream<SampleImportProgress>.Continuation
    ) async {
        if midi.deviceState.outputPortId == nil {
            continuation.yield(.error(message: "No EP-133 connected"))
            return
        }

        guard let safeName = sanitizeName(rawName) else {
            continuation.yield(.error(message: "Invalid sample name: \(rawName)"))
            return
        }

        continuation.yield(.progress(current: 0, total: 1))

        let converted: ConvertedSample
        do {
            converted = try await convert(url: url)
        } catch is CancellationError {
            return  // Kotlin rethrows CancellationException; the stream just ends.
        } catch {
            continuation.yield(.error(message: "Convert failed: \(Self.errorMessage(error))"))
            return
        }

        await uploadConverted(safeName: safeName, converted: converted, continuation: continuation)
    }

    /// Testability seam: import a sample from pre-read WAV bytes.
    ///
    /// Kotlin counterpart: `importSampleBytes(rawName, wavBytes)`.
    ///
    /// Skips `AudioDecoder` (no URL, no security scope needed). Used by view-model tests
    /// to exercise the state-machine contract without a real device or file picker.
    ///
    /// If `wavBytes` is a valid device-format WAV container, the data chunk is sliced out
    /// and channels/sampleRate are extracted from the fmt chunk. Otherwise the bytes are
    /// treated as raw PCM with device-format defaults (46875 Hz, 1 channel) so test
    /// doubles remain simple.
    ///
    /// - Parameters:
    ///   - rawName: Filename to sanitize + upload under (e.g. "kick.wav").
    ///   - wavBytes: Pre-read WAV bytes; format is sniffed via `WavEncoder.isAlreadyDeviceFormat`.
    func importSampleBytes(rawName: String, wavBytes: Data) -> AsyncStream<SampleImportProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor in
                await self.runImportSampleBytes(
                    rawName: rawName, wavBytes: wavBytes, continuation: continuation)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func runImportSampleBytes(
        rawName: String,
        wavBytes: Data,
        continuation: AsyncStream<SampleImportProgress>.Continuation
    ) async {
        if midi.deviceState.outputPortId == nil {
            continuation.yield(.error(message: "No EP-133 connected"))
            return
        }

        guard let safeName = sanitizeName(rawName) else {
            continuation.yield(.error(message: "Invalid sample name: \(rawName)"))
            return
        }

        continuation.yield(.progress(current: 0, total: 1))

        // Extract raw PCM + format parameters from the byte array.
        // If it looks like a device-format WAV, slice out the data chunk. Otherwise treat
        // as raw PCM with device defaults (test doubles use this).
        let converted: ConvertedSample
        if WavEncoder.isAlreadyDeviceFormat(wavBytes) {
            converted = sliceWavData(wavBytes)
                ?? ConvertedSample(pcm: [UInt8](wavBytes), channels: 1, sampleRate: DEVICE_SAMPLE_RATE)
        } else {
            converted = ConvertedSample(pcm: [UInt8](wavBytes), channels: 1, sampleRate: DEVICE_SAMPLE_RATE)
        }

        await uploadConverted(safeName: safeName, converted: converted, continuation: continuation)
    }

    /// Shared upload tail of both import flows: storage pre-flight → mutex-serialized
    /// `putSampleFile` → Done/Error events. (The Kotlin flows duplicate this block; the
    /// event sequence is identical.)
    private func uploadConverted(
        safeName: String,
        converted: ConvertedSample,
        continuation: AsyncStream<SampleImportProgress>.Continuation
    ) async {
        if !preflightStorage(converted.pcm.count) {
            continuation.yield(.error(message: "Not enough space on EP-133 for \(safeName)"))
            return
        }

        let nodeId: Int?
        do {
            nodeId = try await uploadMutex.withLock {
                try await self.midi.putSampleFile(
                    name: safeName,
                    pcmBytes: converted.pcm,
                    channels: converted.channels,
                    sampleRate: converted.sampleRate)
            }
        } catch is CancellationError {
            return  // Kotlin rethrows CancellationException; the stream just ends.
        } catch {
            continuation.yield(.error(message: "Upload failed: \(Self.errorMessage(error))"))
            return
        }

        if nodeId != nil {
            continuation.yield(.progress(current: 1, total: 1))
            continuation.yield(.done(name: safeName))
        } else {
            continuation.yield(.error(
                message: "Upload not confirmed by EP-133 (no STATUS_OK) — reconnect and retry \(safeName)"))
        }
    }

    // ── Convert pipeline ───────────────────────────────────────────────────────

    /// Convert an audio file URL to raw s16 LE PCM bytes ready for device upload.
    ///
    /// Kotlin counterpart: `convert(context, uri, enforceMaxLength)`.
    ///
    /// Must be called while the URL is readable (security-scoped access is acquired
    /// best-effort for document-picker URLs — the iOS Landmine 7 counterpart). Runs the
    /// full convert pipeline:
    ///  - Fast path: if the file is already WAV/s16/46875/(1|2)ch → slice out the data
    ///    chunk (strip the RIFF header) and return the raw PCM bytes + fmt parameters.
    ///  - Slow path: `AudioDecoder.decode` → `Resampler.toRate` → convert `[Int16]` to
    ///    little-endian bytes; channels and sampleRate are read from the decoded result.
    ///
    /// The returned `ConvertedSample.pcm` contains NO RIFF/WAV header — only interleaved
    /// s16 LE PCM samples. `ConvertedSample.sampleRate` is always 46875.
    ///
    /// `nonisolated` so the decode/resample work runs off the main actor (the Kotlin
    /// version hops to Dispatchers.IO/Default).
    ///
    /// - Parameter enforceMaxLength: when true (single-sample import), reject sources
    ///   longer than the device's 20 s single-sample cap up front. The loop chopper passes
    ///   **false**: it needs the full-length PCM to slice, and each resulting slice is
    ///   size-checked against the device's per-sample byte budget before upload, so the
    ///   whole-loop cap must not apply here.
    nonisolated func convert(url: URL, enforceMaxLength: Bool = true) async throws -> ConvertedSample {
        // Read raw bytes inside the grant (Landmine 7 — picker URL lifetime).
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let rawBytes: Data
        do {
            rawBytes = try Data(contentsOf: url)
        } catch {
            throw SampleImportError.cannotRead("Cannot read URL: \(url)")
        }

        if WavEncoder.isAlreadyDeviceFormat(rawBytes) {
            // Pass-through fast path: already 46875/s16 — slice header, keep raw PCM.
            guard let sliced = sliceWavData(rawBytes) else {
                throw SampleImportError.malformedWav(
                    "Failed to slice WAV data chunk from device-format file")
            }
            return sliced
        }

        // Slow path: decode → validate → resample → encode as raw PCM bytes.
        let decoded = try AudioDecoder.decode(url: url)
        let srcRate = decoded.sampleRate
        let channels = decoded.channels

        // Device input guards (verified from data/index.js):
        //   - source sample rate must be in [3000, 768000] Hz.
        //   - total duration must not exceed 20.0 seconds.
        // These throw SampleImportError.invalidArgument, which the import flow's generic
        // catch turns into an Error("Convert failed: …") event.
        if srcRate < 3000 || srcRate > 768_000 {
            throw SampleImportError.invalidArgument(
                "invalid sample rate: \(srcRate) Hz (must be 3000–768000)")
        }
        let durationSec = Double(decoded.pcm.count / channels) / Double(srcRate)
        if enforceMaxLength && durationSec > 20.0 {
            throw SampleImportError.invalidArgument(
                String(format: "max sample length is 20 seconds (was %.1fs)", durationSec))
        }

        let resampled = try Resampler.toRate(
            decoded.pcm, srcRate: srcRate, dstRate: DEVICE_SAMPLE_RATE, channels: channels)
        return ConvertedSample(
            pcm: shortArrayToLeBytes(resampled),
            channels: channels,
            sampleRate: DEVICE_SAMPLE_RATE)
    }

    /// Convert an `[Int16]` of s16 samples to interleaved little-endian PCM bytes.
    ///
    /// Kotlin counterpart: `shortArrayToLeBytes(samples)`.
    ///
    /// Each Int16 is written as 2 bytes, low byte first (little-endian, as required by the
    /// EP-133). The output has no header — pure raw PCM. Pure and unit-testable.
    nonisolated func shortArrayToLeBytes(_ samples: [Int16]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(samples.count * 2)
        for s in samples {
            let u = UInt16(bitPattern: s)
            out.append(UInt8(u & 0xFF))
            out.append(UInt8(u >> 8))
        }
        return out
    }

    /// Locate the RIFF WAV `data` sub-chunk in `wavBytes` and return a `ConvertedSample`
    /// containing only the raw PCM bytes (header stripped), plus channels and sampleRate
    /// read from the `fmt ` chunk.
    ///
    /// Kotlin counterpart: `sliceWavData(wavBytes)`.
    ///
    /// This handles non-standard WAV files where the `data` sub-chunk is not at the
    /// canonical offset 36 (e.g. files with LIST/INFO metadata inserted before the data
    /// chunk). If the file passes `WavEncoder.isAlreadyDeviceFormat` the `fmt ` chunk is
    /// guaranteed to exist at offset 12; we scan forward from there for the `data` four-CC.
    ///
    /// Returns `nil` if the `data` chunk cannot be located (malformed header).
    /// Pure and unit-testable.
    nonisolated func sliceWavData(_ wavBytes: Data) -> ConvertedSample? {
        // Minimum: RIFF(4) + size(4) + WAVE(4) + fmt (4) + fmtSize(4) + 16 bytes of fmt body = 36
        if wavBytes.count < 36 { return nil }
        let bytes = [UInt8](wavBytes)

        // Skip RIFF(4) + chunkSize(4) + WAVE(4) = 12 bytes → now at fmt  sub-chunk.
        // Read fmt  four-CC.
        if Array(bytes[12..<16]) != Array("fmt ".utf8) { return nil }

        let fmtSize = readInt32LE(bytes, at: 16)   // typically 16 for PCM
        let fmtStart = 20                          // start of fmt body
        // audioFormat at fmtStart (skip — already validated by isAlreadyDeviceFormat)
        let channels = readUInt16LE(bytes, at: 22)
        let sampleRate = readInt32LE(bytes, at: 24)

        // Skip past the rest of the fmt  sub-chunk to the next sub-chunk. (Kotlin's
        // ByteBuffer throws on bogus positions and the catch returns null; guard instead.)
        if fmtSize < 0 { return nil }
        var pos = fmtStart + fmtSize

        // Scan forward for the `data` four-CC (handles extra sub-chunks like LIST/INFO).
        var dataOffset = -1
        var dataSize = 0
        while pos >= 0 && bytes.count - pos >= 8 {
            let tag = Array(bytes[pos..<(pos + 4)])
            let chunkSize = readInt32LE(bytes, at: pos + 4)
            pos += 8
            if tag == Array("data".utf8) {
                dataOffset = pos
                dataSize = chunkSize
                break
            }
            // Skip unknown sub-chunk (align to 2 bytes per RIFF spec).
            if chunkSize < 0 { return nil }
            pos += chunkSize + (chunkSize & 1)
        }

        if dataOffset < 0 || dataSize <= 0 { return nil }
        let end = min(dataOffset + dataSize, bytes.count)
        return ConvertedSample(
            pcm: Array(bytes[dataOffset..<end]),
            channels: channels,
            sampleRate: sampleRate)
    }

    // ── Name sanitization (T-05-03-02) ─────────────────────────────────────────

    /// Sanitize `rawName` to a device-safe basename + ".wav" for /sounds.
    ///
    /// Kotlin counterpart: `sanitizeName(rawName)`.
    ///
    /// Produces a pure-ASCII basename safe for `SysExProtocol` frame builders, which
    /// encode the destination name as ASCII. Non-ASCII characters (e.g. accented letters,
    /// emoji) would otherwise be lossily mangled into `?` bytes, corrupting the on-device
    /// `/sounds/<name>` path (T-05-03-02 path-traversal + encoding mitigation).
    ///
    /// Steps:
    /// 1. Extract the basename: strips any leading path components (`/` or `\` separated)
    ///    then drops the file extension.
    /// 2. Replace every character NOT in `[A-Za-z0-9 _-]` with `_` — makes the result pure
    ///    ASCII and losslessly encodable. This subsumes the old `/`, `\`, `..`, control-char
    ///    rejection; those characters all become `_`.
    /// 3. Collapse runs of whitespace to a single space, then trim leading/trailing
    ///    whitespace and trim leading/trailing `.`, `_`, `-` for a clean device name.
    /// 4. Truncate the basename to `MAX_BASENAME_LEN` characters, then re-trim trailing
    ///    `_`, `-`, or space that may be exposed at the cut point.
    /// 5. Return `nil` if the result is empty (caller emits "Invalid sample name" — an
    ///    unsanitizable name is an ERROR, never a fallback; pinned behavior).
    /// 6. Return `"<basename>.wav"`.
    ///
    /// Guaranteed: `sanitizeName("kick.wav") == "kick.wav"`, same for `"snare.wav"` and
    /// `"hihat.wav"` (regression contract for existing tests).
    nonisolated func sanitizeName(_ rawName: String) -> String? {
        // Step 1: extract the basename (strip path components, then drop extension).
        let afterSlash = rawName.components(separatedBy: "/").last ?? rawName
        let withoutPath = afterSlash.components(separatedBy: "\\").last ?? afterSlash
        let stem = withoutPath.substringBeforeLastChar(".")

        // Step 2: replace every character outside [A-Za-z0-9 _-] with '_'.
        let replaced = stem.replacingOccurrences(
            of: "[^A-Za-z0-9 _-]", with: "_", options: .regularExpression)

        // Step 3: collapse whitespace runs, trim leading/trailing whitespace and punctuation.
        let collapsed = replaced
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        let trimmed = collapsed
            .trimmingCharacters(in: CharacterSet(charactersIn: "._-"))
            .trimmingCharacters(in: .whitespaces)

        // Step 4: cap length, then re-trim any punctuation exposed at the cut point.
        var capped = trimmed
        if capped.count > Self.MAX_BASENAME_LEN {
            capped = String(capped.prefix(Self.MAX_BASENAME_LEN))
            while let last = capped.last, last == "_" || last == "-" || last == " " {
                capped.removeLast()
            }
        }

        // Step 5: reject empty result.
        if capped.isEmpty { return nil }

        // Step 6: append device extension.
        return "\(capped).wav"
    }

    // ── Storage pre-flight (T-05-03-03) ────────────────────────────────────────

    /// Pre-flight: check that `wavSize` bytes fit in the remaining /sounds storage.
    ///
    /// Kotlin counterpart: `preflightStorage(wavSize)`.
    ///
    /// Reads `MIDIRepository.deviceState` for storageUsedBytes / storageTotalBytes
    /// (populated by `MIDIRepository.queryDeviceStats` via FILE_METADATA on /sounds).
    /// If storage info is not yet available, allows the upload (best-effort — the device
    /// will reject if full).
    ///
    /// Landmine 6 mitigation: never start a write that would overflow device storage.
    private func preflightStorage(_ wavSize: Int) -> Bool {
        let state = midi.deviceState
        guard let used = state.storageUsedBytes else { return true }   // unknown → allow
        guard let total = state.storageTotalBytes else { return true }
        return (total - used) >= Int64(wavSize)
    }

    // ── Error text (Kotlin `e.message ?: e`) ───────────────────────────────────

    /// Extract a human-readable message from an error, mirroring Kotlin's
    /// `e.message ?: e` in the flow catch blocks.
    private nonisolated static func errorMessage(_ error: Error) -> String {
        switch error {
        case let e as SampleImportError:
            switch e {
            case .cannotRead(let m), .malformedWav(let m), .invalidArgument(let m):
                return m
            }
        case let e as MIDIRepositoryError:
            switch e {
            case .noOutputPort: return "no output port"
            case .invalidState(let m), .deviceRejected(let m): return m
            }
        case let e as AudioDecoderError:
            switch e {
            case .cannotOpen(let m), .decodeFailed(let m), .pcmCapExceeded(let m):
                return m
            case .unsupportedEncoding(let enc):
                return "unsupported PCM encoding: \(enc)"
            }
        case let e as ResamplerError:
            switch e {
            case .invalidArgument(let m): return m
            }
        default:
            return String(describing: error)
        }
    }
}

// ── Little-endian read helpers (file-private, mirror Kotlin ByteBuffer LE reads) ──

private func readUInt16LE(_ bytes: [UInt8], at offset: Int) -> Int {
    Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
}

private func readInt32LE(_ bytes: [UInt8], at offset: Int) -> Int {
    let u = UInt32(bytes[offset])
        | (UInt32(bytes[offset + 1]) << 8)
        | (UInt32(bytes[offset + 2]) << 16)
        | (UInt32(bytes[offset + 3]) << 24)
    return Int(Int32(bitPattern: u))
}

private extension String {
    /// Kotlin `substringBeforeLast(delimiter, missingDelimiterValue = this)`.
    /// (Named to avoid clashing with the file-private twin in MIDIRepository.swift.)
    func substringBeforeLastChar(_ delimiter: Character) -> String {
        guard let idx = lastIndex(of: delimiter) else { return self }
        return String(self[..<idx])
    }
}
