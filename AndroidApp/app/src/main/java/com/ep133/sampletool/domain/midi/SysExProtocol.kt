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
}
