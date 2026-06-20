package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Test
import org.junit.Assert.*

/**
 * Frame-byte and response-parse coverage for the EP-133 paged project file protocol.
 *
 * Asserts the real two-phase INIT/DATA frame layouts (nodeId uint16 BE, offset uint32 BE,
 * page uint16 BE) added in Wave 1 — the replacement for Phase 2's broken single-byte
 * chunkIndex model, which is left untouched for /sounds callers.
 *
 * Reference: data/index.js SysExGetFileInitRequest / SysExGetFileDataRequest /
 * SysexGetFileInitResponse / SysExGetFileDataResponse (RESEARCH "EP-133 Project Protocol").
 */
class ProjectProtocolTest {

    /** Unpack the inner payload of a built frame: frame[9 .. size-2] is the packed payload. */
    private fun unpackPayload(frame: ByteArray): ByteArray =
        SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

    @Test
    fun getInitFrame_carriesSubcommandTypeNodeIdAndOffset() {
        val frame = SysExProtocol.buildFileGetInitFrame(
            deviceId = 0, nodeId = 0x1234, offset = 0x00ABCDEF, requestId = 1,
        )
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, p[1].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET_TYPE_INIT, p[2].toInt() and 0xFF)
        // nodeId uint16 BE
        assertEquals(0x12, p[3].toInt() and 0xFF)
        assertEquals(0x34, p[4].toInt() and 0xFF)
        // offset uint32 BE
        assertEquals(0x00, p[5].toInt() and 0xFF)
        assertEquals(0xAB, p[6].toInt() and 0xFF)
        assertEquals(0xCD, p[7].toInt() and 0xFF)
        assertEquals(0xEF, p[8].toInt() and 0xFF)
    }

    @Test
    fun getInitFrame_defaultsOffsetToZero() {
        val frame = SysExProtocol.buildFileGetInitFrame(deviceId = 0, nodeId = 7, requestId = 1)
        val p = unpackPayload(frame)
        assertEquals(0, p[5].toInt() and 0xFF)
        assertEquals(0, p[6].toInt() and 0xFF)
        assertEquals(0, p[7].toInt() and 0xFF)
        assertEquals(0, p[8].toInt() and 0xFF)
    }

    @Test
    fun getDataFrame_carriesSubcommandTypeAndPage() {
        val frame = SysExProtocol.buildFileGetDataFrame(deviceId = 0, page = 0xBEEF, requestId = 2)
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, p[1].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET_TYPE_DATA, p[2].toInt() and 0xFF)
        assertEquals(0xBE, p[3].toInt() and 0xFF)
        assertEquals(0xEF, p[4].toInt() and 0xFF)
    }

    @Test
    fun putInitFrame_carriesSubcommandTypeNodeIdAndFileSize() {
        val frame = SysExProtocol.buildFilePutInitFrame(
            deviceId = 0, nodeId = 0x0A0B, fileSize = 0x12345678, requestId = 3,
        )
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT, p[1].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, p[2].toInt() and 0xFF)
        assertEquals(0x0A, p[3].toInt() and 0xFF)
        assertEquals(0x0B, p[4].toInt() and 0xFF)
        assertEquals(0x12, p[5].toInt() and 0xFF)
        assertEquals(0x34, p[6].toInt() and 0xFF)
        assertEquals(0x56, p[7].toInt() and 0xFF)
        assertEquals(0x78, p[8].toInt() and 0xFF)
    }

    @Test
    fun putDataFrame_carriesPageAndArchiveChunk() {
        val chunk = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x80.toByte(), 0x42)
        val frame = SysExProtocol.buildFilePutDataFrame(
            deviceId = 0, page = 0x0102, chunk = chunk, requestId = 4,
        )
        assertEquals(SysExProtocol.CMD_PRODUCT_SPECIFIC, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        assertEquals(SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT, p[1].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, p[2].toInt() and 0xFF)
        assertEquals(0x01, p[3].toInt() and 0xFF)
        assertEquals(0x02, p[4].toInt() and 0xFF)
        // Archive chunk bytes survive 7-bit pack/unpack unchanged.
        assertArrayEquals(chunk, p.copyOfRange(5, p.size))
    }

    @Test
    fun parseGetInitResponse_decodesFileSizeBeAndNullTerminatedName() {
        // fileId=0x0102, flags=0x04, fileSize=0x000186A0 (100000), name="P00.tar"
        val name = "P00.tar".toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(
            0x01, 0x02,                                     // fileId
            0x04,                                           // flags
            0x00, 0x01, 0x86.toByte(), 0xA0.toByte(),       // fileSize uint32 BE = 100000
        ) + name + byteArrayOf(0x00, 0x77)                  // null terminator + trailing junk
        val parsed = SysExProtocol.parseGetInitResponse(body)
        assertEquals(0x0102, parsed.fileId)
        assertEquals(0x04, parsed.flags)
        assertEquals(100000, parsed.fileSize)
        assertEquals("P00.tar", parsed.fileName)
    }

    @Test
    fun parseGetDataResponse_decodesPageDataAndNextPage() {
        val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x11, 0x22, 0x33)
        val parsed = SysExProtocol.parseGetDataResponse(payload)
        assertEquals(0xCAFE, parsed.page)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), parsed.data)
        assertEquals((0xCAFE + 1) and 0xFFFF, parsed.nextPage)
    }

    @Test
    fun parseGetDataResponse_emptyDataSignalsEof() {
        val parsed = SysExProtocol.parseGetDataResponse(byteArrayOf(0x00, 0x05))
        assertEquals(5, parsed.page)
        assertEquals(0, parsed.data.size)
    }

    @Test
    fun pack7bit_roundTrips256ByteBinaryBlob() {
        // The project .tar archive bytes route through this codec (RESEARCH Pitfall 5).
        val blob = ByteArray(256) { it.toByte() }
        val roundtrip = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(blob))
        assertArrayEquals(blob, roundtrip)
    }
}
