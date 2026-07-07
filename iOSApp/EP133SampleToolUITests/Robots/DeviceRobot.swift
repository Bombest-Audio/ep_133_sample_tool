import XCTest

/// Robot for the Device screen: connection/permission states, stats, firmware banner, scale mode,
/// backup, and the debug Simulated EP-133 toggle. XCUITest port of `DeviceRobot.kt`.
///
/// Out-of-process caveat: the Kotlin robot's callback-list assertions (e.g. verifying a SAF request
/// fired) live in the test harness there. Here those seams are driven by launch args and asserted
/// through what the user sees, so this robot exposes only the UI-observable operations.
final class DeviceRobot: BaseRobot {

    // ── Connection / permission states ──

    @discardableResult
    func assertOfflineState() -> DeviceRobot {
        assertTextDisplayed("no device")
        return self
    }

    @discardableResult
    func assertPermissionAction(_ label: String) -> DeviceRobot {
        assertTagDisplayed(TestTags.DEVICE_PERMISSION_ACTION)
        // Ghost buttons render their label as-is; match case-insensitively like the Kotlin robot.
        let action = element(TestTags.DEVICE_PERMISSION_ACTION)
        XCTAssertTrue(
            action.label.localizedCaseInsensitiveContains(label),
            "permission action should read '\(label)' (was '\(action.label)')")
        return self
    }

    @discardableResult
    func clickPermissionAction() -> DeviceRobot {
        element(TestTags.DEVICE_PERMISSION_ACTION).tap()
        return self
    }

    // ── Connected stats ──

    @discardableResult
    func assertStatsVisible() -> DeviceRobot {
        assertTagDisplayed(TestTags.DEVICE_STATS_GRID)
        return self
    }

    /// Assert a readout value inside the stats grid (disambiguates from the connection card). The
    /// stats query runs the full FILE_INIT handshake on appear, so allow the longer settle window.
    @discardableResult
    func assertStatInGrid(_ value: String) -> DeviceRobot {
        let grid = waitForTag(TestTags.DEVICE_STATS_GRID, timeout: DeviceRobot.firmwareTimeout)
        let match = grid.staticTexts[value]
        XCTAssertTrue(
            match.waitForExistence(timeout: DeviceRobot.firmwareTimeout),
            "expected stat '\(value)' inside the stats grid")
        return self
    }

    // ── Firmware banner ──

    /// The firmware check runs serially after the on-appear stats query (FILE_INIT handshake ->
    /// queryDeviceStats -> catalog.latestVersion), so the banner can take noticeably longer than a
    /// plain element to appear over the simulated wire. Wait generously.
    private static let firmwareTimeout: TimeInterval = 25

    /// Block until the connected stats have loaded, proven by the firmware readout appearing in the
    /// stats grid. Anchors the banner-absence assertions so they don't race a still-loading screen.
    @discardableResult
    func waitForStatsLoaded(firmware: String) -> DeviceRobot {
        let grid = waitForTag(TestTags.DEVICE_STATS_GRID, timeout: DeviceRobot.firmwareTimeout)
        XCTAssertTrue(
            grid.staticTexts[firmware].waitForExistence(timeout: DeviceRobot.firmwareTimeout),
            "expected firmware '\(firmware)' in the stats grid once stats loaded")
        return self
    }

    @discardableResult
    func assertFirmwareBanner(_ latest: String) -> DeviceRobot {
        let banner = element(TestTags.DEVICE_FIRMWARE_BANNER)
        XCTAssertTrue(
            banner.waitForExistence(timeout: DeviceRobot.firmwareTimeout),
            "expected the firmware update banner")
        assertTextDisplayed("Firmware \(latest) available", timeout: DeviceRobot.firmwareTimeout)
        return self
    }

    @discardableResult
    func assertNoFirmwareBanner() -> DeviceRobot {
        // The absence check is only meaningful after the firmware check has had a chance to run.
        assertTagAbsent(TestTags.DEVICE_FIRMWARE_BANNER)
        return self
    }

    @discardableResult
    func clickOpenUpdater() -> DeviceRobot {
        // "Open updater" is a Button (carries the banner tag), not a static text.
        app.buttons["Open updater"].tap()
        return self
    }

    // ── Backup / restore ──

    @discardableResult
    func clickBackup() -> DeviceRobot {
        // The backup entry point on iOS opens the embedded web tool (see DeviceScreen adaptation).
        staticText("Open Sample Tool").tap()
        return self
    }

    // ── Simulated EP-133 toggle (debug builds only) ──

    /// The toggle is a Switch; read its value through the switches query for on/off state.
    private var simToggle: XCUIElement {
        app.switches[TestTags.DEVICE_SIM_TOGGLE]
    }

    @discardableResult
    func assertSimToggleDisplayed() -> DeviceRobot {
        XCTAssertTrue(
            simToggle.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "expected the simulated EP-133 toggle to be displayed")
        return self
    }

    @discardableResult
    func assertSimToggleAbsent() -> DeviceRobot {
        XCTAssertFalse(simToggle.exists, "expected no simulated EP-133 toggle")
        return self
    }

    @discardableResult
    func flipSimToggle() -> DeviceRobot {
        simToggle.tap()
        return self
    }

    @discardableResult
    func assertSimToggleOn() -> DeviceRobot {
        // A UISwitch reports its value as "1" when on.
        XCTAssertEqual(simToggle.value as? String, "1", "expected the sim toggle to be on")
        return self
    }

    @discardableResult
    func assertSimToggleOff() -> DeviceRobot {
        XCTAssertEqual(simToggle.value as? String, "0", "expected the sim toggle to be off")
        return self
    }
}
