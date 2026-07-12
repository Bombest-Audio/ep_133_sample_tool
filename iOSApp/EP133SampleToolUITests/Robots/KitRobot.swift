import XCTest

/// Robot for the SAMPLES (Kit) screen — the loop chopper's chrome plus a smoke check that KIT
/// mode renders the embedded Kit Builder. XCUITest port of `KitRobot.kt`.
///
/// The Kit Builder's own internals (pack browser, pad canvas) live in `KitBuilderRobot`.
final class KitRobot: BaseRobot {

    /// The chop-mode slice counter is the screen's stable unique anchor.
    @discardableResult
    func assertModeChipsVisible() -> KitRobot {
        assertTextDisplayed("SLICE COUNT")
        return self
    }

    // ── Mode toggle — EP133SectionLabel uppercases, so match the rendered case ──
    @discardableResult
    func selectChopMode() -> KitRobot {
        hittableElement(TestTags.KIT_MODE_CHOP).tap()
        return self
    }

    @discardableResult
    func selectKitMode() -> KitRobot {
        hittableElement(TestTags.KIT_MODE_KIT).tap()
        return self
    }

    @discardableResult
    func assertChopModeVisible() -> KitRobot {
        assertTextDisplayed("LOOP CHOPPER")
        return self
    }

    @discardableResult
    func assertKitModeVisible() -> KitRobot {
        assertTextDisplayed("KIT BUILDER")
        return self
    }

    // ── Group + choke bar (shared with Pads via TestTags.groupChip) ──
    @discardableResult
    func selectGroup(_ group: String) -> KitRobot {
        hittableElement(TestTags.groupChip(group)).tap()
        return self
    }

    @discardableResult
    func toggleChoke() -> KitRobot {
        hittableElement(TestTags.GROUP_CHOKE_TOGGLE).tap()
        return self
    }

    /// The choke toggle renders "ON"/"OFF" as its own text; read it off the tagged element's label
    /// to disambiguate from any other ON/OFF text that may be mounted on a hidden tab.
    @discardableResult
    func assertChokeOn() -> KitRobot {
        assertChokeState("ON")
        return self
    }

    @discardableResult
    func assertChokeOff() -> KitRobot {
        assertChokeState("OFF")
        return self
    }

    /// The choke block surfaces three static texts under the same tag ("CHOKE GROUP", a hint, and
    /// the ON/OFF state). Assert the ON/OFF readout specifically by matching that label.
    private func assertChokeState(_ state: String) {
        let readout = app.staticTexts
            .matching(identifier: TestTags.GROUP_CHOKE_TOGGLE)
            .matching(NSPredicate(format: "label ==[c] %@", state))
            .firstMatch
        XCTAssertTrue(
            readout.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "choke toggle should read '\(state)'")
    }

    // ── Slice count selector ──
    @discardableResult
    func incrementSliceCount() -> KitRobot {
        hittableElement(TestTags.KIT_SLICE_COUNT_INC).tap()
        return self
    }

    @discardableResult
    func decrementSliceCount() -> KitRobot {
        hittableElement(TestTags.KIT_SLICE_COUNT_DEC).tap()
        return self
    }

    @discardableResult
    func tapSlicePad(_ rank: Int) -> KitRobot {
        hittableElement(TestTags.kitSlicePad(rank)).tap()
        return self
    }

    /// A dedicated readout tag avoids ambiguity with a filled pad's rank-overlay digit; the readout
    /// text is the zero-padded count ("08").
    @discardableResult
    func assertSliceCount(_ count: Int) -> KitRobot {
        let readout = waitForTag(TestTags.KIT_SLICE_COUNT_READOUT)
        let expected = String(format: "%02d", count)
        XCTAssertEqual(readout.label, expected, "slice count should read '\(expected)'")
        return self
    }

    // ── Pick / staged loop panel ──
    @discardableResult
    func assertPickPanelVisible() -> KitRobot {
        assertTagDisplayed(TestTags.KIT_PICK_PANEL)
        return self
    }

    /// The panel's hint/headline render as their own text nodes on iOS (SwiftUI doesn't merge the
    /// container's children the way Compose's clickable Column does), so assert the text directly.
    @discardableResult
    func assertPickPrompt(_ text: String) -> KitRobot {
        // Match a static text that contains the prompt substring (headline is an exact string;
        // hints may be longer).
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", text)
        let match = app.staticTexts.containing(predicate).firstMatch
        XCTAssertTrue(
            match.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "expected pick panel to show '\(text)'")
        return self
    }

    // ── Push button ──
    @discardableResult
    func clickPush() -> KitRobot {
        hittableElement(TestTags.KIT_PUSH_BUTTON).tap()
        return self
    }

    /// The push button's label is uppercased and dynamic ("PUSH TO DEVICE · N SLICES → A",
    /// "PICK PACK FOLDER", …); assert a substring on the tagged button's accessibility label.
    @discardableResult
    func assertPushLabel(_ substring: String) -> KitRobot {
        let button = waitForTag(TestTags.KIT_PUSH_BUTTON)
        XCTAssertTrue(
            button.label.localizedCaseInsensitiveContains(substring),
            "push button should contain '\(substring)' (was '\(button.label)')")
        return self
    }

    // ── Chop progress ──
    @discardableResult
    func assertProgressPadVisible(_ rank: Int) -> KitRobot {
        assertTagDisplayed(TestTags.kitProgressPad(rank))
        return self
    }

    @discardableResult
    func waitForProgressStatus(_ bucket: String, timeout: TimeInterval = BaseRobot.defaultTimeout) -> KitRobot {
        let status = element(TestTags.KIT_PROGRESS_STATUS)
        XCTAssertTrue(status.waitForExistence(timeout: timeout), "expected the chop progress status")
        // The status text carries dynamic counts; the tagged node's label buckets the state.
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", bucket)
        let matched = XCTNSPredicateExpectation(predicate: predicate, object: status)
        XCTAssertEqual(
            XCTWaiter().wait(for: [matched], timeout: timeout), .completed,
            "chop progress status should reach '\(bucket)'")
        return self
    }

    // ── Kit Builder smoke (KIT mode content) ──
    @discardableResult
    func assertKitBuilderContentVisible() -> KitRobot {
        assertTextDisplayed("KIT CANVAS · TAP A PAD")
        return self
    }
}
