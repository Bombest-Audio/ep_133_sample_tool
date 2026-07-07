import XCTest

/// Import screen UI: pick action and per-file state badges/progress. XCUITest port of
/// `SampleImportScreenTest.kt`.
///
/// UNMOUNTED MODULE — every case here is skipped, by design. On iOS `SampleImportScreen` is not
/// part of `AppShell` and has no navigation entry point (the Android SAMPLES flow could host the
/// mounted screen and drive it; the iOS shell does not). XCUITest is out-of-process and cannot
/// instantiate an unmounted SwiftUI view, and the Android cases additionally lean on in-process
/// seams the launch hooks don't expose:
/// - `pickButton_firesSafRequest` records an `onRequestPick` callback (in-process fake);
/// - `importedFile_progressesToDoneRow`, `uploadHeld_showsLoadingState`,
///   `failedUpload_showsErrorRowAndBatchCount`, `disconnected_importFailsWithNoDeviceError`
///   all drive `viewModel.importStagedBytes(...)` and assert on a ScriptedMIDIRepository.
///
/// The screen's behavior — sanitize → PUT protocol, state machine, batch counters, error paths —
/// is covered in-process by SampleImportViewModelTests (plus SampleImportTests /
/// SampleImportSanitizeTests / SampleImportConcurrencyTests). Faking a mount out-of-process would
/// test a harness that doesn't ship, so these are honestly skipped rather than reworked. The
/// `ImportRobot` mirrors the Android surface so the suite can be un-skipped if the screen is ever
/// mounted in the shell.
final class SampleImportScreenTests: XCTestCase {

    private static let unmountedReason =
        "SampleImportScreen is an unmounted module on iOS (not in AppShell, no nav entry point); "
        + "XCUITest can't instantiate an unmounted SwiftUI screen out-of-process. Its logic is "
        + "covered in-process by SampleImportViewModelTests and the SampleImport unit suites."

    func testEmptyState_showsPickButtonAndProtocolNote() throws {
        throw XCTSkip(Self.unmountedReason)
    }

    func testPickButton_firesSafRequest() throws {
        throw XCTSkip(Self.unmountedReason)
    }

    func testImportedFile_progressesToDoneRow() throws {
        throw XCTSkip(Self.unmountedReason)
    }

    func testUploadHeld_showsLoadingState() throws {
        throw XCTSkip(Self.unmountedReason)
    }

    func testFailedUpload_showsErrorRowAndBatchCount() throws {
        throw XCTSkip(Self.unmountedReason)
    }

    func testDisconnected_importFailsWithNoDeviceError() throws {
        throw XCTSkip(Self.unmountedReason)
    }
}
