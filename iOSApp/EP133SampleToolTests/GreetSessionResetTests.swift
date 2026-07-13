import XCTest
@testable import EP133SampleTool

/// Regression test for issue #27, option 1: the session reset lives on the port **connect edge**
/// (`updateDeviceStateOnly` → `resetFileSessionForNewConnection()`), not on the greet response.
///
/// A greet response is therefore pure data (firmware/identity) and must NOT fail in-flight file
/// waiters. The old reset-on-greet path could tear down a healthy session when a second greet
/// arrived mid-op — but the device does not double-send on the wire (hardware-verified; the
/// historical "double-send" was an already-fixed dual-receiver artifact), so resetting from the
/// greet handler was both unnecessary and hazardous.
///
/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/GreetSessionResetTest.kt.
@MainActor
final class GreetSessionResetTests: XCTestCase {

    private func reqIdFrom(_ frame: [UInt8]) -> Int {
        ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
    }

    /// A CMD_GREET response frame (device identity + firmware), status 0.
    private func greetFrame(deviceId: UInt8 = 0x33) -> [UInt8] {
        let ascii = Array("product:EP-133;sw_version:2.5.0".utf8) + [0x00]
        return [
            SysExProtocol.MIDI_SYSEX_START,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            deviceId,
            SysExProtocol.MIDI_SYSEX_TE,
            UInt8(SysExProtocol.BIT_REQUEST_ID_AVAILABLE & 0xFF),
            0x01,
            UInt8(SysExProtocol.CMD_GREET),
            0x00,
        ] + SysExProtocol.pack7bit(ascii) + [SysExProtocol.MIDI_SYSEX_END]
    }

    /// A FILE_INFO response echoing `reqId` for node `nodeId`.
    private func infoResponse(reqId: Int, nodeId: Int) -> [UInt8] {
        let body: [UInt8] = [
            UInt8((nodeId >> 8) & 0xFF),
            UInt8(nodeId & 0xFF),
            0x01, 0x02,                 // parentId
            UInt8(SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE & 0xFF),
            0x00, 0x00, 0x10, 0x00,     // size
        ] + Array("kick.wav".utf8) + [0x00]
        let packed = SysExProtocol.pack7bit(body)
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
        ] + packed + [SysExProtocol.MIDI_SYSEX_END]
    }

    private func awaitSentCount(_ port: RecordingPort, _ count: Int) async {
        for _ in 0..<200 {
            if port.sent.count >= count { return }
            await Task.yield()
        }
    }

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
    }

    private final class TestRepo: MIDIRepository {
        override init(_ port: MIDIPort) {
            super.init(port)
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }
        func inject(_ frame: [UInt8]) { dispatchSysEx(frame) }
    }

    func testGreetResponse_doesNotTearDownInFlightFileOp() async throws {
        // Arrange: a file op is in flight, its reqId-correlated waiter registered.
        let port = RecordingPort()
        let repo = TestRepo(port)
        let op = Task { try await repo.getNodeInfo(0x1234) }
        await awaitSentCount(port, 1)
        let reqId = reqIdFrom(port.sent[0])

        // Act: a greet response lands mid-op (e.g. a stats refresh). This used to fail the waiter.
        repo.inject(greetFrame())
        await Task.yield()

        // Assert: the real file response still routes by reqId and completes the op — the greet
        // did not tear it down.
        repo.inject(infoResponse(reqId: reqId, nodeId: 0x1234))
        let info = try await op.value
        XCTAssertNotNil(info, "the file op completes on its own response, unaffected by the greet")
        XCTAssertEqual(0x1234, info?.nodeId)
    }
}
