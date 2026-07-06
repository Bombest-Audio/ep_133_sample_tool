import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/FileOpMutexTest.kt.
///
/// Deadlock-free serialization tests for MIDIRepository's fileOpMutex.
///
/// Verifies that two file ops running concurrently on the same repo both complete without
/// hanging — proving the mutex serializes without deadlocking. The key risk: the mutex is
/// NOT reentrant; if any withLock body called withLock on the same mutex again, the op
/// would suspend waiting for itself. The auto-ack port responds synchronously so each call
/// completes quickly even though it awaits a MIDI response.
@MainActor
final class FileOpMutexTests: XCTestCase {

    // ── Auto-ack MIDIPort ────────────────────────────────────────────────────

    /// Responds to every FILE (cmd=5) outbound frame with a minimal status=0 response
    /// synchronously (before sendMIDI returns).
    private final class MutexAutoAckPort: MIDIPort {
        var onMIDIReceived: ((String, [UInt8]) -> Void)?
        var onDevicesChanged: (() -> Void)?

        func setup() {}
        func close() {}
        func startListening(portId: String) {}
        func stopListening(portId: String) {}

        func getUSBDevices() -> MIDIDeviceList {
            MIDIDeviceList(
                inputs: [MIDIDevice(id: "in", name: "EP-133")],
                outputs: [MIDIDevice(id: "out", name: "EP-133")]
            )
        }

        func sendMIDI(to portId: String, data: [UInt8]) {
            if data.count < 10 { return }
            let command = Int(data[8]) & 0x7F
            if command != SysExProtocol.TE_SYSEX_FILE { return }

            // Echo back reqId extracted from frame[6]/[7].
            // Minimal STATUS-ONLY response: F0 TE_ID 00 40 reqHigh reqLow 05 00 F7
            let response: [UInt8] = [
                0xF0,
                SysExProtocol.TE_ID_0,
                SysExProtocol.TE_ID_1,
                SysExProtocol.TE_ID_2,
                0x00,  // deviceId
                0x40,
                UInt8(Int(data[6]) & 0x0F),
                UInt8(Int(data[7]) & 0x7F),
                UInt8(SysExProtocol.TE_SYSEX_FILE),
                0x00,  // status = STATUS_OK
                0xF7,
            ]
            onMIDIReceived?("in", response)
        }
    }

    // ── Testable repo ────────────────────────────────────────────────────────

    private final class MutexTestRepo: MIDIRepository {
        override init(_ port: MIDIPort) {
            super.init(port)
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }

        /// Stub: skip FILE_LIST round-trips for /sounds.
        override func resolveNodeId(_ path: String) async throws -> Int? {
            path == "/sounds" ? 42 : nil
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /// Two concurrent ensureFileSessionInit calls both complete without hanging.
    ///
    /// The first caller acquires the mutex, sends FILE_INIT, receives the auto-ack, and
    /// releases. The second caller then acquires the mutex, but since the session is already
    /// initialized it returns immediately. Neither call deadlocks.
    func testConcurrentEnsureFileSessionInit_bothCompleteWithoutDeadlock() async throws {
        let port = MutexAutoAckPort()
        let repo = MutexTestRepo(port)

        async let r1 = repo.ensureFileSessionInit()
        async let r2 = repo.ensureFileSessionInit()

        // If either call deadlocked, the test would hang here.
        let first = try await r1
        let second = try await r2

        XCTAssertTrue(first, "first ensureFileSessionInit should return true")
        XCTAssertTrue(second, "second ensureFileSessionInit should return true")
    }

    /// Concurrent getActiveGroupIndex calls both complete without deadlock.
    ///
    /// getActiveGroupIndex acquires fileOpMutex and internally calls the NoLock init helper
    /// (not the locking wrapper). Running two instances concurrently proves no nested
    /// withLock on the same mutex, and that serialization doesn't corrupt shared state.
    /// What matters is that both calls return, not their specific value (the fake node walk
    /// won't find "active" project data, so nil is expected).
    func testConcurrentGetActiveGroupIndex_bothCompleteWithoutDeadlock() async throws {
        let port = MutexAutoAckPort()
        let repo = MutexTestRepo(port)

        async let r1 = repo.getActiveGroupIndex()
        await Task.yield()
        async let r2 = repo.getActiveGroupIndex()

        // Await both — the test hangs if either deadlocks.
        _ = try await r1
        _ = try await r2
        // Both completed = no deadlock.
    }

    /// Concurrent getActiveGroupIndex + ensureFileSessionInit both complete without deadlock.
    ///
    /// Models the actual production race: the 1.5 s active-group poll fires while the file
    /// session is being initialized by another path. The mutex serializes them; neither can
    /// deadlock because ensureFileSessionInit is a top-level locked entry point (not called
    /// from within getActiveGroupIndex's locked body — that uses the NoLock variant).
    func testConcurrentPollAndInit_bothCompleteWithoutDeadlock() async throws {
        let port = MutexAutoAckPort()
        let repo = MutexTestRepo(port)

        async let poll = repo.getActiveGroupIndex()
        async let initResult = repo.ensureFileSessionInit()

        _ = try await poll
        _ = try await initResult
        // Both completed — no deadlock between top-level locking entry points.
    }
}
