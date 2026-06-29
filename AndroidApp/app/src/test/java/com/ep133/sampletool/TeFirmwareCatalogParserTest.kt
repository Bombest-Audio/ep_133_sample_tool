package com.ep133.sampletool

import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.domain.firmware.LATEST_KNOWN_FIRMWARE
import com.ep133.sampletool.domain.firmware.floorToBundled
import com.ep133.sampletool.domain.firmware.parseManifest
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for parseManifest() and the floorToBundled() helper.
 *
 * All tests use inline JSON strings — no network, no file I/O. The manifest format is the tiny
 * document hosted on the Bombest releases bucket: {"latest":"2.5"}.
 */
class TeFirmwareCatalogParserTest {

    // ── parseManifest() ────────────────────────────────────────────────────────

    @Test
    fun parseManifest_wellFormed_returnsLatest() {
        assertEquals(FirmwareVersion.parse("2.5"), parseManifest("""{"latest":"2.5"}"""))
    }

    @Test
    fun parseManifest_withExtraFieldsAndWhitespace_returnsLatest() {
        val json = """{ "latest" : "2.0.5", "released": "2025-12-03", "notes": "stable" }"""
        assertEquals(FirmwareVersion.parse("2.0.5"), parseManifest(json))
    }

    @Test
    fun parseManifest_missingLatestField_returnsNull() {
        assertNull(parseManifest("""{"version":"2.5"}"""))
    }

    @Test
    fun parseManifest_blankLatest_returnsNull() {
        assertNull(parseManifest("""{"latest":""}"""))
    }

    @Test
    fun parseManifest_nonVersionLatest_returnsNull() {
        assertNull(parseManifest("""{"latest":"coming-soon"}"""))
    }

    @Test
    fun parseManifest_garbageBody_returnsNull() {
        assertNull(parseManifest("<html>not json</html>"))
    }

    @Test
    fun parseManifest_emptyBody_returnsNull() {
        assertNull(parseManifest(""))
    }

    // ── floorToBundled() ───────────────────────────────────────────────────────

    @Test
    fun floorToBundled_fetchedOlderThanBundled_returnsBundled() {
        assertEquals(
            "Fetched version older than bundled must return bundled",
            LATEST_KNOWN_FIRMWARE,
            floorToBundled(FirmwareVersion.parse("2.3")),
        )
    }

    @Test
    fun floorToBundled_fetchedNewerThanBundled_returnsFetched() {
        assertEquals(
            "Fetched version newer than bundled must return fetched",
            FirmwareVersion.parse("2.6"),
            floorToBundled(FirmwareVersion.parse("2.6")),
        )
    }

    @Test
    fun floorToBundled_nullFetched_returnsBundledFallback() {
        // Network failure / missing manifest → must fall back to 2.5 (the FW-02 guarantee).
        assertEquals(
            "Null fetched (no manifest) must fall back to LATEST_KNOWN_FIRMWARE (2.5)",
            FirmwareVersion.parse("2.5"),
            floorToBundled(null),
        )
    }

    @Test
    fun floorToBundled_fetchedEqualToBundled_returnsBundled() {
        assertEquals(LATEST_KNOWN_FIRMWARE, floorToBundled(FirmwareVersion.parse("2.5")))
    }

    @Test
    fun latestKnownFirmwareConstant_is2point5() {
        assertEquals(FirmwareVersion.parse("2.5"), LATEST_KNOWN_FIRMWARE)
    }
}
