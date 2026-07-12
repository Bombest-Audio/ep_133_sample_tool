import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/FileCorrelationDispatchTest.kt.
///
/// reqId-correlated dispatch of metadata/info file ops: matching responses complete the
/// right op, mismatches are dropped, interleaved responses never swap, duplicates complete
/// once. (Kotlin fast-forwards virtual time for the timeout case; XCTest has no virtual
/// clock, so that test shortens the repository's metadata timeout instead.)
@MainActor
final class FileCorrelationDispatchTests: XCTestCase {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /// Decode the reqId from an outbound SysEx frame at positions [6]/[7] — the same
    /// formula as the dispatcher: ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F).
    private func reqIdFrom(_ frame: [UInt8]) -> Int {
        ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
    }

    /// Build a METADATA GET response carrying a JSON string.
    /// Body: page u16 = 0xFFFF (terminator), JSON bytes, NUL.
    private func fakeMetaResponse(reqId: Int, json: String) -> [UInt8] {
        let body: [UInt8] = [0xFF, 0xFF] + Array(json.utf8) + [0x00]
        return fakeFileResponse(reqId: reqId, body: body)
    }

    private func fakeInfoResponse(
        reqId: Int,
        nodeId: Int,
        parentId: Int,
        flags: Int,
        sizeBytes: Int64,
        name: String
    ) -> [UInt8] {
        let body: [UInt8] = [
            UInt8((nodeId >> 8) & 0xFF),
            UInt8(nodeId & 0xFF),
            UInt8((parentId >> 8) & 0xFF),
            UInt8(parentId & 0xFF),
            UInt8(flags & 0xFF),
            UInt8((sizeBytes >> 24) & 0xFF),
            UInt8((sizeBytes >> 16) & 0xFF),
            UInt8((sizeBytes >> 8) & 0xFF),
            UInt8(sizeBytes & 0xFF),
        ] + Array(name.utf8) + [0x00]
        return fakeFileResponse(reqId: reqId, body: body)
    }

    /// Real device response layout: raw status byte at position 9, 7-bit-packed body after.
    private func fakeFileResponse(reqId: Int, body: [UInt8]) -> [UInt8] {
        let packedBody = SysExProtocol.pack7bit(body)
        return [
            SysExProtocol.MIDI_SYSEX_START,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,
            SysExProtocol.MIDI_SYSEX_TE,
            UInt8((SysExProtocol.BIT_IS_REQUEST |
                SysExProtocol.BIT_REQUEST_ID_AVAILABLE |
                ((reqId >> 7) & 0x0F)) & 0xFF),
            UInt8(reqId & 0x7F),
            UInt8(SysExProtocol.TE_SYSEX_FILE),
            0x00,
        ] + packedBody + [SysExProtocol.MIDI_SYSEX_END]
    }

    private func awaitSentCount(_ port: RecordingPort, _ count: Int) async {
        for _ in 0..<200 {
            if port.sent.count >= count { return }
            await Task.yield()
        }
    }

    private final class Flag {
        var value = false
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

    func testGetNodeInfo_returnsParsedNodeInfoWhenResponseEchoesSentReqId() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        let op = Task { try await repo.getNodeInfo(0x1234) }
        await awaitSentCount(port, 1)
        let reqId = reqIdFrom(port.sent[0])

        repo.inject(
            fakeInfoResponse(
                reqId: reqId,
                nodeId: 0x1234,
                parentId: 0x0102,
                flags: SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE,
                sizeBytes: 4096,
                name: "kick.wav"
            )
        )

        let info = try await op.value
        XCTAssertNotNil(info)
        XCTAssertEqual(0x1234, info?.nodeId)
        XCTAssertEqual(0x0102, info?.parentId)
        XCTAssertEqual(SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE, info?.flags)
        XCTAssertEqual(Int64(4096), info?.sizeBytes)
        XCTAssertEqual("kick.wav", info?.name)
    }

    func testMismatchedReqId_doesNotCompleteMetadataOpAndReturnsEmptyFallback() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)
        // Kotlin advances virtual time past the 5 s timeout; here the timeout is shortened
        // so the real-time wait stays fast.
        repo.metadataTimeout = 0.3

        let completed = Flag()
        let op = Task { () -> [String: Any] in
            let result = try await repo.getMetadataJson(1000)
            completed.value = true
            return result
        }
        await awaitSentCount(port, 1)
        let reqId = reqIdFrom(port.sent[0])

        repo.inject(fakeMetaResponse(reqId: reqId + 1, json: "{\"name\":\"wrong\"}"))
        for _ in 0..<10 { await Task.yield() }
        XCTAssertFalse(
            completed.value,
            "Mismatched reqId response must not complete the waiting op"
        )

        // Await the op through its timeout — the empty fallback comes back.
        let result = try await op.value
        XCTAssertEqual(0, result.count)
    }

    func testInterleavedMetadataResponses_routeByReqIdWithoutSwapping() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        let first = Task { try await repo.getMetadataJson(1001) }
        await awaitSentCount(port, 1)
        let firstReqId = reqIdFrom(port.sent[0])

        let second = Task { try await repo.getMetadataJson(1002) }
        await awaitSentCount(port, 2)
        let secondReqId = reqIdFrom(port.sent[1])
        XCTAssertNotEqual(firstReqId, secondReqId)

        repo.inject(fakeMetaResponse(reqId: secondReqId, json: "{\"node\":\"second\"}"))
        repo.inject(fakeMetaResponse(reqId: firstReqId, json: "{\"node\":\"first\"}"))

        let firstResult = try await first.value
        let secondResult = try await second.value
        XCTAssertEqual("first", firstResult["node"] as? String)
        XCTAssertEqual("second", secondResult["node"] as? String)
    }

    func testDuplicateMatchingResponse_completesOnceWithoutCrash() async throws {
        let port = RecordingPort()
        let repo = ActiveGroupTestRepo(port)

        let op = Task { try await repo.getMetadataJson(1003) }
        await awaitSentCount(port, 1)
        let reqId = port.lastSentReqId()
        let response = fakeMetaResponse(reqId: reqId, json: "{\"dup\":true}")

        repo.inject(response)
        repo.inject(response)

        let result = try await op.value
        XCTAssertEqual(true, result["dup"] as? Bool)
    }
}
