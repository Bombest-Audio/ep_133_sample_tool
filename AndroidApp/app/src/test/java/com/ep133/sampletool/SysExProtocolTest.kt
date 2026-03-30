package com.ep133.sampletool

import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SysExProtocol — TE frame builder, 7-bit codec, and command constants.
 * Wave 0 stubs — production code created in Task 1-01.
 */
class SysExProtocolTest {

    @Ignore("Wave 1 — SysExProtocol not yet implemented")
    @Test
    fun greetsFrameHasCorrectManufacturerId() {
        // val frame = SysExProtocol.buildGreetFrame(deviceId = 0)
        // assertEquals(0x00.toByte(), frame[1])
        // assertEquals(0x20.toByte(), frame[2])
        // assertEquals(0x76.toByte(), frame[3])
    }

    @Ignore("Wave 1 — SysExProtocol not yet implemented")
    @Test
    fun pack7bitRoundtrip_preservesAllBytes() {
        // val input = ByteArray(256) { it.toByte() }
        // val roundtrip = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(input))
        // assertArrayEquals(input, roundtrip)
    }

    @Ignore("Wave 1 — SysExProtocol not yet implemented")
    @Test
    fun greetResponse_parsedFirmwareVersion() {
        // val response = "sw_version:1.3.2;serial:ABC"
        // val payload = SysExProtocol.pack7bit(response.toByteArray(Charsets.US_ASCII))
        // val parsed = SysExProtocol.parseGreetResponse(payload)
        // assertEquals("1.3.2", parsed["sw_version"])
    }

    @Ignore("Wave 1 — SysExProtocol not yet implemented")
    @Test
    fun fileListFrame_commandByteIsCorrect() {
        // val frame = SysExProtocol.buildFileListFrame(deviceId = 0, path = "/sounds", requestId = 1)
        // Verify FILE_LIST command == 4 appears at the right offset
    }

    @Ignore("Wave 1 — SysExProtocol not yet implemented")
    @Test
    fun fileGetFrame_commandByteIsCorrect() {
        // val frame = SysExProtocol.buildFileGetFrame(deviceId = 0, path = "/sounds/001.wav", chunkIndex = 0, requestId = 1)
        // Verify FILE_GET command == 3 appears at the right offset
    }
}
