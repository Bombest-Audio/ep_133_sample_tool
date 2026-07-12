import XCTest

/// Projects screen UI: slot list per connection state, the backup library, and the restore gate.
/// XCUITest port of `ProjectsScreenTest.kt`.
///
/// Out-of-process adaptations:
/// - The Compose test scripts arbitrary slots on a fake repository (nodeId 11/12). Here the
///   connected slots come from the EP-133 simulator, which seeds nine projects "01".."09" at node
///   ids 3000..11000 with "01" active — so the "connected slots" cases assert the simulator's real
///   slots and the full count of 9 rather than the Android-injected pair.
/// - `connected_noSlots_showsEmptyNote` is unreachable: a connected simulated device always lists
///   its nine slots, so the empty-note branch can't be produced. Skipped; the empty branch is
///   covered by the ProjectsViewModel unit suite.
/// - The backup library is seeded via `-EP133UITestSeedBackup` (the analog of the Android test's
///   real `.tar` seed); the library is cleared on every UI-test launch for determinism.
/// - `restoreConfirmDialog_appearsViaRequestAndCancels` drives `viewModel.requestRestore(...)`
///   in-process; the user-facing RESTORE button is gated (disabled) so the dialog can't be reached
///   through the UI out-of-process. Skipped; the confirm/cancel flow is covered in-process.
final class ProjectsScreenTests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchOffline(seedBackup: Bool = false) -> ProjectsRobot {
        let app = XCUIApplication()
        app.launchForUITest(seedBackup: seedBackup)
        return AppRobot(app).goToProjects()
    }

    private func launchConnected() -> ProjectsRobot {
        let app = XCUIApplication()
        app.launchForUITest(simPort: true)
        return AppRobot(app).goToProjects()
    }

    func testDisconnected_showsOfflinePanel() {
        launchOffline().assertOfflinePanel()
    }

    func testConnected_rendersSimulatorSlots() {
        // The simulator seeds nine slots "01".."09" at node ids 3000..11000.
        launchConnected()
            .assertSlotVisible(3000, "01")
            .assertSlotVisible(4000, "02")
            .assertSlotCount(9)
    }

    func testConnected_activeSlotShowsActiveBadge() {
        // The simulator's active project is slot "01" (node 3000).
        launchConnected()
            .assertSlotVisible(3000, "01")
            .assertActiveBadgeVisible()
    }

    func testConnected_noSlots_showsEmptyNote() throws {
        throw XCTSkip("A connected simulated device always lists its nine seeded slots, so the empty-note branch can't be produced out-of-process. Covered by ProjectsViewModelTests.")
    }

    func testBackupLibrary_emptyState() {
        // Fresh launch clears the backup dir → empty-library note.
        launchOffline().assertEmptyBackupLibrary()
    }

    func testBackupLibrary_rendersSeededBackupWithGatedRestore() {
        // A seeded P01.tar in the backups dir: card renders, share is live, restore stays gated.
        launchOffline(seedBackup: true)
            .assertBackupCardVisible("P01.tar")
            .assertShareEnabled("P01.tar")
            .assertRestoreGated("P01.tar")
    }

    func testRestoreConfirmDialog_appearsViaRequestAndCancels() throws {
        throw XCTSkip("Reaching the restore-confirm dialog requires `viewModel.requestRestore(file)`; the user-facing RESTORE button is gated (disabled) so the dialog is unreachable through the UI out-of-process. Covered in-process by the ProjectsViewModel tests.")
    }
}
