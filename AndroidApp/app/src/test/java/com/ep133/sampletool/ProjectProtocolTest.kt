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
        // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
        val frame = SysExProtocol.buildFileGetInitFrame(
            deviceId = 0, nodeId = 0x1234, offset = 0x00ABCDEF, requestId = 1,
        )
        assertEquals("command = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        // Body: [GET(3), INIT(0), nodeId u16, offset u32]
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET_TYPE_INIT, p[1].toInt() and 0xFF)
        // nodeId uint16 BE
        assertEquals(0x12, p[2].toInt() and 0xFF)
        assertEquals(0x34, p[3].toInt() and 0xFF)
        // offset uint32 BE
        assertEquals(0x00, p[4].toInt() and 0xFF)
        assertEquals(0xAB, p[5].toInt() and 0xFF)
        assertEquals(0xCD, p[6].toInt() and 0xFF)
        assertEquals(0xEF, p[7].toInt() and 0xFF)
    }

    @Test
    fun getInitFrame_defaultsOffsetToZero() {
        val frame = SysExProtocol.buildFileGetInitFrame(deviceId = 0, nodeId = 7, requestId = 1)
        val p = unpackPayload(frame)
        // Body: [GET(3), INIT(0), nodeId u16, offset u32]; offset starts at p[4].
        assertEquals(0, p[4].toInt() and 0xFF)
        assertEquals(0, p[5].toInt() and 0xFF)
        assertEquals(0, p[6].toInt() and 0xFF)
        assertEquals(0, p[7].toInt() and 0xFF)
    }

    @Test
    fun getDataFrame_carriesSubcommandTypeAndPage() {
        val frame = SysExProtocol.buildFileGetDataFrame(deviceId = 0, page = 0xBEEF, requestId = 2)
        assertEquals("command = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        // Body: [GET(3), DATA(1), page u16]
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_GET_TYPE_DATA, p[1].toInt() and 0xFF)
        assertEquals(0xBE, p[2].toInt() and 0xFF)
        assertEquals(0xEF, p[3].toInt() and 0xFF)
    }

    @Test
    fun putInitFrame_carriesSubcommandTypeNodeIdAndFileSize() {
        val frame = SysExProtocol.buildFilePutInitFrame(
            deviceId = 0, nodeId = 0x0A0B, fileSize = 0x12345678, requestId = 3,
        )
        assertEquals("command = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        // Body: [PUT(2), INIT(0), nodeId u16, fileSize u32]
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, p[1].toInt() and 0xFF)
        assertEquals(0x0A, p[2].toInt() and 0xFF)
        assertEquals(0x0B, p[3].toInt() and 0xFF)
        assertEquals(0x12, p[4].toInt() and 0xFF)
        assertEquals(0x34, p[5].toInt() and 0xFF)
        assertEquals(0x56, p[6].toInt() and 0xFF)
        assertEquals(0x78, p[7].toInt() and 0xFF)
    }

    @Test
    fun putDataFrame_carriesPageAndArchiveChunk() {
        val chunk = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x80.toByte(), 0x42)
        val frame = SysExProtocol.buildFilePutDataFrame(
            deviceId = 0, page = 0x0102, chunk = chunk, requestId = 4,
        )
        assertEquals("command = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)
        // Body: [PUT(2), DATA(1), page u16, chunk...]
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT, p[0].toInt() and 0xFF)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, p[1].toInt() and 0xFF)
        assertEquals(0x01, p[2].toInt() and 0xFF)
        assertEquals(0x02, p[3].toInt() and 0xFF)
        // Archive chunk bytes survive 7-bit pack/unpack unchanged.
        assertArrayEquals(chunk, p.copyOfRange(4, p.size))
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

    /** Build one FILE_LIST entry: nodeId u16 BE | flags | size u32 BE | null-term name. */
    private fun fileListEntry(nodeId: Int, flags: Int, size: Long, name: String): ByteArray =
        byteArrayOf(
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),
            flags.toByte(),
            (size shr 24).toByte(), (size shr 16).toByte(),
            (size shr 8).toByte(), (size and 0xFF).toByte(),
        ) + name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)

    @Test
    fun parseFileListEntries_decodesMultipleConcatenatedEntries() {
        val body = fileListEntry(0x0010, SysExProtocol.TE_SYSEX_FILE_FILE_TYPE_DIR, 4096L, "P00") +
            fileListEntry(0x0011, SysExProtocol.TE_SYSEX_FILE_FILE_TYPE_DIR, 0x0001FFFFL, "P01") +
            fileListEntry(0x0012, SysExProtocol.TE_SYSEX_FILE_FILE_TYPE_FILE, 12L, "P02")

        val entries = SysExProtocol.parseFileListEntries(body)
        assertEquals(3, entries.size)

        assertEquals(0x0010, entries[0].nodeId)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_FILE_TYPE_DIR, entries[0].flags)
        assertEquals(4096L, entries[0].sizeBytes)
        assertEquals("P00", entries[0].name)

        // uint32 BE size that exceeds Int range stays positive as a Long.
        assertEquals(0x0001FFFFL, entries[1].sizeBytes)
        assertEquals("P01", entries[1].name)

        assertEquals(0x0012, entries[2].nodeId)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_FILE_TYPE_FILE, entries[2].flags)
        assertEquals("P02", entries[2].name)
    }

    @Test
    fun parseFileListEntries_stopsOnTruncatedTrailingEntry() {
        // A complete entry followed by a truncated header (< 7 bytes) — must not overrun (T-04-07).
        val body = fileListEntry(0x0001, 2, 8L, "P00") + byteArrayOf(0x00, 0x02, 0x01)
        val entries = SysExProtocol.parseFileListEntries(body)
        assertEquals(1, entries.size)
        assertEquals("P00", entries[0].name)
    }

    @Test
    fun parseFileListEntries_toleratesFinalEntryWithoutNulTerminator() {
        // Last entry's name runs to the buffer end with no NUL — take the rest as the name.
        val body = byteArrayOf(
            0x00, 0x05, 0x02, 0x00, 0x00, 0x00, 0x40,
        ) + "P08".toByteArray(Charsets.US_ASCII)
        val entries = SysExProtocol.parseFileListEntries(body)
        assertEquals(1, entries.size)
        assertEquals(0x0005, entries[0].nodeId)
        assertEquals(64L, entries[0].sizeBytes)
        assertEquals("P08", entries[0].name)
    }

    @Test
    fun pack7bit_roundTrips256ByteBinaryBlob() {
        // The project .tar archive bytes route through this codec (RESEARCH Pitfall 5).
        val blob = ByteArray(256) { it.toByte() }
        val roundtrip = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(blob))
        assertArrayEquals(blob, roundtrip)
    }
}
