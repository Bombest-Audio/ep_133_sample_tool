import XCTest

/// UI-branch pinning for the debug-only Simulated EP-133 toggle. XCUITest port of
/// `DeviceSimToggleTest.kt`.
///
/// In a DEBUG build `SimulatedPortProvider.available` is compile-time true, so the toggle is always
/// wired — the same condition the Kotlin `wireToggle = true` case sets up. Flipping it swaps the
/// repository onto a fresh simulator, so the observable result of "callback invoked with true" is
/// the switch turning on (and the device coming online).
final class DeviceSimToggleTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testOffline_withCallbackWired_toggleDisplayed_flipInvokesCallbackAndTurnsOn() {
        // Offline (inert port, no hardware); the debug toggle is wired as in MainActivity.
        let app = XCUIApplication()
        app.launchForUITest()
        let robot = AppRobot(app).goToDevice()

        robot.assertOfflineState()
            .assertSimToggleDisplayed()
            .assertSimToggleOff()
            .flipSimToggle()

        // The switch reflects simModeActive; flipping on swaps in the simulated device.
        robot.assertSimToggleOn()
    }

    func testOffline_withoutCallback_toggleAbsent() throws {
        // The toggle-absent branch is the Release / provider-unavailable path
        // (onSimToggle == nil). A DEBUG UI-test build has SimulatedPortProvider.available == true
        // at compile time, so the toggle is always present and this branch can't be produced
        // out-of-process. It stays covered in-process by the ViewModel-level toggle tests.
        throw XCTSkip("The toggle-absent branch requires provider-unavailable (Release); unreachable in a DEBUG UI-test build.")
    }
}
