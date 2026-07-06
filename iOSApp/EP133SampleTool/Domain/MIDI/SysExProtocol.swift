import Foundation

/// TE SysEx protocol frame builder and 7-bit codec.
///
/// Manufacturer ID: 0x00 0x20 0x76 (Teenage Engineering)
///
/// Frame format: 0xF0 [TE_ID x3] [deviceId] [0x40] [flags] [requestId] [command] [payload] 0xF7
///
/// Mirrors AndroidApp/app/src/main/java/com/ep133/sampletool/domain/midi/SysExProtocol.kt
/// (hardware-verified, byte-exact). Reference: reverse-engineered from data/index.js
/// (EP-133 K.O. II firmware).
enum SysExProtocol {

    /// Kotlin `require` → invalidArgument; Kotlin `check` → invalidState.
    enum ProtocolError: Error, Equatable {
        case invalidArgument(String)
        case invalidState(String)
    }

    // ── Manufacturer ID (Teenage Engineering) ──
    static let TE_ID_0: UInt8 = 0x00
    static let TE_ID_1: UInt8 = 0x20
    static let TE_ID_2: UInt8 = 0x76     // 118 decimal

    // ── MIDI SysEx framing ──
    static let MIDI_SYSEX_TE: UInt8 = 0x40         // TE subsystem byte
    static let MIDI_SYSEX_START: UInt8 = 0xF0
    static let MIDI_SYSEX_END: UInt8 = 0xF7

    // ── Request flags ──
    static let BIT_IS_REQUEST = 0x40
    static let BIT_REQUEST_ID_AVAILABLE = 0x20

    // ── Top-level commands ──
    static let CMD_GREET = 1
    static let CMD_PRODUCT_SPECIFIC = 127
    // Hardware-verified (2026-06-23): file ops use command=5 directly, NOT CMD_PRODUCT_SPECIFIC(127).
    // Frame: F0 00 20 76 <dev> 40 <flags> <reqId7> <5> <7bit-packed body> F7
    // Body starts at the subcommand — no leading TE_SYSEX_FILE byte repeated in payload.
    static let TE_SYSEX_FILE = 5

    // ── File system subcommands (payload body byte 0 when command=TE_SYSEX_FILE) ──
    // Hardware-verified sequence: FILE_INIT(1) → FILE_LIST(4, node=0) → root dir returned.
    static let TE_SYSEX_FILE_INIT = 1       // SESSION INIT — must send before any listing
    static let TE_SYSEX_FILE_PUT = 2
    static let TE_SYSEX_FILE_GET = 3
    static let TE_SYSEX_FILE_LIST = 4
    static let TE_SYSEX_FILE_DELETE = 6
    static let TE_SYSEX_FILE_METADATA = 7
    static let TE_SYSEX_FILE_INFO = 11

    // ── FILE_INIT flags ──
    static let TE_SYSEX_FILE_INIT_SUBSCRIBE = 1  // subscribe to device-push events

    // ── FILE_PUT INIT capability + file-type flags ──
    // capabilities is ORed into flags: READ=4, WRITE=8, file-type FILE=1, DIR=2.
    // New-file INIT: flags = TE_SYSEX_FILE_CAPABILITY_READ | TE_SYSEX_FILE_TYPE_FILE = 5.
    static let TE_SYSEX_FILE_CAPABILITY_READ = 4
    static let TE_SYSEX_FILE_CAPABILITY_WRITE = 8
    static let TE_SYSEX_FILE_TYPE_FILE = 1

    // ── FILE_METADATA sub-opcodes ──
    static let TE_SYSEX_FILE_METADATA_SET = 1
    static let TE_SYSEX_FILE_METADATA_GET = 2

    // ── Two-phase GET/PUT transfer sub-types (under FILE_GET / FILE_PUT) ──
    // Project archives are multi-page .tar blobs; the real device protocol is an
    // INIT request (returns fileSize/fileName) then a paged DATA loop. The path-string
    // single-byte chunkIndex builders are kept intact for existing /sounds callers.
    static let TE_SYSEX_FILE_GET_TYPE_INIT = 0
    static let TE_SYSEX_FILE_GET_TYPE_DATA = 1
    static let TE_SYSEX_FILE_PUT_TYPE_INIT = 0
    static let TE_SYSEX_FILE_PUT_TYPE_DATA = 1

    // ── Status codes ──
    static let STATUS_OK = 0
    static let STATUS_SPECIFIC_SUCCESS_START = 64  // more data chunks coming

    // ── ASCII codec (mirrors Kotlin Charsets.US_ASCII behavior) ─────────────

    /// Encode like Kotlin `String.toByteArray(Charsets.US_ASCII)`: unmappable scalars become '?'.
    private static func asciiBytes(_ s: String) -> [UInt8] {
        s.unicodeScalars.map { $0.isASCII ? UInt8($0.value) : 0x3F }
    }

    /// Decode like Kotlin `String(bytes, Charsets.US_ASCII)`: bytes > 0x7F become U+FFFD.
    private static func asciiString<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        var view = String.UnicodeScalarView()
        for b in bytes {
            view.append(Unicode.Scalar(b < 0x80 ? UInt32(b) : 0xFFFD)!)
        }
        return String(view)
    }

    // ── 7-bit codec ─────────────────────────────────────────────────────────

    /// Pack 8-bit data bytes into MIDI-safe 7-bit encoding.
    ///
    /// For each group of up to 7 input bytes:
    /// - A leading byte holds the high bits (bit 7 of each data byte), packed LSB-first
    ///   (bit j of the leading byte carries the high bit of data byte j)
    /// - Each data byte is then written with its high bit cleared (AND 0x7F)
    ///
    /// All output bytes are < 128 and therefore MIDI SysEx-safe.
    static func pack7bit(_ data: [UInt8]) -> [UInt8] {
        if data.isEmpty { return [] }
        var output = [UInt8]()
        output.reserveCapacity(data.count + data.count / 7 + 2)
        var i = 0
        while i < data.count {
            let groupSize = min(7, data.count - i)
            var highBits: UInt8 = 0
            for j in 0..<groupSize where data[i + j] & 0x80 != 0 {
                // Device convention (data/index.js packToBuffer): bit j, LSB-first.
                highBits |= UInt8(1 << j)
            }
            output.append(highBits)
            for j in 0..<groupSize {
                output.append(data[i + j] & 0x7F)
            }
            i += groupSize
        }
        return output
    }

    /// Unpack 7-bit encoded data back to 8-bit bytes.
    ///
    /// Reverses `pack7bit`: reads leading high-bits byte then 1–7 data bytes,
    /// restoring the high bit of each from the leading byte.
    static func unpack7bit(_ data: [UInt8]) -> [UInt8] {
        if data.isEmpty { return [] }
        var output = [UInt8]()
        output.reserveCapacity(data.count)
        var i = 0
        while i < data.count {
            let highBits = data[i] & 0x7F
            i += 1
            // Device convention (data/index.js unpackInPlace): data byte k's high bit
            // = bit k of the high-bits byte, LSB-first.
            var k = 0
            while k <= 6 && i < data.count {
                let highBit: UInt8 = (highBits & UInt8(1 << k)) != 0 ? 0x80 : 0x00
                output.append((data[i] & 0x7F) | highBit)
                i += 1
                k += 1
            }
        }
        return output
    }

    // ── Frame construction ───────────────────────────────────────────────────

    /// Build a TE SysEx frame.
    ///
    /// Header layout (10 bytes before packed payload):
    ///   [0] 0xF0  — SysEx start
    ///   [1] 0x00  — TE ID byte 0
    ///   [2] 0x20  — TE ID byte 1
    ///   [3] 0x76  — TE ID byte 2
    ///   [4] deviceId (0–127)
    ///   [5] 0x40  — TE subsystem
    ///   [6] flags (BIT_IS_REQUEST | BIT_REQUEST_ID_AVAILABLE | requestId >> 7)
    ///   [7] requestId & 0x7F
    ///   [8] command
    ///   ... packed payload ...
    ///   [last] 0xF7 — SysEx end
    static func buildFrame(deviceId: Int, command: Int, requestId: Int, payload: [UInt8]) -> [UInt8] {
        let packedPayload = payload.isEmpty ? [] : pack7bit(payload)
        var output = [UInt8]()
        output.reserveCapacity(10 + packedPayload.count + 1)
        output.append(MIDI_SYSEX_START)
        output.append(TE_ID_0)
        output.append(TE_ID_1)
        output.append(TE_ID_2)
        output.append(UInt8(deviceId & 0x7F))
        output.append(MIDI_SYSEX_TE)
        let flags = BIT_IS_REQUEST | BIT_REQUEST_ID_AVAILABLE | ((requestId >> 7) & 0x0F)
        output.append(UInt8(flags & 0xFF))
        output.append(UInt8(requestId & 0x7F))
        output.append(UInt8(command & 0x7F))
        output.append(contentsOf: packedPayload)
        output.append(MIDI_SYSEX_END)
        return output
    }

    /// Build a GREET frame — queries firmware version, serial number, and device identity.
    static func buildGreetFrame(deviceId: Int, requestId: Int = 1) -> [UInt8] {
        buildFrame(deviceId: deviceId, command: CMD_GREET, requestId: requestId, payload: [])
    }

    /// Parse a GREET response payload into key-value pairs.
    ///
    /// The EP-133 responds with a 7-bit-encoded semicolon-delimited string:
    /// "sw_version:1.3.2;serial:ABCDEF;..."
    static func parseGreetResponse(_ rawPayload: [UInt8]) -> [String: String] {
        if rawPayload.count < 2 { return [:] }
        // Hardware-verified (2026-06-27): the greet body is a 1-byte status prefix followed by
        // 7-bit-packed "key:value;…" ASCII (e.g. sw_version:2.0.5;serial:E3PUA1T2). Skipping the
        // prefix is required — unpacking from byte 0 desyncs the high-bits groups and garbles it.
        let decoded = asciiString(unpack7bit(Array(rawPayload[1...])))
        var result: [String: String] = [:]
        let trimmed = decoded.trimmingCharacters(in: CharacterSet(charactersIn: "\0"))
        for entry in trimmed.split(separator: ";") {
            guard let colonIdx = entry.firstIndex(of: ":") else { continue }
            result[String(entry[..<colonIdx])] = String(entry[entry.index(after: colonIdx)...])
        }
        return result
    }

    // ── File system frame builders ───────────────────────────────────────────

    /// Build a TE SysEx frame for a file subsystem operation (command = TE_SYSEX_FILE = 5).
    ///
    /// Hardware-verified (2026-06-23): the correct envelope is command=5 with the body
    /// starting at the file subcommand — NOT command=127 with [5, subcmd, ...] payload.
    ///
    /// - Parameter body: Raw (pre-pack) payload starting with the file subcommand byte.
    static func buildFileFrame(deviceId: Int, requestId: Int, body: [UInt8]) -> [UInt8] {
        buildFrame(deviceId: deviceId, command: TE_SYSEX_FILE, requestId: requestId, payload: body)
    }

    /// Build a file-system frame wrapping a path-string file subcommand.
    ///
    /// Body structure: fileCmd, pathBytes..., extraPayload...
    /// (No leading TE_SYSEX_FILE byte — it is the top-level command, not a payload prefix.)
    private static func buildFileSystemFrame(
        deviceId: Int,
        fileCmd: Int,
        requestId: Int,
        pathBytes: [UInt8],
        extraPayload: [UInt8] = []
    ) -> [UInt8] {
        let body = [UInt8(fileCmd & 0xFF)] + pathBytes + extraPayload
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: body)
    }

    /// Build a FILE_LIST request frame for the given path.
    static func buildFileListFrame(deviceId: Int, path: String, requestId: Int) -> [UInt8] {
        buildFileSystemFrame(
            deviceId: deviceId,
            fileCmd: TE_SYSEX_FILE_LIST,
            requestId: requestId,
            pathBytes: asciiBytes(path)
        )
    }

    /// Build a FILE_GET request frame to download a chunk of a file.
    static func buildFileGetFrame(
        deviceId: Int,
        path: String,
        chunkIndex: Int,
        requestId: Int
    ) -> [UInt8] {
        let chunkBytes: [UInt8] = [
            UInt8((chunkIndex >> 8) & 0xFF),
            UInt8(chunkIndex & 0xFF),
        ]
        return buildFileSystemFrame(
            deviceId: deviceId,
            fileCmd: TE_SYSEX_FILE_GET,
            requestId: requestId,
            pathBytes: asciiBytes(path),
            extraPayload: chunkBytes
        )
    }

    /// Build a FILE_PUT request frame to upload a chunk of a file.
    static func buildFilePutFrame(
        deviceId: Int,
        path: String,
        data: [UInt8],
        chunkIndex: Int,
        requestId: Int
    ) -> [UInt8] {
        let chunkBytes: [UInt8] = [
            UInt8((chunkIndex >> 8) & 0xFF),
            UInt8(chunkIndex & 0xFF),
        ]
        return buildFileSystemFrame(
            deviceId: deviceId,
            fileCmd: TE_SYSEX_FILE_PUT,
            requestId: requestId,
            pathBytes: asciiBytes(path),
            extraPayload: chunkBytes + data
        )
    }

    /// Build a FILE_LIST request frame that lists a node by its numeric ID (the device's
    /// actual addressing model per the reference impl: `SysExFileListRequest(page, nodeId)`).
    ///
    /// Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body = [LIST(4), page u16, nodeId u16].
    /// No leading TE_SYSEX_FILE byte in the body — that byte is the top-level command.
    static func buildFileListByNodeFrame(deviceId: Int, nodeId: Int, page: Int = 0, requestId: Int) -> [UInt8] {
        let body: [UInt8] = [
            UInt8(TE_SYSEX_FILE_LIST),
            UInt8((page >> 8) & 0xFF), UInt8(page & 0xFF),       // uint16 BE
            UInt8((nodeId >> 8) & 0xFF), UInt8(nodeId & 0xFF),   // uint16 BE
        ]
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: body)
    }

    // ── FILE_LIST response parsing (directory entries) ──────────────────────────

    /// One parsed FILE_LIST directory entry.
    struct FileEntry: Equatable {
        let nodeId: Int
        let flags: Int
        let sizeBytes: Int64
        let name: String
    }

    /// Directory-entry flag: a FILE node (vs DIR).
    static let TE_SYSEX_FILE_FILE_TYPE_FILE = 1
    /// Directory-entry flag: a DIR node.
    static let TE_SYSEX_FILE_FILE_TYPE_DIR = 2

    /// Decode concatenated FILE_LIST directory entries from an already-unpacked response body.
    ///
    /// Per-entry layout (RESEARCH "FILE_LIST response"):
    ///   nodeId   = a[0..1] uint16 BE
    ///   flags    = a[2]
    ///   fileSize = a[3..6] uint32 BE
    ///   fileName = null-terminated ASCII from a[7]
    /// Entries are concatenated; the next entry begins after the name's NUL terminator.
    ///
    /// Bounds-checks every field read and STOPS on a truncated entry rather than reading
    /// past the buffer (threat T-04-07). A missing NUL terminator on the final entry is
    /// tolerated — the rest of the buffer is taken as the name.
    static func parseFileListEntries(_ body: [UInt8]) -> [FileEntry] {
        var entries = [FileEntry]()
        var i = 0
        while i + 7 <= body.count {
            let nodeId = (Int(body[i]) << 8) | Int(body[i + 1])
            let flags = Int(body[i + 2])
            let sizeBytes = (Int64(body[i + 3]) << 24) |
                (Int64(body[i + 4]) << 16) |
                (Int64(body[i + 5]) << 8) |
                Int64(body[i + 6])
            let nameStart = i + 7
            var nul = nameStart
            while nul < body.count && body[nul] != 0 { nul += 1 }
            let name = asciiString(body[nameStart..<nul])
            entries.append(FileEntry(nodeId: nodeId, flags: flags, sizeBytes: sizeBytes, name: name))
            if nul >= body.count { break }   // no terminator → final entry, stop
            i = nul + 1                       // skip the NUL terminator
        }
        return entries
    }

    /// Build a FILE_METADATA request frame to query storage info for a path.
    static func buildFileMetadataFrame(deviceId: Int, path: String, requestId: Int) -> [UInt8] {
        buildFileSystemFrame(
            deviceId: deviceId,
            fileCmd: TE_SYSEX_FILE_METADATA,
            requestId: requestId,
            pathBytes: asciiBytes(path)
        )
    }

    // ── Paged GET/PUT (the real INIT/DATA protocol — multi-kilobyte archives) ──
    //
    // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at
    // the subcommand byte — no leading TE_SYSEX_FILE byte in the payload body.
    // nodeId uint16 BE, offset uint32 BE, page uint16 BE.
    // Reference: data/index.js SysExGetFileInitRequest / SysExGetFileDataRequest.

    private static func buildFileGetInitBody(nodeId: Int, offset: Int) -> [UInt8] {
        [
            UInt8(TE_SYSEX_FILE_GET),
            UInt8(TE_SYSEX_FILE_GET_TYPE_INIT),
            UInt8((nodeId >> 8) & 0xFF), UInt8(nodeId & 0xFF),                 // uint16 BE
            UInt8((offset >> 24) & 0xFF), UInt8((offset >> 16) & 0xFF),
            UInt8((offset >> 8) & 0xFF), UInt8(offset & 0xFF),                 // uint32 BE
        ]
    }

    private static func buildFileGetDataBody(page: Int) -> [UInt8] {
        [
            UInt8(TE_SYSEX_FILE_GET),
            UInt8(TE_SYSEX_FILE_GET_TYPE_DATA),
            UInt8((page >> 8) & 0xFF), UInt8(page & 0xFF),                     // uint16 BE
        ]
    }

    private static func buildFilePutInitBody(nodeId: Int, fileSize: Int) -> [UInt8] {
        [
            UInt8(TE_SYSEX_FILE_PUT),
            UInt8(TE_SYSEX_FILE_PUT_TYPE_INIT),
            UInt8((nodeId >> 8) & 0xFF), UInt8(nodeId & 0xFF),                 // uint16 BE
            UInt8((fileSize >> 24) & 0xFF), UInt8((fileSize >> 16) & 0xFF),
            UInt8((fileSize >> 8) & 0xFF), UInt8(fileSize & 0xFF),             // uint32 BE
        ]
    }

    /// Build a paged FILE_GET INIT request. Response carries fileSize + fileName.
    static func buildFileGetInitFrame(deviceId: Int, nodeId: Int, offset: Int = 0, requestId: Int) -> [UInt8] {
        buildFileFrame(deviceId: deviceId, requestId: requestId, body: buildFileGetInitBody(nodeId: nodeId, offset: offset))
    }

    /// Build a paged FILE_GET DATA request for the given page.
    static func buildFileGetDataFrame(deviceId: Int, page: Int, requestId: Int) -> [UInt8] {
        buildFileFrame(deviceId: deviceId, requestId: requestId, body: buildFileGetDataBody(page: page))
    }

    /// Build a paged FILE_PUT INIT request announcing the total upload size.
    static func buildFilePutInitFrame(deviceId: Int, nodeId: Int, fileSize: Int, requestId: Int) -> [UInt8] {
        buildFileFrame(deviceId: deviceId, requestId: requestId, body: buildFilePutInitBody(nodeId: nodeId, fileSize: fileSize))
    }

    /// Build a FILE_PUT INIT for CREATING a new file under `parentNodeId` (e.g. the /sounds dir).
    ///
    /// Matches the device reference tool (data/index.js SysExFilePutInitRequest): announces the
    /// parent directory node ID, a fileId of 0 (device assigns the real id on the response), the
    /// byte size, and the filename (truncated to 54 chars, NUL-terminated). Metadata is optional
    /// and omitted when nil.
    ///
    /// Wire body (before 7-bit packing; command = TE_SYSEX_FILE = 5 is the envelope command):
    ///   [PUT(2), INIT(0), flags, fileId u16 BE, parentId u16 BE, fileSize u32 BE,
    ///    filename ASCII + 0x00, (metadata ASCII + 0x00 only when non-nil)]
    ///
    /// Default flags = TE_SYSEX_FILE_CAPABILITY_READ | TE_SYSEX_FILE_TYPE_FILE = 5, matching
    /// uploadSound in the reference tool. Default fileId = 0 (new file — device assigns the id).
    static func buildFileCreatePutInitFrame(
        deviceId: Int,
        parentNodeId: Int,
        fileSize: Int,
        filename: String,
        requestId: Int,
        flags: Int = TE_SYSEX_FILE_CAPABILITY_READ | TE_SYSEX_FILE_TYPE_FILE,
        fileId: Int = 0,
        metadataJson: String? = nil
    ) -> [UInt8] {
        let nameBytes = asciiBytes(String(filename.prefix(54)))
        var out = [UInt8]()
        out.reserveCapacity(16 + nameBytes.count)
        out.append(UInt8(TE_SYSEX_FILE_PUT))
        out.append(UInt8(TE_SYSEX_FILE_PUT_TYPE_INIT))
        out.append(UInt8(flags & 0xFF))
        out.append(UInt8((fileId >> 8) & 0xFF)); out.append(UInt8(fileId & 0xFF))              // fileId u16 BE
        out.append(UInt8((parentNodeId >> 8) & 0xFF)); out.append(UInt8(parentNodeId & 0xFF))  // parentId u16 BE
        out.append(UInt8((fileSize >> 24) & 0xFF)); out.append(UInt8((fileSize >> 16) & 0xFF))
        out.append(UInt8((fileSize >> 8) & 0xFF)); out.append(UInt8(fileSize & 0xFF))          // fileSize u32 BE
        out.append(contentsOf: nameBytes); out.append(0)                                        // filename + NUL
        if let metadataJson {
            out.append(contentsOf: asciiBytes(metadataJson)); out.append(0)
        }
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: out)
    }

    /// Build a paged FILE_PUT DATA request: a page header followed by the archive chunk.
    /// The chunk bytes are raw 8-bit; buildFrame 7-bit-packs the whole body.
    /// Command = TE_SYSEX_FILE (5); body starts at PUT(2) subcommand (no leading 5).
    static func buildFilePutDataFrame(deviceId: Int, page: Int, chunk: [UInt8], requestId: Int) -> [UInt8] {
        let body: [UInt8] = [
            UInt8(TE_SYSEX_FILE_PUT),
            UInt8(TE_SYSEX_FILE_PUT_TYPE_DATA),
            UInt8((page >> 8) & 0xFF), UInt8(page & 0xFF),
        ] + chunk
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: body)
    }

    // ── Paged GET response parsers ─────────────────────────────────────────────

    /// Parsed FILE_GET INIT response.
    struct GetInitResponse: Equatable {
        let fileId: Int
        let flags: Int
        let fileSize: Int
        let fileName: String
    }

    /// Parsed FILE_GET DATA response. `data` empty signals end-of-file.
    struct GetDataResponse {
        let page: Int
        let data: [UInt8]

        /// Page the next DATA request should ask for.
        var nextPage: Int { (page + 1) & 0xFFFF }
    }

    /// Parse an already-unpacked FILE_GET INIT response body.
    ///
    /// The dispatcher strips the [TE_SYSEX_FILE, TE_SYSEX_FILE_GET, status] prefix
    /// before calling this; `body` starts at the INIT payload.
    static func parseGetInitResponse(_ body: [UInt8]) throws -> GetInitResponse {
        guard body.count >= 7 else {
            throw ProtocolError.invalidArgument("GET INIT response too short: \(body.count) bytes")
        }
        let fileId = (Int(body[0]) << 8) | Int(body[1])
        let flags = Int(body[2])
        let fileSize = (Int(body[3]) << 24) |
            (Int(body[4]) << 16) |
            (Int(body[5]) << 8) |
            Int(body[6])
        let nameBytes = body[7...]
        let nul = nameBytes.firstIndex(where: { $0 == 0 })
        let fileName = asciiString(nul.map { nameBytes[..<$0] } ?? nameBytes)
        return GetInitResponse(fileId: fileId, flags: flags, fileSize: fileSize, fileName: fileName)
    }

    /// Parse an already-unpacked FILE_GET DATA response body.
    static func parseGetDataResponse(_ body: [UInt8]) throws -> GetDataResponse {
        guard body.count >= 2 else {
            throw ProtocolError.invalidArgument("GET DATA response too short: \(body.count) bytes")
        }
        let page = (Int(body[0]) << 8) | Int(body[1])
        return GetDataResponse(page: page, data: Array(body[2...]))
    }

    /// Pure page-assembly loop for a multi-page FILE_GET. Hardware-free: `supplier` is
    /// called with the expected page number and returns that page's `GetDataResponse`.
    ///
    /// Mirrors data/index.js `iterGet`:
    ///  - accumulates until `out.count >= fileSize` or an empty-data page terminates early
    ///  - requires `resp.page == expectedPage`, else throws "unexpected page" (no silent accept)
    ///  - advances `page = (page + 1) & 0xFFFF`
    ///  - caps the buffer at fileSize + slack to defend against an oversized/runaway stream
    ///    (threat T-04-03); aborts on overflow
    static func assembleGetPages(fileSize: Int, supplier: (_ expectedPage: Int) throws -> GetDataResponse) throws -> [UInt8] {
        let cap = fileSize + MAX_PAGE_BYTES   // small slack: one page beyond the declared size
        var out = [UInt8]()
        out.reserveCapacity(max(fileSize, 0))
        var page = 0
        while out.count < fileSize {
            let resp = try supplier(page)
            guard resp.page == page else {
                throw ProtocolError.invalidState("unexpected page \(resp.page), expected \(page)")
            }
            if resp.data.isEmpty { break }
            guard out.count + resp.data.count <= cap else {
                throw ProtocolError.invalidState("GET overflow: \(out.count + resp.data.count) bytes exceeds cap \(cap)")
            }
            out.append(contentsOf: resp.data)
            page = resp.nextPage
        }
        return out
    }

    /// Defensive per-page slack for the assembly-buffer overflow cap (threat T-04-03).
    static let MAX_PAGE_BYTES = 4096

    /// Outcome of a paged-transfer response status (the dispatch continuation decision).
    enum TransferStatus {
        case pending, complete, error
    }

    /// Pure classification of a paged-transfer response status byte:
    ///  - `>= STATUS_SPECIFIC_SUCCESS_START` → more chunks coming, keep the request registered
    ///  - `== STATUS_OK` → terminal success, complete the request
    ///  - otherwise (`< SUCCESS_START`, non-OK) → error, drop the request
    static func classifyTransferStatus(_ status: Int) -> TransferStatus {
        if status >= STATUS_SPECIFIC_SUCCESS_START { return .pending }
        if status == STATUS_OK { return .complete }
        return .error
    }

    // ── Metadata nodeId-form builders (Step 1 — active-group sync) ──────────────
    //
    // These use the reference tool's nodeId+page+key form rather than the legacy
    // path-string form (buildFileMetadataFrame). The path-form is kept intact for
    // Phase-4 /sounds storage stats and /projects active-node reads.
    //
    // Wire body (command = TE_SYSEX_FILE = 5; no leading 5 in the body):
    //   GET: [METADATA(7), GET(2), nodeId u16 BE, page u16 BE, (key ASCII + 0x00)?]
    //   SET: [METADATA(7), SET(1), nodeId u16 BE, json ASCII, 0x00]
    //   INFO:[FILE_INFO(11), nodeId u16 BE]
    //
    // Reference: data/index.js SysExFileGetMetadataRequest / SysExFileSetMetadataRequest
    //            / SysExFileInfoRequest — verified 2026-06-21.

    /// Build a nodeId-form METADATA GET frame (METADATA_GET = 2).
    ///
    /// Body (command = TE_SYSEX_FILE): [METADATA(7), GET(2), nodeId u16, page u16, (key+NUL)?]
    ///
    /// - Parameters:
    ///   - nodeId: Numeric node ID of the directory or file to query.
    ///   - page: Page index (0-based); devices return `[page u16][JSON fragment]` pages.
    ///   - key: Optional key filter (ASCII string). When nil, full metadata is returned.
    ///   - requestId: Matched by the dispatcher to route the response.
    static func buildMetadataGetFrame(
        deviceId: Int,
        nodeId: Int,
        page: Int = 0,
        key: String? = nil,
        requestId: Int
    ) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(7 + (key.map { $0.count + 1 } ?? 0))
        out.append(UInt8(TE_SYSEX_FILE_METADATA))
        out.append(UInt8(TE_SYSEX_FILE_METADATA_GET))
        out.append(UInt8((nodeId >> 8) & 0xFF)); out.append(UInt8(nodeId & 0xFF))  // u16 BE
        out.append(UInt8((page >> 8) & 0xFF)); out.append(UInt8(page & 0xFF))      // u16 BE
        if let key {
            out.append(contentsOf: asciiBytes(key))
            out.append(0)
        }
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: out)
    }

    /// Build a nodeId-form METADATA SET frame (METADATA_SET = 1).
    ///
    /// Body (command = TE_SYSEX_FILE): [METADATA(7), SET(1), nodeId u16, json ASCII, 0x00]
    ///
    /// For active-group selection the JSON is `{"active":<groupNodeId>}` — always
    /// single-frame (far below any device chunk size).
    ///
    /// - Parameters:
    ///   - nodeId: Numeric node ID of the directory whose metadata to update.
    ///   - json: JSON string to write (e.g. `{"active":1234}`).
    static func buildMetadataSetFrame(
        deviceId: Int,
        nodeId: Int,
        json: String,
        requestId: Int
    ) -> [UInt8] {
        let jsonBytes = asciiBytes(json)
        var out = [UInt8]()
        out.reserveCapacity(4 + jsonBytes.count + 1)
        out.append(UInt8(TE_SYSEX_FILE_METADATA))
        out.append(UInt8(TE_SYSEX_FILE_METADATA_SET))
        out.append(UInt8((nodeId >> 8) & 0xFF)); out.append(UInt8(nodeId & 0xFF))  // u16 BE
        out.append(contentsOf: jsonBytes)
        out.append(0)
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: out)
    }

    /// Build a FILE_INFO (getNode) frame (op 11).
    ///
    /// Body (command = TE_SYSEX_FILE): [FILE_INFO(11), nodeId u16 BE]
    ///
    /// Response layout: nodeId u16, parentId u16, flags u8, size u32, name@9 NUL-terminated ASCII.
    /// Parse with `parseFileInfo`.
    ///
    /// Reference: data/index.js SysExFileInfoRequest, byte ~833656.
    static func buildFileInfoFrame(deviceId: Int, nodeId: Int, requestId: Int) -> [UInt8] {
        buildFileFrame(
            deviceId: deviceId,
            requestId: requestId,
            body: [
                UInt8(TE_SYSEX_FILE_INFO),
                UInt8((nodeId >> 8) & 0xFF),
                UInt8(nodeId & 0xFF),
            ]
        )
    }

    // ── FILE_INIT handshake builders/parsers ─────────────────────────────────

    /// Build a FILE_INIT request frame (TE_SYSEX_FILE_INIT = 1).
    ///
    /// Hardware-verified: must be sent once per connection before any file listing.
    /// Device returns "can't list unless initialized" without this handshake.
    ///
    /// Body (command = TE_SYSEX_FILE): [INIT(1), flags, maxResponseLength u32 BE]
    ///
    /// - Parameters:
    ///   - maxResponseLength: Maximum response size the client accepts (try 512).
    ///   - flags: Init flags; default = TE_SYSEX_FILE_INIT_SUBSCRIBE (1).
    static func buildFileInitFrame(
        deviceId: Int,
        requestId: Int,
        maxResponseLength: Int = 512,
        flags: Int = TE_SYSEX_FILE_INIT_SUBSCRIBE
    ) -> [UInt8] {
        let body: [UInt8] = [
            UInt8(TE_SYSEX_FILE_INIT),
            UInt8(flags & 0xFF),
            UInt8((maxResponseLength >> 24) & 0xFF),
            UInt8((maxResponseLength >> 16) & 0xFF),
            UInt8((maxResponseLength >> 8) & 0xFF),
            UInt8(maxResponseLength & 0xFF),
        ]
        return buildFileFrame(deviceId: deviceId, requestId: requestId, body: body)
    }

    /// Parse an already-unpacked FILE_INIT response body into a negotiated chunk size.
    ///
    /// Body layout: body[1..4] = chunkSize u32 BE (reference: SysExFileInitRequest
    /// response in data/index.js, a[1..4]; hardware-observed flags=12, chunkSize=512).
    /// Returns 512 as a safe default if the body is too short to parse.
    static func parseFileInitResponse(_ body: [UInt8]) -> Int {
        if body.count < 5 { return 512 }
        return (Int(body[1]) << 24) |
            (Int(body[2]) << 16) |
            (Int(body[3]) << 8) |
            Int(body[4])
    }

    // ── FILE_INFO response ────────────────────────────────────────────────────

    /// Parsed FILE_INFO (getNode) response.
    ///
    /// - `nodeId`: Numeric ID of the node.
    /// - `parentId`: Numeric ID of the parent directory.
    /// - `flags`: Raw capability + type flags byte.
    /// - `sizeBytes`: File size in bytes (0 for directories).
    /// - `name`: Node name (filename or directory name, e.g. "A", "01", "kick.wav").
    struct NodeInfo: Equatable {
        let nodeId: Int
        let parentId: Int
        let flags: Int
        let sizeBytes: Int64
        let name: String

        /// True if the CAPABILITY_WRITE flag is set — required for the active-group write gate.
        var isWritable: Bool { flags & TE_SYSEX_FILE_CAPABILITY_WRITE != 0 }
        /// True if the FILE_TYPE_FILE flag is NOT set (i.e. this is a directory node).
        var isDir: Bool { flags & TE_SYSEX_FILE_TYPE_FILE == 0 }
    }

    /// Parse an already-unpacked FILE_INFO response body into `NodeInfo`.
    ///
    /// Body layout (SysexFileInfoResponse, data/index.js byte ~833859):
    ///   [0..1] nodeId   u16 BE
    ///   [2..3] parentId u16 BE
    ///   [4]    flags    u8
    ///   [5..8] fileSize u32 BE
    ///   [9..]  fileName null-terminated ASCII
    ///
    /// This is distinct from FILE_LIST per-entry layout (which has no parentId field).
    ///
    /// - Throws: `ProtocolError.invalidArgument` if `body` is too short to contain the fixed header.
    static func parseFileInfo(_ body: [UInt8]) throws -> NodeInfo {
        guard body.count >= 9 else {
            throw ProtocolError.invalidArgument("FILE_INFO response too short: \(body.count) bytes")
        }
        let nodeId   = (Int(body[0]) << 8) | Int(body[1])
        let parentId = (Int(body[2]) << 8) | Int(body[3])
        let flags    = Int(body[4])
        let sizeBytes = (Int64(body[5]) << 24) |
            (Int64(body[6]) << 16) |
            (Int64(body[7]) << 8) |
            Int64(body[8])
        let nameStart = 9
        var nul = nameStart
        while nul < body.count && body[nul] != 0 { nul += 1 }
        let name = asciiString(body[nameStart..<nul])
        return NodeInfo(nodeId: nodeId, parentId: parentId, flags: flags, sizeBytes: sizeBytes, name: name)
    }

    // ── Metadata GET response paging ──────────────────────────────────────────

    /// Parse one page from a nodeId-form METADATA GET response body.
    ///
    /// Body layout: `[page u16 BE][JSON fragment ASCII … NUL?]`
    ///
    /// The JSON fragment is trimmed of a trailing NUL byte (the device NUL-terminates the
    /// last page; intermediate pages have no trailing NUL). Callers accumulate the fragment
    /// strings and call `isMetadataTerminator` to detect the end of the sequence.
    ///
    /// - Returns: Tuple of (page index, JSON fragment string).
    /// - Throws: `ProtocolError.invalidArgument` if `body` is shorter than 2 bytes.
    static func parseMetadataPage(_ body: [UInt8]) throws -> (page: Int, fragment: String) {
        guard body.count >= 2 else {
            throw ProtocolError.invalidArgument("METADATA GET response too short: \(body.count) bytes")
        }
        let page = (Int(body[0]) << 8) | Int(body[1])
        var fragmentBytes = body[2...]
        // Trim trailing NUL — the device appends 0x00 on the final page.
        if fragmentBytes.last == 0 {
            fragmentBytes = fragmentBytes.dropLast()
        }
        return (page, asciiString(fragmentBytes))
    }

    /// True if `body` represents the terminating page of a METADATA GET sequence.
    ///
    /// Two terminator conditions (reference: data/index.js FileHandler.getMetadata loop):
    ///  (a) body.count <= 2 — empty or page-only response, no JSON fragment.
    ///  (b) body.last == 0x00 — last byte is NUL, signalling the final page.
    static func isMetadataTerminator(_ body: [UInt8]) -> Bool {
        body.count <= 2 || body.last == 0
    }
}
