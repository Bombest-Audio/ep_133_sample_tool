package com.ep133.sampletool

import com.ep133.sampletool.domain.firmware.FirmwareVersion
import org.junit.Test
import org.junit.Assert.*

class FirmwareVersionTest {

    @Test
    fun parse_validTwoPart_returnsFirmwareVersion() {
        val v = FirmwareVersion.parse("2.5")
        assertNotNull(v)
        assertEquals(FirmwareVersion(listOf(2, 5)), v)
    }

    @Test
    fun parse_validThreePart_returnsFirmwareVersion() {
        val v = FirmwareVersion.parse("2.0.5")
        assertNotNull(v)
        assertEquals(FirmwareVersion(listOf(2, 0, 5)), v)
    }

    @Test
    fun parse_null_returnsNull() {
        assertNull(FirmwareVersion.parse(null))
    }

    @Test
    fun parse_emptyString_returnsNull() {
        assertNull(FirmwareVersion.parse(""))
    }

    @Test
    fun parse_blankString_returnsNull() {
        assertNull(FirmwareVersion.parse("  "))
    }

    @Test
    fun parse_nonNumeric_returnsNull() {
        assertNull(FirmwareVersion.parse("abc"))
    }

    @Test
    fun parse_mixedNonNumeric_returnsNull() {
        assertNull(FirmwareVersion.parse("2.x.5"))
    }

    @Test
    fun compare_zeroPaddingRule_twoFiveGreaterThanTwoZeroFive() {
        // 2.5 → [2,5,0] padded; 2.0.5 → [2,0,5]; at index 1: 5 > 0
        val v25 = FirmwareVersion.parse("2.5")!!
        val v205 = FirmwareVersion.parse("2.0.5")!!
        assertTrue("2.5 must be > 2.0.5 (zero-padding rule)", v25 > v205)
    }

    @Test
    fun compare_twoZeroFiveGreaterThanTwoZeroTwo() {
        val v205 = FirmwareVersion.parse("2.0.5")!!
        val v202 = FirmwareVersion.parse("2.0.2")!!
        assertTrue("2.0.5 must be > 2.0.2", v205 > v202)
    }

    @Test
    fun compare_twoFiveEqualsWhenPaddedToTwoFiveZero() {
        // 2.5 == 2.5.0 after zero-padding
        val v25 = FirmwareVersion.parse("2.5")!!
        val v250 = FirmwareVersion.parse("2.5.0")!!
        assertEquals("2.5 and 2.5.0 must be equal after zero-padding", 0, v25.compareTo(v250))
    }

    @Test
    fun compare_knownTeHistoryOrder() {
        // Known TE version history (descending): 2.5 > 2.0.5 > 2.0.2 > 2.0.1 > 2.0 > 1.2.3
        val versions = listOf("2.5", "2.0.5", "2.0.2", "2.0.1", "2.0", "1.2.3")
            .mapNotNull { FirmwareVersion.parse(it) }
        assertEquals("All 6 versions must parse", 6, versions.size)
        for (i in 0 until versions.size - 1) {
            assertTrue(
                "${versions[i]} must be > ${versions[i + 1]}",
                versions[i] > versions[i + 1],
            )
        }
    }
}
