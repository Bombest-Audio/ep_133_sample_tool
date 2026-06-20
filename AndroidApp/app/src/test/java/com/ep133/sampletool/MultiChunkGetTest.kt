package com.ep133.sampletool

import org.junit.Test
import org.junit.Assert.*
import java.util.zip.CRC32

/**
 * Wave 0 scaffold for the paged FILE_GET (INIT → DATA loop) assembly.
 *
 * Covers (when filled by Wave 1):
 *  - DATA loop accumulates fileSize bytes across pages
 *  - page mismatch throws, empty data terminates
 *  - CRC32 verification across chunks
 *
 * The placeholder below is a real CRC32 assertion (java.util.zip.CRC32 exists in
 * the JVM today) so the class compiles, runs, and is green now. The page-loop /
 * page-mismatch / empty-terminator assertions are filled once the paged GET lands.
 *
 * TODO(04-project-management-02): replace the CRC placeholder with real paged-GET
 * loop assertions (assemble fileSize bytes, page-mismatch throws, empty terminates).
 */
class MultiChunkGetTest {

    @Test
    fun crc32_ofKnownBlob_matchesExpected() {
        // TODO(04-project-management-02): replace placeholder with real paged-GET assertions
        // Wave 1 accumulates DATA-page chunks and CRC32-verifies the assembled archive.
        val blob = "EP133".toByteArray(Charsets.US_ASCII)
        // CRC32 of the ASCII bytes "EP133" — deterministic, computed independently.
        assertEquals(0xF32EA407L, crcOf(blob))
    }

    private fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
