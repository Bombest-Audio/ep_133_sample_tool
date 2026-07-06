import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/PutDataAckTest.kt.
///
/// Tests for PUT DATA page-ack routing through MIDIRepository.dispatchSysEx.
///
/// Bug (fixed 2026-06-25): `if body.isEmpty { return }` dropped the STATUS-ONLY empty-body
/// page ack from the device before reqId-matching, leaving the page-0 ack pending until a
/// 15-second timeout. The guard now bails only when there is no status byte at all
/// (`payload.isEmpty`), and PUT completion is based on the extracted status even when the
/// body is empty. These tests drive the full ack path via a MIDIPort that auto-responds
/// with hardware-realistic frames (status-only, empty body) when sendMIDI is called.

/// Node ID the fake device "assigns" in its PUT INIT ack body (hardware returned 193).
private let INIT_NODE_ID = 193

/// A MIDIPort that, on each sendMIDI call, immediately fires onMIDIReceived with a response
/// frame whose status is controlled by `statusForDataAcks`.
///
/// Frame routing:
///   - FILE_INIT frame → FILE ack with status=0, empty body.
///   - PUT INIT frame  → FILE ack with status=0 and the assigned node ID (u16 BE) as body.
///   - PUT DATA frame  → FILE ack with status=`statusForDataAcks` + empty body.
///
/// The response is a minimal raw SysEx: F0 00 20 76 00 40 <reqHigh> <reqLow> 05 <status> F7
/// exactly what the hardware emits for a STATUS-ONLY ack.
private final class AutoAckMIDIPort: MIDIPort {
    private let statusForDataAcks: Int

    init(statusForDataAcks: Int = SysExProtocol.STATUS_OK) {
        self.statusForDataAcks = statusForDataAcks
    }

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
        // Extract reqId from the outbound frame (layout matches dispatchSysEx extraction):
        //   frame[6] low nibble = reqId >> 7; frame[7] = reqId & 0x7F
        if data.count < 10 { return }
        let reqId = ((Int(data[6]) & 0x0F) << 7) | (Int(data[7]) & 0x7F)
        let command = Int(data[8]) & 0x7F

        // Only respond to FILE-command frames (command = TE_SYSEX_FILE = 5)
        if command != SysExProtocol.TE_SYSEX_FILE { return }

        // The outbound payload is 7-bit-packed starting at data[9].
        let packedPayload: [UInt8] = data.count > 10 ? Array(data[9..<(data.count - 1)]) : []
        let innerPayload = packedPayload.isEmpty ? [] : SysExProtocol.unpack7bit(packedPayload)

        // PUT INIT frames carry innerPayload[0]=PUT, [1]=INIT; PUT DATA carry [1]=DATA.
        let isPut = innerPayload.count >= 2 &&
            Int(innerPayload[0]) == SysExProtocol.TE_SYSEX_FILE_PUT
        let isPutData = isPut &&
            Int(innerPayload[1]) == SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA
        let isPutInit = isPut &&
            Int(innerPayload[1]) == SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT
        let responseStatus = isPutData ? statusForDataAcks : SysExProtocol.STATUS_OK

        // DATA/terminator acks are STATUS-ONLY with an empty body (hardware-confirmed — that
        // emptiness is the regression under test). The PUT INIT ack instead carries the
        // device-assigned node ID in body[0..1] u16 BE, exactly like the hardware.
        let packedBody: [UInt8] = isPutInit
            ? SysExProtocol.pack7bit([
                UInt8((INIT_NODE_ID >> 8) & 0xFF),
                UInt8(INIT_NODE_ID & 0xFF),
            ])
            : []
        let response: [UInt8] = [
            0xF0,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,  // deviceId
            0x40,  // TE subsystem
            UInt8((reqId >> 7) & 0x0F),
            UInt8(reqId & 0x7F),
            UInt8(SysExProtocol.TE_SYSEX_FILE),
            UInt8(responseStatus & 0xFF),
        ] + packedBody + [0xF7]
        onMIDIReceived?("in", response)
    }
}

/// Fake repo that stubs /sounds node resolution to skip FILE_LIST round-trips, letting
/// putSampleFile proceed to the INIT/DATA/ack cycle without hardware. Overrides
/// `resolveSoundsNodeId` (called from within the fileOpMutex-locked block) rather than
/// `resolveNodeId` to avoid acquiring the mutex a second time.
@MainActor
private final class PutAckTestRepo: MIDIRepository {
    override init(_ port: MIDIPort) {
        super.init(port)
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func resolveSoundsNodeId() async throws -> Int? { 42 }
}

@MainActor
final class PutDataAckTests: XCTestCase {

    /// Empty-body STATUS_OK ack on the awaited PUT DATA reqId must complete the page ack.
    ///
    /// Before the fix, the empty-body guard in dispatchSysEx dropped this ack entirely —
    /// the waiter timed out after 15 s and putSampleFile failed. After the fix, the
    /// status-only ack is routed and the page completes.
    func testPutSampleFile_emptyBodyStatusOkAck_completesPageAckTrue() async throws {
        let port = AutoAckMIDIPort(statusForDataAcks: SysExProtocol.STATUS_OK)
        let repo = PutAckTestRepo(port)

        // Ensure file session initialized using auto-ack port (FILE_INIT → empty-body status=0).
        _ = try await repo.ensureFileSessionInit()

        // Upload 1 byte so we get exactly one DATA page + one terminator.
        let result = try await repo.putSampleFile(name: "kick.wav", pcmBytes: [0x42])

        // The empty-body DATA/terminator acks must complete the pages (the regression under
        // test), and the nodeId must come through from the INIT ack body — putSampleFile
        // returns nil when no nodeId can be determined, so non-nil here proves both.
        XCTAssertEqual(
            INIT_NODE_ID,
            result,
            "putSampleFile must return the INIT-ack nodeId when device sends empty-body STATUS_OK DATA acks"
        )
    }

    /// Empty-body non-OK status on the awaited PUT DATA reqId must complete the page ack
    /// as failure. Status = 1 is not STATUS_OK (0) and not >= SUCCESS_START (64), so it is
    /// an error status — the upload aborts.
    func testPutSampleFile_emptyBodyErrorStatusAck_completesPageAckFalse() async throws {
        let port = AutoAckMIDIPort(statusForDataAcks: 1 /* error status */)
        let repo = PutAckTestRepo(port)

        _ = try await repo.ensureFileSessionInit()

        let result = try await repo.putSampleFile(name: "kick.wav", pcmBytes: [0x42])

        XCTAssertNil(result, "putSampleFile must return nil when device sends non-OK status on page ack")
    }
}
