import XCTest

/// Robot for the Pads screen: group chips, the 12-pad grid, pad labels, and pad visual state.
/// XCUITest port of `PadsRobot.kt`.
///
/// Out-of-process caveat on touch: the pad grid handles input through a transparent UIKit
/// multi-touch overlay (the Android grid-level `pointerInteropFilter` analog), not per-pad tap
/// handlers. XCUITest can't hold one touch down and assert the pressed state before releasing it
/// the way the Compose `performTouchInput { down }` / `up()` split does — `press(forDuration:)`
/// completes the whole down→up synchronously. So `tapPad` drives a real touch (down+up) at the
/// pad's center, and the held-`pressed`-state assertion is adapted in the test class.
final class PadsRobot: BaseRobot {

    @discardableResult
    func selectGroup(_ group: String) -> PadsRobot {
        // The group-chip tag is shared with the Kit screen (both stay mounted); tap the visible one.
        hittableElement(TestTags.groupChip(group)).tap()
        return self
    }

    /// Tap a pad at its center — a full down+up touch that lands on the overlay touch layer,
    /// firing padDown then padUp (the `tapPad` analog).
    @discardableResult
    func tapPad(_ index: Int) -> PadsRobot {
        element(TestTags.pad(index)).tap()
        return self
    }

    /// Press-and-hold a pad for [duration]. The overlay fires padDown on touch-begin and padUp on
    /// touch-end; the pressed glow is held only while down, so this is the closest out-of-process
    /// analog of the Compose down/hold/up.
    @discardableResult
    func pressPad(_ index: Int, forDuration duration: TimeInterval = 0.3) -> PadsRobot {
        element(TestTags.pad(index)).press(forDuration: duration)
        return self
    }

    /// Assert a pad's visual state via its accessibilityValue: "pressed" / "in_scale" / "idle"
    /// (the Android `stateDescription` analog).
    @discardableResult
    func assertPadState(_ index: Int, _ state: String) -> PadsRobot {
        let pad = waitForTag(TestTags.pad(index))
        XCTAssertEqual(
            pad.value as? String, state,
            "pad \(index) should be in state '\(state)' (was '\(String(describing: pad.value))')")
        return self
    }

    @discardableResult
    func assertPadLabel(_ label: String) -> PadsRobot {
        assertTextDisplayed(label)
        return self
    }

    @discardableResult
    func assertGridVisible() -> PadsRobot {
        assertTagDisplayed(TestTags.PADS_GRID)
        return self
    }
}
