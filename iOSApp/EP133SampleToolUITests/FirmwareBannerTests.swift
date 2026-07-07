import XCTest

/// Firmware update banner rendering per FirmwareUpdateState. XCUITest port of `FirmwareBannerTest.kt`.
///
/// The Kotlin test composes `DeviceScreen` with a pre-seeded firmware version and a
/// `FakeFirmwareCatalog`. Out-of-process, the device firmware comes from the simulated EP-133
/// (`EP133_FW_DEVICE`) and the catalog's latest version from the injected `StubFirmwareCatalog`
/// (`EP133_FW_LATEST`, or "none" for an unavailable catalog).
final class FirmwareBannerTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchConnected(deviceFirmware: String, latest: String) -> DeviceRobot {
        let app = XCUIApplication()
        app.launchForUITest(simPort: true, deviceFirmware: deviceFirmware, latestFirmware: latest)
        return AppRobot(app).goToDevice()
    }

    func testOlderFirmware_showsUpdateBannerAndOpensUpdater() {
        let robot = launchConnected(deviceFirmware: "2.0.5", latest: "2.5")
        // Banner names the newer version.
        robot.assertFirmwareBanner("2.5")
        // The updater control is present and tappable. Adaptation: the Kotlin test asserts the
        // onOpenFirmwareUpdater callback fired; here tapping opens an external URL (Safari), which
        // isn't a deterministic in-app assertion, so we verify the control exists instead.
        XCTAssertTrue(
            robot.app.buttons["Open updater"].waitForExistence(timeout: BaseRobot.defaultTimeout),
            "expected the Open updater control on the update banner")
    }

    func testCurrentFirmware_showsNoBanner() {
        // Wait for the firmware check to run (stats loaded) before asserting the banner never shows.
        launchConnected(deviceFirmware: "2.5", latest: "2.5")
            .waitForStatsLoaded(firmware: "2.5")
            .assertNoFirmwareBanner()
    }

    func testUnparseableFirmware_showsNoBanner() {
        // "garbage" is an unparseable firmware; the grid shows it verbatim once stats load.
        launchConnected(deviceFirmware: "garbage", latest: "2.5")
            .waitForStatsLoaded(firmware: "garbage")
            .assertNoFirmwareBanner()
    }

    func testCatalogUnavailable_showsNoBanner() {
        // "none" → StubFirmwareCatalog(latest: nil) → Unknown state → no banner.
        launchConnected(deviceFirmware: "2.0.5", latest: "none")
            .waitForStatsLoaded(firmware: "2.0.5")
            .assertNoFirmwareBanner()
    }

    func testWhileChecking_showsSpinnerThenBanner() throws {
        // Not reproducible out-of-process: the Kotlin test gates the catalog with a
        // CompletableDeferred to hold the transient "CHECKING FIRMWARE…" state open. The injected
        // StubFirmwareCatalog resolves immediately and there's no cross-process gate, so the
        // Checking frame can't be reliably observed. The final banner is covered by
        // testOlderFirmware_showsUpdateBannerAndOpensUpdater.
        throw XCTSkip("The transient Checking state needs a gated catalog, which isn't reachable across the process boundary.")
    }
}
