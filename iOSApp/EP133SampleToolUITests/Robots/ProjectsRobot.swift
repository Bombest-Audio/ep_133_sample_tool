import XCTest

/// Robot for the Projects screen: device slots, backup library, restore gate. XCUITest port of
/// `ProjectsRobot.kt`.
///
/// Minimal surface for now — only the offline panel check the app-navigation tests reach. A
/// follow-up agent extends this with slot-list and backup-library helpers.
final class ProjectsRobot: BaseRobot {

    @discardableResult
    func assertOfflinePanel() -> ProjectsRobot {
        assertTextDisplayed("Connect your EP-133 via USB to browse and back up projects.")
        assertTextDisplayed("OFFLINE")
        return self
    }
}
