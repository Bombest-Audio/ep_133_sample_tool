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
        val payload = SysExProtocol.pack7bit(response.toByteArray(Charsets.US_ASCII))
        val parsed = SysExProtocol.parseGreetResponse(payload)
        assertEquals("1.3.2", parsed["sw_version"])
        assertEquals("ABC", parsed["serial"])
    }

    @Test
    fun fileListFrame_commandByteIsCorrect() {
        val frame = SysExProtocol.buildFileListFrame(deviceId = 0, path = "/sounds", requestId = 1)
        // frame[0] = 0xF0, frame[1..3] = TE_ID, frame[4] = deviceId, frame[5] = 0x40
        // frame[6] = flags, frame[7] = requestId, frame[8] = CMD_PRODUCT_SPECIFIC
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        // Payload is 7-bit packed: first two bytes of unpacked payload are TE_SYSEX_FILE, TE_SYSEX_FILE_LIST
        val payloadStart = 9
        val packedPayload = frame.copyOfRange(payloadStart, frame.size - 1)
        val unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, unpackedPayload[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_LIST, unpackedPayload[1].toInt() and 0xFF)
    }

    @Test
    fun fileGetFrame_commandByteIsCorrect() {
        val frame = SysExProtocol.buildFileGetFrame(
            deviceId = 0,
            path = "/sounds/001.wav",
            chunkIndex = 0,
            requestId = 1,
        )
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        val payloadStart = 9
        val packedPayload = frame.copyOfRange(payloadStart, frame.size - 1)
        val unpackedPayload = SysExProtocol.unpack7bit(packedPayload)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, unpackedPayload[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, unpackedPayload[1].toInt() and 0xFF)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildFileCreatePutInitFrame — wire layout tests
    //
    // Expected unpacked payload (with leading TE_SYSEX_FILE = 5 prepended by buildFrame):
    //   [0]     = 5  (TE_SYSEX_FILE)
    //   [1]     = 2  (TE_SYSEX_FILE_PUT)
    //   [2]     = 0  (TE_SYSEX_FILE_PUT_TYPE_INIT)
    //   [3]     = flags (default = TE_SYSEX_FILE_CAPABILITY_READ | TE_SYSEX_FILE_TYPE_FILE = 5)
    //   [4-5]   = fileId u16 BE (0 for new file)
    //   [6-7]   = parentId u16 BE
    //   [8-11]  = fileSize u32 BE
    //   [12..]  = filename ASCII + 0x00, then (metadata ASCII + 0x00 if non-null)
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

        val p = unpackPayload(frame)

        // Subsystem + opcode + subtype
        assertEquals("p[0] = TE_SYSEX_FILE (5)",  5, p[0].toInt() and 0xFF)
        assertEquals("p[1] = TE_SYSEX_FILE_PUT (2)", 2, p[1].toInt() and 0xFF)
        assertEquals("p[2] = TYPE_INIT (0)", 0, p[2].toInt() and 0xFF)

        // Default flags = CAPABILITY_READ | FILE_TYPE_FILE = 5
        assertEquals("p[3] = flags (5)", SysExProtocol.TE_SYSEX_FILE_CAPABILITY_READ or SysExProtocol.TE_SYSEX_FILE_TYPE_FILE, p[3].toInt() and 0xFF)

        // fileId u16 BE = 0 (new file)
        val fileId = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        assertEquals("fileId must be 0 for a new file", 0, fileId)

        // parentId u16 BE
        val parsedParent = ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
        assertEquals("parentId must equal parentNodeId", parentNodeId, parsedParent)

        // fileSize u32 BE
        val parsedSize = ((p[8].toInt() and 0xFF) shl 24) or
            ((p[9].toInt() and 0xFF) shl 16) or
            ((p[10].toInt() and 0xFF) shl 8) or
            (p[11].toInt() and 0xFF)
        assertEquals("fileSize u32 BE must equal fileSize", fileSize, parsedSize)

        // filename ASCII + NUL
        val nameBytes = "kick.wav".toByteArray(Charsets.US_ASCII)
        val nameInPayload = p.copyOfRange(12, 12 + nameBytes.size)
        assertArrayEquals("filename bytes in INIT", nameBytes, nameInPayload)
        assertEquals("NUL terminator after filename", 0, p[12 + nameBytes.size].toInt() and 0xFF)

        // No metadata: payload ends right after NUL (no extra bytes)
        assertEquals("payload size = 12 + filename + 1 (no metadata)", 12 + nameBytes.size + 1, p.size)
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
        // filename starts at p[12]; find NUL terminator
        var nulIdx = 12
        while (nulIdx < p.size && p[nulIdx].toInt() != 0) nulIdx++
        val nameLen = nulIdx - 12
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
        // filename "snare.wav" starts at p[12]
        val nameBytes = "snare.wav".toByteArray(Charsets.US_ASCII)
        val nameNulOffset = 12 + nameBytes.size  // p[12+9] = NUL after name
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
        assertEquals("flags override", 0x07, p[3].toInt() and 0xFF)
        val fileId = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        assertEquals("fileId override", 0x00AB, fileId)
    }
}

