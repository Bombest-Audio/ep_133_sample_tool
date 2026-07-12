import XCTest

/// Robot for the Projects screen: device slots, backup library, restore gate. XCUITest port of
/// `ProjectsRobot.kt`.
///
/// Out-of-process adaptations: the Compose test scripts slots on a fake repository and seeds real
/// `.tar` files from the test process. Here the connected slots come from the EP-133 simulator
/// (which seeds nine projects "01".."09", "01" active), and the backup library is seeded via the
/// `-EP133UITestSeedBackup` launch arg. The slot list is a lazy ScrollView, so a slot below the
/// fold is scrolled into view before it's asserted (the `performScrollToNode` analog).
final class ProjectsRobot: BaseRobot {

    @discardableResult
    func assertOfflinePanel() -> ProjectsRobot {
        assertTextDisplayed("Connect your EP-133 via USB to browse and back up projects.")
        assertTextDisplayed("OFFLINE")
        return self
    }

    /// The count header reads "N SLOTS" (connected). Scroll the list back to the top first in case
    /// a prior assertion scrolled a slot below the fold into view.
    @discardableResult
    func assertSlotCount(_ count: Int) -> ProjectsRobot {
        assertTextDisplayed("\(count) SLOTS")
        return self
    }

    /// Scroll the (lazy) slot list to the slot, then assert it renders with its "PROJECT <name>"
    /// title. Off-screen LazyVStack items aren't composed, so scroll first.
    @discardableResult
    func assertSlotVisible(_ nodeId: Int, _ name: String) -> ProjectsRobot {
        scrollToSlot(nodeId)
        assertTagDisplayed(TestTags.projectSlot(nodeId))
        // Untitled slots title as "PROJECT <NN>". The title is a static text inside the tagged card.
        let title = app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS[c] %@", "PROJECT \(name)"))
            .firstMatch
        XCTAssertTrue(
            title.waitForExistence(timeout: BaseRobot.defaultTimeout),
            "slot \(nodeId) should title 'PROJECT \(name)'")
        return self
    }

    @discardableResult
    func assertActiveBadgeVisible() -> ProjectsRobot {
        assertTextDisplayed("ACTIVE")
        return self
    }

    @discardableResult
    func assertEmptySlots() -> ProjectsRobot {
        assertTextDisplayed("No projects found.")
        return self
    }

    @discardableResult
    func assertBackupCardVisible(_ name: String) -> ProjectsRobot {
        assertTagDisplayed(TestTags.backupCard(name))
        return self
    }

    @discardableResult
    func assertEmptyBackupLibrary() -> ProjectsRobot {
        assertTextDisplayed("No backups yet. Back up a slot to add one.")
        return self
    }

    /// SHARE is a live control (a ShareLink) inside the backup card — assert it exists and is
    /// enabled/hittable.
    @discardableResult
    func assertShareEnabled(_ backupName: String) -> ProjectsRobot {
        let card = element(TestTags.backupCard(backupName))
        XCTAssertTrue(card.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "backup card '\(backupName)' should exist")
        let share = card.buttons.containing(
            NSPredicate(format: "label CONTAINS[c] %@", "SHARE")).firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "SHARE should be present on '\(backupName)'")
        XCTAssertTrue(share.isEnabled, "SHARE should be enabled")
        return self
    }

    /// RESTORE stays gated (disabled) until the hardware round-trip lands; its label reads
    /// "RESTORE · SOON".
    @discardableResult
    func assertRestoreGated(_ backupName: String) -> ProjectsRobot {
        let card = element(TestTags.backupCard(backupName))
        XCTAssertTrue(card.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "backup card '\(backupName)' should exist")
        let restore = card.buttons.containing(
            NSPredicate(format: "label CONTAINS[c] %@", "RESTORE")).firstMatch
        XCTAssertTrue(restore.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "RESTORE should be present on '\(backupName)'")
        XCTAssertFalse(restore.isEnabled, "RESTORE should be gated (disabled)")
        return self
    }

    @discardableResult
    func assertRestoreConfirmVisible() -> ProjectsRobot {
        assertTagDisplayed(TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG)
        return self
    }

    // ── Scroll helper ──

    private func scrollToSlot(_ nodeId: Int) {
        let list = element(TestTags.PROJECTS_SLOT_LIST)
        _ = list.waitForExistence(timeout: BaseRobot.defaultTimeout)
        let slot = element(TestTags.projectSlot(nodeId))
        var attempts = 0
        while attempts < 8 {
            if slot.exists && slot.isHittable { return }
            list.swipeUp()
            attempts += 1
        }
    }
}
