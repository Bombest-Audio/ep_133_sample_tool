import XCTest
@testable import EP133SampleTool

/// Unit tests for parseReleases() and the floorToBundled() helper.
///
/// All tests use inline JSON strings — no network, no file I/O. The fixture mirrors TE's real
/// release manifest (https://teenage.engineering/_software/releases.json): a `devices` array of
/// flat { sku, version, fw_url, link, release_notes } records. The EP-133 SKU is TE032AS001.
///
/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/TeFirmwareCatalogParserTest.kt —
/// every test method ports 1:1 (XCTest requires the `test` prefix).
final class TeFirmwareCatalogParserTests: XCTestCase {

    // A trimmed copy of TE's real manifest shape — several devices, EP-133 in the middle.
    private let manifest = """
        {"devices":[
          {"sku":"TE028AS001","version":"1.3.3","fw_url":"https://teenage.engineering/_software/tx-6/tx-6_firmware_1_3_3.tfw","link":"x","release_notes":"- usb audio stability"},
          {"sku":"TE032AS001","version":"2.5.0","fw_url":"https://teenage.engineering/_software/ep-133/ep-133_firmware_2_5_0.tfw","link":"https://teenage.engineering/downloads/ep-133","release_notes":"- summer update"},
          {"sku":"TE025AS001","version":"1.1.11","fw_url":"https://teenage.engineering/_software/tp-7/tp-7_firmware_1_1_11.tfw","link":"x","release_notes":"- fixes"}
        ]}
        """

    // ── parseReleases() ────────────────────────────────────────────────────────

    func test_parseReleases_picksEp133BySku_notFirstDevice() {
        // EP-133 (TE032AS001) is the *second* device; a naive "first version" parse would wrongly
        // return the TX-6's 1.3.3. Matching by SKU must return 2.5.0.
        XCTAssertEqual(FirmwareVersion.parse("2.5.0"), parseReleases(manifest))
    }

    func test_parseReleases_ep133Absent_returnsNull() {
        let json = #"{"devices":[{"sku":"TE028AS001","version":"1.3.3","release_notes":"x"}]}"#
        XCTAssertNil(parseReleases(json))
    }

    func test_parseReleases_ep133PresentButBlankVersion_returnsNull() {
        let json = #"{"devices":[{"sku":"TE032AS001","version":"","release_notes":"x"}]}"#
        XCTAssertNil(parseReleases(json))
    }

    func test_parseReleases_ep133NonVersion_returnsNull() {
        let json = #"{"devices":[{"sku":"TE032AS001","version":"coming-soon"}]}"#
        XCTAssertNil(parseReleases(json))
    }

    func test_parseReleases_garbageBody_returnsNull() {
        XCTAssertNil(parseReleases("<html>not json</html>"))
    }

    func test_parseReleases_emptyBody_returnsNull() {
        XCTAssertNil(parseReleases(""))
    }

    // ── floorToBundled() ───────────────────────────────────────────────────────

    func test_floorToBundled_fetchedOlderThanBundled_returnsBundled() {
        XCTAssertEqual(
            LATEST_KNOWN_FIRMWARE,
            floorToBundled(FirmwareVersion.parse("2.3")),
            "Fetched version older than bundled must return bundled"
        )
    }

    func test_floorToBundled_fetchedNewerThanBundled_returnsFetched() {
        XCTAssertEqual(
            FirmwareVersion.parse("2.6"),
            floorToBundled(FirmwareVersion.parse("2.6")),
            "Fetched version newer than bundled must return fetched"
        )
    }

    func test_floorToBundled_nullFetched_returnsBundledFallback() {
        // Network failure / unreachable manifest → must fall back to 2.5 (the FW-02 guarantee).
        XCTAssertEqual(
            FirmwareVersion.parse("2.5"),
            floorToBundled(nil),
            "Nil fetched (no manifest) must fall back to LATEST_KNOWN_FIRMWARE (2.5)"
        )
    }

    func test_floorToBundled_fetchedEqualToBundled_returnsBundled() {
        XCTAssertEqual(LATEST_KNOWN_FIRMWARE, floorToBundled(FirmwareVersion.parse("2.5")))
    }

    func test_latestKnownFirmwareConstant_is2point5() {
        XCTAssertEqual(FirmwareVersion.parse("2.5"), LATEST_KNOWN_FIRMWARE)
    }
}
