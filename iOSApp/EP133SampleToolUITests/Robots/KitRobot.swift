import XCTest

/// Robot for the SAMPLES (Kit) screen — loop chopper chrome + Kit Builder smoke checks. XCUITest
/// port of `KitRobot.kt`.
///
/// Minimal surface for now — only the mode-chip check the app-navigation tests reach. A follow-up
/// agent extends this with the slice-count, push, and Kit Builder helpers.
final class KitRobot: BaseRobot {

    /// The chop-mode slice counter is the screen's stable unique anchor.
    @discardableResult
    func assertModeChipsVisible() -> KitRobot {
        assertTextDisplayed("SLICE COUNT")
        return self
    }
}
