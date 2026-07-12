import XCTest

/// Pads screen UI: grid rendering, group switching, and pad visual state. XCUITest port of
/// `PadsScreenTest.kt`.
///
/// Out-of-process adaptations:
/// - The Compose test composes `PadsScreen(PadsViewModel(midi))` directly; here the whole app
///   launches and the default tab is already Pads, so no navigation is needed.
/// - `padPress_sendsNoteOnAndOffToDevice` inspects a ScriptedMIDIRepository's captured note
///   frames — unreachable out-of-process, so it's skipped (covered by the PadsViewModel unit
///   suite).
/// - `padPress_showsPressedStateAndClearsOnRelease` relies on holding one touch down and asserting
///   the pressed glow before releasing; XCUITest's `press(forDuration:)` completes the whole
///   down→up synchronously through the grid's UIKit multi-touch overlay, so the intermediate
///   held state can't be observed. Skipped with that reason; the state math is covered in-process.
final class PadsScreenTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchPads() -> PadsRobot {
        let app = XCUIApplication()
        app.launchForUITest()
        // Default tab is Pads; the no-op navigation also returns the PadsRobot.
        return AppRobot(app).goToPads()
    }

    func testPadGrid_displaysChannelALabels() {
        launchPads()
            .assertGridVisible()
            .assertPadLabel("A.")
            .assertPadLabel("A2")
            .assertPadLabel("A5")
    }

    func testBankSwitch_updatesGridToChannelB() {
        launchPads()
            .selectGroup("B")
            .assertPadLabel("B.")
            .assertPadLabel("B5")
    }

    func testBankSwitch_updatesGridToChannelD() {
        launchPads()
            .selectGroup("D")
            .assertPadLabel("D.")
            .assertPadLabel("DENT")
    }

    func testBankSwitch_channelSelectorShowsAllChannels() {
        let pads = launchPads()
        pads.assertPadLabel("A.")
        // All four group chips exist (shared with the Kit screen, so match the visible one).
        for group in ["A", "B", "C", "D"] {
            XCTAssertTrue(
                pads.hittableElement(TestTags.groupChip(group)).waitForExistence(timeout: 5),
                "group chip \(group) should exist")
        }
    }

    func testPadPress_showsPressedStateAndClearsOnRelease() throws {
        // The pressed glow is held only while a finger is down. XCUITest's press(forDuration:)
        // drives the grid's UIKit multi-touch overlay through a full down→up synchronously and
        // returns only after release, so the intermediate `pressed` state is never observable
        // out-of-process. The press/release state transition is covered in-process (PadsScreen
        // cell semantics + PadsViewModel). We can still confirm the pad settles back to idle.
        let pads = launchPads()
        pads.assertPadState(4, TestTags.PAD_STATE_IDLE)
            .tapPad(4)
            .assertPadState(4, TestTags.PAD_STATE_IDLE)
        throw XCTSkip("Held `pressed` state is unobservable out-of-process (press drives a synchronous down→up on the grid overlay); covered in-process.")
    }

    func testPadPress_sendsNoteOnAndOffToDevice() throws {
        throw XCTSkip("Asserts on a ScriptedMIDIRepository's captured note frames; the sent-MIDI capture isn't reachable out-of-process. Covered by PadsViewModelTests.")
    }

    func testNoScaleSelected_allPadsIdle() {
        let pads = launchPads()
        for i in 0..<12 { pads.assertPadState(i, TestTags.PAD_STATE_IDLE) }
    }
}
