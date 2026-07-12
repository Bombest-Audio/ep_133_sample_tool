import XCTest
@testable import EP133SampleTool

/// DRAFT (issue #27, hardware-gated): unit tests for the GREET-echo dedup decision.
///
/// The device double-sends responses and the stats query self-issues a GREET, so a second GREET
/// copy processed mid-session used to fail the in-flight waiter and tear the session down. The
/// guard swallows a greet whose signature matches the previous one within the dedup window (an
/// echo) while still resetting on a genuinely-later reconnect greet. These tests pin the pure
/// decision helpers; the full path still needs a hardware session to confirm the timing model.
final class GreetIdempotencyTests: XCTestCase {

    private let payload: [UInt8] = [1, 2, 3, 4]

    func test_firstGreet_isNeverADuplicate() {
        let sig = MIDIRepository.greetSignature(deviceId: 0x33, payload: payload)
        XCTAssertFalse(
            MIDIRepository.isDuplicateGreet(signature: sig, nowMs: 1_000, lastSignature: nil, lastAtMs: 0),
            "the first greet (no prior signature) must reset")
    }

    func test_identicalGreetWithinWindow_isADuplicate() {
        let sig = MIDIRepository.greetSignature(deviceId: 0x33, payload: payload)
        XCTAssertTrue(
            MIDIRepository.isDuplicateGreet(signature: sig, nowMs: 1_010, lastSignature: sig, lastAtMs: 1_000),
            "an identical greet a few ms later is a device echo")
    }

    func test_identicalGreetAfterWindow_isNotADuplicate() {
        let sig = MIDIRepository.greetSignature(deviceId: 0x33, payload: payload)
        let after = 1_000 + MIDIRepository.greetDedupWindowMs
        XCTAssertFalse(
            MIDIRepository.isDuplicateGreet(signature: sig, nowMs: after, lastSignature: sig, lastAtMs: 1_000),
            "same signature but past the window is a reconnect and must reset")
    }

    func test_differentSignatureWithinWindow_isNotADuplicate() {
        let a = MIDIRepository.greetSignature(deviceId: 0x33, payload: payload)
        let b = MIDIRepository.greetSignature(deviceId: 0x40, payload: payload)
        XCTAssertFalse(
            MIDIRepository.isDuplicateGreet(signature: b, nowMs: 1_010, lastSignature: a, lastAtMs: 1_000),
            "a different device/payload within the window is not an echo")
    }

    func test_greetSignature_isStableAndDiscriminating() {
        XCTAssertEqual(
            MIDIRepository.greetSignature(deviceId: 0x33, payload: [1, 2, 3]),
            MIDIRepository.greetSignature(deviceId: 0x33, payload: [1, 2, 3]),
            "same inputs → same signature")
        XCTAssertNotEqual(
            MIDIRepository.greetSignature(deviceId: 0x33, payload: [1, 2, 3]),
            MIDIRepository.greetSignature(deviceId: 0x34, payload: [1, 2, 3]),
            "different device id → different signature")
        XCTAssertNotEqual(
            MIDIRepository.greetSignature(deviceId: 0x33, payload: [1, 2, 3]),
            MIDIRepository.greetSignature(deviceId: 0x33, payload: [1, 2, 4]),
            "different payload → different signature")
    }
}
