import XCTest

/// Robot for the Import screen: pick action and per-file staged rows. XCUITest port of
/// `ImportRobot.kt`.
///
/// NOTE: `SampleImportScreen` is an UNMOUNTED module on iOS — it is not part of `AppShell` and has
/// no navigation entry point (unlike Android, where the SAMPLES flow can host it). XCUITest runs
/// out-of-process and cannot instantiate an unmounted SwiftUI view, so the `SampleImportScreenTests`
/// cases are all skipped (the screen's logic is covered by the SampleImportViewModel unit suite).
/// This robot mirrors the Kotlin surface so it's ready if the screen is ever mounted; the methods
/// address the same tags/text the Android robot uses.
final class ImportRobot: BaseRobot {

    @discardableResult
    func clickPickFiles() -> ImportRobot {
        hittableElement(TestTags.IMPORT_PICK_BUTTON).tap()
        return self
    }

    @discardableResult
    func assertPickButton(_ label: String) -> ImportRobot {
        assertTextDisplayed(label)
        return self
    }

    @discardableResult
    func assertRowVisible(_ name: String) -> ImportRobot {
        assertTagDisplayed(TestTags.importRow(name))
        return self
    }

    /// Assert a staged row shows the given state label (PENDING/CONVERTING/LOADING/DONE/ERROR).
    @discardableResult
    func assertRowState(_ name: String, _ stateLabel: String) -> ImportRobot {
        assertRowContains(name, stateLabel)
        return self
    }

    /// Wait until a staged row reaches the given state label.
    @discardableResult
    func waitForRowState(_ name: String, _ stateLabel: String,
                         timeout: TimeInterval = BaseRobot.defaultTimeout) -> ImportRobot {
        let row = element(TestTags.importRow(name))
        let inner = row.staticTexts.containing(
            NSPredicate(format: "label CONTAINS[c] %@", stateLabel)).firstMatch
        XCTAssertTrue(inner.waitForExistence(timeout: timeout),
                      "row '\(name)' should reach state '\(stateLabel)'")
        return self
    }

    /// Assert a row's foot label shows this error message (unique within the row).
    @discardableResult
    func assertRowError(_ name: String, _ message: String) -> ImportRobot {
        assertRowContains(name, message)
        return self
    }

    @discardableResult
    func assertBatchLabel(_ label: String) -> ImportRobot {
        assertTextDisplayed(label)
        return self
    }

    @discardableResult
    func assertProtocolNoteVisible() -> ImportRobot {
        assertTextDisplayed("i")
        return self
    }

    private func assertRowContains(_ name: String, _ substring: String) {
        let row = element(TestTags.importRow(name))
        let inner = row.staticTexts.containing(
            NSPredicate(format: "label CONTAINS[c] %@", substring)).firstMatch
        XCTAssertTrue(inner.waitForExistence(timeout: BaseRobot.defaultTimeout),
                      "row '\(name)' should contain '\(substring)'")
    }
}
