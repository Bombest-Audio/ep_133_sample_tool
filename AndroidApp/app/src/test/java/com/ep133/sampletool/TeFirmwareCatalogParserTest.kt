package com.ep133.sampletool

import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.domain.firmware.LATEST_KNOWN_FIRMWARE
import com.ep133.sampletool.domain.firmware.floorToBundled
import com.ep133.sampletool.domain.firmware.parseReleases
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for parseReleases() and the floorToBundled() helper.
 *
 * All tests use inline JSON strings — no network, no file I/O. The fixture mirrors TE's real
 * release manifest (https://teenage.engineering/_software/releases.json): a `devices` array of flat
 * { sku, version, fw_url, link, release_notes } records. The EP-133 SKU is TE032AS001.
 */
class TeFirmwareCatalogParserTest {

    // A trimmed copy of TE's real manifest shape — several devices, EP-133 in the middle.
    private val manifest = """
        {"devices":[
          {"sku":"TE028AS001","version":"1.3.3","fw_url":"https://teenage.engineering/_software/tx-6/tx-6_firmware_1_3_3.tfw","link":"x","release_notes":"- usb audio stability"},
          {"sku":"TE032AS001","version":"2.5.0","fw_url":"https://teenage.engineering/_software/ep-133/ep-133_firmware_2_5_0.tfw","link":"https://teenage.engineering/downloads/ep-133","release_notes":"- summer update"},
          {"sku":"TE025AS001","version":"1.1.11","fw_url":"https://teenage.engineering/_software/tp-7/tp-7_firmware_1_1_11.tfw","link":"x","release_notes":"- fixes"}
        ]}
    """.trimIndent()

    // ── parseReleases() ────────────────────────────────────────────────────────

    @Test
    fun parseReleases_picksEp133BySku_notFirstDevice() {
        // EP-133 (TE032AS001) is the *second* device; a naive "first version" parse would wrongly
        // return the TX-6's 1.3.3. Matching by SKU must return 2.5.0.
        assertEquals(FirmwareVersion.parse("2.5.0"), parseReleases(manifest))
    }

    @Test
    fun parseReleases_ep133Absent_returnsNull() {
        val json = """{"devices":[{"sku":"TE028AS001","version":"1.3.3","release_notes":"x"}]}"""
        assertNull(parseReleases(json))
    }

    @Test
    fun parseReleases_ep133PresentButBlankVersion_returnsNull() {
        val json = """{"devices":[{"sku":"TE032AS001","version":"","release_notes":"x"}]}"""
        assertNull(parseReleases(json))
    }

    @Test
    fun parseReleases_ep133NonVersion_returnsNull() {
        val json = """{"devices":[{"sku":"TE032AS001","version":"coming-soon"}]}"""
        assertNull(parseReleases(json))
    }

    @Test
    fun parseReleases_garbageBody_returnsNull() {
        assertNull(parseReleases("<html>not json</html>"))
    }

    @Test
    fun parseReleases_emptyBody_returnsNull() {
        assertNull(parseReleases(""))
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
        // Network failure / unreachable manifest → must fall back to 2.5 (the FW-02 guarantee).
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
