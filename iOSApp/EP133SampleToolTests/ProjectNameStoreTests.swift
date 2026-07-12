import XCTest
@testable import EP133SampleTool

/// Mirrors the store-contract half of AndroidApp's ProjectNameStoreTest.kt. The filename tests
/// there exercise ProjectBackupManager.suggestedProjectFilename and live with the
/// ProjectBackupManager port.
@MainActor
final class ProjectNameStoreTests: XCTestCase {

    func test_store_setAndReadBack_thenClearOnBlank() {
        let store = InMemoryProjectNameStore()
        XCTAssertNil(store.nameFor(slot: 3))

        store.setName(slot: 3, name: "Summer Beat Tape")
        XCTAssertEqual("Summer Beat Tape", store.nameFor(slot: 3))
        XCTAssertEqual("Summer Beat Tape", store.names[3])

        // Blank name is treated as a clear.
        store.setName(slot: 3, name: "   ")
        XCTAssertNil(store.nameFor(slot: 3))
        XCTAssertNil(store.names[3])
    }

    func test_store_trimsWhitespace() {
        let store = InMemoryProjectNameStore()
        store.setName(slot: 1, name: "  Drum Kit  ")
        XCTAssertEqual("Drum Kit", store.nameFor(slot: 1))
    }

    func test_defaultsStore_persistsAcrossInstances() {
        let suite = "ep133_project_names_test_\(UUID().uuidString)"
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        let store = DefaultsProjectNameStore(suiteName: suite)
        store.setName(slot: 5, name: "Live Set")

        let reloaded = DefaultsProjectNameStore(suiteName: suite)
        XCTAssertEqual("Live Set", reloaded.nameFor(slot: 5))

        reloaded.clearName(slot: 5)
        let again = DefaultsProjectNameStore(suiteName: suite)
        XCTAssertNil(again.nameFor(slot: 5))
    }
}
