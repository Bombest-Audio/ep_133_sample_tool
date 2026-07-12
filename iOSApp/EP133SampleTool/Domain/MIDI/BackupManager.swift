import Foundation

// Swift port of AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/BackupManager.kt.
//
// Translation map from the Kotlin original:
// - `Flow<BackupProgress>` / `Flow<RestoreProgress>` → `AsyncStream` (unbounded buffer, so a
//   late consumer still sees every event; the producer Task is cancelled on termination)
// - `java.util.zip.ZipOutputStream/ZipInputStream` → `ZIPArchive` below (same on-disk format:
//   a standard ZIP; entries are written STORED, and both STORED and DEFLATE entries are read,
//   so `.pak` files made by the Android app restore here and vice versa)
// - `Log.w(TAG, …)` → `print("[EP133BACKUP] …")` (project logging convention)

// MARK: - Progress events

/// Progress events emitted by `BackupManager.createBackup` (Kotlin `BackupProgress`).
enum BackupProgress: Equatable {
    /// Backup in progress: `current` files downloaded of `total`.
    case progress(current: Int, total: Int)
    /// Backup complete: `pakBytes` is the ZIP archive ready to write.
    case done(pakBytes: [UInt8])
    /// Backup failed: `message` describes the error.
    case error(message: String)
}

/// Progress events emitted by `BackupManager.restore` (Kotlin `RestoreProgress`).
enum RestoreProgress: Equatable {
    /// Restore in progress: `current` files uploaded of `total`.
    case progress(current: Int, total: Int)
    /// Restore complete.
    case done
    /// Restore failed: `message` describes the error.
    case error(message: String)
}

// MARK: - ZIP archive helper (java.util.zip replacement)

/// Minimal ZIP reader/writer replacing the JVM's `ZipOutputStream`/`ZipInputStream`.
///
/// Writer: entries are STORED (method 0, no compression) with correct CRC-32 and sizes and a
/// central directory — a fully standard ZIP that `ZipInputStream` (and any other tool) reads.
/// STORED is deliberate: the payloads are raw PCM (barely compressible) and the output is
/// deterministic across platforms.
///
/// Reader: parses the central directory (so Android-written DEFLATE entries with trailing data
/// descriptors are handled too) and inflates method-8 entries via Foundation's raw-DEFLATE
/// `NSData.decompressed(using: .zlib)`.
enum ZIPArchive {

    enum ZIPError: Error, Equatable {
        case notAZip
        case truncated
        case unsupportedMethod(Int)
    }

    struct Entry: Equatable {
        let name: String
        let data: [UInt8]
        let isDirectory: Bool
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /// Build a ZIP archive from ordered (name, data) pairs. Entries are STORED.
    static func build(_ entries: [(name: String, data: [UInt8])]) -> [UInt8] {
        var out = [UInt8]()
        var central = [UInt8]()
        let (dosTime, dosDate) = dosTimestamp(Date())

        for (name, data) in entries {
            let nameBytes = Array(name.utf8)
            let crc = crc32(data)
            let headerOffset = out.count

            // Local file header.
            appendUInt32(&out, 0x0403_4B50)          // signature "PK\x03\x04"
            appendUInt16(&out, 20)                   // version needed to extract
            appendUInt16(&out, 0)                    // general purpose flags
            appendUInt16(&out, 0)                    // method: STORED
            appendUInt16(&out, dosTime)
            appendUInt16(&out, dosDate)
            appendUInt32(&out, crc)
            appendUInt32(&out, UInt32(data.count))   // compressed size (== raw, STORED)
            appendUInt32(&out, UInt32(data.count))   // uncompressed size
            appendUInt16(&out, UInt16(nameBytes.count))
            appendUInt16(&out, 0)                    // extra length
            out.append(contentsOf: nameBytes)
            out.append(contentsOf: data)

            // Matching central directory record.
            appendUInt32(&central, 0x0201_4B50)      // signature "PK\x01\x02"
            appendUInt16(&central, 20)               // version made by
            appendUInt16(&central, 20)               // version needed
            appendUInt16(&central, 0)                // flags
            appendUInt16(&central, 0)                // method: STORED
            appendUInt16(&central, dosTime)
            appendUInt16(&central, dosDate)
            appendUInt32(&central, crc)
            appendUInt32(&central, UInt32(data.count))
            appendUInt32(&central, UInt32(data.count))
            appendUInt16(&central, UInt16(nameBytes.count))
            appendUInt16(&central, 0)                // extra length
            appendUInt16(&central, 0)                // comment length
            appendUInt16(&central, 0)                // disk number start
            appendUInt16(&central, 0)                // internal attributes
            appendUInt32(&central, 0)                // external attributes
            appendUInt32(&central, UInt32(headerOffset))
            central.append(contentsOf: nameBytes)
        }

        // End of central directory.
        let cdOffset = out.count
        out.append(contentsOf: central)
        appendUInt32(&out, 0x0605_4B50)              // signature "PK\x05\x06"
        appendUInt16(&out, 0)                        // this disk
        appendUInt16(&out, 0)                        // central directory disk
        appendUInt16(&out, UInt16(entries.count))
        appendUInt16(&out, UInt16(entries.count))
        appendUInt32(&out, UInt32(central.count))
        appendUInt32(&out, UInt32(cdOffset))
        appendUInt16(&out, 0)                        // comment length
        return out
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /// Parse all entries of a ZIP archive, in central-directory (written) order.
    static func read(_ bytes: [UInt8]) throws -> [Entry] {
        guard let eocd = findEndOfCentralDirectory(bytes) else { throw ZIPError.notAZip }
        let entryCount = Int(readUInt16(bytes, eocd + 10))
        var offset = Int(readUInt32(bytes, eocd + 16))

        var entries = [Entry]()
        entries.reserveCapacity(entryCount)
        for _ in 0..<entryCount {
            guard offset + 46 <= bytes.count, readUInt32(bytes, offset) == 0x0201_4B50 else {
                throw ZIPError.truncated
            }
            let method = Int(readUInt16(bytes, offset + 10))
            let compressedSize = Int(readUInt32(bytes, offset + 20))
            let nameLength = Int(readUInt16(bytes, offset + 28))
            let extraLength = Int(readUInt16(bytes, offset + 30))
            let commentLength = Int(readUInt16(bytes, offset + 32))
            let localOffset = Int(readUInt32(bytes, offset + 42))
            guard offset + 46 + nameLength <= bytes.count else { throw ZIPError.truncated }
            let name = String(decoding: bytes[(offset + 46)..<(offset + 46 + nameLength)],
                              as: UTF8.self)
            entries.append(try readEntry(
                bytes, localOffset: localOffset, name: name,
                method: method, compressedSize: compressedSize))
            offset += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    private static func readEntry(
        _ bytes: [UInt8], localOffset: Int, name: String,
        method: Int, compressedSize: Int
    ) throws -> Entry {
        guard localOffset + 30 <= bytes.count,
              readUInt32(bytes, localOffset) == 0x0403_4B50 else {
            throw ZIPError.truncated
        }
        // The local header's own name/extra lengths locate the data (extra can differ from
        // the central record's).
        let nameLength = Int(readUInt16(bytes, localOffset + 26))
        let extraLength = Int(readUInt16(bytes, localOffset + 28))
        let dataStart = localOffset + 30 + nameLength + extraLength
        guard dataStart + compressedSize <= bytes.count else { throw ZIPError.truncated }
        let raw = Array(bytes[dataStart..<(dataStart + compressedSize)])

        let isDirectory = name.hasSuffix("/")
        switch method {
        case 0:
            return Entry(name: name, data: raw, isDirectory: isDirectory)
        case 8:
            // ZIP entries are raw DEFLATE streams; Foundation's .zlib algorithm is raw
            // DEFLATE (RFC 1951, no zlib wrapper) — exactly the right decoder.
            let inflated = try (Data(raw) as NSData).decompressed(using: .zlib) as Data
            return Entry(name: name, data: [UInt8](inflated), isDirectory: isDirectory)
        default:
            throw ZIPError.unsupportedMethod(method)
        }
    }

    /// Locate the End of Central Directory record (scan backward past a possible comment).
    private static func findEndOfCentralDirectory(_ bytes: [UInt8]) -> Int? {
        guard bytes.count >= 22 else { return nil }
        let lowest = max(0, bytes.count - 22 - 0xFFFF)
        var i = bytes.count - 22
        while i >= lowest {
            if readUInt32(bytes, i) == 0x0605_4B50 { return i }
            i -= 1
        }
        return nil
    }

    // ── Little-endian and CRC helpers ────────────────────────────────────────

    private static func appendUInt16(_ out: inout [UInt8], _ v: UInt16) {
        out.append(UInt8(v & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
    }

    private static func appendUInt32(_ out: inout [UInt8], _ v: UInt32) {
        out.append(UInt8(v & 0xFF))
        out.append(UInt8((v >> 8) & 0xFF))
        out.append(UInt8((v >> 16) & 0xFF))
        out.append(UInt8((v >> 24) & 0xFF))
    }

    private static func readUInt16(_ bytes: [UInt8], _ i: Int) -> UInt16 {
        UInt16(bytes[i]) | (UInt16(bytes[i + 1]) << 8)
    }

    private static func readUInt32(_ bytes: [UInt8], _ i: Int) -> UInt32 {
        UInt32(bytes[i]) | (UInt32(bytes[i + 1]) << 8) |
        (UInt32(bytes[i + 2]) << 16) | (UInt32(bytes[i + 3]) << 24)
    }

    /// MS-DOS (time, date) words for ZIP headers, clamped to the 1980 epoch.
    private static func dosTimestamp(_ date: Date) -> (time: UInt16, date: UInt16) {
        let c = Calendar(identifier: .gregorian)
            .dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        // Explicit Int locals: folding the shift/or chain and the UInt16 init into one
        // expression makes the type-checker time out on older Swift toolchains.
        let year: Int = max((c.year ?? 1980) - 1980, 0)
        let hour: Int = c.hour ?? 0
        let minute: Int = c.minute ?? 0
        let second: Int = c.second ?? 0
        let month: Int = c.month ?? 1
        let dayOfMonth: Int = c.day ?? 1
        let timeBits: Int = (hour << 11) | (minute << 5) | (second / 2)
        let dateBits: Int = (year << 9) | (month << 5) | dayOfMonth
        return (UInt16(timeBits), UInt16(dateBits))
    }

    private static let crcTable: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 {
            c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    static func crc32(_ data: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in data {
            c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
        }
        return c ^ 0xFFFF_FFFF
    }
}

// MARK: - BackupManager

/// Orchestrates EP-133 /sounds backup and restore on top of `MIDIRepository`'s reqId-correlated
/// file ops. Mirrors AndroidApp's `BackupManager.kt`.
///
/// **Backup** (`createBackup`): enumerates /sounds via `MIDIRepository.listSoundEntries`,
/// downloads each file with the multi-chunk `MIDIRepository.getFileBytes`, and assembles a ZIP
/// (.pak) of the files plus a metadata.json. (The old single-response-per-file model truncated
/// anything bigger than one chunk — real samples exceed one chunk, so backups used to contain
/// only metadata.json.)
///
/// **Restore** (`restore`): validates the ZIP, then re-creates each file in /sounds via the
/// paged, per-page-acked `MIDIRepository.putSampleFile` (no more fire-and-forget). Restore is
/// gated off in the UI pending hardware UAT; per-file channel/samplerate metadata is not yet
/// captured, so restored files default to the device-native mono / 46875 Hz.
@MainActor
final class BackupManager {

    private let midi: MIDIRepository

    init(_ midi: MIDIRepository) {
        self.midi = midi
    }

    /// Suggested backup filename: `EP133-YYYY-MM-DD-HHmm.pak`.
    func suggestedBackupFilename() -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd-HHmm"
        return "EP133-\(fmt.string(from: Date())).pak"
    }

    /// Create a backup of the EP-133's /sounds directory as a .pak (ZIP) archive.
    ///
    /// Emits `.progress` per file downloaded, `.done` with the completed ZIP bytes, or
    /// `.error` on failure. (Kotlin's flow rethrows repository exceptions to the collector;
    /// `AsyncStream` cannot throw, so unexpected errors surface as `.error` and cancellation
    /// just finishes the stream.)
    func createBackup() -> AsyncStream<BackupProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor [midi] in
                defer { continuation.finish() }
                continuation.yield(.progress(current: 0, total: 0))

                if midi.deviceState.outputPortId == nil {
                    continuation.yield(.error(message: "No EP-133 connected"))
                    return
                }

                do {
                    // Enumerate /sounds via the reqId-correlated listing (replaces the old
                    // 5s collect window).
                    let entries = try await midi.listSoundEntries()
                    if entries.isEmpty {
                        continuation.yield(.error(
                            message: "No files found on device (or it did not respond)"))
                        return
                    }

                    let total = entries.count
                    // Ordered names + by-name map → preserve device order in the archive
                    // (Kotlin LinkedHashMap semantics: a duplicate name keeps its position).
                    var order = [String]()
                    var fileData = [String: [UInt8]]()
                    for (index, entry) in entries.enumerated() {
                        continuation.yield(.progress(current: index, total: total))
                        // Multi-chunk download — assembles the whole file, no truncation.
                        let bytes = try await midi.getFileBytes(nodeId: entry.nodeId)
                        if let bytes, !bytes.isEmpty {
                            if fileData[entry.name] == nil { order.append(entry.name) }
                            fileData[entry.name] = bytes
                        } else {
                            EP133Log.warning(.backup, "skip '\(entry.name)' (node \(entry.nodeId)): GET returned no data")
                        }
                    }

                    if fileData.isEmpty {
                        continuation.yield(.error(
                            message: "Downloaded 0 of \(total) files — the device returned no data"))
                        return
                    }

                    var zipEntries = order.map { name in
                        (name: name, data: fileData[name] ?? [])
                    }
                    let metadataJson = buildMetadataJson(
                        fileCount: fileData.count, deviceState: midi.deviceState)
                    zipEntries.append((name: "metadata.json", data: Array(metadataJson.utf8)))
                    let zipBytes = ZIPArchive.build(zipEntries)

                    continuation.yield(.progress(current: total, total: total))
                    continuation.yield(.done(pakBytes: zipBytes))
                } catch is CancellationError {
                    // Consumer went away — finish silently.
                } catch {
                    continuation.yield(.error(message: "Backup failed: \(error)"))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Restore a .pak backup to the EP-133's /sounds directory.
    ///
    /// Validates the ZIP, then re-creates each entry via the paged, per-page-acked
    /// `MIDIRepository.putSampleFile`. Emits `.progress` per file, `.done` on completion, or
    /// `.error` on an invalid archive, no connection, or a failed page.
    func restore(pakBytes: [UInt8]) -> AsyncStream<RestoreProgress> {
        AsyncStream { continuation in
            let task = Task { @MainActor [midi] in
                defer { continuation.finish() }

                // Validate ZIP magic: PK header [0x50, 0x4B, 0x03, 0x04].
                if pakBytes.count < 4 ||
                    pakBytes[0] != 0x50 || pakBytes[1] != 0x4B ||
                    pakBytes[2] != 0x03 || pakBytes[3] != 0x04 {
                    continuation.yield(.error(message: "Invalid PAK file — not a ZIP archive"))
                    return
                }

                if midi.deviceState.outputPortId == nil {
                    continuation.yield(.error(message: "No EP-133 connected"))
                    return
                }

                // Parse the archive once (the whole pak is already in memory — the Kotlin
                // two-pass ZipInputStream walk existed to stream entries off disk).
                let entries: [ZIPArchive.Entry]
                do {
                    entries = try ZIPArchive.read(pakBytes)
                } catch {
                    continuation.yield(.error(message: "Invalid PAK file — not a ZIP archive"))
                    return
                }
                let restorable = entries.filter { !$0.isDirectory && $0.name != "metadata.json" }

                let total = restorable.count
                if total == 0 {
                    continuation.yield(.error(message: "Backup contains no sound files"))
                    return
                }

                for (index, entry) in restorable.enumerated() {
                    continuation.yield(.progress(current: index, total: total))
                    let nodeId: Int?
                    do {
                        // Paged create-PUT with a per-page ack and a closing terminator.
                        nodeId = try await midi.putSampleFile(name: entry.name, pcmBytes: entry.data)
                    } catch is CancellationError {
                        return
                    } catch {
                        EP133Log.error(.backup, "restore failed on '\(entry.name)': \(error)")
                        nodeId = nil
                    }
                    if nodeId == nil {
                        continuation.yield(.error(message: "Restore failed on '\(entry.name)'"))
                        return
                    }
                }

                continuation.yield(.progress(current: total, total: total))
                continuation.yield(.done)
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func buildMetadataJson(fileCount: Int, deviceState: DeviceState) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return """
        {
          "backup_date": "\(fmt.string(from: Date()))",
          "firmware_version": "\(deviceState.firmwareVersion ?? "unknown")",
          "device_name": "\(deviceState.deviceName)",
          "file_count": \(fileCount)
        }
        """
    }
}
