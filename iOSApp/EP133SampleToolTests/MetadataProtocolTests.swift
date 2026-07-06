import XCTest
@testable import EP133SampleTool

/// Unit tests for the Step-1 active-group protocol additions:
///   - parseFileInfo byte layout
///   - parseMetadataPage / isMetadataTerminator
///   - buildMetadataGetFrame / buildMetadataSetFrame / buildFileInfoFrame wire layouts
///
/// These are pure parser / frame-builder tests — no MIDI I/O.
/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/MetadataProtocolTest.kt.
final class MetadataProtocolTests: XCTestCase {

    // ── Helper: unpack the inner payload of a SysEx frame ───────────────────
    private func unpackPayload(_ frame: [UInt8]) -> [UInt8] {
        SysExProtocol.unpack7bit(Array(frame[9..<(frame.count - 1)]))
    }

    private func asciiBytes(_ s: String) -> [UInt8] {
        Array(s.utf8)
    }

    // ── parseFileInfo ────────────────────────────────────────────────────────

    func testParseFileInfo_correctFieldOffsets() throws {
        // Craft a FILE_INFO response body (before 7-bit packing):
        //   [0..1] nodeId   = 0x1234
        //   [2..3] parentId = 0x0005
        //   [4]    flags    = 0x0E  (WRITE=8 | DIR=2 | READ=4)
        //   [5..8] size     = 0x00001000 (4096)
        //   [9..]  name     = "A\0"
        let body: [UInt8] = [
            0x12, 0x34,        // nodeId
            0x00, 0x05,        // parentId
            0x0E,              // flags: READ(4) | WRITE(8) | DIR(2)
            0x00, 0x00, 0x10, 0x00,  // size = 4096
            UInt8(ascii: "A"), 0x00, // name = "A"
        ]
        let info = try SysExProtocol.parseFileInfo(body)

        XCTAssertEqual(0x1234, info.nodeId, "nodeId")
        XCTAssertEqual(0x0005, info.parentId, "parentId")
        XCTAssertEqual(0x0E, info.flags, "flags")
        XCTAssertEqual(4096, info.sizeBytes, "sizeBytes")
        XCTAssertEqual("A", info.name, "name")
    }

    func testParseFileInfo_isWritable_whenFlagSet() throws {
        let body: [UInt8] = [
            0x00, 0x01,  // nodeId
            0x00, 0x00,  // parentId
            0x08,        // flags = CAPABILITY_WRITE only
            0x00, 0x00, 0x00, 0x00, // size = 0
            UInt8(ascii: "B"), 0x00,
        ]
        let info = try SysExProtocol.parseFileInfo(body)
        XCTAssertTrue(info.isWritable, "isWritable should be true when WRITE flag set")
    }

    func testParseFileInfo_isDir_whenFileTypeFlagClear() throws {
        let body: [UInt8] = [
            0x00, 0x02,   // nodeId
            0x00, 0x00,   // parentId
            0x02,         // flags = FILE_TYPE_DIR (2) only — FILE bit (1) clear
            0x00, 0x00, 0x00, 0x00,
            UInt8(ascii: "C"), 0x00,
        ]
        let info = try SysExProtocol.parseFileInfo(body)
        XCTAssertTrue(info.isDir, "isDir should be true when FILE_TYPE_FILE bit is clear")
    }

    func testParseFileInfo_throwsWhenBodyTooShort() {
        XCTAssertThrowsError(try SysExProtocol.parseFileInfo([UInt8](repeating: 0, count: 5))) { error in
            // needs >= 9 bytes — Kotlin IllegalArgumentException
            guard case SysExProtocol.ProtocolError.invalidArgument = error else {
                return XCTFail("expected invalidArgument, got \(error)")
            }
        }
    }

    func testParseFileInfo_nameWithoutNulTerminator_usesRestOfBuffer() throws {
        // A name that is not NUL-terminated — should take bytes to end of buffer.
        let body: [UInt8] = [
            0x00, 0x03,
            0x00, 0x00,
            0x00,
            0x00, 0x00, 0x00, 0x00,
            UInt8(ascii: "D"), // no NUL
        ]
        let info = try SysExProtocol.parseFileInfo(body)
        XCTAssertEqual("D", info.name, "name without NUL terminator")
    }

    // ── parseMetadataPage ────────────────────────────────────────────────────

    func testParseMetadataPage_extractsPageAndFragment() throws {
        // Page 0, JSON fragment `{"active":` (without trailing NUL — intermediate page)
        let fragment = #"{"active":"#
        let body: [UInt8] = [
            0x00, 0x00,  // page 0 u16 BE
        ] + asciiBytes(fragment)
        let (page, text) = try SysExProtocol.parseMetadataPage(body)
        XCTAssertEqual(0, page, "page")
        XCTAssertEqual(fragment, text, "fragment")
    }

    func testParseMetadataPage_trailingNulIsStripped() throws {
        // Final page: `1234}` followed by NUL
        let fragment = "1234}"
        let body: [UInt8] = [
            0x00, 0x01,  // page 1
        ] + asciiBytes(fragment) + [0x00]
        let (page, text) = try SysExProtocol.parseMetadataPage(body)
        XCTAssertEqual(1, page, "page")
        XCTAssertEqual(fragment, text, "trailing NUL stripped")
    }

    func testParseMetadataPage_throwsWhenBodyTooShort() {
        XCTAssertThrowsError(try SysExProtocol.parseMetadataPage([UInt8](repeating: 0, count: 1))) { error in
            // needs >= 2 bytes — Kotlin IllegalArgumentException
            guard case SysExProtocol.ProtocolError.invalidArgument = error else {
                return XCTFail("expected invalidArgument, got \(error)")
            }
        }
    }

    // ── isMetadataTerminator ─────────────────────────────────────────────────

    func testIsMetadataTerminator_trueWhenBodySizeLeq2() {
        XCTAssertTrue(SysExProtocol.isMetadataTerminator([]), "empty body")
        XCTAssertTrue(SysExProtocol.isMetadataTerminator([0x00, 0x00]), "1-byte body")
    }

    func testIsMetadataTerminator_trueWhenLastByteIsNul() {
        let body: [UInt8] = [0x00, 0x00, UInt8(ascii: "A"), 0x00]
        XCTAssertTrue(SysExProtocol.isMetadataTerminator(body), "last byte NUL")
    }

    func testIsMetadataTerminator_falseForNonTerminatingPage() {
        let body: [UInt8] = [0x00, 0x00] + asciiBytes(#"{"active":"#)
        XCTAssertFalse(SysExProtocol.isMetadataTerminator(body), "intermediate page should not terminate")
    }

    // ── buildMetadataGetFrame wire layout ────────────────────────────────────

    func testBuildMetadataGetFrame_wireLayout_noKey() {
        // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
        let nodeId = 0x00AB
        let page   = 0x0002
        let frame = SysExProtocol.buildMetadataGetFrame(
            deviceId: 0, nodeId: nodeId, page: page, key: nil, requestId: 80
        )
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")
        let p = unpackPayload(frame)

        // Body starts at subcommand (no leading TE_SYSEX_FILE byte):
        // [0] = METADATA (7), [1] = GET (2), [2..3] nodeId u16 BE, [4..5] page u16 BE
        XCTAssertEqual(7, Int(p[0]), "p[0] = METADATA (7)")
        XCTAssertEqual(2, Int(p[1]), "p[1] = GET (2)")
        let parsedNode = (Int(p[2]) << 8) | Int(p[3])
        XCTAssertEqual(nodeId, parsedNode, "nodeId u16 BE")
        let parsedPage = (Int(p[4]) << 8) | Int(p[5])
        XCTAssertEqual(page, parsedPage, "page u16 BE")
        // No key → body ends at p[5]
        XCTAssertEqual(6, p.count, "no-key body size = 6")
    }

    func testBuildMetadataGetFrame_wireLayout_withKey() {
        let key = "active"
        let frame = SysExProtocol.buildMetadataGetFrame(
            deviceId: 0, nodeId: 1, page: 0, key: key, requestId: 80
        )
        let p = unpackPayload(frame)
        // Body: [METADATA(7), GET(2), nodeId u16, page u16] = 6 bytes; key starts at p[6].
        let keyBytes = asciiBytes(key)
        let keyInPayload = Array(p[6..<(6 + keyBytes.count)])
        XCTAssertEqual(keyBytes, keyInPayload, "key ASCII bytes")
        XCTAssertEqual(0, Int(p[6 + keyBytes.count]), "NUL after key")
        XCTAssertEqual(6 + keyBytes.count + 1, p.count, "body size with key")
    }

    // ── buildMetadataSetFrame wire layout ────────────────────────────────────

    func testBuildMetadataSetFrame_wireLayout() {
        // Body: [METADATA(7), SET(1), nodeId u16, json ASCII, NUL]
        let nodeId = 0x0055
        let json   = #"{"active":99}"#
        let frame = SysExProtocol.buildMetadataSetFrame(
            deviceId: 0, nodeId: nodeId, json: json, requestId: 81
        )
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")
        let p = unpackPayload(frame)

        XCTAssertEqual(7, Int(p[0]), "p[0] = METADATA (7)")
        XCTAssertEqual(1, Int(p[1]), "p[1] = SET (1)")
        let parsedNode = (Int(p[2]) << 8) | Int(p[3])
        XCTAssertEqual(nodeId, parsedNode, "nodeId u16 BE")
        let jsonBytes = asciiBytes(json)
        let jsonInPayload = Array(p[4..<(4 + jsonBytes.count)])
        XCTAssertEqual(jsonBytes, jsonInPayload, "json ASCII bytes")
        XCTAssertEqual(0, Int(p[4 + jsonBytes.count]), "NUL after json")
        XCTAssertEqual(4 + jsonBytes.count + 1, p.count, "body size")
    }

    // ── buildFileInfoFrame wire layout ───────────────────────────────────────

    func testBuildFileInfoFrame_wireLayout() {
        // Body: [FILE_INFO(11), nodeId u16]
        let nodeId = 0x1ABC
        let frame = SysExProtocol.buildFileInfoFrame(deviceId: 0, nodeId: nodeId, requestId: 82)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")
        let p = unpackPayload(frame)

        XCTAssertEqual(11, Int(p[0]), "p[0] = FILE_INFO (11)")
        let parsedNode = (Int(p[1]) << 8) | Int(p[2])
        XCTAssertEqual(nodeId, parsedNode, "nodeId u16 BE")
        XCTAssertEqual(3, p.count, "body size = 3 (FILE_INFO + nodeId u16)")
    }

    // ── Constant values ──────────────────────────────────────────────────────

    func testConstants_matchReferenceValues() {
        XCTAssertEqual(5, SysExProtocol.TE_SYSEX_FILE, "TE_SYSEX_FILE = 5")
        XCTAssertEqual(7, SysExProtocol.TE_SYSEX_FILE_METADATA, "TE_SYSEX_FILE_METADATA = 7")
        XCTAssertEqual(2, SysExProtocol.TE_SYSEX_FILE_METADATA_GET, "METADATA_GET = 2")
        XCTAssertEqual(1, SysExProtocol.TE_SYSEX_FILE_METADATA_SET, "METADATA_SET = 1")
        XCTAssertEqual(11, SysExProtocol.TE_SYSEX_FILE_INFO, "TE_SYSEX_FILE_INFO = 11")
        XCTAssertEqual(8, SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE, "CAPABILITY_WRITE = 8")
    }

    // ── JSON span extraction (hardware-verified body shape) ──────────────────
    // Hardware-verified (2026-06-24): METADATA GET response body is
    //   `00 00 7B 22 61 63 74 69 76 65 22 3A 33 30 30 30 7D 00`
    // = 2-byte page prefix + `{"active":3000}` + trailing NUL.
    // getMetadataJson extracts the outermost `{...}` span before JSON parsing.

    /// Mirror of the span-extraction logic in the Android MIDIRepository.getMetadataJson.
    private func extractJsonSpan(_ accumulated: String) -> String {
        guard let s = accumulated.firstIndex(of: "{"),
              let e = accumulated.lastIndex(of: "}"),
              e > s
        else { return accumulated }
        return String(accumulated[s...e])
    }

    func testJsonSpan_stripsPagePrefixAndTrailingNul() {
        // Simulated accumulated string after parseMetadataPage strips page u16 and NUL:
        // page u16 = 0x0000 → two NUL chars prepended; trailing NUL from device.
        // Use char code 0 for NUL (parseMetadataPage trims trailing NUL but page u16
        // bytes may appear as prefix chars in the accumulated buffer if not stripped there).
        let prefix = "  "   // 2-byte page prefix that survives into accumulated
        let json   = #"{"active":3000}"#
        let trailingNul = " "
        let accumulated = prefix + json + trailingNul

        let span = extractJsonSpan(accumulated)
        XCTAssertEqual(json, span, "should extract exact JSON object")
    }

    func testJsonSpan_cleanJsonPassesThrough() {
        // When the accumulated string is already valid JSON (no prefix garbage),
        // the extracted span must equal the input unchanged.
        let json = #"{"active":42}"#
        XCTAssertEqual(json, extractJsonSpan(json), "clean JSON passes through unchanged")
    }

    func testJsonSpan_nestedObjectsRespectLastBrace() {
        // Nested JSON: the span must capture from the first '{' to the LAST '}'.
        let json = #"{"a":{"b":1}}"#
        XCTAssertEqual(json, extractJsonSpan(json), "nested JSON span correct")
    }

    func testJsonSpan_noJsonBraces_returnsAccumulated() {
        // If there are no '{' / '}' at all, the fallback returns the whole accumulated string
        // (same as the original behavior — greet fallback then handles it).
        let noJson = "key:value;other:stuff"
        XCTAssertEqual(noJson, extractJsonSpan(noJson), "no braces → fallback returns input")
    }
}
