package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SysExProtocol — TE frame builder, 7-bit codec, and command constants.
 */
class SysExProtocolTest {

    // ── Helper: unpack the inner payload of a SysEx frame ──
    private fun unpackPayload(frame: ByteArray): ByteArray =
        SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

    @Test
    fun greetsFrameHasCorrectManufacturerId() {
        val frame = SysExProtocol.buildGreetFrame(deviceId = 0)
        assertEquals(0x00.toByte(), frame[1])
        assertEquals(0x20.toByte(), frame[2])
        assertEquals(0x76.toByte(), frame[3])
    }

    @Test
    fun pack7bitRoundtrip_preservesAllBytes() {
        // Test 0-127 (all 7-bit values)
        val input127 = ByteArray(128) { it.toByte() }
        val roundtrip127 = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(input127))
        assertArrayEquals(input127, roundtrip127)

        // Test values 128-255 (high-bit set)
        val input256 = ByteArray(128) { (it + 128).toByte() }
        val roundtrip256 = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(input256))
        assertArrayEquals(input256, roundtrip256)
    }

    @Test
    fun greetResponse_parsedFirmwareVersion() {
        val response = "sw_version:1.3.2;serial:ABC"
        // Hardware-verified: the device prefixes the greet body with a 1-byte status byte
        // before the 7-bit-packed key:value;… payload.
        val payload = byteArrayOf(0x00) + SysExProtocol.pack7bit(response.toByteArray(Charsets.US_ASCII))
        val parsed = SysExProtocol.parseGreetResponse(payload)
        assertEquals("1.3.2", parsed["sw_version"])
        assertEquals("ABC", parsed["serial"])
    }

    @Test
    fun fileListFrame_commandByteIsCorrect() {
        val frame = SysExProtocol.buildFileListFrame(deviceId = 0, path = "/sounds", requestId = 1)
        // Hardware-verified (2026-06-23): command byte = TE_SYSEX_FILE (5), NOT CMD_PRODUCT_SPECIFIC (127).
        // frame[8] is the command byte.
        assertEquals("command byte must be TE_SYSEX_FILE (5)",
            SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        // Body (unpacked payload) starts at the subcommand — no leading TE_SYSEX_FILE byte.
        val payloadStart = 9
        val packedPayload = frame.copyOfRange(payloadStart, frame.size - 1)
        val unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        assertEquals("body[0] = TE_SYSEX_FILE_LIST (4)", SysExProtocol.TE_SYSEX_FILE_LIST, unpackedPayload[0].toInt() and 0xFF)
    }

    @Test
    fun fileGetFrame_commandByteIsCorrect() {
        val frame = SysExProtocol.buildFileGetFrame(
            deviceId = 0,
            path = "/sounds/001.wav",
            chunkIndex = 0,
            requestId = 1,
        )
        // Hardware-verified: command = TE_SYSEX_FILE (5).
        assertEquals("command byte must be TE_SYSEX_FILE (5)",
            SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val payloadStart = 9
        val packedPayload = frame.copyOfRange(payloadStart, frame.size - 1)
        val unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        assertEquals("body[0] = TE_SYSEX_FILE_GET (3)", SysExProtocol.TE_SYSEX_FILE_GET, unpackedPayload[0].toInt() and 0xFF)
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
    //   [11..]  = filename ASCII + 0x00, then (metadata ASCII + 0x00 if non-null)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun buildFileCreatePutInitFrame_wireLayout() {
        val parentNodeId = 0x1234
        val fileSize = 0xABCD
        val frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = parentNodeId,
            fileSize = fileSize,
            filename = "kick.wav",
            requestId = 30,
        )

        // Command byte must be TE_SYSEX_FILE (5).
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)

        val p = unpackPayload(frame)

        // Body starts at subcommand (no leading TE_SYSEX_FILE byte).
        assertEquals("p[0] = TE_SYSEX_FILE_PUT (2)", 2, p[0].toInt() and 0xFF)
        assertEquals("p[1] = TYPE_INIT (0)", 0, p[1].toInt() and 0xFF)

        // Default flags = CAPABILITY_READ | FILE_TYPE_FILE = 5
        assertEquals("p[2] = flags (5)", SysExProtocol.TE_SYSEX_FILE_CAPABILITY_READ or SysExProtocol.TE_SYSEX_FILE_TYPE_FILE, p[2].toInt() and 0xFF)

        // fileId u16 BE = 0 (new file)
        val fileId = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
        assertEquals("fileId must be 0 for a new file", 0, fileId)

        // parentId u16 BE
        val parsedParent = ((p[5].toInt() and 0xFF) shl 8) or (p[6].toInt() and 0xFF)
        assertEquals("parentId must equal parentNodeId", parentNodeId, parsedParent)

        // fileSize u32 BE
        val parsedSize = ((p[7].toInt() and 0xFF) shl 24) or
            ((p[8].toInt() and 0xFF) shl 16) or
            ((p[9].toInt() and 0xFF) shl 8) or
            (p[10].toInt() and 0xFF)
        assertEquals("fileSize u32 BE must equal fileSize", fileSize, parsedSize)

        // filename ASCII + NUL
        val nameBytes = "kick.wav".toByteArray(Charsets.US_ASCII)
        val nameInPayload = p.copyOfRange(11, 11 + nameBytes.size)
        assertArrayEquals("filename bytes in INIT", nameBytes, nameInPayload)
        assertEquals("NUL terminator after filename", 0, p[11 + nameBytes.size].toInt() and 0xFF)

        // No metadata: payload ends right after NUL (no extra bytes)
        assertEquals("payload size = 11 + filename + 1 (no metadata)", 11 + nameBytes.size + 1, p.size)
    }

    @Test
    fun buildFileCreatePutInitFrame_truncatesFilenameAt54Chars() {
        val longName = "a".repeat(60) + ".wav"  // 64 chars — must be sliced to 54
        val frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = 1,
            fileSize = 100,
            filename = longName,
            requestId = 30,
        )
        val p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, parentId u16, fileSize u32] = 11 bytes before name.
        var nulIdx = 11
        while (nulIdx < p.size && p[nulIdx].toInt() != 0) nulIdx++
        val nameLen = nulIdx - 11
        assertEquals("filename must be truncated to 54 chars", 54, nameLen)
    }

    @Test
    fun buildFileCreatePutInitFrame_appendsMetadataWhenNonNull() {
        val meta = """{"tuning":0}"""
        val frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = 1,
            fileSize = 100,
            filename = "snare.wav",
            requestId = 30,
            metadataJson = meta,
        )
        val p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, parentId u16, fileSize u32] = 11 bytes before name.
        val nameBytes = "snare.wav".toByteArray(Charsets.US_ASCII)
        val nameNulOffset = 11 + nameBytes.size  // NUL after name
        assertEquals("NUL after filename", 0, p[nameNulOffset].toInt() and 0xFF)
        // metadata starts right after the NUL
        val metaStart = nameNulOffset + 1
        val metaBytes = meta.toByteArray(Charsets.US_ASCII)
        val metaInPayload = p.copyOfRange(metaStart, metaStart + metaBytes.size)
        assertArrayEquals("metadata bytes in INIT", metaBytes, metaInPayload)
        assertEquals("NUL after metadata", 0, p[metaStart + metaBytes.size].toInt() and 0xFF)
    }

    @Test
    fun buildFileCreatePutInitFrame_customFileIdAndFlags() {
        val frame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = 5,
            fileSize = 200,
            filename = "hi.wav",
            requestId = 30,
            flags = 0x07,
            fileId = 0x00AB,
        )
        val p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), flags, fileId u16, ...]; flags = p[2], fileId = p[3..4].
        assertEquals("flags override", 0x07, p[2].toInt() and 0xFF)
        val fileId = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
        assertEquals("fileId override", 0x00AB, fileId)
    }

    // ── New tests: buildFileInitFrame + reframed buildFileListByNodeFrame ───────

    @Test
    fun buildFileInitFrame_commandByteAndBodyLayout() {
        // Hardware-verified: FILE_INIT (subcmd=1) must be sent before listing.
        // Body: [INIT(1), flags, maxResponseLength u32 BE]
        val frame = SysExProtocol.buildFileInitFrame(
            deviceId = 0,
            requestId = 83,
            maxResponseLength = 512,
            flags = SysExProtocol.TE_SYSEX_FILE_INIT_SUBSCRIBE,
        )
        // Command byte must be TE_SYSEX_FILE (5).
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals("p[0] = TE_SYSEX_FILE_INIT (1)", SysExProtocol.TE_SYSEX_FILE_INIT, p[0].toInt() and 0xFF)
        assertEquals("p[1] = flags (SUBSCRIBE=1)", SysExProtocol.TE_SYSEX_FILE_INIT_SUBSCRIBE, p[1].toInt() and 0xFF)
        val maxResp = ((p[2].toInt() and 0xFF) shl 24) or
            ((p[3].toInt() and 0xFF) shl 16) or
            ((p[4].toInt() and 0xFF) shl 8) or
            (p[5].toInt() and 0xFF)
        assertEquals("maxResponseLength u32 BE = 512", 512, maxResp)
        assertEquals("body size = 6 (INIT + flags + u32)", 6, p.size)
    }

    @Test
    fun buildFileListByNodeFrame_commandByteAndBodyLayout() {
        // Hardware-verified: FILE_LIST by node, command=5, body=[LIST(4), page u16, nodeId u16].
        val nodeId = 0x00AB
        val page   = 0x0000
        val frame = SysExProtocol.buildFileListByNodeFrame(
            deviceId = 0, nodeId = nodeId, page = page, requestId = 50,
        )
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals("p[0] = TE_SYSEX_FILE_LIST (4)", SysExProtocol.TE_SYSEX_FILE_LIST, p[0].toInt() and 0xFF)
        val parsedPage = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        assertEquals("page u16 BE", page, parsedPage)
        val parsedNode = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        assertEquals("body size = 5 (LIST + page u16 + nodeId u16)", 5, p.size)
    }
}

