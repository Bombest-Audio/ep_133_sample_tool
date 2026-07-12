import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/PadsViewModelTest.kt.
///
/// Tests for PadsViewModel multi-touch velocity support.
///
/// The three noteOn tests are `@Ignore`d on Android (`MIDIRepository.noteOn()` calls
/// `android.util.Log`, unavailable in JVM unit tests). No such constraint exists on iOS —
/// `noteOn` sends synchronously through the port — so they run for real here.

/// FakeMIDIPortRecording: MIDIPort that records all sendMIDI calls for assertion.
final class FakeMIDIPortRecording: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    var sentMessages: [(portId: String, data: [UInt8])] = []

    func setup() {}
    func close() {}

    func getUSBDevices() -> MIDIDeviceList {
        MIDIDeviceList(
            inputs: [MIDIDevice(id: "port-in", name: "EP-133")],
            outputs: [MIDIDevice(id: "port-out", name: "EP-133")]
        )
    }

    func sendMIDI(to portId: String, data: [UInt8]) {
        sentMessages.append((portId, data))
    }

    func startListening(portId: String) {}
    func stopListening(portId: String) {}
}

/// Wraps FakeMIDIPortRecording and ensures outputPortId is non-nil for MIDI sends.
@MainActor
final class RecordingMIDIRepository: MIDIRepository {
    override init(_ port: MIDIPort) {
        super.init(port)
        // Simulate device connected so outputPortId is non-nil
        port.onDevicesChanged?()
    }
}

@MainActor
final class PadsViewModelTests: XCTestCase {

    /// Kotlin filters on `(data[0] and 0xF0) == 0x90` — noteOn frames only (the connect-time
    /// auto-stats greet SysEx starts with 0xF0 and is filtered out the same way here).
    private func noteOnMessages(_ port: FakeMIDIPortRecording) -> [[UInt8]] {
        port.sentMessages
            .map(\.data)
            .filter { !$0.isEmpty && (Int($0[0]) & 0xF0) == 0x90 }
    }

    func test_padDown_withVelocity_sendsNoteOnWithCorrectVelocity() async throws {
        let port = FakeMIDIPortRecording()
        let fakeMidi = RecordingMIDIRepository(port)
        let vm = PadsViewModel(fakeMidi)
        vm.padDown(0, velocity: 64)
        let noteOns = noteOnMessages(port)
        XCTAssertFalse(noteOns.isEmpty, "Expected at least one noteOn message")
        let lastNoteOn = try XCTUnwrap(noteOns.last)
        XCTAssertEqual(64, Int(lastNoteOn[2]) & 0x7F, "Velocity should be 64")
    }

    func test_padDown_defaultVelocity_is100() async throws {
        let port = FakeMIDIPortRecording()
        let fakeMidi = RecordingMIDIRepository(port)
        let vm = PadsViewModel(fakeMidi)
        vm.padDown(0)
        let noteOns = noteOnMessages(port)
        XCTAssertFalse(noteOns.isEmpty, "Expected at least one noteOn message")
        let lastNoteOn = try XCTUnwrap(noteOns.last)
        XCTAssertEqual(100, Int(lastNoteOn[2]) & 0x7F, "Default velocity should be 100")
    }

    func test_multiTouch_twoSimultaneousPadDowns_sendsTwoNoteOns() async {
        let port = FakeMIDIPortRecording()
        let fakeMidi = RecordingMIDIRepository(port)
        let vm = PadsViewModel(fakeMidi)
        vm.padDown(0, velocity: 80)
        vm.padDown(1, velocity: 90)
        XCTAssertEqual(
            2, noteOnMessages(port).count,
            "Two simultaneous pad downs should send two noteOns"
        )
    }

    func test_velocity_fromPressure_halfPressure_yields63or64() {
        // Calls the production mapping (PadVelocity.fromNormalizedForce), so a regression in the
        // touch layer's pressure→velocity curve fails here. The old test re-implemented the
        // formula inline and asserted on its own arithmetic, which passed regardless of the app.
        let velocity = PadVelocity.fromNormalizedForce(0.5)
        XCTAssertTrue((63...64).contains(velocity), "Half pressure should yield 63 or 64")
    }

    func test_velocity_fromPressure_boundaries() {
        XCTAssertEqual(1, PadVelocity.fromNormalizedForce(0), "Zero/started touch floors to 1")
        XCTAssertEqual(1, PadVelocity.fromNormalizedForce(-0.5), "Negative clamps to the 1 floor")
        XCTAssertEqual(127, PadVelocity.fromNormalizedForce(1), "Full force maps to max velocity")
        XCTAssertEqual(127, PadVelocity.fromNormalizedForce(2), "Over-range clamps to 127")
    }
}
