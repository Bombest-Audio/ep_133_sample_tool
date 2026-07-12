import XCTest

/// Device screen UI: the offline permission branches, the connected stats panel, and the
/// scale/channel controls. XCUITest port of `DeviceScreenTest.kt`.
///
/// The Kotlin test composes `DeviceScreen(viewModel)` directly with a Harness recording SAF
/// callbacks. XCUITest launches the whole app and navigates to the Device tab; offline permission
/// states are seeded via `EP133_PERMISSION`, and the connected panel is driven by the simulated
/// EP-133 (`-EP133UITestSimPort`). The connected stats assert the simulator's own deterministic
/// readouts (a freshly booted sim reports firmware 2.0.5, 0 samples, 0/59 MB) rather than the
/// Android-injected 42 / 12 / 64, since exact stat injection isn't part of the minimum hook set.
final class DeviceScreenTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchOffline(permission: String) -> DeviceRobot {
        let app = XCUIApplication()
        app.launchForUITest(permission: permission)
        return AppRobot(app).goToDevice()
    }

    private func launchConnected() -> DeviceRobot {
        let app = XCUIApplication()
        app.launchForUITest(simPort: true, deviceFirmware: "2.0.5")
        return AppRobot(app).goToDevice()
    }

    // ── CONN-04: three-state offline UI ──

    func testOffline_unknownPermission_showsGrantPermission() {
        launchOffline(permission: "unknown")
            .assertOfflineState()
            .assertPermissionAction("Grant Permission")
    }

    func testOffline_awaitingPermission_showsSpinnerText() {
        let robot = launchOffline(permission: "awaiting")
        robot.assertOfflineState()
        robot.assertTextDisplayed("waiting for USB permission…")
    }

    func testOffline_deniedPermission_showsOpenSettings() {
        launchOffline(permission: "denied")
            .assertPermissionAction("Open Settings")
    }

    func testOffline_grantPermission_requestsDeviceRefresh() {
        let robot = launchOffline(permission: "unknown")
        // Grant Permission triggers a device refresh; with no device the screen stays offline.
        robot.clickPermissionAction()
        robot.assertOfflineState()
    }

    // ── Connected panel ──

    func testConnected_showsConnectionCard() {
        let robot = launchConnected()
        robot.assertTextDisplayed("CONNECTED OVER USB")
        robot.assertTextDisplayed("EP-133")
    }

    func testConnected_showsStatsFromDeviceState() {
        // A freshly booted simulator reports 0 samples, 0/59MB used, firmware 2.0.5.
        launchConnected()
            .assertStatsVisible()
            .assertStatInGrid("0")
            .assertStatInGrid("0/59MB")
            .assertStatInGrid("2.0.5")
    }

    func testConnected_statsPlaceholdersWhenNotQueried() throws {
        // Not reproducible out-of-process: a connected simulated device always answers the stats
        // query on appear, so the never-queried em-dash placeholder state can't be reached. The
        // placeholder rendering is covered in-process by the Compose/ViewModel-level tests.
        throw XCTSkip("Connected-but-unqueried stats state is unreachable via out-of-process launch hooks.")
    }

    func testConnected_showsChannelSelector() {
        let robot = launchConnected()
        robot.assertTextDisplayed("MIDI CHANNEL")
        robot.assertTextDisplayed("A")
        robot.assertTextDisplayed("D")
    }

    func testConnected_showsScaleModeSection() {
        launchConnected().assertTextDisplayed("SCALE MODE")
    }

    // ── Backup / restore ──

    func testBackupButton_firesSafRequestWithSuggestedName() throws {
        // iOS runs backup inside the embedded web tool (the SampleManagerActivity analog), not the
        // Android SAF create-document flow — there is no native .pak-name SAF request to observe
        // out-of-process. The web-tool entry point is exercised via clickBackup() in other flows.
        throw XCTSkip("iOS backup runs in the embedded web tool; there is no native SAF request to assert.")
    }

    func testRestoreButton_firesSafRequest() throws {
        // Same as above: the iOS Device screen has no standalone RESTORE button / SAF open-document
        // callback; restore is handled inside the embedded web tool.
        throw XCTSkip("iOS restore runs in the embedded web tool; there is no native SAF request to assert.")
    }
}
