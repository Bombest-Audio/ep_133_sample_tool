import XCTest

/// E2E scale-lock flow: a scale + root chosen on the Device screen flow through the shared
/// MIDIRepository state and re-skin the Pads grid. XCUITest port of `ScaleLockFlowTest.kt`.
///
/// Pitch-class math is covered by the computeInScaleSet unit tests; this asserts the cross-screen
/// UX. Pad index → pitch class (group A): index 9 = "A." = note 36 (pc 0), index 10 = "A0" =
/// note 37 (pc 1), index 0 = "A7" = note 45 (pc 9).
///
/// The Compose test drives a fake connected repository; here the connected device is the EP-133
/// simulator (`-EP133UITestSimPort`), which is what mounts the Device screen's scale controls.
final class ScaleLockFlowTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchConnected() -> AppRobot {
        let app = XCUIApplication()
        app.launchForUITest(simPort: true)
        return AppRobot(app)
    }

    func testScaleSelectedOnDevice_highlightsInScalePadsOnPads() {
        let app = launchConnected()
        // C Major on the Device screen.
        app.goToDevice().selectScale("Major")
        // pc 0 ("A.") in scale, pc 1 ("A0") out, pc 9 ("A7") in scale.
        app.goToPads()
            .assertPadState(9, TestTags.PAD_STATE_IN_SCALE)
            .assertPadState(10, TestTags.PAD_STATE_IDLE)
            .assertPadState(0, TestTags.PAD_STATE_IN_SCALE)
    }

    func testRootNoteChange_recomputesInScalePads() {
        let app = launchConnected()
        // D Major.
        app.goToDevice()
            .selectScale("Major")
            .selectRootNote("D")
        // pc 1 ("A0") now in scale, pc 0 ("A.") out.
        app.goToPads()
            .assertPadState(10, TestTags.PAD_STATE_IN_SCALE)
            .assertPadState(9, TestTags.PAD_STATE_IDLE)
    }

    func testClearingScale_returnsAllPadsToIdle() {
        let app = launchConnected()
        app.goToDevice().selectScale("Major")
        app.goToPads().assertPadState(9, TestTags.PAD_STATE_IN_SCALE)
        app.goToDevice().selectScale("None")
        let pads = app.goToPads()
        for i in 0..<12 { pads.assertPadState(i, TestTags.PAD_STATE_IDLE) }
    }
}
