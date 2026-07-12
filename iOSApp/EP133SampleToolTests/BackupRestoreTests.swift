import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/BackupRestoreTest.kt.
///
/// Tests for BackupManager and BackupProgress. The JVM ZipOutputStream/ZipInputStream fixture
/// helpers map to `ZIPArchive.build` / `ZIPArchive.read`.
final class BackupRestoreTests: XCTestCase {

    /// Build a minimal valid ZIP file in memory with at least one .wav and one .json entry.
    private func buildMinimalPak() -> [UInt8] {
        ZIPArchive.build([
            (name: "001.wav", data: (0..<16).map { UInt8($0) }),
            (name: "metadata.json", data: Array("{\"file_count\":1}".utf8)),
        ])
    }

    func test_backupFile_isZipFormat() {
        let pakBytes = buildMinimalPak()
        XCTAssertEqual(0x50, pakBytes[0], "ZIP magic byte 0")
        XCTAssertEqual(0x4B, pakBytes[1], "ZIP magic byte 1")
        XCTAssertEqual(0x03, pakBytes[2], "ZIP magic byte 2")
        XCTAssertEqual(0x04, pakBytes[3], "ZIP magic byte 3")
    }

    func test_backupFile_containsWavFiles() throws {
        let pakBytes = buildMinimalPak()
        let entryNames = try ZIPArchive.read(pakBytes).map(\.name)
        XCTAssertTrue(entryNames.contains { $0.hasSuffix(".wav") },
                      "At least one .wav entry expected")
    }

    func test_backupFile_containsMetadataJson() throws {
        let pakBytes = buildMinimalPak()
        let entryNames = try ZIPArchive.read(pakBytes).map(\.name)
        XCTAssertTrue(entryNames.contains { $0.hasSuffix(".json") },
                      "metadata.json expected in archive")
    }

    func test_fileGetProtocol_buildsCorrectFrame() {
        let frame = SysExProtocol.buildFileGetFrame(
            deviceId: 0,
            path: "/sounds/001.wav",
            chunkIndex: 0,
            requestId: 1
        )
        // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5), NOT CMD_PRODUCT_SPECIFIC (127).
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE, Int(frame[8]) & 0x7F,
                       "frame[8] = TE_SYSEX_FILE (5)")
        // Body starts at subcommand (no leading TE_SYSEX_FILE byte): byte[0] = TE_SYSEX_FILE_GET (3)
        let packedPayload = Array(frame[9..<(frame.count - 1)])
        let unpacked = SysExProtocol.unpack7bit(packedPayload)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_GET, Int(unpacked[0]) & 0xFF,
                       "body[0] = TE_SYSEX_FILE_GET (3)")
    }

    // Kotlin: @Ignore("restore flow requires wiring BackupManager to a fake MIDIRepository that
    // tracks sendRawBytes calls — deferred for Phase 4 test coverage")
    func test_restoreFromValidPak_sendsFilePutCommands() throws {
        // Given a valid PAK byte array, BackupManager.restore() should invoke sendMidi with
        // FILE_PUT (cmd=2) frames
        throw XCTSkip("restore flow requires wiring BackupManager to a fake MIDIRepository that tracks sendRawBytes calls — deferred for Phase 4 test coverage")
    }
}
