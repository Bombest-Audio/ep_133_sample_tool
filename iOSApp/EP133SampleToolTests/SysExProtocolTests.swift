import XCTest
@testable import EP133SampleTool

/// Tests for SysExProtocol — TE frame builder, 7-bit codec, and command constants.
/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SysExProtocolTest.kt.
final class SysExProtocolTests: XCTestCase {

    // ── Helper: unpack the inner payload of a SysEx frame ──
    private func unpackPayload(_ frame: [UInt8]) -> [UInt8] {
        SysExProtocol.unpack7bit(Array(frame[9..<(frame.count - 1)]))
    }

    private func asciiBytes(_ s: String) -> [UInt8] {
        Array(s.utf8)
    }

    func testGreetsFrameHasCorrectManufacturerId() {
        let frame = SysExProtocol.buildGreetFrame(deviceId: 0)
        XCTAssertEqual(0x00, frame[1])
        XCTAssertEqual(0x20, frame[2])
        XCTAssertEqual(0x76, frame[3])
    }

    func testPack7bitRoundtrip_preservesAllBytes() {
        // Test 0-127 (all 7-bit values)
        let input127 = (0..<128).map { UInt8($0) }
        let roundtrip127 = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(input127))
        XCTAssertEqual(input127, roundtrip127)

        // Test values 128-255 (high-bit set)
        let input256 = (0..<128).map { UInt8($0 + 128) }
        let roundtrip256 = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(input256))
        XCTAssertEqual(input256, roundtrip256)
    }

    func testGreetResponse_parsedFirmwareVersion() {
        let response = "sw_version:1.3.2;serial:ABC"
        // Hardware-verified: the device prefixes the greet body with a 1-byte status byte
        // before the 7-bit-packed key:value;… payload.
        let payload: [UInt8] = [0x00] + SysExProtocol.pack7bit(asciiBytes(response))
        let parsed = SysExProtocol.parseGreetResponse(payload)
        XCTAssertEqual("1.3.2", parsed["sw_version"])
        XCTAssertEqual("ABC", parsed["serial"])
    }

    func testFileListFrame_commandByteIsCorrect() {
        let frame = SysExProtocol.buildFileListFrame(deviceId: 0, path: "/sounds", requestId: 1)
        // Hardware-verified (2026-06-23): command byte = TE_SYSEX_FILE (5), NOT CMD_PRODUCT_SPECIFIC (127).
        // frame[8] is the command byte.
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F,
                       "command byte must be TE_SYSEX_FILE (5)")
        // Body (unpacked payload) starts at the subcommand — no leading TE_SYSEX_FILE byte.
        let payloadStart = 9
        let packedPayload = Array(frame[payloadStart..<(frame.count - 1)])
        let unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_LIST, Int(unpackedPayload[0]),
                       "body[0] = TE_SYSEX_FILE_LIST (4)")
    }

    func testFileGetFrame_commandByteIsCorrect() {
        let frame = SysExProtocol.buildFileGetFrame(
            deviceId: 0,
            path: "/sounds/001.wav",
            chunkIndex: 0,
            requestId: 1
        )
        // Hardware-verified: command = TE_SYSEX_FILE (5).
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F,
                       "command byte must be TE_SYSEX_FILE (5)")
        let payloadStart = 9
        let packedPayload = Array(frame[payloadStart..<(frame.count - 1)])
        let unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_GET, Int(unpackedPayload[0]),
                       "body[0] = TE_SYSEX_FILE_GET (3)")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildFileCreatePutInitFrame — wire layout tests
    //
    // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
    // Expected unpacked body (no leading TE_SYSEX_FILE byte):
    //   [0]     = 2  (TE_SYSEX_FILE_PUT)
    //   [1]     = 0  (TE_SYSEX_FILE_PUT_TYPE_INIT)
    //   [2]     = flags (default = TE_SYSEX_FILE_CAPABILITY_READ | TE_SYSEX_FILE_TYPE_FILE = 5)
    //   [3-4]   = fileId u16 BE (0 for new file)
    //   [5-6]   = parentId u16 BE
    //   [7-10]  = fileSize u32 BE
    //   [11..]  = filename ASCII + 0x00, then (metadata ASCII + 0x00 if non-nil)
    // ──────────────────────────────────────────────────────────────────────────

    func testBuildFileCreatePutInitFrame_wireLayout() {
        let parentNodeId = 0x1234
        let fileSize = 0xABCD
        let frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: parentNodeId,
            fileSize: fileSize,
            filename: "kick.wav",
            requestId: 30
        )

        // Command byte must be TE_SYSEX_FILE (5).
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")

        let p = unpackPayload(frame)

        // Body starts at subcommand (no leading TE_SYSEX_FILE byte).
        XCTAssertEqual(2, Int(p[0]), "p[0] = TE_SYSEX_FILE_PUT (2)")
        XCTAssertEqual(0, Int(p[1]), "p[1] = TYPE_INIT (0)")

        // Default flags = CAPABILITY_READ | FILE_TYPE_FILE = 5
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_CAPABILITY_READ | SysExProtocol.TE_SYSEX_FILE_TYPE_FILE,
                       Int(p[2]), "p[2] = flags (5)")

        // fileId u16 BE = 0 (new file)
        let fileId = (Int(p[3]) << 8) | Int(p[4])
        XCTAssertEqual(0, fileId, "fileId must be 0 for a new file")

        // parentId u16 BE
        let parsedParent = (Int(p[5]) << 8) | Int(p[6])
        XCTAssertEqual(parentNodeId, parsedParent, "parentId must equal parentNodeId")

        // fileSize u32 BE
        let parsedSize = (Int(p[7]) << 24) |
            (Int(p[8]) << 16) |
            (Int(p[9]) << 8) |
            Int(p[10])
        XCTAssertEqual(fileSize, parsedSize, "fileSize u32 BE must equal fileSize")

        // filename ASCII + NUL
        let nameBytes = asciiBytes("kick.wav")
        let nameInPayload = Array(p[11..<(11 + nameBytes.count)])
        XCTAssertEqual(nameBytes, nameInPayload, "filename bytes in INIT")
        XCTAssertEqual(0, Int(p[11 + nameBytes.count]), "NUL terminator after filename")

        // No metadata: payload ends right after NUL (no extra bytes)
        XCTAssertEqual(11 + nameBytes.count + 1, p.count, "payload size = 11 + filename + 1 (no metadata)")
    }

    func testBuildFileCreatePutInitFrame_truncatesFilenameAt54Chars() {
        let longName = String(repeating: "a", count: 60) + ".wav"  // 64 chars — must be sliced to 54
        let frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: 1,
            fileSize: 100,
            filename: longName,
            requestId: 30
        )
        let p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, parentId u16, fileSize u32] = 11 bytes before name.
        var nulIdx = 11
        while nulIdx < p.count && p[nulIdx] != 0 { nulIdx += 1 }
        let nameLen = nulIdx - 11
        XCTAssertEqual(54, nameLen, "filename must be truncated to 54 chars")
    }

    func testBuildFileCreatePutInitFrame_appendsMetadataWhenNonNull() {
        let meta = #"{"tuning":0}"#
        let frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: 1,
            fileSize: 100,
            filename: "snare.wav",
            requestId: 30,
            metadataJson: meta
        )
        let p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, parentId u16, fileSize u32] = 11 bytes before name.
        let nameBytes = asciiBytes("snare.wav")
        let nameNulOffset = 11 + nameBytes.count  // NUL after name
        XCTAssertEqual(0, Int(p[nameNulOffset]), "NUL after filename")
        // metadata starts right after the NUL
        let metaStart = nameNulOffset + 1
        let metaBytes = asciiBytes(meta)
        let metaInPayload = Array(p[metaStart..<(metaStart + metaBytes.count)])
        XCTAssertEqual(metaBytes, metaInPayload, "metadata bytes in INIT")
        XCTAssertEqual(0, Int(p[metaStart + metaBytes.count]), "NUL after metadata")
    }

    func testBuildFileCreatePutInitFrame_customFileIdAndFlags() {
        let frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: 5,
            fileSize: 200,
            filename: "hi.wav",
            requestId: 30,
            flags: 0x07,
            fileId: 0x00AB
        )
        let p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, ...]; flags = p[2], fileId = p[3..4].
        XCTAssertEqual(0x07, Int(p[2]), "flags override")
        let fileId = (Int(p[3]) << 8) | Int(p[4])
        XCTAssertEqual(0x00AB, fileId, "fileId override")
    }

    // ── buildFileInitFrame + buildFileListByNodeFrame ────────────────────────

    func testBuildFileInitFrame_commandByteAndBodyLayout() {
        // Hardware-verified: FILE_INIT (subcmd=1) must be sent before listing.
        // Body: [INIT(1), flags, maxResponseLength u32 BE]
        let frame = SysExProtocol.buildFileInitFrame(
            deviceId: 0,
            requestId: 83,
            maxResponseLength: 512,
            flags: SysExProtocol.TE_SYSEX_FILE_INIT_SUBSCRIBE
        )
        // Command byte must be TE_SYSEX_FILE (5).
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")
        let p = unpackPayload(frame)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_INIT, Int(p[0]), "p[0] = TE_SYSEX_FILE_INIT (1)")
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_INIT_SUBSCRIBE, Int(p[1]), "p[1] = flags (SUBSCRIBE=1)")
        let maxResp = (Int(p[2]) << 24) |
            (Int(p[3]) << 16) |
            (Int(p[4]) << 8) |
            Int(p[5])
        XCTAssertEqual(512, maxResp, "maxResponseLength u32 BE = 512")
        XCTAssertEqual(6, p.count, "body size = 6 (INIT + flags + u32)")
    }

    func testBuildFileListByNodeFrame_commandByteAndBodyLayout() {
        // Hardware-verified: FILE_LIST by node, command=5, body=[LIST(4), page u16, nodeId u16].
        let nodeId = 0x00AB
        let page   = 0x0000
        let frame = SysExProtocol.buildFileListByNodeFrame(
            deviceId: 0, nodeId: nodeId, page: page, requestId: 50
        )
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F, "frame[8] = TE_SYSEX_FILE (5)")
        let p = unpackPayload(frame)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_LIST, Int(p[0]), "p[0] = TE_SYSEX_FILE_LIST (4)")
        let parsedPage = (Int(p[1]) << 8) | Int(p[2])
        XCTAssertEqual(page, parsedPage, "page u16 BE")
        let parsedNode = (Int(p[3]) << 8) | Int(p[4])
        XCTAssertEqual(nodeId, parsedNode, "nodeId u16 BE")
        XCTAssertEqual(5, p.count, "body size = 5 (LIST + page u16 + nodeId u16)")
    }
}
