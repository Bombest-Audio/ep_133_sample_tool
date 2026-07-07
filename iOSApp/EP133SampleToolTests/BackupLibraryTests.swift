import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/BackupLibraryTest.kt.
///
/// Backup-library enumeration (PROJ-03) against the real ProjectBackupManager helper.
///
/// Hardware-free and pure: creates temp .tar files with distinct mtimes and asserts the
/// Context-free `ProjectBackupManager.enumerateBackups` helper returns them newest-first,
/// excluding non-.tar files.
final class BackupLibraryTests: XCTestCase {

    private var tempDirs: [URL] = []

    override func tearDown() {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs = []
        super.tearDown()
    }

    /// Fresh temp directory, cleaned up in tearDown (Kotlin `File.createTempFile` + mkdirs).
    private func makeTempDir(_ prefix: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tempDirs.append(dir)
        return dir
    }

    /// Create an empty file and pin its modification time (Kotlin `setLastModified`).
    private func createFile(in dir: URL, name: String, mtimeMillis: Int64? = nil) throws -> URL {
        let url = dir.appendingPathComponent(name)
        try Data().write(to: url)
        if let mtimeMillis {
            try FileManager.default.setAttributes(
                [.modificationDate: Date(timeIntervalSince1970: Double(mtimeMillis) / 1000.0)],
                ofItemAtPath: url.path)
        }
        return url
    }

    func test_enumeratesTarFiles_sortedByMtimeDescending() throws {
        let dir = try makeTempDir("ep133-backups")

        let older = try createFile(in: dir, name: "EP133-P00-old.tar",
                                   mtimeMillis: 1_000_000_000_000)
        let newer = try createFile(in: dir, name: "EP133-P01-new.tar",
                                   mtimeMillis: 2_000_000_000_000)
        // Decoy non-.tar file that must be excluded.
        _ = try createFile(in: dir, name: "notes.txt")

        let items = ProjectBackupManager.enumerateBackups(dir: dir)

        XCTAssertEqual([newer.lastPathComponent, older.lastPathComponent], items.map(\.name))
        XCTAssertEqual(2_000_000_000_000, items[0].timestamp)
        XCTAssertEqual(newer.standardizedFileURL.path, items[0].file.standardizedFileURL.path)
    }

    func test_enumerateBackups_emptyDirReturnsEmptyList() throws {
        let dir = try makeTempDir("ep133-empty")
        XCTAssertTrue(ProjectBackupManager.enumerateBackups(dir: dir).isEmpty)
    }
}
