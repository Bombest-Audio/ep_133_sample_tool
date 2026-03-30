package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SysExProtocol — TE frame builder, 7-bit codec, and command constants.
 */
class SysExProtocolTest {

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
}
