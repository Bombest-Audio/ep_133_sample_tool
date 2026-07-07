import XCTest

/// KitScreen UI: mode toggle (Loop Chopper / Kit Builder), the shared group + choke bar, the
/// slice-count selector, the pick panel, and the KIT-mode Kit Builder smoke. XCUITest port of
/// `KitScreenTest.kt`.
///
/// Out-of-process adaptations: the Compose test composes `KitScreen(kitVM, builderVM)` directly
/// and drives ViewModel seams (`onLoopFilePicked(Uri)`, `chopFromPcm(pcm)`, a scripted
/// `putSampleScript` gate) that a separate-process XCUITest can't reach. Those four cases — the
/// staged-loop-after-pick display and the three chop-progress statuses — are skipped with that
/// reason; the chop state machine itself is covered in-process by the Kit ViewModel unit suite.
/// The SAMPLES page runs against a fresh in-memory GroupSession under UI test (see UITestConfig),
/// so designation/choke state can't leak across launches.
final class KitScreenTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchKit() -> KitRobot {
        let app = XCUIApplication()
        app.launchForUITest()
        return AppRobot(app).goToKit()
    }

    // ── Mode toggle ────────────────────────────────────────────────────────────

    func testDefaultState_startsInChopModeWithSliceSelectorVisible() {
        launchKit()
            .assertModeChipsVisible()
            .assertChopModeVisible()
    }

    func testModeToggle_switchingToKitShowsKitBuilderChrome() {
        launchKit()
            .selectKitMode()
            .assertKitModeVisible()
            .assertKitBuilderContentVisible()
    }

    func testModeToggle_switchingBackToChopRestoresSliceSelector() {
        let kit = launchKit()
        kit.selectKitMode()
        kit.selectChopMode()
            .assertChopModeVisible()
            .assertModeChipsVisible()
    }

    // ── Group selection ────────────────────────────────────────────────────────

    func testGroupSelection_allFourChipsRenderAndAreTappable() {
        launchKit()
            .selectGroup("A")
            .selectGroup("B")
            .selectGroup("C")
            .selectGroup("D")
            .selectGroup("A")
    }

    func testGroupSelection_perGroupDesignationReflectedInScreen() {
        // Designate group B as KIT, bounce through C (still CHOP), return to B → KIT persists.
        let kit = launchKit()
        kit.selectGroup("B").selectKitMode()
        kit.selectGroup("C").assertChopModeVisible()
        kit.selectGroup("B")
            .assertKitModeVisible()
            .assertKitBuilderContentVisible()
    }

    // ── Choke toggle ───────────────────────────────────────────────────────────

    func testChokeToggle_defaultsOnAndTogglesOff() {
        let kit = launchKit()
        kit.assertChokeOn()
        kit.toggleChoke()
        kit.assertChokeOff()
    }

    // ── Slice count selector ───────────────────────────────────────────────────

    func testSliceCount_defaultsToEight() {
        launchKit().assertSliceCount(8)
    }

    func testSliceCount_incrementAndDecrementAdjustCount() {
        let kit = launchKit()
        kit.incrementSliceCount().assertSliceCount(9)
        kit.decrementSliceCount().decrementSliceCount().assertSliceCount(7)
    }

    func testSliceCount_tappingAPadSetsCountToItsFillRank() {
        launchKit()
            .tapSlicePad(4)
            .assertSliceCount(4)
    }

    func testSliceCount_decrementClampsAtOne() {
        let kit = launchKit()
        for _ in 0..<8 { kit.decrementSliceCount() }
        kit.assertSliceCount(1)
    }

    func testSliceCount_incrementClampsAtTwelve() {
        let kit = launchKit()
        for _ in 0..<6 { kit.incrementSliceCount() }
        kit.assertSliceCount(12)
    }

    // ── Staged loop display ────────────────────────────────────────────────────

    func testPickPanel_idleShowsPickPrompt() {
        launchKit()
            .assertPickPanelVisible()
            .assertPickPrompt("pick loop to chop")
    }

    func testStagedLoop_afterPick_showsFileNameAndPushLabel() throws {
        throw XCTSkip("Staging a loop drives `onLoopFilePicked(url)` in-process (or the native file picker); neither is reachable out-of-process. The staged-loop → push-label state is covered by the Kit ViewModel unit suite.")
    }

    // ── Chop progress rendering (in-process seam only) ─────────────────────────

    func testChopProgress_uploadHeld_showsUploadingStatus() throws {
        throw XCTSkip("Requires the `chopFromPcm` seam with a scripted upload gate — an in-process fake with no out-of-process equivalent. Covered by KitViewModelTests.")
    }

    func testChopProgress_allSlicesSucceed_showsDoneStatus() throws {
        throw XCTSkip("Requires the `chopFromPcm` seam feeding pre-decoded PCM; not reachable out-of-process. Covered by KitViewModelTests.")
    }

    func testChopProgress_assignRejected_showsFailedStatus() throws {
        throw XCTSkip("Requires a scripted assign-rejection repository injected in-process; not reachable out-of-process. Covered by KitViewModelTests.")
    }

    // ── KIT mode smoke (KitBuilderScreen embedded) ─────────────────────────────

    func testKitMode_rendersKitBuilderScreenContent() {
        launchKit()
            .selectKitMode()
            .assertKitBuilderContentVisible()
            .assertPushLabel("PICK PACK FOLDER")
    }
}
