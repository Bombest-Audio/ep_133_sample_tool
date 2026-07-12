import XCTest

/// App shell: bottom-tab navigation, header title/connection indicator, SKU + theme toggles, and
/// cross-tab state retention. XCUITest port of `AppNavigationTest.kt`.
///
/// Out-of-process adaptation: the Kotlin `connectionIndicator_reflectsDeviceState` drives a
/// NO DEVICE -> CONNECTED transition mid-test by mutating a ScriptedMIDIRepository. XCUITest can't
/// reach into the app process to flip state, so that case launches already connected (sim port)
/// and asserts the indicator reflects the connected device.
final class AppNavigationTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testDefaultTab_isPadsWithTitle() {
        let app = XCUIApplication()
        app.launchForUITest()
        AppRobot(app)
            .assertTitle("PADS")
            .goToPads() // no-op navigation, but proves the grid renders
            .assertGridVisible()
    }

    func testTabSwitch_updatesHeaderTitlePerScreen() {
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app)
        robot.goToProjects()
        robot.assertTitle("PROJ")
        robot.goToDevice()
        robot.assertTitle("DEVICE")
        robot.goToKit()
        robot.assertTitle("SAMPLES")
        robot.goToPads()
        robot.assertTitle("PADS")
    }

    func testTabSwitch_showsEachScreenBody() {
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app)
        robot.goToProjects().assertOfflinePanel()
        robot.goToDevice().assertOfflineState()
        robot.goToKit().assertModeChipsVisible()
        robot.goToPads().assertPadLabel("A.")
    }

    func testGroupSelection_survivesTabRoundTrip() {
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app)
        // Select group B, bounce through Device, come back.
        robot.goToPads().selectGroup("B").assertPadLabel("B.")
        robot.goToDevice()
        robot.goToPads().assertPadLabel("B.")
    }

    func testConnectionIndicator_reflectsDeviceState() {
        // Adaptation: launch already connected (simulated EP-133) and assert the indicator.
        let app = XCUIApplication()
        app.launchForUITest(simPort: true)
        AppRobot(app).assertConnectionLabel("CONNECTED")
    }

    func testSkuBadge_togglesBetweenModels() {
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app)
        robot.assertSkuBadge("EP·133")
        robot.toggleSkuBadge().assertSkuBadge("EP·1320")
        robot.toggleSkuBadge().assertSkuBadge("EP·133")
    }

    func testThemeToggle_cyclesWithoutBreakingLayout() {
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app)
        // Cycle system -> light -> dark -> system.
        robot.cycleTheme().assertHeaderVisible()
        robot.cycleTheme().assertHeaderVisible()
        robot.cycleTheme().assertHeaderVisible()
    }
}
