import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportSanitizeTest.kt.

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles — disconnected no-op stubs sufficient for sanitizeName, which
// never touches the device. Mirrors the Kotlin SanitizeNoOpPort/SanitizeNoOpRepo.
// ─────────────────────────────────────────────────────────────────────────────

private final class SanitizeNoOpPort: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?
    func setup() {}
    func close() {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
    func getUSBDevices() -> MIDIDeviceList { MIDIDeviceList(inputs: [], outputs: []) }
    func sendMIDI(to portId: String, data: [UInt8]) {}
}

/// Disconnected repo (the base MIDIRepository init already yields a disconnected
/// DeviceState; the subclass exists for name parity with the Kotlin SanitizeNoOpRepo).
@MainActor
private final class SanitizeNoOpRepo: MIDIRepository {}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/// Unit tests for `SampleImportManager.sanitizeName`.
///
/// sanitizeName is pure (no I/O, no device interaction), so a disconnected no-op
/// repository is sufficient — only the method under test matters.
@MainActor
final class SampleImportSanitizeTests: XCTestCase {

    /// Kotlin uses a @Before-created field; XCTest setUp() is nonisolated, so a
    /// MainActor-isolated computed property is the equivalent fixture here.
    private var manager: SampleImportManager {
        SampleImportManager(SanitizeNoOpRepo(SanitizeNoOpPort()))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Regression lock: well-formed short ASCII names pass through unchanged
    // ──────────────────────────────────────────────────────────────────────────

    func testKick_passesThroughUnchanged() {
        XCTAssertEqual("kick.wav", manager.sanitizeName("kick.wav"))
    }

    func testSnare_passesThroughUnchanged() {
        XCTAssertEqual("snare.wav", manager.sanitizeName("snare.wav"))
    }

    func testHihat_passesThroughUnchanged() {
        XCTAssertEqual("hihat.wav", manager.sanitizeName("hihat.wav"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Non-ASCII names must produce pure ASCII output, no '?' bytes
    // ──────────────────────────────────────────────────────────────────────────

    func testNonAscii_cafe_producesAsciiResult() {
        let result = manager.sanitizeName("café.wav")
        XCTAssertNotNil(result, "café.wav should produce a non-nil result")
        guard let result else { return }
        XCTAssertTrue(result.hasSuffix(".wav"), "Result must end in .wav, got: \(result)")
        XCTAssertTrue(
            result.unicodeScalars.allSatisfy { $0.value < 128 },
            "Result must be pure ASCII (all code points < 128), got: \(result)")
        XCTAssertFalse(
            result.contains("?"),
            "Result must not contain '?' (ASCII encoding loss), got: \(result)")
    }

    func testEmoji_unicodeOnly_producesAsciiOrNil() {
        let result = manager.sanitizeName("🥁.wav")  // 🥁 drum emoji
        // Either nil (no safe chars at all after replacement+trim) or a pure-ASCII string
        if let result {
            XCTAssertTrue(
                result.unicodeScalars.allSatisfy { $0.value < 128 },
                "Non-nil result from emoji name must be pure ASCII, got: \(result)")
            XCTAssertTrue(result.hasSuffix(".wav"), "Non-nil result must end in .wav")
        }
        // nil is also acceptable — "Invalid sample name" is the correct UX response
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Path traversal: no '/', '\', or ".." in output
    // ──────────────────────────────────────────────────────────────────────────

    func testTraversal_dotDot_noSlashOrDoubleDotInResult() {
        let result = manager.sanitizeName("../../etc/passwd")
        XCTAssertNotNil(result, "Path traversal input should yield a sanitized name, not nil")
        guard let result else { return }
        XCTAssertFalse(result.contains("/"), "Result must not contain '/'")
        XCTAssertFalse(result.contains("\\"), "Result must not contain '\\'")
        XCTAssertFalse(result.contains(".."), "Result must not contain '..'")
    }

    func testSubdirPath_noSlashInResult() {
        let result = manager.sanitizeName("a/b/c.wav")
        XCTAssertNotNil(result, "Subdir path should yield a non-nil result (basename extracted)")
        guard let result else { return }
        XCTAssertFalse(result.contains("/"), "Result must not contain '/'")
        XCTAssertFalse(result.contains(".."), "Result must not contain '..'")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Length cap: basename (excluding .wav) must be <= MAX_BASENAME_LEN
    // ──────────────────────────────────────────────────────────────────────────

    func testOverlongName_basenameCappedAt32() {
        let longName = String(repeating: "a", count: 200) + ".wav"
        let result = manager.sanitizeName(longName)
        XCTAssertNotNil(result, "200-char name should produce a non-nil result")
        guard let result else { return }
        let basename = String(result.dropLast(".wav".count))
        XCTAssertTrue(
            basename.count <= SampleImportManager.MAX_BASENAME_LEN,
            "Basename must be <= \(SampleImportManager.MAX_BASENAME_LEN) chars, got \(basename.count)")
        XCTAssertTrue(result.hasSuffix(".wav"), "Result must still end in .wav")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Whitespace and trailing dots are trimmed
    // ──────────────────────────────────────────────────────────────────────────

    func testTrailingWhitespace_isTrimmed() {
        let result = manager.sanitizeName("  kick  .wav")
        XCTAssertNotNil(result, "Name with surrounding spaces should be valid")
        guard let result else { return }
        // Should sanitize to something valid ending in .wav
        XCTAssertTrue(result.hasSuffix(".wav"), "Result must end in .wav, got: \(result)")
        // Leading/trailing whitespace in the basename must be gone
        let basename = String(result.dropLast(".wav".count))
        XCTAssertEqual(
            basename.trimmingCharacters(in: .whitespaces), basename,
            "Basename must not start or end with space")
    }

    func testTrailingDots_areTrimmed() {
        let result = manager.sanitizeName("kick...wav")
        // "kick...wav" → stem is "kick.." → after replacement of '.' → "kick__" → trimmed → "kick"
        XCTAssertNotNil(result, "Name with trailing dots should produce a valid result")
        guard let result else { return }
        XCTAssertTrue(result.hasSuffix(".wav"), "Result must end in .wav, got: \(result)")
        let basename = String(result.dropLast(".wav".count))
        XCTAssertFalse(basename.hasSuffix("_"), "Basename must not end with '_'")
        XCTAssertFalse(basename.hasSuffix("-"), "Basename must not end with '-'")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Empty / all-unsafe inputs return nil
    // ──────────────────────────────────────────────────────────────────────────

    func testEmptyString_returnsNil() {
        XCTAssertNil(manager.sanitizeName(""))
    }

    func testSlashesOnly_returnsNil() {
        XCTAssertNil(manager.sanitizeName("///"))
    }

    func testDotsOnly_returnsNil() {
        // "..." → strip "path" components → basename is "..." → stem via substringBeforeLast('.')
        // → ".." → after char replacement "__" → trim('_', '-', '.') → "" → nil
        XCTAssertNil(manager.sanitizeName("..."))
    }
}
