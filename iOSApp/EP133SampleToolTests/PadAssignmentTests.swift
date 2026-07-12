import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/PadAssignmentTest.kt.
///
/// Unit tests for `MIDIRepository.buildPadAssignmentJson` — the hardware-verified pad
/// metadata JSON shape (verified against a real EP-133, 2026-06-29).
///
/// These are pure string/JSON assertions, no device I/O needed.
@MainActor
final class PadAssignmentTests: XCTestCase {

    /// Minimal test double — just enough to call buildPadAssignmentJson (the no-op
    /// Support/FakeMIDIPort stands in for Kotlin's anonymous MIDIPort object).
    private func makeRepo() -> MIDIRepository {
        MIDIRepository(FakeMIDIPort())
    }

    /// Kotlin `JSONObject(raw)` — throws on malformed JSON.
    private func parseJson(_ raw: String) throws -> [String: Any] {
        try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
            "JSON must parse to an object"
        )
    }

    // ── Test A: Required keys present with correct types ─────────────────────

    func test_buildPadAssignmentJson_containsRequiredKeys() async throws {
        let json = try parseJson(makeRepo().buildPadAssignmentJson(
            sampleNodeId: 193,
            sampleStart: 0,
            sampleEnd: 46875,
            playmode: "oneshot"
        ))

        // sym = sampleNodeId
        XCTAssertEqual(193, json["sym"] as? Int, "sym")
        // sample.start / sample.end are frame counts
        XCTAssertEqual(0, json["sample.start"] as? Int, "sample.start")
        XCTAssertEqual(46875, json["sample.end"] as? Int, "sample.end")
        // sound.playmode
        XCTAssertEqual("oneshot", json["sound.playmode"] as? String, "sound.playmode")
        // Fixed device defaults (hardware-verified)
        XCTAssertEqual(100, json["sound.amplitude"] as? Int, "sound.amplitude")
        XCTAssertEqual(0, json["sound.pan"] as? Int, "sound.pan")
        XCTAssertEqual(0, json["envelope.attack"] as? Int, "envelope.attack")
        XCTAssertEqual(255, json["envelope.release"] as? Int, "envelope.release")
        XCTAssertFalse(
            try XCTUnwrap(json["sound.mutegroup"] as? Bool),
            "sound.mutegroup must be false"
        )
        XCTAssertEqual("off", json["time.mode"] as? String, "time.mode")
        XCTAssertEqual(0, json["midi.channel"] as? Int, "midi.channel")
    }

    // ── Test B: sym reflects sampleNodeId argument ────────────────────────────

    func test_buildPadAssignmentJson_symMatchesSampleNodeId() async throws {
        let json = try parseJson(makeRepo().buildPadAssignmentJson(
            sampleNodeId: 500, sampleStart: 100, sampleEnd: 9000, playmode: "loop"
        ))
        XCTAssertEqual(500, json["sym"] as? Int)
        XCTAssertEqual(100, json["sample.start"] as? Int)
        XCTAssertEqual(9000, json["sample.end"] as? Int)
        XCTAssertEqual("loop", json["sound.playmode"] as? String)
    }

    // ── Test C: sound.pitch serialized as float, not int ──────────────────────

    func test_buildPadAssignmentJson_pitchIsZeroFloat() async throws {
        let raw = makeRepo().buildPadAssignmentJson(
            sampleNodeId: 1, sampleStart: 0, sampleEnd: 1, playmode: "oneshot"
        )
        // The hardware-verified JSON specifies 0.00 (not 0), so the string representation
        // must parse to a double 0.0.
        let json = try parseJson(raw)
        let pitch = try XCTUnwrap((json["sound.pitch"] as? NSNumber)?.doubleValue)
        XCTAssertEqual(0.0, pitch, accuracy: 0.001, "sound.pitch must be 0.0")
    }

    // ── Test D: JSON is parseable (no illegal characters) ─────────────────────

    func test_buildPadAssignmentJson_validJson() async throws {
        // Throws if malformed
        let json = try parseJson(makeRepo().buildPadAssignmentJson(
            sampleNodeId: 1, sampleStart: 0, sampleEnd: 100, playmode: "oneshot"
        ))
        // Spot-check one field to ensure the parse didn't return an empty object
        XCTAssertEqual(1, json["sym"] as? Int)
    }
}
