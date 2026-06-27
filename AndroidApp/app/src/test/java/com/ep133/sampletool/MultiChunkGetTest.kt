package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.midi.SysExProtocol.GetDataResponse
import org.junit.Test
import org.junit.Assert.*
import java.util.zip.CRC32

/**
 * Paged FILE_GET assembly: drives the pure [SysExProtocol.assembleGetPages] loop with a
 * fake page supplier — no device, no coroutine timing. Covers fileSize accumulation across
 * pages, page-mismatch detection, empty-data termination, and a CRC32 integrity guard.
 */
class MultiChunkGetTest {

    /** Build a fake supplier that returns fixed-size pages slicing [blob]. */
    private fun pagedSupplier(blob: ByteArray, pageSize: Int): (Int) -> GetDataResponse {
        val pages = blob.toList().chunked(pageSize).map { it.toByteArray() }
        return { page ->
            val data = pages.getOrNull(page) ?: ByteArray(0)
            GetDataResponse(page, data)
        }
    }

    @Test
    fun assemblesFileSizeAcrossPages() {
        val blob = ByteArray(1000) { (it and 0xFF).toByte() }
        val assembled = SysExProtocol.assembleGetPages(blob.size, pagedSupplier(blob, 256))
        assertArrayEquals(blob, assembled)
    }

    @Test
    fun assemblesExactSizeWhenLastPagePartial() {
        val blob = ByteArray(777) { (it * 3 and 0xFF).toByte() }
        val assembled = SysExProtocol.assembleGetPages(blob.size, pagedSupplier(blob, 100))
        assertEquals(777, assembled.size)
        assertArrayEquals(blob, assembled)
    }

    @Test
    fun pageMismatchThrows() {
        // Supplier lies about the page number on the second request.
        val supplier: (Int) -> GetDataResponse = { page ->
            if (page == 0) GetDataResponse(0, ByteArray(256) { 1 })
            else GetDataResponse(99, ByteArray(256) { 2 })   // expected 1, got 99
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            SysExProtocol.assembleGetPages(1024, supplier)
        }
        assertTrue(ex.message!!.contains("unexpected page"))
    }

    @Test
    fun emptyDataTerminatesEvenBelowFileSize() {
        // Declare a large fileSize but return an empty page after one real page.
        val supplier: (Int) -> GetDataResponse = { page ->
            when (page) {
                0 -> GetDataResponse(0, ByteArray(64) { 7 })
                else -> GetDataResponse(page, ByteArray(0))   // EOF
            }
        }
        val assembled = SysExProtocol.assembleGetPages(100_000, supplier)
        assertEquals(64, assembled.size)
    }

    @Test
    fun oversizedPageAborts() {
        // A malformed/oversized DATA page that alone exceeds fileSize + slack must abort
        // the accumulation rather than corrupt the buffer (threat T-04-03).
        val supplier: (Int) -> GetDataResponse = { page ->
            GetDataResponse(page, ByteArray(SysExProtocol.MAX_PAGE_BYTES * 4) { 1 })
        }
        val ex = assertThrows(IllegalStateException::class.java) {
            SysExProtocol.assembleGetPages(100, supplier)
        }
        assertTrue(ex.message!!.contains("overflow"))
    }

    @Test
    fun assembledArchiveCrc32Matches() {
        val blob = "EP133".toByteArray(Charsets.US_ASCII)
        val assembled = SysExProtocol.assembleGetPages(blob.size, pagedSupplier(blob, 2))
        val crc = CRC32().apply { update(assembled) }.value
        assertEquals(0xF32EA407L, crc)
    }
}
