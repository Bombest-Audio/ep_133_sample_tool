import XCTest

/// Robot for the Kit Builder (embedded in KitScreen's KIT mode): pack browser, category tabs,
/// sample list, pad canvas, clear-pad confirmation, and the load banner. XCUITest port of
/// `KitBuilderRobot.kt`.
///
/// Out-of-process notes:
/// - The pack is seeded via the `-EP133UITestKbPack` launch arg (the analog of the Android test's
///   `viewModel.loadPack(testPack())`), since XCUITest can't hand a `KitPack` to the app.
/// - The sample list scrolls (canvas + tabs sit above it), so row interactions swipe the list up
///   until the row is hittable — the `performScrollToNode` analog.
/// - Each pad cell surfaces its label digit and its name as two separate static texts that share
///   the pad's identifier, so pad content is asserted by an identifier+label query.
final class KitBuilderRobot: BaseRobot {

    @discardableResult
    func assertEmptyState() -> KitBuilderRobot {
        assertTextDisplayed("pick a sample-pack folder to start")
        return self
    }

    @discardableResult
    func assertPackName(_ name: String) -> KitBuilderRobot {
        assertTextDisplayed(name)
        return self
    }

    // ── Category tabs ──
    @discardableResult
    func selectCategory(_ id: String) -> KitBuilderRobot {
        hittableElement(TestTags.kbCategoryTab(id)).tap()
        return self
    }

    // ── Sample list — tap the row body to assign, tap the ▶ circle to audition ──
    @discardableResult
    func tapSample(_ name: String) -> KitBuilderRobot {
        // Tap the row container (an accessibility group carrying the row id) so its onTapGesture
        // assign fires; the audition Button is a distinct child element with its own id.
        scrollToRow(name)
        let row = app.descendants(matching: .any).matching(identifier: TestTags.kbSampleRow(name)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "sample row '\(name)' should exist")
        row.tap()
        return self
    }

    @discardableResult
    func tapAudition(_ name: String) -> KitBuilderRobot {
        scrollToRow(name)
        // The ▶ audition control is the Button carrying the audition-button id.
        let button = app.buttons[TestTags.kbAuditionButton(name)]
        XCTAssertTrue(button.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "audition button for '\(name)' should exist")
        button.tap()
        return self
    }

    @discardableResult
    func assertSampleRowVisible(_ name: String) -> KitBuilderRobot {
        scrollToRow(name)
        assertTagDisplayed(TestTags.kbSampleRow(name))
        return self
    }

    @discardableResult
    func assertSampleRowNotVisible(_ name: String) -> KitBuilderRobot {
        assertTagAbsent(TestTags.kbSampleRow(name))
        return self
    }

    @discardableResult
    func assertSampleAssignedToPad(_ name: String, _ padLabel: String) -> KitBuilderRobot {
        scrollToRow(name)
        // The "◉ pad" badge is a static text nested inside the row container (kbSampleRow), so
        // scope the query to that row's descendants to avoid matching another row's badge.
        let row = app.descendants(matching: .any).matching(identifier: TestTags.kbSampleRow(name)).firstMatch
        let badge = row.staticTexts
            .containing(NSPredicate(format: "label CONTAINS[c] %@", "◉ \(padLabel)"))
            .firstMatch
        XCTAssertTrue(
            badge.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "sample '\(name)' should show a '◉ \(padLabel)' pad badge")
        return self
    }

    // ── Pad canvas ──
    @discardableResult
    func selectPad(_ index: Int) -> KitBuilderRobot {
        hittableElement(TestTags.kbPadCell(index)).tap()
        return self
    }

    @discardableResult
    func assertPadEmpty(_ index: Int) -> KitBuilderRobot {
        assertPadContains(index, "empty")
        return self
    }

    @discardableResult
    func assertPadAssigned(_ index: Int, _ sampleName: String) -> KitBuilderRobot {
        assertPadContains(index, sampleName)
        return self
    }

    @discardableResult
    func assertPadShowsAssignPrompt(_ index: Int) -> KitBuilderRobot {
        assertPadContains(index, "assign →")
        return self
    }

    /// Assert a static text with the pad's identifier and a label containing [substring] exists.
    private func assertPadContains(_ index: Int, _ substring: String) {
        let match = app.staticTexts
            .matching(identifier: TestTags.kbPadCell(index))
            .containing(NSPredicate(format: "label CONTAINS[c] %@", substring))
            .firstMatch
        XCTAssertTrue(
            match.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "pad \(index) should contain '\(substring)'")
    }

    // ── Clear pad ──
    @discardableResult
    func clickClearPad() -> KitBuilderRobot {
        hittableElement(TestTags.KB_CLEAR_PAD_BUTTON).tap()
        return self
    }

    @discardableResult
    func assertClearConfirmVisible() -> KitBuilderRobot {
        assertTagDisplayed(TestTags.KB_CLEAR_CONFIRM_DIALOG)
        return self
    }

    @discardableResult
    func confirmClear() -> KitBuilderRobot {
        // The "Clear" button lives inside the confirm dialog; addressed by its title.
        app.buttons["Clear"].tap()
        return self
    }

    @discardableResult
    func cancelClear() -> KitBuilderRobot {
        app.buttons["Cancel"].tap()
        return self
    }

    @discardableResult
    func assertClearConfirmDismissed() -> KitBuilderRobot {
        XCTAssertTrue(
            waitForAbsence(TestTags.KB_CLEAR_CONFIRM_DIALOG),
            "clear-confirm dialog should be dismissed")
        return self
    }

    // ── Footer — fill meter + pack switcher ──
    @discardableResult
    func assertAssignedCount(_ count: Int) -> KitBuilderRobot {
        let readout = waitForTag(TestTags.KB_ASSIGNED_COUNT)
        let expected = String(format: "%02d", count)
        XCTAssertEqual(readout.label, expected, "assigned count should read '\(expected)'")
        return self
    }

    @discardableResult
    func clickSwitchPack() -> KitBuilderRobot {
        hittableElement(TestTags.KB_SWITCH_PACK_BUTTON).tap()
        return self
    }

    // ── Load banner — async (upload runs in a Task), so wait rather than assert immediately ──
    @discardableResult
    func waitForLoadBanner(_ substring: String, timeout: TimeInterval = BaseRobot.defaultTimeout) -> KitBuilderRobot {
        // The banner text is a child static text of the tagged container (SwiftUI keeps them
        // separate), so wait on any static text containing the bucket substring.
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", substring)
        let text = app.staticTexts.containing(predicate).firstMatch
        XCTAssertTrue(
            text.waitForExistence(timeout: timeout),
            "kit load banner should contain '\(substring)'")
        return self
    }

    // ── Scroll helpers ──

    /// Bring the sample row for [name] into a hittable position by swiping the list up.
    private func scrollToRow(_ name: String) {
        let list = element(TestTags.KB_SAMPLE_LIST)
        _ = list.waitForExistence(timeout: BaseRobot.defaultTimeout)
        let row = app.descendants(matching: .any).matching(identifier: TestTags.kbSampleRow(name)).firstMatch
        var attempts = 0
        while attempts < 6 {
            if row.exists && row.isHittable { return }
            list.swipeUp()
            attempts += 1
        }
    }

    /// Wait until an element with [identifier] no longer exists (the dismissal analog).
    private func waitForAbsence(_ identifier: String, timeout: TimeInterval = BaseRobot.defaultTimeout) -> Bool {
        let el = element(identifier)
        let gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"), object: el)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }
}
