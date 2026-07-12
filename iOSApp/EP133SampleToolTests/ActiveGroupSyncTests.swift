import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/ActiveGroupSyncTest.kt.
///
/// Tests for the active-group poll fix: poll-in-flight guard and reqId uniqueness.
/// A counting spy port verifies that a concurrent second poll returns nil immediately
/// without queuing on the file-op mutex, and that consecutive FILE ops receive distinct,
/// non-reserved reqIds.
@MainActor
final class ActiveGroupSyncTests: XCTestCase {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /// Decode the reqId from an outbound SysEx frame at positions [6]/[7] — the same
    /// formula as the dispatcher: ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F).
    private func reqIdFrom(_ frame: [UInt8]) -> Int {
        ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
    }

    /// Build a minimal FILE (cmd=5) status-0 response frame echoing the given reqId.
    /// Body: status=0 + dummy bytes (matches the real HW INIT shape) so payload isn't empty.
    private func fakeOkResponse(reqId: Int) -> [UInt8] {
        let body: [UInt8] = [
            0x00,                                // status = 0 (STATUS_OK)
            0x00, 0x0C, 0x00, 0x00, 0x02, 0x00,  // dummy (matches real HW INIT shape)
        ]
        return SysExProtocol.buildFrame(
            deviceId: 0,
            command: SysExProtocol.TE_SYSEX_FILE,
            requestId: reqId,
            payload: body
        )
    }

    private func awaitSentCount(_ port: RecordingPort, _ count: Int) async {
        for _ in 0..<200 {
            if port.sent.count >= count { return }
            await Task.yield()
        }
    }

    // ── Recording spy MIDIPort ───────────────────────────────────────────────

    /// Records every sent frame. Does NOT auto-ack — tests deliver responses manually.
    private final class RecordingPort: MIDIPort {
        var onMIDIReceived: ((String, [UInt8]) -> Void)?
        var onDevicesChanged: (() -> Void)?
        var sent: [[UInt8]] = []

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

        func sendMIDI(to portId: String, data: [UInt8]) { sent.append(data) }

        func lastSentReqId() -> Int {
            guard let frame = sent.last, frame.count >= 8 else { return -1 }
            return ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
        }
    }

    // ── Testable repo ────────────────────────────────────────────────────────

    private final class ActiveGroupTestRepo: MIDIRepository {
        override init(_ port: MIDIPort) {
            super.init(port)
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }

        /// Expose dispatchSysEx for frame injection.
        func inject(_ frame: [UInt8]) { dispatchSysEx(frame) }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /// A concurrent second getActiveGroupIndex call returns nil immediately without queuing
    /// on the file-op mutex. The first call holds the mutex; the second sees the
    /// poll-in-flight guard and bails before acquiring the lock.
    func testConcurrentPoll_secondCallReturnsNullImmediately() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        // Launch the first poll — it blocks inside the mutex awaiting MIDI responses.
        let firstPoll = Task { try await repo.getActiveGroupIndex() }
        // Wait until the first poll has started and set the in-flight guard.
        await awaitSentCount(port, 1)

        // The second poll should return nil immediately (guard fires).
        let secondResult = try await repo.getActiveGroupIndex()
        XCTAssertNil(
            secondResult,
            "Second concurrent poll must return nil immediately (poll already in flight)"
        )

        // Cancel the first poll (it's waiting for MIDI responses that will never arrive).
        firstPoll.cancel()
        _ = try? await firstPoll.value
    }

    /// Unique reqIds: consecutive FILE ops within a single getActiveGroupIndex call all
    /// receive distinct reqIds so no response can satisfy the wrong waiter.
    func testConsecutiveFileOps_haveDistinctReqIds() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        // Run one getActiveGroupIndex call — drive it until it has sent at least two frames.
        let pollTask = Task { try await repo.getActiveGroupIndex() }
        await awaitSentCount(port, 1)

        // Respond to FILE_INIT so the poll can proceed to the next op.
        let initReqId = port.lastSentReqId()
        XCTAssertTrue(
            (MIDIRepository.FILE_REQ_ID_MIN...MIDIRepository.FILE_REQ_ID_MAX).contains(initReqId),
            "INIT reqId must be in range"
        )
        repo.inject(fakeOkResponse(reqId: initReqId))
        await awaitSentCount(port, 2)

        // If a second frame was sent, it must have a different reqId.
        if port.sent.count >= 2 {
            let secondReqId = reqIdFrom(port.sent[1])
            XCTAssertNotEqual(initReqId, secondReqId, "Second FILE op reqId must differ from INIT reqId")
        }

        pollTask.cancel()
        _ = try? await pollTask.value
    }

    /// Verify the counter skips the reserved PUT range 30..99: all reqIds emitted by FILE
    /// ops on a fresh repo are >= FILE_REQ_ID_MIN (100).
    func testNextFileReqId_skipsReservedRange() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        // Kick off a few ops to collect some reqIds.
        let pollTask = Task { try await repo.getActiveGroupIndex() }
        await awaitSentCount(port, 1)

        // Drain FILE_INIT and first resolution frames.
        for _ in 0..<5 {
            if !port.sent.isEmpty {
                let rId = port.lastSentReqId()
                // Respond to advance the poll.
                repo.inject(fakeOkResponse(reqId: rId))
                for _ in 0..<10 { await Task.yield() }
            }
        }

        // Every reqId we saw must be outside the put-range [30..99] and != 0 and != 1.
        for frame in port.sent {
            if frame.count < 8 { continue }
            let rId = reqIdFrom(frame)
            if rId == 0 { continue }  // greet may produce 0 — skip
            XCTAssertFalse(
                (1...99).contains(rId),
                "reqId \(rId) must not be in reserved range [1..99]"
            )
        }

        pollTask.cancel()
        _ = try? await pollTask.value
    }
}
