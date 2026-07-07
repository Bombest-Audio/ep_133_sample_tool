import XCTest

/// Robot for the Pads screen: group chips, the 12-pad grid, pad labels. XCUITest port of
/// `PadsRobot.kt`.
///
/// Minimal surface for now — only the methods the app-navigation tests reach. A follow-up agent
/// extends this with the pad press/release/state helpers (pressPad, tapPad, assertPadState) using
/// the same `TestTags.pad(_:)` identifiers the Compose robot uses.
final class PadsRobot: BaseRobot {

    @discardableResult
    func selectGroup(_ group: String) -> PadsRobot {
        // The group-chip tag is shared with the Kit screen (both stay mounted); tap the visible one.
        hittableElement(TestTags.groupChip(group)).tap()
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
