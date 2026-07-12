import XCTest

/// Kit Builder UI: pack browser (category tabs + sample list), tap-to-assign onto the pad canvas
/// with auto-advance, the clear-pad confirmation dialog, the audition failure path, and the load
/// banner. XCUITest port of `KitBuilderScreenTest.kt`.
///
/// The Kit Builder is embedded in the SAMPLES screen's KIT mode, so each test navigates to the
/// Kit tab and flips to KIT mode. The pack is seeded via the `-EP133UITestKbPack` launch arg
/// (the out-of-process analog of the Android test's `viewModel.loadPack(testPack())`) — the same
/// deterministic "Drum Breaks" pack (KICKS: Kick 1, Kick 2 / SNARES: Snare 1). Its sample URLs
/// are synthetic and unreadable, so audition and upload fail naturally, matching the Kotlin
/// "the fake genuinely can't do it" pattern. Upload *success* / device read-back stay out of
/// scope, exactly as in the Android suite.
final class KitBuilderScreenTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    /// Launch with the Kit tab already in KIT mode and (optionally) the test pack seeded.
    private func launchBuilder(withPack: Bool = true) -> KitBuilderRobot {
        let app = XCUIApplication()
        app.launchForUITest(kitBuilderPack: withPack)
        // Enter the SAMPLES tab and switch to KIT mode so the embedded Kit Builder renders.
        AppRobot(app).goToKit().selectKitMode()
        return KitBuilderRobot(app)
    }

    func testEmptyState_showsPickPrompt() {
        launchBuilder(withPack: false).assertEmptyState()
    }

    func testPackLoaded_showsNameAndFirstCategorySamples() {
        // First category (KICKS) is selected by default.
        launchBuilder()
            .assertPackName("Drum Breaks")
            .assertSampleRowVisible("Kick 1")
            .assertSampleRowVisible("Kick 2")
    }

    func testCategorySwitch_showsDifferentCategorysSamples() {
        launchBuilder()
            .selectCategory("SNARES")
            .assertSampleRowVisible("Snare 1")
            .assertSampleRowNotVisible("Kick 1")
    }

    func testTapSample_assignsToSelectedPadAndAutoAdvances() {
        // Default selected pad is index 9 ("."), next fill-order pad is index 10 ("0").
        launchBuilder()
            .tapSample("Kick 1")
            .assertPadAssigned(9, "Kick 1")
            .assertPadShowsAssignPrompt(10)
            .assertSampleAssignedToPad("Kick 1", ".")
    }

    func testClearPad_cancelDismissesWithoutClearing() {
        let builder = launchBuilder()
        builder.tapSample("Kick 1")
        builder.clickClearPad()
        builder.assertClearConfirmVisible()
        builder.cancelClear()
        builder.assertClearConfirmDismissed()
            .assertPadAssigned(9, "Kick 1")
    }

    func testClearPad_confirmOnDisconnectedShowsFailureSnackbar() {
        // Default offline (inert port): clearPad returns false with no scripting needed.
        let builder = launchBuilder()
        builder.clickClearPad()
        builder.assertClearConfirmVisible()
        builder.confirmClear()
        builder.assertClearConfirmDismissed()
        builder.assertTextDisplayed("Couldn't clear pad . — is the EP-133 connected?")
    }

    func testAudition_bogusUriShowsCantPlaySnackbar() {
        // The synthetic ep133-uitest:// URL can't be opened/decoded → "Can't play …".
        let builder = launchBuilder()
        builder.tapAudition("Kick 1")
        builder.assertTextDisplayed("Can't play Kick 1")
    }

    func testUploadFailure_showsFailedBanner() {
        // Assign one pad, then push via the shared PUSH TO DEVICE button (drives onLoadKit).
        // manager.convert can't decode the synthetic URL → FAILED banner.
        let app = XCUIApplication()
        app.launchForUITest(kitBuilderPack: true)
        let kit = AppRobot(app).goToKit().selectKitMode()
        let builder = KitBuilderRobot(app)
        builder.tapSample("Kick 1")
        kit.clickPush()
        builder.waitForLoadBanner("FAILED")
    }
}
