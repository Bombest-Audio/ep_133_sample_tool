import XCTest
@testable import EP133SampleTool

/// Behavior tests for SamplePackLoader (the Android SamplePackLoader has no JVM-runnable
/// tests because it is SAF/DocumentFile-bound; the iOS loader walks real directories, so
/// the contract is pinned here).
final class SamplePackLoaderTests: XCTestCase {

    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pack-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func makeFile(_ relativePath: String, bytes: Int = 1024) throws {
        let url = root.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        FileManager.default.createFile(atPath: url.path, contents: Data(count: bytes))
    }

    func test_load_stripsOrderingPrefixAndUppercasesCategories() throws {
        try makeFile("1. Kicks/kick one.wav")
        try makeFile("2) snares/snare.aif")

        let pack = SamplePackLoader.loadSync(from: root)

        XCTAssertEqual(["KICKS", "SNARES"], pack.categories.map(\.id))
        XCTAssertEqual(root.lastPathComponent, pack.name)
        XCTAssertFalse(pack.isEmpty)
    }

    func test_load_recursesOneNestedLevelAndSkipsNonAudio() throws {
        try makeFile("HATS/closed.wav")
        try makeFile("HATS/OPEN HATS/open one.wav")
        try makeFile("HATS/readme.txt")
        try makeFile("EMPTY/notes.md")

        let pack = SamplePackLoader.loadSync(from: root)

        XCTAssertEqual(1, pack.categories.count)
        let hats = try XCTUnwrap(pack.categories.first)
        XCTAssertEqual("HATS", hats.id)
        XCTAssertEqual(["closed", "open one"], hats.samples.map(\.name))
        XCTAssertTrue(hats.samples.allSatisfy { $0.category == "HATS" })
    }

    func test_load_reportsSizeInKB() throws {
        try makeFile("KICKS/big.wav", bytes: 145_408) // 142 KB
        let pack = SamplePackLoader.loadSync(from: root)
        XCTAssertEqual("142 KB", pack.categories.first?.samples.first?.meta)
    }

    func test_load_unreadableRootReturnsEmptyPack() {
        let missing = root.appendingPathComponent("nope", isDirectory: true)
        let pack = SamplePackLoader.loadSync(from: missing)
        XCTAssertEqual("(unreadable)", pack.name)
        XCTAssertTrue(pack.isEmpty)
    }
}
