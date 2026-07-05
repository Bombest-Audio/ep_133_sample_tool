package com.ep133.sampletool

import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.domain.firmware.LATEST_KNOWN_FIRMWARE
import com.ep133.sampletool.domain.firmware.TeFirmwareCatalog
import com.ep133.sampletool.domain.firmware.floorToBundled
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for TeFirmwareCatalog.parseVersions() and the floorToBundled() helper.
 *
 * All tests use inline HTML fixture strings — no network, no file I/O.
 * The fixture format mirrors realistic TE downloads page fragments.
 */
class TeFirmwareCatalogParserTest {

    // ── Realistic TE page fixture ──────────────────────────────────────────────

    /**
     * A representative excerpt from the TE downloads page. Contains:
     * - A release date token: "2026.06.24" (must be excluded — major=2026 > 50)
     * - Valid firmware version tokens in various positions: 2.5, 2.0.5, 2.0.2, 1.2.3
     */
    private val realisticFixture = """
        <html>
        <body>
          <div class="downloads">
            <h3>EP-133 firmware 2.5</h3>
            <p>Released 2026.06.24</p>
            <ul>
              <li>version 2.5 — see changelog</li>
              <li>version 2.0.5 — previous stable</li>
              <li>version 2.0.2 — older release</li>
              <li>version 1.2.3 — legacy</li>
            </ul>
          </div>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun parseVersions_realisticFixture_excludesDateAndReturnsLatest() {
        // 2026.06.24 must be excluded (major=2026 > 50); 2.5 is the winner
        val result = TeFirmwareCatalog.parseVersions(realisticFixture)
        assertEquals(FirmwareVersion.parse("2.5"), result)
    }

    @Test
    fun parseVersions_dateOnlyFixture_returnsNull() {
        // Only a date token — nothing survives the major>50 filter
        val html = "<p>Released 2026.06.24</p>"
        assertNull(TeFirmwareCatalog.parseVersions(html))
    }

    @Test
    fun parseVersions_singleValidVersion_returnsThatVersion() {
        val html = "<p>firmware version 1.2.3 available</p>"
        assertEquals(FirmwareVersion.parse("1.2.3"), TeFirmwareCatalog.parseVersions(html))
    }

    @Test
    fun parseVersions_emptyString_returnsNull() {
        assertNull(TeFirmwareCatalog.parseVersions(""))
    }

    @Test
    fun parseVersions_majorAbove50_excluded() {
        // 51.0 has major=51 > 50 — must be excluded
        val html = "version 51.0 is not a real firmware"
        assertNull(TeFirmwareCatalog.parseVersions(html))
    }

    @Test
    fun parseVersions_majorExactly50_notExcluded() {
        // 50.0 has major=50 which is NOT > 50 — borderline value must pass through
        val html = "version 50.0"
        assertEquals(FirmwareVersion(listOf(50, 0)), TeFirmwareCatalog.parseVersions(html))
    }

    @Test
    fun parseVersions_multipleValidVersions_returnsMax() {
        val html = "versions: 1.2.3, 2.0.2, 2.0.5, 2.5"
        assertEquals(FirmwareVersion.parse("2.5"), TeFirmwareCatalog.parseVersions(html))
    }

    @Test
    fun parseVersions_dateAndOlderVersion_returnsOlderVersion() {
        // 2025.12.01 is a date (excluded), 1.2.3 is valid — 1.2.3 wins
        val html = "released 2025.12.01, firmware 1.2.3"
        assertEquals(FirmwareVersion.parse("1.2.3"), TeFirmwareCatalog.parseVersions(html))
    }

    // ── floorToBundled() tests ─────────────────────────────────────────────────

    @Test
    fun floorToBundled_scrapedOlderThanBundled_returnsBundled() {
        // 2.3 < 2.5 (LATEST_KNOWN_FIRMWARE) → bundled wins
        val scraped = FirmwareVersion.parse("2.3")
        val result = floorToBundled(scraped)
        assertEquals("Scraped version older than bundled must return bundled", LATEST_KNOWN_FIRMWARE, result)
    }

    @Test
    fun floorToBundled_scrapedNewerThanBundled_returnsScraped() {
        // 2.6 > 2.5 → scraped wins
        val scraped = FirmwareVersion.parse("2.6")
        val result = floorToBundled(scraped)
        assertEquals("Scraped version newer than bundled must return scraped", FirmwareVersion.parse("2.6"), result)
    }

    @Test
    fun floorToBundled_nullScraped_returnsBundledFallback() {
        // Network failure / empty scrape → must fall back to 2.5 (FW-02 guarantee)
        val result = floorToBundled(null)
        assertEquals(
            "Null scraped (network failure) must fall back to LATEST_KNOWN_FIRMWARE (2.5)",
            FirmwareVersion.parse("2.5"),
            result,
        )
    }

    @Test
    fun floorToBundled_scrapedEqualToBundled_returnsBundled() {
        val scraped = FirmwareVersion.parse("2.5")
        val result = floorToBundled(scraped)
        assertEquals(LATEST_KNOWN_FIRMWARE, result)
    }

    @Test
    fun latestKnownFirmwareConstant_is2point5() {
        assertEquals(FirmwareVersion.parse("2.5"), LATEST_KNOWN_FIRMWARE)
    }
}
