import XCTest
@testable import EP133SampleTool

/// Mirrors the four `filename_*` tests of
/// AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectNameStoreTest.kt — the effect of a
/// custom name on `ProjectBackupManager.suggestedProjectFilename`. Device slots have no rename
/// op, so the name lives in the app and only shows up at export time.
///
/// The store-contract half of ProjectNameStoreTest.kt lives in ProjectNameStoreTests.swift.
@MainActor
final class ProjectBackupFilenameTests: XCTestCase {

    private func slot(_ name: String) -> MIDIRepository.ProjectSlot {
        MIDIRepository.ProjectSlot(nodeId: 10, name: name, sizeBytes: 2048, isActive: false)
    }

    private func manager() -> ProjectBackupManager {
        ProjectBackupManager(MIDIRepository(FakeMIDIPort()))
    }

    func test_filename_withoutCustomName_isPlainSlotForm() {
        let name = manager().suggestedProjectFilename(slot: slot("03"))
        XCTAssertTrue(
            name.range(of: #"^EP133-P03-\d{4}-\d{2}-\d{2}-\d{4}\.tar$"#,
                       options: .regularExpression) != nil,
            name)
    }

    func test_filename_prefixesSanitizedCustomName() {
        let name = manager().suggestedProjectFilename(slot: slot("03"),
                                                      customName: "Summer Beat Tape")
        XCTAssertTrue(name.hasPrefix("Summer_Beat_Tape-EP133-P03-"), name)
        XCTAssertTrue(name.hasSuffix(".tar"))
    }

    func test_filename_stripsFilesystemUnsafeCharacters() {
        let name = manager().suggestedProjectFilename(slot: slot("07"),
                                                      customName: "Lo-Fi/Beats: v2!")
        // '-', '/', ':', '!' removed; remaining word chars kept, spaces → underscores.
        XCTAssertTrue(name.hasPrefix("LoFiBeats_v2-EP133-P07-"), name)
    }

    func test_filename_blankCustomNameFallsBackToPlainForm() {
        let name = manager().suggestedProjectFilename(slot: slot("02"), customName: "   ")
        XCTAssertTrue(name.hasPrefix("EP133-P02-"), name)
    }
}
