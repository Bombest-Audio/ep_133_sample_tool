import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/BackupManagerRebuildTest.kt.
///
/// Covers the BackupManager rebuild on the reqId-correlated file ops: backup must assemble the
/// FULL multi-chunk bytes (the old single-chunk model truncated/dropped them) and restore must
/// PUT every file back exactly. (Kotlin's NoopPort maps to the shared FakeMIDIPort test double.)
@MainActor
final class BackupManagerRebuildTests: XCTestCase {

    /// Repo double: canned /sounds entries + whole-file bytes, records restore PUTs
    /// (Kotlin `FakeRepo`).
    private final class FakeRepo: MIDIRepository {
        private let files: [(nodeId: Int, name: String, bytes: [UInt8])]
        var restored: [String: [UInt8]] = [:]

        init(files: [(nodeId: Int, name: String, bytes: [UInt8])], connected: Bool = true) {
            self.files = files
            super.init(FakeMIDIPort())
            if connected {
                deviceState = DeviceState(connected: true, outputPortId: "out")
            }
        }

        override func listSoundEntries() async throws -> [SysExProtocol.FileEntry] {
            files.map { file in
                SysExProtocol.FileEntry(
                    nodeId: file.nodeId, flags: 1,
                    sizeBytes: Int64(file.bytes.count), name: file.name)
            }
        }

        override func getFileBytes(nodeId: Int) async throws -> [UInt8]? {
            files.first { $0.nodeId == nodeId }?.bytes
        }

        override func putSampleFile(
            name: String,
            pcmBytes: [UInt8],
            channels: Int,
            sampleRate: Int
        ) async throws -> Int? {
            restored[name] = pcmBytes
            return 42  // fake node ID
        }
    }

    private func unzip(_ bytes: [UInt8]) throws -> [String: [UInt8]] {
        var out = [String: [UInt8]]()
        for entry in try ZIPArchive.read(bytes) where !entry.isDirectory {
            out[entry.name] = entry.data
        }
        return out
    }

    private func collect(_ stream: AsyncStream<BackupProgress>) async -> [BackupProgress] {
        var events = [BackupProgress]()
        for await event in stream { events.append(event) }
        return events
    }

    private func collect(_ stream: AsyncStream<RestoreProgress>) async -> [RestoreProgress] {
        var events = [RestoreProgress]()
        for await event in stream { events.append(event) }
        return events
    }

    func test_backup_assemblesFullMultiChunkFiles_noTruncation() async throws {
        // Each file is far larger than one SysEx chunk — the bug was truncation to a single chunk.
        let big1 = (0..<5000).map { UInt8($0 % 251) }
        let big2 = (0..<9000).map { UInt8(($0 * 7) % 251) }
        let repo = FakeRepo(files: [
            (nodeId: 1001, name: "001.pcm", bytes: big1),
            (nodeId: 1002, name: "002.pcm", bytes: big2),
        ])

        let events = await collect(BackupManager(repo).createBackup())
        var pakBytes: [UInt8]?
        for case let .done(bytes) in events { pakBytes = bytes }
        let zip = try unzip(try XCTUnwrap(pakBytes, "backup emits Done"))

        XCTAssertEqual(big1, zip["001.pcm"], "001.pcm full bytes")
        XCTAssertEqual(big2, zip["002.pcm"], "002.pcm full bytes")
        XCTAssertNotNil(zip["metadata.json"], "metadata.json present")
    }

    func test_backup_offline_emitsError() async {
        let repo = FakeRepo(files: [], connected: false)
        let events = await collect(BackupManager(repo).createBackup())
        XCTAssertTrue(events.contains { if case .error = $0 { return true } else { return false } },
                      "offline backup errors")
    }

    func test_restore_putsEveryFileBack_withExactBytes() async {
        let a = (0..<3000).map { UInt8(truncatingIfNeeded: $0) }
        let b = (0..<4096).map { UInt8(255 - ($0 % 256)) }
        let pak = ZIPArchive.build([
            (name: "kick.pcm", data: a),
            (name: "snare.pcm", data: b),
            (name: "metadata.json", data: Array("{}".utf8)),
        ])

        let repo = FakeRepo(files: [])
        let events = await collect(BackupManager(repo).restore(pakBytes: pak))

        XCTAssertTrue(events.contains { $0 == .done }, "restore completes")
        XCTAssertEqual(Set(["kick.pcm", "snare.pcm"]), Set(repo.restored.keys))
        XCTAssertEqual(a, repo.restored["kick.pcm"])
        XCTAssertEqual(b, repo.restored["snare.pcm"])
    }
}
