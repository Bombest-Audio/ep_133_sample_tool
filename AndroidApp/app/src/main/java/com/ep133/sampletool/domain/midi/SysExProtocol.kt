package com.ep133.sampletool.domain.midi

/**
 * TE SysEx protocol frame builder and 7-bit codec.
 *
 * Manufacturer ID: 0x00 0x20 0x76 (Teenage Engineering)
 *
 * Frame format: 0xF0 [TE_ID x3] [deviceId] [0x40] [flags] [requestId] [command] [payload] 0xF7
 *
 * Reference: Reverse-engineered from data/index.js (EP-133 K.O. II firmware).
 */
object SysExProtocol {

    // ── Manufacturer ID (Teenage Engineering) ──
    const val TE_ID_0: Byte = 0x00
    const val TE_ID_1: Byte = 0x20
    const val TE_ID_2: Byte = 0x76.toByte()     // 118 decimal

    // ── MIDI SysEx framing ──
    const val MIDI_SYSEX_TE: Byte = 0x40         // TE subsystem byte
    const val MIDI_SYSEX_START: Byte = 0xF0.toByte()
    const val MIDI_SYSEX_END: Byte = 0xF7.toByte()

    // ── Request flags ──
    const val BIT_IS_REQUEST = 0x40
    const val BIT_REQUEST_ID_AVAILABLE = 0x20

    // ── Top-level commands ──
    const val CMD_GREET = 1
    const val CMD_PRODUCT_SPECIFIC = 127

    // ── File system subcommands (under PRODUCT_SPECIFIC, subsystem TE_SYSEX_FILE = 5) ──
    const val TE_SYSEX_FILE = 5
    const val TE_SYSEX_FILE_PUT = 2
    const val TE_SYSEX_FILE_GET = 3
    const val TE_SYSEX_FILE_LIST = 4
    const val TE_SYSEX_FILE_DELETE = 6
    const val TE_SYSEX_FILE_METADATA = 7
    const val TE_SYSEX_FILE_INFO = 11

    // ── Two-phase GET/PUT transfer sub-types (under FILE_GET / FILE_PUT) ──
    // Project archives are multi-page .tar blobs; the real device protocol is an
    // INIT request (returns fileSize/fileName) then a paged DATA loop. This REPLACES
    // Phase 2's broken single-byte chunkIndex model (buildFileGetFrame/buildFilePutFrame),
    // which is left intact for existing /sounds callers.
    const val TE_SYSEX_FILE_GET_TYPE_INIT = 0
    const val TE_SYSEX_FILE_GET_TYPE_DATA = 1
    const val TE_SYSEX_FILE_PUT_TYPE_INIT = 0
    const val TE_SYSEX_FILE_PUT_TYPE_DATA = 1

    // ── Status codes ──
    const val STATUS_OK = 0
    const val STATUS_SPECIFIC_SUCCESS_START = 64  // more data chunks coming

    // ── 7-bit codec ─────────────────────────────────────────────────────────

    /**
     * Pack 8-bit data bytes into MIDI-safe 7-bit encoding.
     *
     * For each group of up to 7 input bytes:
     * - A leading byte holds the high bits (bit 7 of each data byte), packed MSB-first
     * - Each data byte is then written with its high bit cleared (AND 0x7F)
     *
     * All output bytes are < 128 and therefore MIDI SysEx-safe.
     */
    fun pack7bit(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(data.size + data.size / 7 + 2)
        var i = 0
        while (i < data.size) {
            val groupSize = minOf(7, data.size - i)
            var highBits = 0
            for (j in 0 until groupSize) {
                if (data[i + j].toInt() and 0x80 != 0) {
                    highBits = highBits or (1 shl (6 - j))
                }
            }
            output.write(highBits)
            for (j in 0 until groupSize) {
                output.write(data[i + j].toInt() and 0x7F)
            }
            i += groupSize
        }
        return output.toByteArray()
    }

    /**
     * Unpack 7-bit encoded data back to 8-bit bytes.
     *
     * Reverses [pack7bit]: reads leading high-bits byte then 1–7 data bytes,
     * restoring the high bit of each from the leading byte.
     */
    fun unpack7bit(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val output = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val highBits = data[i].toInt() and 0x7F
            i++
            var j = 6
            while (j >= 0 && i < data.size) {
                val highBit = if (highBits and (1 shl j) != 0) 0x80 else 0x00
                output.write((data[i].toInt() and 0x7F) or highBit)
                i++
                j--
            }
        }
        return output.toByteArray()
    }

    // ── Frame construction ───────────────────────────────────────────────────

    /**
     * Build a TE SysEx frame.
     *
     * Header layout (10 bytes before packed payload):
     *   [0] 0xF0  — SysEx start
     *   [1] 0x00  — TE ID byte 0
     *   [2] 0x20  — TE ID byte 1
     *   [3] 0x76  — TE ID byte 2
     *   [4] deviceId (0–127)
     *   [5] 0x40  — TE subsystem
     *   [6] flags (BIT_IS_REQUEST | BIT_REQUEST_ID_AVAILABLE | requestId >> 7)
     *   [7] requestId & 0x7F
     *   [8] command
     *   ... packed payload ...
     *   [last] 0xF7 — SysEx end
     */
    fun buildFrame(deviceId: Int, command: Int, requestId: Int, payload: ByteArray): ByteArray {
        val packedPayload = if (payload.isNotEmpty()) pack7bit(payload) else ByteArray(0)
        val output = java.io.ByteArrayOutputStream(10 + packedPayload.size + 1)
        output.write(MIDI_SYSEX_START.toInt())
        output.write(TE_ID_0.toInt())
        output.write(TE_ID_1.toInt())
        output.write(TE_ID_2.toInt())
        output.write(deviceId and 0x7F)
        output.write(MIDI_SYSEX_TE.toInt())
        val flags = BIT_IS_REQUEST or BIT_REQUEST_ID_AVAILABLE or ((requestId shr 7) and 0x0F)
        output.write(flags)
        output.write(requestId and 0x7F)
        output.write(command and 0x7F)
        if (packedPayload.isNotEmpty()) output.write(packedPayload)
        output.write(MIDI_SYSEX_END.toInt())
        return output.toByteArray()
    }

    /** Build a GREET frame — queries firmware version, serial number, and device identity. */
    fun buildGreetFrame(deviceId: Int, requestId: Int = 1): ByteArray =
        buildFrame(deviceId, CMD_GREET, requestId, ByteArray(0))

    /**
     * Parse a GREET response payload into key-value pairs.
     *
     * The EP-133 responds with a 7-bit-encoded semicolon-delimited string:
     * "sw_version:1.3.2;serial:ABCDEF;..."
     */
    fun parseGreetResponse(rawPayload: ByteArray): Map<String, String> {
        if (rawPayload.isEmpty()) return emptyMap()
        val decoded = try {
            String(unpack7bit(rawPayload), Charsets.US_ASCII)
        } catch (_: Exception) {
            return emptyMap()
        }
        return decoded.trim('\u0000').split(";")
            .filter { it.contains(":") }
            .associate { entry ->
                val colonIdx = entry.indexOf(':')
                entry.substring(0, colonIdx) to entry.substring(colonIdx + 1)
            }
    }

    // ── File system frame builders ───────────────────────────────────────────

    /**
     * Build a file-system frame wrapping a file subcommand.
     *
     * Payload structure: TE_SYSEX_FILE, fileCmd, pathBytes..., extraPayload...
     *
     * Path and extra payload are included in the payload passed to buildFrame,
     * which then applies 7-bit packing to the whole payload. The path is ASCII text
     * and the extra payload (for FILE_PUT) contains raw file data.
     */
    private fun buildFileSystemFrame(
        deviceId: Int,
        fileCmd: Int,
        requestId: Int,
        pathBytes: ByteArray,
        extraPayload: ByteArray = ByteArray(0),
    ): ByteArray {
        val payload = byteArrayOf(TE_SYSEX_FILE.toByte(), fileCmd.toByte()) +
            pathBytes + extraPayload
        return buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, payload)
    }

    /** Build a FILE_LIST request frame for the given path. */
    fun buildFileListFrame(deviceId: Int, path: String, requestId: Int): ByteArray =
        buildFileSystemFrame(
            deviceId,
            TE_SYSEX_FILE_LIST,
            requestId,
            path.toByteArray(Charsets.US_ASCII),
        )

    /** Build a FILE_GET request frame to download a chunk of a file. */
    fun buildFileGetFrame(
        deviceId: Int,
        path: String,
        chunkIndex: Int,
        requestId: Int,
    ): ByteArray {
        val chunkBytes = byteArrayOf(
            (chunkIndex shr 8).toByte(),
            (chunkIndex and 0xFF).toByte(),
        )
        return buildFileSystemFrame(
            deviceId,
            TE_SYSEX_FILE_GET,
            requestId,
            path.toByteArray(Charsets.US_ASCII),
            chunkBytes,
        )
    }

    /** Build a FILE_PUT request frame to upload a chunk of a file. */
    fun buildFilePutFrame(
        deviceId: Int,
        path: String,
        data: ByteArray,
        chunkIndex: Int,
        requestId: Int,
    ): ByteArray {
        val chunkBytes = byteArrayOf(
            (chunkIndex shr 8).toByte(),
            (chunkIndex and 0xFF).toByte(),
        )
        return buildFileSystemFrame(
            deviceId,
            TE_SYSEX_FILE_PUT,
            requestId,
            path.toByteArray(Charsets.US_ASCII),
            chunkBytes + data,
        )
    }

    /** Build a FILE_METADATA request frame to query storage info for a path. */
    fun buildFileMetadataFrame(deviceId: Int, path: String, requestId: Int): ByteArray =
        buildFileSystemFrame(
            deviceId,
            TE_SYSEX_FILE_METADATA,
            requestId,
            path.toByteArray(Charsets.US_ASCII),
        )

    // ── Paged GET/PUT (the real INIT/DATA protocol — multi-kilobyte archives) ──
    //
    // These mirror buildFileSystemFrame's [TE_SYSEX_FILE, subcommand, ...] header
    // but use the device's two-phase paging model rather than the broken single
    // chunkIndex. nodeId is uint16 BE, offset uint32 BE, page uint16 BE.
    // Reference: data/index.js SysExGetFileInitRequest / SysExGetFileDataRequest.

    private fun buildFileGetInitPayload(nodeId: Int, offset: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE.toByte(),
            TE_SYSEX_FILE_GET.toByte(),
            TE_SYSEX_FILE_GET_TYPE_INIT.toByte(),
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),                 // uint16 BE
            (offset shr 24).toByte(), (offset shr 16).toByte(),
            (offset shr 8).toByte(), (offset and 0xFF).toByte(),                 // uint32 BE
        )

    private fun buildFileGetDataPayload(page: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE.toByte(),
            TE_SYSEX_FILE_GET.toByte(),
            TE_SYSEX_FILE_GET_TYPE_DATA.toByte(),
            (page shr 8).toByte(), (page and 0xFF).toByte(),                     // uint16 BE
        )

    private fun buildFilePutInitPayload(nodeId: Int, fileSize: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE.toByte(),
            TE_SYSEX_FILE_PUT.toByte(),
            TE_SYSEX_FILE_PUT_TYPE_INIT.toByte(),
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),                 // uint16 BE
            (fileSize shr 24).toByte(), (fileSize shr 16).toByte(),
            (fileSize shr 8).toByte(), (fileSize and 0xFF).toByte(),             // uint32 BE
        )

    /** Build a paged FILE_GET INIT request. Response carries fileSize + fileName. */
    fun buildFileGetInitFrame(deviceId: Int, nodeId: Int, offset: Int = 0, requestId: Int): ByteArray =
        buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, buildFileGetInitPayload(nodeId, offset))

    /** Build a paged FILE_GET DATA request for the given page. */
    fun buildFileGetDataFrame(deviceId: Int, page: Int, requestId: Int): ByteArray =
        buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, buildFileGetDataPayload(page))

    /** Build a paged FILE_PUT INIT request announcing the total upload size. */
    fun buildFilePutInitFrame(deviceId: Int, nodeId: Int, fileSize: Int, requestId: Int): ByteArray =
        buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, buildFilePutInitPayload(nodeId, fileSize))

    /**
     * Build a paged FILE_PUT DATA request: a page header followed by the archive chunk.
     * The chunk bytes are raw 8-bit; buildFrame 7-bit-packs the whole payload.
     */
    fun buildFilePutDataFrame(deviceId: Int, page: Int, chunk: ByteArray, requestId: Int): ByteArray {
        val payload = byteArrayOf(
            TE_SYSEX_FILE.toByte(),
            TE_SYSEX_FILE_PUT.toByte(),
            TE_SYSEX_FILE_PUT_TYPE_DATA.toByte(),
            (page shr 8).toByte(), (page and 0xFF).toByte(),
        ) + chunk
        return buildFrame(deviceId, CMD_PRODUCT_SPECIFIC, requestId, payload)
    }

    // ── Paged GET response parsers ─────────────────────────────────────────────

    /** Parsed FILE_GET INIT response. */
    data class GetInitResponse(val fileId: Int, val flags: Int, val fileSize: Int, val fileName: String)

    /** Parsed FILE_GET DATA response. [data] empty signals end-of-file. */
    data class GetDataResponse(val page: Int, val data: ByteArray) {
        /** Page the next DATA request should ask for. */
        val nextPage: Int get() = (page + 1) and 0xFFFF
    }

    /**
     * Parse an already-unpacked FILE_GET INIT response body.
     *
     * HARDWARE-VERIFY (A3): response body offset after [FILE,GET,TYPE] header.
     * The dispatcher strips the [TE_SYSEX_FILE, TE_SYSEX_FILE_GET, status] prefix
     * before calling this; [body] starts at the INIT payload. Exact offset needs a
     * physical EP-133 confirmation (RESEARCH Assumption A3).
     */
    fun parseGetInitResponse(body: ByteArray): GetInitResponse {
        require(body.size >= 7) { "GET INIT response too short: ${body.size} bytes" }
        val fileId = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val flags = body[2].toInt() and 0xFF
        val fileSize = ((body[3].toInt() and 0xFF) shl 24) or
            ((body[4].toInt() and 0xFF) shl 16) or
            ((body[5].toInt() and 0xFF) shl 8) or
            (body[6].toInt() and 0xFF)
        val nameBytes = body.copyOfRange(7, body.size)
        val nul = nameBytes.indexOfFirst { it.toInt() == 0 }
        val fileName = String(
            if (nul >= 0) nameBytes.copyOfRange(0, nul) else nameBytes,
            Charsets.US_ASCII,
        )
        return GetInitResponse(fileId, flags, fileSize, fileName)
    }

    /**
     * Parse an already-unpacked FILE_GET DATA response body.
     *
     * HARDWARE-VERIFY (A3): response body offset after [FILE,GET,TYPE] header.
     */
    fun parseGetDataResponse(body: ByteArray): GetDataResponse {
        require(body.size >= 2) { "GET DATA response too short: ${body.size} bytes" }
        val page = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val data = body.copyOfRange(2, body.size)
        return GetDataResponse(page, data)
    }
}
