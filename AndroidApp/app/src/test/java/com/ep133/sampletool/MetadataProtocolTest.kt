package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the Step-1 active-group protocol additions:
 *   - parseFileInfo byte layout
 *   - parseMetadataPage / isMetadataTerminator
 *   - buildMetadataGetFrame / buildMetadataSetFrame / buildFileInfoFrame wire layouts
 *
 * These are pure parser / frame-builder tests — no Android runtime, no MIDI I/O.
 * Active-group logic (getActiveGroupIndex / setActiveGroup) is hardware-bound
 * and is covered by the live device capture (HW-VERIFY-2, HW-VERIFY-3).
 */
class MetadataProtocolTest {

    // ── Helper: unpack the inner payload of a SysEx frame ───────────────────
    private fun unpackPayload(frame: ByteArray): ByteArray =
        SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

    // ── parseFileInfo ────────────────────────────────────────────────────────

    @Test
    fun parseFileInfo_correctFieldOffsets() {
        // Craft a FILE_INFO response body (before 7-bit packing):
        //   [0..1] nodeId   = 0x1234
        //   [2..3] parentId = 0x0005
        //   [4]    flags    = 0x0E  (WRITE=8 | DIR=2 | READ=4)
        //   [5..8] size     = 0x00001000 (4096)
        //   [9..]  name     = "A\0"
        val body = byteArrayOf(
            0x12, 0x34,        // nodeId
            0x00, 0x05,        // parentId
            0x0E.toByte(),     // flags: READ(4) | WRITE(8) | DIR(2)
            0x00, 0x00, 0x10, 0x00,  // size = 4096
            'A'.code.toByte(), 0x00, // name = "A"
        )
        val info = SysExProtocol.parseFileInfo(body)

        assertEquals("nodeId", 0x1234, info.nodeId)
        assertEquals("parentId", 0x0005, info.parentId)
        assertEquals("flags", 0x0E, info.flags)
        assertEquals("sizeBytes", 4096L, info.sizeBytes)
        assertEquals("name", "A", info.name)
    }

    @Test
    fun parseFileInfo_isWritable_whenFlagSet() {
        val body = byteArrayOf(
            0x00, 0x01,  // nodeId
            0x00, 0x00,  // parentId
            0x08.toByte(), // flags = CAPABILITY_WRITE only
            0x00, 0x00, 0x00, 0x00, // size = 0
            'B'.code.toByte(), 0x00,
        )
        val info = SysExProtocol.parseFileInfo(body)
        assertTrue("isWritable should be true when WRITE flag set", info.isWritable)
    }

    @Test
    fun parseFileInfo_isDir_whenFileTypeFlagClear() {
        val body = byteArrayOf(
            0x00, 0x02,   // nodeId
            0x00, 0x00,   // parentId
            0x02.toByte(), // flags = FILE_TYPE_DIR (2) only — FILE bit (1) clear
            0x00, 0x00, 0x00, 0x00,
            'C'.code.toByte(), 0x00,
        )
        val info = SysExProtocol.parseFileInfo(body)
        assertTrue("isDir should be true when FILE_TYPE_FILE bit is clear", info.isDir)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFileInfo_throwsWhenBodyTooShort() {
        SysExProtocol.parseFileInfo(ByteArray(5)) // needs >= 9 bytes
    }

    @Test
    fun parseFileInfo_nameWithoutNulTerminator_usesRestOfBuffer() {
        // A name that is not NUL-terminated — should take bytes to end of buffer.
        val body = byteArrayOf(
            0x00, 0x03,
            0x00, 0x00,
            0x00,
            0x00, 0x00, 0x00, 0x00,
            'D'.code.toByte(), // no NUL
        )
        val info = SysExProtocol.parseFileInfo(body)
        assertEquals("name without NUL terminator", "D", info.name)
    }

    // ── parseMetadataPage ────────────────────────────────────────────────────

    @Test
    fun parseMetadataPage_extractsPageAndFragment() {
        // Page 0, JSON fragment `{"active":` (without trailing NUL — intermediate page)
        val fragment = """{"active":"""
        val body = byteArrayOf(
            0x00, 0x00,  // page 0 u16 BE
        ) + fragment.toByteArray(Charsets.US_ASCII)
        val (page, text) = SysExProtocol.parseMetadataPage(body)
        assertEquals("page", 0, page)
        assertEquals("fragment", fragment, text)
    }

    @Test
    fun parseMetadataPage_trailingNulIsStripped() {
        // Final page: `1234}` followed by NUL
        val fragment = "1234}"
        val body = byteArrayOf(
            0x00, 0x01,  // page 1
        ) + fragment.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        val (page, text) = SysExProtocol.parseMetadataPage(body)
        assertEquals("page", 1, page)
        assertEquals("trailing NUL stripped", fragment, text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMetadataPage_throwsWhenBodyTooShort() {
        SysExProtocol.parseMetadataPage(ByteArray(1)) // needs >= 2 bytes
    }

    // ── isMetadataTerminator ─────────────────────────────────────────────────

    @Test
    fun isMetadataTerminator_trueWhenBodySizeLeq2() {
        assertTrue("empty body", SysExProtocol.isMetadataTerminator(ByteArray(0)))
        assertTrue("1-byte body", SysExProtocol.isMetadataTerminator(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun isMetadataTerminator_trueWhenLastByteIsNul() {
        val body = byteArrayOf(0x00, 0x00, 'A'.code.toByte(), 0x00)
        assertTrue("last byte NUL", SysExProtocol.isMetadataTerminator(body))
    }

    @Test
    fun isMetadataTerminator_falseForNonTerminatingPage() {
        val body = byteArrayOf(0x00, 0x00) + """{"active":""".toByteArray(Charsets.US_ASCII)
        assertFalse("intermediate page should not terminate", SysExProtocol.isMetadataTerminator(body))
    }

    // ── buildMetadataGetFrame wire layout ────────────────────────────────────

    @Test
    fun buildMetadataGetFrame_wireLayout_noKey() {
        val nodeId = 0x00AB
        val page   = 0x0002
        val frame = SysExProtocol.buildMetadataGetFrame(
            deviceId = 0, nodeId = nodeId, page = page, key = null, requestId = 80,
        )
        val p = unpackPayload(frame)

        // [0] = TE_SYSEX_FILE (5)
        assertEquals("p[0] = TE_SYSEX_FILE", 5, p[0].toInt() and 0xFF)
        // [1] = TE_SYSEX_FILE_METADATA (7)
        assertEquals("p[1] = METADATA", 7, p[1].toInt() and 0xFF)
        // [2] = METADATA_GET (2)
        assertEquals("p[2] = GET", 2, p[2].toInt() and 0xFF)
        // [3..4] nodeId u16 BE
        val parsedNode = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        // [5..6] page u16 BE
        val parsedPage = ((p[5].toInt() and 0xFF) shl 8) or (p[6].toInt() and 0xFF)
        assertEquals("page u16 BE", page, parsedPage)
        // No key → payload ends at p[6]
        assertEquals("no-key payload size = 7", 7, p.size)
    }

    @Test
    fun buildMetadataGetFrame_wireLayout_withKey() {
        val key = "active"
        val frame = SysExProtocol.buildMetadataGetFrame(
            deviceId = 0, nodeId = 1, page = 0, key = key, requestId = 80,
        )
        val p = unpackPayload(frame)
        // key starts at p[7], followed by NUL
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        val keyInPayload = p.copyOfRange(7, 7 + keyBytes.size)
        assertArrayEquals("key ASCII bytes", keyBytes, keyInPayload)
        assertEquals("NUL after key", 0, p[7 + keyBytes.size].toInt() and 0xFF)
        assertEquals("payload size with key", 7 + keyBytes.size + 1, p.size)
    }

    // ── buildMetadataSetFrame wire layout ────────────────────────────────────

    @Test
    fun buildMetadataSetFrame_wireLayout() {
        val nodeId = 0x0055
        val json   = """{"active":99}"""
        val frame = SysExProtocol.buildMetadataSetFrame(
            deviceId = 0, nodeId = nodeId, json = json, requestId = 81,
        )
        val p = unpackPayload(frame)

        assertEquals("p[0] = TE_SYSEX_FILE", 5, p[0].toInt() and 0xFF)
        assertEquals("p[1] = METADATA", 7, p[1].toInt() and 0xFF)
        assertEquals("p[2] = SET", 1, p[2].toInt() and 0xFF)
        val parsedNode = ((p[3].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        val jsonBytes = json.toByteArray(Charsets.US_ASCII)
        val jsonInPayload = p.copyOfRange(5, 5 + jsonBytes.size)
        assertArrayEquals("json ASCII bytes", jsonBytes, jsonInPayload)
        assertEquals("NUL after json", 0, p[5 + jsonBytes.size].toInt() and 0xFF)
        assertEquals("payload size", 5 + jsonBytes.size + 1, p.size)
    }

    // ── buildFileInfoFrame wire layout ───────────────────────────────────────

    @Test
    fun buildFileInfoFrame_wireLayout() {
        val nodeId = 0x1ABC
        val frame = SysExProtocol.buildFileInfoFrame(deviceId = 0, nodeId = nodeId, requestId = 82)
        val p = unpackPayload(frame)

        assertEquals("p[0] = TE_SYSEX_FILE", 5, p[0].toInt() and 0xFF)
        assertEquals("p[1] = FILE_INFO (11)", 11, p[1].toInt() and 0xFF)
        val parsedNode = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        assertEquals("payload size = 4", 4, p.size)
    }

    // ── Constant values ──────────────────────────────────────────────────────

    @Test
    fun constants_matchReferenceValues() {
        assertEquals("TE_SYSEX_FILE = 5",        5,  SysExProtocol.TE_SYSEX_FILE)
        assertEquals("TE_SYSEX_FILE_METADATA = 7", 7, SysExProtocol.TE_SYSEX_FILE_METADATA)
        assertEquals("METADATA_GET = 2",           2,  SysExProtocol.TE_SYSEX_FILE_METADATA_GET)
        assertEquals("METADATA_SET = 1",           1,  SysExProtocol.TE_SYSEX_FILE_METADATA_SET)
        assertEquals("TE_SYSEX_FILE_INFO = 11",   11, SysExProtocol.TE_SYSEX_FILE_INFO)
        assertEquals("CAPABILITY_WRITE = 8",       8,  SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE)
    }
}
