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
    // Hardware-verified (2026-06-23): file ops use command=5 directly, NOT CMD_PRODUCT_SPECIFIC(127).
    // Frame: F0 00 20 76 <dev> 40 <flags> <reqId7> <5> <7bit-packed body> F7
    // Body starts at the subcommand — no leading TE_SYSEX_FILE byte repeated in payload.
    const val TE_SYSEX_FILE = 5

    // ── File system subcommands (payload body byte 0 when command=TE_SYSEX_FILE) ──
    // Hardware-verified sequence: FILE_INIT(1) → FILE_LIST(4, node=0) → root dir returned.
    const val TE_SYSEX_FILE_INIT = 1       // SESSION INIT — must send before any listing
    const val TE_SYSEX_FILE_PUT = 2
    const val TE_SYSEX_FILE_GET = 3
    const val TE_SYSEX_FILE_LIST = 4
    const val TE_SYSEX_FILE_DELETE = 6
    const val TE_SYSEX_FILE_METADATA = 7
    const val TE_SYSEX_FILE_INFO = 11

    // ── FILE_INIT flags ──
    const val TE_SYSEX_FILE_INIT_SUBSCRIBE = 1  // subscribe to device-push events

    // ── FILE_PUT INIT capability + file-type flags ──
    // capabilities is ORed into flags: READ=4, WRITE=8, file-type FILE=1, DIR=2.
    // New-file INIT: flags = TE_SYSEX_FILE_CAPABILITY_READ or TE_SYSEX_FILE_TYPE_FILE = 5.
    const val TE_SYSEX_FILE_CAPABILITY_READ = 4
    const val TE_SYSEX_FILE_CAPABILITY_WRITE = 8
    const val TE_SYSEX_FILE_TYPE_FILE = 1

    // ── FILE_METADATA sub-opcodes ──
    const val TE_SYSEX_FILE_METADATA_SET = 1
    const val TE_SYSEX_FILE_METADATA_GET = 2

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
     * Build a TE SysEx frame for a file subsystem operation (command = TE_SYSEX_FILE = 5).
     *
     * Hardware-verified (2026-06-23): the correct envelope is command=5 with the body
     * starting at the file subcommand — NOT command=127 with [5, subcmd, ...] payload.
     *
     * @param body  Raw (pre-pack) payload starting with the file subcommand byte.
     */
    fun buildFileFrame(deviceId: Int, requestId: Int, body: ByteArray): ByteArray =
        buildFrame(deviceId, TE_SYSEX_FILE, requestId, body)

    /**
     * Build a file-system frame wrapping a path-string file subcommand.
     *
     * Body structure: fileCmd, pathBytes..., extraPayload...
     * (No leading TE_SYSEX_FILE byte — it is now the top-level command, not a payload prefix.)
     */
    private fun buildFileSystemFrame(
        deviceId: Int,
        fileCmd: Int,
        requestId: Int,
        pathBytes: ByteArray,
        extraPayload: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = byteArrayOf(fileCmd.toByte()) + pathBytes + extraPayload
        return buildFileFrame(deviceId, requestId, body)
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

    /**
     * Build a FILE_LIST request frame that lists a node by its numeric ID (the device's
     * actual addressing model per the reference impl: `SysExFileListRequest(page, nodeId)`).
     *
     * Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body = [LIST(4), page u16, nodeId u16].
     * No leading TE_SYSEX_FILE byte in the body — that byte is now the top-level command.
     */
    fun buildFileListByNodeFrame(deviceId: Int, nodeId: Int, page: Int = 0, requestId: Int): ByteArray {
        val body = byteArrayOf(
            TE_SYSEX_FILE_LIST.toByte(),
            (page shr 8).toByte(), (page and 0xFF).toByte(),       // uint16 BE
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),   // uint16 BE
        )
        return buildFileFrame(deviceId, requestId, body)
    }

    // ── FILE_LIST response parsing (directory entries) ──────────────────────────

    /** One parsed FILE_LIST directory entry. */
    data class FileEntry(val nodeId: Int, val flags: Int, val sizeBytes: Long, val name: String)

    /** Directory-entry flag: a FILE node (vs DIR). */
    const val TE_SYSEX_FILE_FILE_TYPE_FILE = 1
    /** Directory-entry flag: a DIR node. */
    const val TE_SYSEX_FILE_FILE_TYPE_DIR = 2

    /**
     * Decode concatenated FILE_LIST directory entries from an already-unpacked response body.
     *
     * Per-entry layout (RESEARCH "FILE_LIST response"):
     *   nodeId   = a[0..1] uint16 BE
     *   flags    = a[2]
     *   fileSize = a[3..6] uint32 BE
     *   fileName = null-terminated ASCII from a[7]
     * Entries are concatenated; the next entry begins after the name's NUL terminator.
     *
     * Bounds-checks every field read and STOPS on a truncated entry rather than reading
     * past the buffer (threat T-04-07). A missing NUL terminator on the final entry is
     * tolerated — the rest of the buffer is taken as the name.
     *
     * HARDWARE-VERIFY (Open Q1 / A3): exact body offset after the status byte is stripped
     * by the dispatcher; confirm against a physical EP-133.
     */
    fun parseFileListEntries(body: ByteArray): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        var i = 0
        while (i + 7 <= body.size) {
            val nodeId = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            val flags = body[i + 2].toInt() and 0xFF
            val sizeBytes = (((body[i + 3].toInt() and 0xFF).toLong()) shl 24) or
                (((body[i + 4].toInt() and 0xFF).toLong()) shl 16) or
                (((body[i + 5].toInt() and 0xFF).toLong()) shl 8) or
                ((body[i + 6].toInt() and 0xFF).toLong())
            var nameStart = i + 7
            var nul = nameStart
            while (nul < body.size && body[nul].toInt() != 0) nul++
            val name = String(body.copyOfRange(nameStart, nul), Charsets.US_ASCII)
            entries.add(FileEntry(nodeId, flags, sizeBytes, name))
            if (nul >= body.size) break   // no terminator → final entry, stop
            i = nul + 1                    // skip the NUL terminator
        }
        return entries
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
    // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at
    // the subcommand byte — no leading TE_SYSEX_FILE byte in the payload body.
    // nodeId uint16 BE, offset uint32 BE, page uint16 BE.
    // Reference: data/index.js SysExGetFileInitRequest / SysExGetFileDataRequest.

    private fun buildFileGetInitBody(nodeId: Int, offset: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE_GET.toByte(),
            TE_SYSEX_FILE_GET_TYPE_INIT.toByte(),
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),                 // uint16 BE
            (offset shr 24).toByte(), (offset shr 16).toByte(),
            (offset shr 8).toByte(), (offset and 0xFF).toByte(),                 // uint32 BE
        )

    private fun buildFileGetDataBody(page: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE_GET.toByte(),
            TE_SYSEX_FILE_GET_TYPE_DATA.toByte(),
            (page shr 8).toByte(), (page and 0xFF).toByte(),                     // uint16 BE
        )

    private fun buildFilePutInitBody(nodeId: Int, fileSize: Int): ByteArray =
        byteArrayOf(
            TE_SYSEX_FILE_PUT.toByte(),
            TE_SYSEX_FILE_PUT_TYPE_INIT.toByte(),
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),                 // uint16 BE
            (fileSize shr 24).toByte(), (fileSize shr 16).toByte(),
            (fileSize shr 8).toByte(), (fileSize and 0xFF).toByte(),             // uint32 BE
        )

    /** Build a paged FILE_GET INIT request. Response carries fileSize + fileName. */
    fun buildFileGetInitFrame(deviceId: Int, nodeId: Int, offset: Int = 0, requestId: Int): ByteArray =
        buildFileFrame(deviceId, requestId, buildFileGetInitBody(nodeId, offset))

    /** Build a paged FILE_GET DATA request for the given page. */
    fun buildFileGetDataFrame(deviceId: Int, page: Int, requestId: Int): ByteArray =
        buildFileFrame(deviceId, requestId, buildFileGetDataBody(page))

    /** Build a paged FILE_PUT INIT request announcing the total upload size. */
    fun buildFilePutInitFrame(deviceId: Int, nodeId: Int, fileSize: Int, requestId: Int): ByteArray =
        buildFileFrame(deviceId, requestId, buildFilePutInitBody(nodeId, fileSize))

    /**
     * Build a FILE_PUT INIT for CREATING a new file under [parentNodeId] (e.g. the /sounds dir).
     *
     * Matches the device reference tool (data/index.js SysExFilePutInitRequest): announces the
     * parent directory node ID, a fileId of 0 (device assigns the real id on the response), the
     * byte size, and the filename (truncated to 54 chars, NUL-terminated). Metadata is optional
     * and omitted when null.
     *
     * Wire body (before 7-bit packing; command = TE_SYSEX_FILE = 5 is the envelope command):
     *   [PUT(2), INIT(0), flags, fileId u16 BE, parentId u16 BE, fileSize u32 BE,
     *    filename ASCII + 0x00, (metadata ASCII + 0x00 only when non-null)]
     *
     * Default flags = TE_SYSEX_FILE_CAPABILITY_READ or TE_SYSEX_FILE_TYPE_FILE = 5, matching
     * uploadSound in the reference tool. Default fileId = 0 (new file — device assigns the id).
     */
    fun buildFileCreatePutInitFrame(
        deviceId: Int,
        parentNodeId: Int,
        fileSize: Int,
        filename: String,
        requestId: Int,
        flags: Int = TE_SYSEX_FILE_CAPABILITY_READ or TE_SYSEX_FILE_TYPE_FILE,
        fileId: Int = 0,
        metadataJson: String? = null,
    ): ByteArray {
        val nameBytes = filename.take(54).toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream(16 + nameBytes.size)
        out.write(TE_SYSEX_FILE_PUT)
        out.write(TE_SYSEX_FILE_PUT_TYPE_INIT)
        out.write(flags)
        out.write(fileId shr 8); out.write(fileId and 0xFF)              // fileId u16 BE
        out.write(parentNodeId shr 8); out.write(parentNodeId and 0xFF)  // parentId u16 BE
        out.write(fileSize shr 24); out.write(fileSize shr 16)
        out.write(fileSize shr 8); out.write(fileSize and 0xFF)          // fileSize u32 BE
        out.write(nameBytes); out.write(0)                                // filename + NUL
        if (metadataJson != null) {
            out.write(metadataJson.toByteArray(Charsets.US_ASCII)); out.write(0)
        }
        return buildFileFrame(deviceId, requestId, out.toByteArray())
    }

    /**
     * Build a paged FILE_PUT DATA request: a page header followed by the archive chunk.
     * The chunk bytes are raw 8-bit; buildFrame 7-bit-packs the whole body.
     * Command = TE_SYSEX_FILE (5); body starts at PUT(2) subcommand (no leading 5).
     */
    fun buildFilePutDataFrame(deviceId: Int, page: Int, chunk: ByteArray, requestId: Int): ByteArray {
        val body = byteArrayOf(
            TE_SYSEX_FILE_PUT.toByte(),
            TE_SYSEX_FILE_PUT_TYPE_DATA.toByte(),
            (page shr 8).toByte(), (page and 0xFF).toByte(),
        ) + chunk
        return buildFileFrame(deviceId, requestId, body)
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

    /**
     * Pure page-assembly loop for a multi-page FILE_GET. Hardware-free: [supplier] is
     * called with the expected page number and returns that page's [GetDataResponse].
     *
     * Mirrors data/index.js `iterGet`:
     *  - accumulates until `out.size >= fileSize` or an empty-data page terminates early
     *  - requires `resp.page == expectedPage`, else throws "unexpected page" (no silent accept)
     *  - advances `page = (page + 1) & 0xFFFF`
     *  - caps the buffer at fileSize + slack to defend against an oversized/runaway stream
     *    (threat T-04-03); aborts on overflow
     *
     * @throws IllegalStateException on page mismatch or buffer overflow.
     */
    fun assembleGetPages(fileSize: Int, supplier: (expectedPage: Int) -> GetDataResponse): ByteArray {
        val cap = fileSize + MAX_PAGE_BYTES   // small slack: one page beyond the declared size
        val out = java.io.ByteArrayOutputStream(fileSize.coerceAtLeast(0))
        var page = 0
        while (out.size() < fileSize) {
            val resp = supplier(page)
            check(resp.page == page) { "unexpected page ${resp.page}, expected $page" }
            if (resp.data.isEmpty()) break
            check(out.size() + resp.data.size <= cap) {
                "GET overflow: ${out.size() + resp.data.size} bytes exceeds cap $cap"
            }
            out.write(resp.data)
            page = resp.nextPage
        }
        return out.toByteArray()
    }

    /** Defensive per-page slack for the assembly-buffer overflow cap (threat T-04-03). */
    const val MAX_PAGE_BYTES = 4096

    /** Outcome of a paged-transfer response status (the dispatch continuation decision). */
    enum class TransferStatus { PENDING, COMPLETE, ERROR }

    /**
     * Pure classification of a paged-transfer response status byte:
     *  - `>= STATUS_SPECIFIC_SUCCESS_START` → more chunks coming, keep the request registered
     *  - `== STATUS_OK` → terminal success, complete the request
     *  - otherwise (`< SUCCESS_START`, non-OK) → error, drop the request
     */
    fun classifyTransferStatus(status: Int): TransferStatus = when {
        status >= STATUS_SPECIFIC_SUCCESS_START -> TransferStatus.PENDING
        status == STATUS_OK -> TransferStatus.COMPLETE
        else -> TransferStatus.ERROR
    }

    // ── Metadata nodeId-form builders (Step 1 — active-group sync) ──────────────
    //
    // These use the reference tool's nodeId+page+key form rather than the legacy
    // path-string form (buildFileMetadataFrame). The path-form is kept intact for
    // Phase-4 /sounds storage stats and /projects active-node reads.
    //
    // Wire body (command = TE_SYSEX_FILE = 5; no leading 5 in the body):
    //   GET: [METADATA(7), GET(2), nodeId u16 BE, page u16 BE, (key ASCII + 0x00)?]
    //   SET: [METADATA(7), SET(1), nodeId u16 BE, json ASCII, 0x00]
    //   INFO:[FILE_INFO(11), nodeId u16 BE]
    //
    // Reference: data/index.js SysExFileGetMetadataRequest / SysExFileSetMetadataRequest
    //            / SysExFileInfoRequest — verified 2026-06-21.

    /**
     * Build a nodeId-form METADATA GET frame (METADATA_GET = 2).
     *
     * Body (command = TE_SYSEX_FILE): [METADATA(7), GET(2), nodeId u16, page u16, (key+NUL)?]
     *
     * @param nodeId    Numeric node ID of the directory or file to query.
     * @param page      Page index (0-based); devices return `[page u16][JSON fragment]` pages.
     * @param key       Optional key filter (ASCII string). When null, full metadata is returned.
     * @param requestId Matched by the dispatcher to route the response.
     */
    fun buildMetadataGetFrame(
        deviceId: Int,
        nodeId: Int,
        page: Int = 0,
        key: String? = null,
        requestId: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(7 + (key?.length?.plus(1) ?: 0))
        out.write(TE_SYSEX_FILE_METADATA)
        out.write(TE_SYSEX_FILE_METADATA_GET)
        out.write(nodeId shr 8); out.write(nodeId and 0xFF)    // u16 BE
        out.write(page shr 8);   out.write(page and 0xFF)      // u16 BE
        if (key != null) {
            out.write(key.toByteArray(Charsets.US_ASCII))
            out.write(0)
        }
        return buildFileFrame(deviceId, requestId, out.toByteArray())
    }

    /**
     * Build a nodeId-form METADATA SET frame (METADATA_SET = 1).
     *
     * Body (command = TE_SYSEX_FILE): [METADATA(7), SET(1), nodeId u16, json ASCII, 0x00]
     *
     * For active-group selection the JSON is `{"active":<groupNodeId>}` — always
     * single-frame (far below any device chunk size).
     *
     * @param nodeId Numeric node ID of the directory whose metadata to update.
     * @param json   JSON string to write (e.g. `{"active":1234}`).
     */
    fun buildMetadataSetFrame(
        deviceId: Int,
        nodeId: Int,
        json: String,
        requestId: Int,
    ): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream(4 + jsonBytes.size + 1)
        out.write(TE_SYSEX_FILE_METADATA)
        out.write(TE_SYSEX_FILE_METADATA_SET)
        out.write(nodeId shr 8); out.write(nodeId and 0xFF)    // u16 BE
        out.write(jsonBytes)
        out.write(0)
        return buildFileFrame(deviceId, requestId, out.toByteArray())
    }

    /**
     * Build a FILE_INFO (getNode) frame (op 11).
     *
     * Body (command = TE_SYSEX_FILE): [FILE_INFO(11), nodeId u16 BE]
     *
     * Response layout: nodeId u16, parentId u16, flags u8, size u32, name@9 NUL-terminated ASCII.
     * Parse with [parseFileInfo].
     *
     * Reference: data/index.js SysExFileInfoRequest, byte ~833656.
     */
    fun buildFileInfoFrame(deviceId: Int, nodeId: Int, requestId: Int): ByteArray =
        buildFileFrame(
            deviceId,
            requestId,
            byteArrayOf(
                TE_SYSEX_FILE_INFO.toByte(),
                (nodeId shr 8).toByte(),
                (nodeId and 0xFF).toByte(),
            ),
        )

    // ── FILE_INIT handshake builders/parsers ─────────────────────────────────

    /**
     * Build a FILE_INIT request frame (TE_SYSEX_FILE_INIT = 1).
     *
     * Hardware-verified: must be sent once per connection before any file listing.
     * Device returns "can't list unless initialized" without this handshake.
     *
     * Body (command = TE_SYSEX_FILE): [INIT(1), flags, maxResponseLength u32 BE]
     *
     * @param maxResponseLength Maximum response size the client accepts (try 512).
     * @param flags             Init flags; default = TE_SYSEX_FILE_INIT_SUBSCRIBE (1).
     */
    fun buildFileInitFrame(
        deviceId: Int,
        requestId: Int,
        maxResponseLength: Int = 512,
        flags: Int = TE_SYSEX_FILE_INIT_SUBSCRIBE,
    ): ByteArray {
        val body = byteArrayOf(
            TE_SYSEX_FILE_INIT.toByte(),
            flags.toByte(),
            (maxResponseLength shr 24).toByte(),
            (maxResponseLength shr 16).toByte(),
            (maxResponseLength shr 8).toByte(),
            (maxResponseLength and 0xFF).toByte(),
        )
        return buildFileFrame(deviceId, requestId, body)
    }

    /**
     * Parse an already-unpacked FILE_INIT response body into a negotiated chunk size.
     *
     * HARDWARE-VERIFY (INIT-A1): response body offset and exact field layout need
     * a physical EP-133 confirmation. Current assumption: body[1..4] = chunkSize u32 BE
     * (reference: SysExFileInitRequest response in data/index.js, a[1..4]).
     * Returns 512 as a safe default if the body is too short to parse.
     */
    fun parseFileInitResponse(body: ByteArray): Int {
        if (body.size < 5) return 512
        return ((body[1].toInt() and 0xFF) shl 24) or
            ((body[2].toInt() and 0xFF) shl 16) or
            ((body[3].toInt() and 0xFF) shl 8) or
            (body[4].toInt() and 0xFF)
    }

    // ── FILE_INFO response ────────────────────────────────────────────────────

    /**
     * Parsed FILE_INFO (getNode) response.
     *
     * @param nodeId    Numeric ID of the node.
     * @param parentId  Numeric ID of the parent directory.
     * @param flags     Raw capability + type flags byte.
     * @param sizeBytes File size in bytes (0 for directories).
     * @param name      Node name (filename or directory name, e.g. "A", "01", "kick.wav").
     */
    data class NodeInfo(
        val nodeId: Int,
        val parentId: Int,
        val flags: Int,
        val sizeBytes: Long,
        val name: String,
    ) {
        /** True if the CAPABILITY_WRITE flag is set — required for the active-group write gate. */
        val isWritable: Boolean get() = flags and TE_SYSEX_FILE_CAPABILITY_WRITE != 0
        /** True if the FILE_TYPE_FILE flag is NOT set (i.e. this is a directory node). */
        val isDir: Boolean get() = flags and TE_SYSEX_FILE_TYPE_FILE == 0
    }

    /**
     * Parse an already-unpacked FILE_INFO response body into [NodeInfo].
     *
     * Body layout (SysexFileInfoResponse, data/index.js byte ~833859):
     *   [0..1] nodeId   u16 BE
     *   [2..3] parentId u16 BE
     *   [4]    flags    u8
     *   [5..8] fileSize u32 BE
     *   [9..]  fileName null-terminated ASCII
     *
     * This is distinct from FILE_LIST per-entry layout (which has no parentId field).
     *
     * @throws IllegalArgumentException if [body] is too short to contain the fixed header.
     */
    fun parseFileInfo(body: ByteArray): NodeInfo {
        require(body.size >= 9) { "FILE_INFO response too short: ${body.size} bytes" }
        val nodeId   = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val parentId = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val flags    = body[4].toInt() and 0xFF
        val sizeBytes = (((body[5].toInt() and 0xFF).toLong()) shl 24) or
            (((body[6].toInt() and 0xFF).toLong()) shl 16) or
            (((body[7].toInt() and 0xFF).toLong()) shl  8) or
            ((body[8].toInt() and 0xFF).toLong())
        val nameStart = 9
        var nul = nameStart
        while (nul < body.size && body[nul].toInt() != 0) nul++
        val name = String(body.copyOfRange(nameStart, nul), Charsets.US_ASCII)
        return NodeInfo(nodeId, parentId, flags, sizeBytes, name)
    }

    // ── Metadata GET response paging ──────────────────────────────────────────

    /**
     * Parse one page from a nodeId-form METADATA GET response body.
     *
     * Body layout: `[page u16 BE][JSON fragment ASCII … NUL?]`
     *
     * The JSON fragment is trimmed of a trailing NUL byte (the device NUL-terminates the
     * last page; intermediate pages have no trailing NUL). Callers accumulate the fragment
     * strings and call [isMetadataTerminator] to detect the end of the sequence.
     *
     * @return Pair of (page index, JSON fragment string).
     * @throws IllegalArgumentException if [body] is shorter than 2 bytes.
     */
    fun parseMetadataPage(body: ByteArray): Pair<Int, String> {
        require(body.size >= 2) { "METADATA GET response too short: ${body.size} bytes" }
        val page = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val fragmentBytes = body.copyOfRange(2, body.size)
        // Trim trailing NUL — the device appends 0x00 on the final page.
        val end = if (fragmentBytes.isNotEmpty() && fragmentBytes.last().toInt() == 0) {
            fragmentBytes.size - 1
        } else {
            fragmentBytes.size
        }
        val fragment = String(fragmentBytes.copyOfRange(0, end), Charsets.US_ASCII)
        return page to fragment
    }

    /**
     * True if [body] represents the terminating page of a METADATA GET sequence.
     *
     * Two terminator conditions (reference: data/index.js FileHandler.getMetadata loop):
     *  (a) body.size <= 2 — empty or page-only response, no JSON fragment.
     *  (b) body.last() == 0x00 — last byte is NUL, signalling the final page.
     */
    fun isMetadataTerminator(body: ByteArray): Boolean =
        body.size <= 2 || body.last().toInt() == 0
}
