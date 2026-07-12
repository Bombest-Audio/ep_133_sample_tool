import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/FileReqIdDedupTest.kt.
///
/// Tests for the reqId-match guard on FILE (cmd=5) responses.
///
/// Root cause (hardware-confirmed 2026-06-24): the device sends each response twice due to
/// a duplicate receiver connection. Without reqId filtering, a duplicate FILE_INIT response
/// arriving just after the LIST waiter is registered would complete it with the INIT body —
/// causing resolveNodeId to return nil and aborting the upload. These tests verify the
/// dispatcher ignores stale/duplicate responses and passes matching ones.
@MainActor
final class FileReqIdDedupTests: XCTestCase {

    // ── Test double: spy MIDIPort that records sent frames ──────────────────

    private final class SpyPort: MIDIPort {
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

        /// Extract the reqId from the last sent FILE frame (frame[6] high nibble + frame[7] low 7).
        func lastSentReqId() -> Int {
            guard let frame = sent.last, frame.count >= 8 else { return -1 }
            return ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
        }
    }

    // ── Test doubles ─────────────────────────────────────────────────────────

    /// Exposes dispatchSysEx for frame injection and overrides resolveNodeId to avoid
    /// hardware round-trips for tests 1–3.
    private final class TestableRepo: MIDIRepository {
        override init(_ port: MIDIPort) {
            super.init(port)
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }

        /// Inject a raw SysEx frame as if received from the device.
        func injectSysEx(_ frame: [UInt8]) { dispatchSysEx(frame) }

        /// Override resolveNodeId to return a fixed nodeId without hardware round-trips.
        override func resolveNodeId(_ path: String) async throws -> Int? {
            path == "/sounds" ? 99 : nil
        }
    }

    /// Exposes dispatchSysEx WITHOUT overriding resolveNodeId — used by the poisoned-LIST
    /// test, which drives the real ensureFileSessionInit + listNodeBody pipeline so the
    /// INIT→LIST reqId transition can be observed and stale-response injection tested.
    private final class RealWalkRepo: MIDIRepository {
        override init(_ port: MIDIPort) {
            super.init(port)
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }

        func injectSysEx(_ frame: [UInt8]) { dispatchSysEx(frame) }
    }

    // ── Frame-building helpers ───────────────────────────────────────────────

    /// Build a minimal FILE (cmd=5) response frame for injection into dispatchSysEx.
    ///
    /// SysExProtocol.buildFrame packs the ENTIRE payload; the dispatcher reads payload[0]
    /// (here: the pack high-bits byte, 0x00 for these all-low bytes) as the status and
    /// unpacks the rest — which lands on exactly the real HW INIT body shape. The reqId
    /// byte positions are identical between request and response frames.
    private func buildFakeFileResponse(reqId: Int, statusByte: Int = 0) -> [UInt8] {
        // Body (pre-pack): status, then dummy chunk-size bytes matching a real HW INIT.
        let rawBody: [UInt8] = [
            UInt8(statusByte & 0xFF),
            0x00, 0x0C, 0x00, 0x00, 0x02, 0x00,
        ]
        return SysExProtocol.buildFrame(
            deviceId: 0,
            command: SysExProtocol.TE_SYSEX_FILE,
            requestId: reqId,
            payload: rawBody
        )
    }

    /// Build a minimal FILE_LIST response frame that the dispatcher will correctly parse.
    ///
    /// The real device layout puts the RAW status byte at position 9 and only the body is
    /// 7-bit packed, so this cannot use buildFrame (which would pack the status byte too).
    /// Body content after unpacking = page u16 BE followed by entry bytes; the FILE_LIST
    /// handler strips the page word and passes the rest to parseFileListEntries.
    private func buildFakeFileListResponse(reqId: Int) -> [UInt8] {
        // Entry for "sounds" with nodeId=99.
        let entry: [UInt8] = [
            0x00, 0x63,              // nodeId = 99
            0x02,                    // flags = DIRECTORY — sounds is a dir
            0x00, 0x00, 0x00, 0x00,  // size = 0
        ] + Array("sounds".utf8) + [0x00]
        // Body = page u16 (0x0000) + entry
        let body: [UInt8] = [0x00, 0x00] + entry
        let packed = SysExProtocol.pack7bit(body)
        return [
            0xF0,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,  // deviceId
            0x40,
            UInt8((reqId >> 7) & 0x0F),
            UInt8(reqId & 0x7F),
            UInt8(SysExProtocol.TE_SYSEX_FILE),
            0x00,  // status = STATUS_OK (raw byte at position 9, NOT packed)
        ] + packed + [0xF7]
    }

    private func awaitSentCount(_ port: SpyPort, _ count: Int) async {
        for _ in 0..<200 {
            if port.sent.count >= count { return }
            await Task.yield()
        }
    }

    private final class Flag {
        var value = false
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /// A FILE response whose reqId matches the registered waiter completes the in-flight op.
    ///
    /// With nextFileReqId() the INIT reqId is no longer a fixed constant — it is whatever
    /// the counter returns at test time, so it is read from the sent frame.
    func testMatchingFileResponse_completesDeferred() async throws {
        let port = SpyPort()
        let repo = TestableRepo(port)

        // Drive ensureFileSessionInit asynchronously — it awaits the INIT waiter.
        let initTask = Task { try await repo.ensureFileSessionInit() }

        // Wait until the frame is sent (the waiter is registered before sendMIDI).
        await awaitSentCount(port, 1)

        // Extract the reqId the repo used from the sent frame.
        let initReqId = port.lastSentReqId()
        XCTAssertTrue(initReqId > 0, "port should have sent a FILE_INIT frame (reqId > 0)")

        // Inject a matching response.
        repo.injectSysEx(buildFakeFileResponse(reqId: initReqId))

        // ensureFileSessionInit should have returned true (init waiter completed).
        let initResult = try await initTask.value
        XCTAssertTrue(initResult, "Matching FILE response should complete the INIT waiter")
    }

    /// A FILE response with a stale/duplicate reqId (mismatch) does NOT complete any waiter.
    func testStaleFileResponse_doesNotCompleteDeferred() async throws {
        let port = SpyPort()
        let repo = TestableRepo(port)

        // Start FILE_INIT — the INIT waiter is live.
        let initCompleted = Flag()
        let initTask = Task {
            _ = try await repo.ensureFileSessionInit()
            initCompleted.value = true
        }
        await awaitSentCount(port, 1)

        let initReqId = port.lastSentReqId()
        XCTAssertTrue(initReqId > 0)

        // Inject a stale response with a different reqId (initReqId + 1 wraps safely).
        let staleReqId = initReqId < MIDIRepository.FILE_REQ_ID_MAX ? initReqId + 1 : initReqId - 1
        repo.injectSysEx(buildFakeFileResponse(reqId: staleReqId))

        // Give the task a chance to complete if the waiter was wrongly completed.
        for _ in 0..<10 { await Task.yield() }

        XCTAssertFalse(
            initCompleted.value,
            "Stale FILE response (reqId=\(staleReqId)) must not complete INIT waiter (awaiting reqId=\(initReqId))"
        )

        // Clean up: cancel the dangling task.
        initTask.cancel()
        _ = try? await initTask.value
    }

    /// A duplicate FILE_INIT response (same reqId, arrives again) is dropped after the first
    /// match has been consumed: the waiter is deregistered, so the duplicate routes as
    /// unmatched — no double-complete, no crash.
    func testDuplicateMatchingResponse_afterFirstConsumed_isHandledGracefully() async throws {
        let port = SpyPort()
        let repo = TestableRepo(port)

        // Drive FILE_INIT to completion.
        let initTask = Task { try await repo.ensureFileSessionInit() }
        await awaitSentCount(port, 1)

        let initReqId = port.lastSentReqId()
        XCTAssertTrue(initReqId > 0)

        let response = buildFakeFileResponse(reqId: initReqId)
        // First injection: matches, completes the waiter, deregisters it.
        repo.injectSysEx(response)
        _ = try await initTask.value

        // Second injection of the exact same frame: no live waiter — must not throw or
        // double-complete. (dispatchSysEx drops unmatched responses.)
        repo.injectSysEx(response)
    }

    /// When a FILE_INIT duplicate arrives while the LIST waiter is in flight, the duplicate
    /// is ignored — the exact hardware-confirmed failure mode. The reqIds differ →
    /// duplicate dropped → LIST waiter unaffected.
    func testDuplicateInitResponse_doesNotPoisonListDeferred() async throws {
        let port = SpyPort()
        let repo = RealWalkRepo(port)

        // Drive resolveNodeId("/sounds") — the real implementation acquires the mutex, then:
        //   ensureFileSessionInitNoLock (sends FILE_INIT) → resolveNodeIdInternal (sends
        //   FILE_LIST of root). Both reqIds are observed from the sent frames.
        let resolveTask = Task { try await repo.resolveNodeId("/sounds") }

        // Let resolveNodeId start and reach the FILE_INIT send.
        await awaitSentCount(port, 1)

        let initReqId = port.lastSentReqId()
        XCTAssertTrue(initReqId > 0, "port should have sent FILE_INIT")

        // Inject the FILE_INIT response → INIT waiter completes and deregisters.
        repo.injectSysEx(buildFakeFileResponse(reqId: initReqId))

        // Now resolveNodeIdInternal sends a FILE_LIST of the root node (segment="sounds").
        await awaitSentCount(port, 2)
        let listReqId = port.lastSentReqId()
        XCTAssertTrue(listReqId > 0, "port should have sent FILE_LIST after INIT")
        XCTAssertNotEqual(listReqId, initReqId, "LIST reqId must differ from INIT reqId")

        // Inject a STALE DUPLICATE of the INIT response — simulates the HW duplicate arriving
        // late. With reqId routing: initReqId has no live waiter → dropped.
        repo.injectSysEx(buildFakeFileResponse(reqId: initReqId))
        await Task.yield()

        // The LIST waiter must NOT have been completed by the stale INIT duplicate.
        // Inject the CORRECT LIST response with a "sounds" entry → LIST waiter completes.
        repo.injectSysEx(buildFakeFileListResponse(reqId: listReqId))
        let nodeId = try await resolveTask.value

        // If the stale INIT response had poisoned the LIST waiter, resolveNodeId would have
        // returned nil (no "sounds" in an INIT body). With reqId routing the real LIST
        // response arrived and nodeId is the "sounds" entry's nodeId (99).
        XCTAssertNotNil(
            nodeId,
            "resolveNodeId must succeed after stale INIT duplicate is dropped and real LIST arrives"
        )
    }
}
