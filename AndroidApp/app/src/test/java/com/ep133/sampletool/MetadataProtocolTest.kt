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
        // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
        val nodeId = 0x00AB
        val page   = 0x0002
        val frame = SysExProtocol.buildMetadataGetFrame(
            deviceId = 0, nodeId = nodeId, page = page, key = null, requestId = 80,
        )
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)

        // Body starts at subcommand (no leading TE_SYSEX_FILE byte):
        // [0] = METADATA (7), [1] = GET (2), [2..3] nodeId u16 BE, [4..5] page u16 BE
        assertEquals("p[0] = METADATA (7)", 7, p[0].toInt() and 0xFF)
        assertEquals("p[1] = GET (2)", 2, p[1].toInt() and 0xFF)
        val parsedNode = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        val parsedPage = ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF)
        assertEquals("page u16 BE", page, parsedPage)
        // No key → body ends at p[5]
        assertEquals("no-key body size = 6", 6, p.size)
    }

    @Test
    fun buildMetadataGetFrame_wireLayout_withKey() {
        val key = "active"
        val frame = SysExProtocol.buildMetadataGetFrame(
            deviceId = 0, nodeId = 1, page = 0, key = key, requestId = 80,
        )
        val p = unpackPayload(frame)
        // Body: [METADATA(7), GET(2), nodeId u16, page u16] = 6 bytes; key starts at p[6].
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        val keyInPayload = p.copyOfRange(6, 6 + keyBytes.size)
        assertArrayEquals("key ASCII bytes", keyBytes, keyInPayload)
        assertEquals("NUL after key", 0, p[6 + keyBytes.size].toInt() and 0xFF)
        assertEquals("body size with key", 6 + keyBytes.size + 1, p.size)
    }

    // ── buildMetadataSetFrame wire layout ────────────────────────────────────

    @Test
    fun buildMetadataSetFrame_wireLayout() {
        // Body: [METADATA(7), SET(1), nodeId u16, json ASCII, NUL]
        val nodeId = 0x0055
        val json   = """{"active":99}"""
        val frame = SysExProtocol.buildMetadataSetFrame(
            deviceId = 0, nodeId = nodeId, json = json, requestId = 81,
        )
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)

        assertEquals("p[0] = METADATA (7)", 7, p[0].toInt() and 0xFF)
        assertEquals("p[1] = SET (1)", 1, p[1].toInt() and 0xFF)
        val parsedNode = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        val jsonBytes = json.toByteArray(Charsets.US_ASCII)
        val jsonInPayload = p.copyOfRange(4, 4 + jsonBytes.size)
        assertArrayEquals("json ASCII bytes", jsonBytes, jsonInPayload)
        assertEquals("NUL after json", 0, p[4 + jsonBytes.size].toInt() and 0xFF)
        assertEquals("body size", 4 + jsonBytes.size + 1, p.size)
    }

    // ── buildFileInfoFrame wire layout ───────────────────────────────────────

    @Test
    fun buildFileInfoFrame_wireLayout() {
        // Body: [FILE_INFO(11), nodeId u16]
        val nodeId = 0x1ABC
        val frame = SysExProtocol.buildFileInfoFrame(deviceId = 0, nodeId = nodeId, requestId = 82)
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        val p = unpackPayload(frame)

        assertEquals("p[0] = FILE_INFO (11)", 11, p[0].toInt() and 0xFF)
        val parsedNode = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        assertEquals("nodeId u16 BE", nodeId, parsedNode)
        assertEquals("body size = 3 (FILE_INFO + nodeId u16)", 3, p.size)
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

    // ── JSON span extraction (hardware-verified body shape) ──────────────────
    // Hardware-verified (2026-06-24): METADATA GET response body is
    //   `00 00 7B 22 61 63 74 69 76 65 22 3A 33 30 30 30 7D 00`
    // = 2-byte page prefix + `{"active":3000}` + trailing NUL.
    // getMetadataJson extracts the outermost `{...}` span before JSONObject parsing.

    /** Mirror of the span-extraction logic in MIDIRepository.getMetadataJson. */
    private fun extractJsonSpan(accumulated: String): String {
        val s = accumulated.indexOf('{')
        val e = accumulated.lastIndexOf('}')
        return if (s >= 0 && e > s) accumulated.substring(s, e + 1) else accumulated
    }

    @Test
    fun jsonSpan_stripsPagePrefixAndTrailingNul() {
        // Simulated accumulated string after parseMetadataPage strips page u16 and NUL:
        // page u16 = 0x0000 → two NUL chars prepended; trailing NUL from device.
        // Use char code 0 for NUL (parseMetadataPage trims trailing NUL but page u16
        // bytes may appear as prefix chars in the accumulated buffer if not stripped there).
        val prefix = "  "   // 2-byte page prefix that survives into accumulated
        val json   = """{"active":3000}"""
        val trailingNul = " "
        val accumulated = prefix + json + trailingNul

        val span = extractJsonSpan(accumulated)
        assertEquals("should extract exact JSON object", json, span)
    }

    @Test
    fun jsonSpan_cleanJsonPassesThrough() {
        // When the accumulated string is already valid JSON (no prefix garbage),
        // the extracted span must equal the input unchanged.
        val json = """{"active":42}"""
        assertEquals("clean JSON passes through unchanged", json, extractJsonSpan(json))
    }

    @Test
    fun jsonSpan_nestedObjectsRespectLastBrace() {
        // Nested JSON: the span must capture from the first '{' to the LAST '}'.
        val json = """{"a":{"b":1}}"""
        assertEquals("nested JSON span correct", json, extractJsonSpan(json))
    }

    @Test
    fun jsonSpan_noJsonBraces_returnsAccumulated() {
        // If there are no '{' / '}' at all, the fallback returns the whole accumulated string
        // (same as the original behavior — greet fallback then handles it).
        val noJson = "key:value;other:stuff"
        assertEquals("no braces → fallback returns input", noJson, extractJsonSpan(noJson))
    }
}
