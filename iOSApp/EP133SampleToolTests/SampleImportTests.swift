import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportTest.kt.
///
/// Tests for `MIDIRepository.putSampleFile` — node-ID INIT protocol contract.
///
/// The protocol (verified from data/index.js):
///  1. resolve /sounds → parentNodeId (done by resolveSoundsNodeId inside the locked block)
///  2. send FILE_PUT INIT via buildFileCreatePutInitFrame (parentId, fileId=0, size, filename)
///  3. send paged DATA frames via buildFilePutDataFrame
///  4. send a zero-length DATA terminator
///  5. await STATUS_OK
///
/// These tests assert the frame-level contract using a spy MIDIPort and a fake repo that
/// provides a known /sounds nodeId without hardware (resolveNodeId is overridden).
///
/// No path-string bytes are asserted — the old buildFilePutFrame path-string protocol is gone.

// ── File-level test doubles (shared by SampleImportTests and RawPcmFormatTests) ──

/// Spy port: records all sendMIDI calls for frame-level assertion.
private final class SampleImportSpyMIDIPort: MIDIPort {
    private let connected: Bool

    init(connected: Bool = false) {
        self.connected = connected
    }

    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    private(set) var sent: [[UInt8]] = []

    func setup() {}
    func close() {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}

    func getUSBDevices() -> MIDIDeviceList {
        connected
            ? MIDIDeviceList(
                inputs: [MIDIDevice(id: "in", name: "EP-133")],
                outputs: [MIDIDevice(id: "out", name: "EP-133")])
            : MIDIDeviceList(inputs: [], outputs: [])
    }

    func sendMIDI(to portId: String, data: [UInt8]) { sent.append(data) }
}

/// Fake repo that:
///  - exposes a deviceState with a connected outputPortId
///  - overrides resolveNodeId("/sounds") to return a known nodeId (42) deterministically
///    without any FILE_LIST round-trips
///  - overrides putSampleFile to skip the STATUS_OK await so the test needs no
///    deferred completion
///  - records the last metadata JSON / channels / sampleRate passed for assertion
@MainActor
private final class SampleImportFakeMIDIRepo: MIDIRepository {
    let spy: SampleImportSpyMIDIPort
    private let soundsNodeId: Int

    var lastMetadataJson: String?
    var lastChannels: Int = -1
    var lastSampleRate: Int = -1

    init(spy: SampleImportSpyMIDIPort, connected: Bool, soundsNodeId: Int = 42) {
        self.spy = spy
        self.soundsNodeId = soundsNodeId
        super.init(spy)
        if connected {
            deviceState = DeviceState(connected: true, outputPortId: "out")
        }
    }

    override func resolveNodeId(_ path: String) async throws -> Int? {
        path == "/sounds" ? soundsNodeId : nil
    }

    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        lastChannels = channels
        lastSampleRate = sampleRate
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        guard let parent = try await resolveNodeId("/sounds") else { return nil }

        let metaJson = "{\"channels\":\(channels),\"samplerate\":\(sampleRate)}"
        lastMetadataJson = metaJson

        let initFrame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: parent,
            fileSize: pcmBytes.count,
            filename: name,
            requestId: 30,
            metadataJson: metaJson)
        spy.sendMIDI(to: portId, data: initFrame)

        var page = 0
        var offset = 0
        while offset < pcmBytes.count {
            let end = min(offset + SysExProtocol.MAX_PAGE_BYTES, pcmBytes.count)
            let chunk = Array(pcmBytes[offset..<end])
            spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
                deviceId: 0, page: page, chunk: chunk, requestId: 31))
            offset = end
            page = (page + 1) & 0xFFFF
        }

        spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
            deviceId: 0, page: page, chunk: [], requestId: 31))

        return soundsNodeId  // fake: return the /sounds node id as the assigned node id
    }
}

// ── File-level helper: unpack the inner payload of a SysEx frame (frame[9..<count-1] is packed) ──
private func unpackPayload(_ frame: [UInt8]) -> [UInt8] {
    SysExProtocol.unpack7bit(Array(frame[9..<(frame.count - 1)]))
}

/// True when the frame is a FILE PUT frame (INIT, DATA, or terminator).
private func isPutFrame(_ frame: [UInt8]) -> Bool {
    guard frame.count > 10 else { return false }
    let inner = unpackPayload(frame)
    return inner.count >= 2 && Int(inner[0]) == SysExProtocol.TE_SYSEX_FILE_PUT
}

@MainActor
final class SampleImportTests: XCTestCase {

    // ──────────────────────────────────────────────────────────────────────────
    // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
    // INIT frame body: [PUT(2), INIT(0), flags=5, fileId u16, parentId u16, size u32, name+NUL]
    // DATA frames body: [PUT(2), DATA(1), pageHi, pageLo, chunk...]
    // Terminator: DATA frame with zero-length chunk (body size = 4)
    // ──────────────────────────────────────────────────────────────────────────

    func testPutSampleFile_sendsInitPlusPagedDataFrames() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true)

        // Synthetic WAV payload larger than one page (10,000 bytes → 3 DATA + 1 terminator)
        let wavBytes = (0..<10_000).map { UInt8($0 & 0xFF) }

        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        let frames = spy.sent
        // 1 INIT + ceil(size/MAX_PAGE_BYTES) DATA pages + 1 terminator
        let expectedDataFrames =
            (wavBytes.count + SysExProtocol.MAX_PAGE_BYTES - 1) / SysExProtocol.MAX_PAGE_BYTES
        let expectedTotal = 1 + expectedDataFrames + 1
        XCTAssertEqual(
            expectedTotal, frames.count,
            "Total frames: 1 INIT + \(expectedDataFrames) DATA + 1 terminator")

        // Frame 0 must be a TYPE_INIT carrying the filename; command = TE_SYSEX_FILE (5).
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE, Int(frames[0][8]) & 0x7F,
            "INIT frame[8] = TE_SYSEX_FILE (5)")
        let initPayload = unpackPayload(frames[0])
        // Body starts at subcommand: [PUT(2), INIT(0), flags, fileId u16, parentId u16, size u32, name...]
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT, Int(initPayload[0]),
            "INIT body[0] = TE_SYSEX_FILE_PUT (2)")
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, Int(initPayload[1]),
            "INIT body[1] = TYPE_INIT (0)")

        // filename "kick.wav" must appear at offset 11:
        //   [0]=PUT, [1]=INIT, [2]=flags, [3-4]=fileId u16, [5-6]=parentId u16, [7-10]=size u32
        let nameBytes = Array("kick.wav".utf8)
        let nameInInit = Array(initPayload[11..<(11 + nameBytes.count)])
        XCTAssertEqual(nameBytes, nameInInit, "INIT must carry the sanitized filename")
        XCTAssertEqual(
            0, Int(initPayload[11 + nameBytes.count]),
            "Byte after filename must be NUL terminator")

        // All DATA frames (indices 1..expectedDataFrames) must be TYPE_DATA
        for i in 1...expectedDataFrames {
            let p = unpackPayload(frames[i])
            XCTAssertEqual(
                SysExProtocol.TE_SYSEX_FILE_PUT, Int(p[0]),
                "DATA frame \(i) body[0] = TE_SYSEX_FILE_PUT (2)")
            XCTAssertEqual(
                SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, Int(p[1]),
                "DATA frame \(i) body[1] = TYPE_DATA (1)")
        }

        // Last frame must be a zero-length DATA terminator
        let termPayload = unpackPayload(frames.last!)
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, Int(termPayload[1]),
            "Terminator body[1] = TYPE_DATA (1)")
        // After [PUT(2), DATA(1), pageHi, pageLo] (4 bytes), there must be no data bytes
        XCTAssertEqual(4, termPayload.count, "Terminator must carry no chunk data (zero-length)")
    }

    func testPutSampleFile_chunkPayloadsSurvive7bitPackUnpack() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true)

        // Deterministic pattern with all byte values to catch any packing/truncation bug
        let wavBytes = (0..<10_000).map { UInt8($0 % 256) }

        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        let frames = spy.sent
        // DATA frames are frames[1] up to (but not including) the last terminator frame
        let dataFrames = frames[1..<(frames.count - 1)]

        // DATA frame body layout: [PUT(2), DATA(1), pageHi, pageLo, chunk...]
        // Chunk data starts at byte 4 of the unpacked body (no leading TE_SYSEX_FILE byte).
        let reassembled = dataFrames.flatMap { frame in
            Array(unpackPayload(frame).dropFirst(4))  // skip [PUT, DATA, pageHi, pageLo]
        }

        XCTAssertEqual(
            wavBytes, reassembled,
            "Concatenating unpacked DATA chunk payloads must reconstruct wavBytes byte-for-byte")
    }

    func testPutSampleFile_initCarriesCorrectFileIdAndParentId() async throws {
        let soundsNodeId = 42
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true, soundsNodeId: soundsNodeId)

        let wavBytes = [UInt8](repeating: 0, count: 100)
        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        let initPayload = unpackPayload(spy.sent[0])
        // Body layout: [PUT(2), INIT(0), flags, fileId u16 BE, parentId u16 BE, fileSize u32 BE, ...]
        // [0]=PUT, [1]=INIT, [2]=flags, [3-4]=fileId, [5-6]=parentId, [7-10]=fileSize
        let fileId = (Int(initPayload[3]) << 8) | Int(initPayload[4])
        XCTAssertEqual(0, fileId, "fileId must be 0 for a new file")
        let parentId = (Int(initPayload[5]) << 8) | Int(initPayload[6])
        XCTAssertEqual(soundsNodeId, parentId, "parentId must be the /sounds nodeId")
        let size = (Int(initPayload[7]) << 24) |
            (Int(initPayload[8]) << 16) |
            (Int(initPayload[9]) << 8) |
            Int(initPayload[10])
        XCTAssertEqual(wavBytes.count, size, "fileSize in INIT must equal wavBytes.count")
    }

    func testPutSampleFile_whenDisconnected_sendsNoFrames() async {
        let spy = SampleImportSpyMIDIPort(connected: false)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: false)

        let wavBytes = [UInt8](repeating: 0, count: 1000)

        // MIDIRepositoryError.noOutputPort is acceptable
        _ = try? await repo.putSampleFile(
            name: "kick.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        XCTAssertTrue(spy.sent.isEmpty, "No frames should be sent when disconnected")
    }
}

// ── PUT INIT ack-gate tests ────────────────────────────────────────────────
//
// Verifies the hardware-verified fix: putSampleFile must await the device's PUT INIT
// response before sending DATA pages. "unexpected page" was the device symptom when
// DATA frames arrived before the INIT ack.
//
// These tests use the REAL putSampleFile path (no override) with a port that simulates
// device responses via the onMIDIReceived callback.
//
// The Kotlin originals are @Ignore'd (android.util.Log + fixed 15 s PUT_ACK_TIMEOUT_MS
// cannot be stubbed in JVM unit tests). Neither constraint exists on iOS — putAckTimeout
// is a settable var — so both tests run for real here.

/// Node ID the fake device "assigns" in its PUT INIT ack body (hardware returned 193).
private let ACK_GATE_NODE_ID = 193

/// Sim port: captures sent frames and auto-replies like the hardware.
///
/// Frame routing:
///   - non-PUT FILE frames (e.g. session FILE_INIT) → FILE ack with STATUS_OK, empty body.
///   - PUT INIT frame  → when `ackPutFrames`, STATUS_SPECIFIC_SUCCESS_START with the
///     assigned node ID (u16 BE, 7-bit packed) as body; otherwise NO reply (timeout sim).
///   - PUT DATA frame  → when `ackPutFrames`, STATUS_SPECIFIC_SUCCESS_START (empty body);
///     otherwise no reply.
///   - PUT terminator  → when `ackPutFrames`, STATUS_OK; otherwise no reply.
private final class AckSimMIDIPort: MIDIPort {
    private let ackPutFrames: Bool

    init(ackPutFrames: Bool) {
        self.ackPutFrames = ackPutFrames
    }

    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    private(set) var sent: [[UInt8]] = []

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
        sent.append(data)
        guard data.count >= 10 else { return }
        let reqId = ((Int(data[6]) & 0x0F) << 7) | (Int(data[7]) & 0x7F)
        let command = Int(data[8]) & 0x7F
        guard command == SysExProtocol.TE_SYSEX_FILE else { return }

        let packed: [UInt8] = data.count > 10 ? Array(data[9..<(data.count - 1)]) : []
        let inner = packed.isEmpty ? [] : SysExProtocol.unpack7bit(packed)
        let isPut = inner.count >= 2 && Int(inner[0]) == SysExProtocol.TE_SYSEX_FILE_PUT
        if isPut && !ackPutFrames { return }  // timeout scenario: never ack PUT frames

        let isPutInit = isPut && Int(inner[1]) == SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT
        let isPutData = isPut && Int(inner[1]) == SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA
        let isTerminator = isPutData && inner.count == 4

        let status: Int
        if isPutInit || (isPutData && !isTerminator) {
            status = SysExProtocol.STATUS_SPECIFIC_SUCCESS_START
        } else {
            status = SysExProtocol.STATUS_OK  // session FILE_INIT ack + PUT terminator ack
        }

        // The PUT INIT ack carries the device-assigned node ID in body[0..1] u16 BE,
        // exactly like the hardware (2026-06-29 capture).
        let packedBody: [UInt8] = isPutInit
            ? SysExProtocol.pack7bit([
                UInt8((ACK_GATE_NODE_ID >> 8) & 0xFF),
                UInt8(ACK_GATE_NODE_ID & 0xFF),
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
            UInt8(status & 0xFF),
        ] + packedBody + [0xF7]
        onMIDIReceived?("in", response)
    }
}

/// Repo that stubs /sounds node resolution without FILE_LIST round-trips.
/// Overrides resolveSoundsNodeId (called from within putSampleFile's locked block)
/// rather than resolveNodeId, which would re-acquire fileOpMutex and deadlock.
@MainActor
private final class AckSimRepo: MIDIRepository {
    private let soundsNodeId: Int

    init(_ port: MIDIPort, soundsNodeId: Int = 7) {
        self.soundsNodeId = soundsNodeId
        super.init(port)
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func resolveSoundsNodeId() async throws -> Int? { soundsNodeId }
}

@MainActor
final class PutInitAckGateTests: XCTestCase {

    func testPutSampleFile_sendsOnlyInitFrameWhenInitAckTimesOut() async throws {
        let port = AckSimMIDIPort(ackPutFrames: false)
        let repo = AckSimRepo(port)
        // Kotlin @Ignore'd this over the un-stubbable 15 s PUT_ACK_TIMEOUT_MS; here the
        // timeout is a settable var, so shorten it and run the test for real.
        repo.putAckTimeout = 0.2

        // Use a very small WAV so paging is quick. Do NOT ack the PUT INIT → timeout.
        let wavBytes = [UInt8](repeating: 42, count: 100)
        let result = try await repo.putSampleFile(
            name: "snare.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        // Should return nil (timeout on init ack)
        XCTAssertNil(result, "putSampleFile must return nil when INIT ack times out")
        // Only the PUT INIT frame should have been sent — NO DATA frames. (The session
        // FILE_INIT handshake frame is not a PUT frame; filter to PUT frames like the
        // Kotlin assertion intends.)
        let putFrames = port.sent.filter(isPutFrame)
        XCTAssertEqual(
            1, putFrames.count,
            "Only the INIT frame must be sent when INIT ack is not received; no DATA frames")
        // Verify that single sent frame is indeed the INIT frame (body[0]=PUT=2, body[1]=INIT=0)
        let initPayload = unpackPayload(putFrames[0])
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT, Int(initPayload[0]),
            "body[0] = TE_SYSEX_FILE_PUT (2)")
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, Int(initPayload[1]),
            "body[1] = INIT type (0)")
    }

    func testPutSampleFile_sendsDataFramesAfterInitAckReceived() async throws {
        // Self-replying port: each sendMIDI call immediately triggers a device response.
        // PUT INIT → STATUS_SPECIFIC_SUCCESS_START (+ node id body); DATA →
        // STATUS_SPECIFIC_SUCCESS_START; terminator → STATUS_OK.
        let port = AckSimMIDIPort(ackPutFrames: true)
        let repo = AckSimRepo(port)

        // 100-byte WAV → 1 INIT + 1 DATA page + 1 terminator = 3 PUT frames total.
        let wavBytes = [UInt8](repeating: 1, count: 100)
        let totalExpected = 3

        let result = try await repo.putSampleFile(
            name: "hi-hat.wav", pcmBytes: wavBytes, channels: 1, sampleRate: 46875)

        XCTAssertNotNil(result, "putSampleFile must return a nodeId when device acks correctly")
        let putFrames = port.sent.filter(isPutFrame)
        XCTAssertEqual(
            totalExpected, putFrames.count,
            "INIT + 1 DATA + 1 terminator = \(totalExpected) frames")
        // The second PUT frame must be a DATA frame.
        let dataPayload = unpackPayload(putFrames[1])
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT, Int(dataPayload[0]),
            "DATA frame body[0] = PUT (2)")
        XCTAssertEqual(
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, Int(dataPayload[1]),
            "DATA frame body[1] = DATA type (1)")
    }
}

// ── Raw-PCM format-correctness tests ──────────────────────────────────────────
//
// Tests for:
//   1. [Int16] → little-endian bytes (SampleImportManager.shortArrayToLeBytes)
//   2. WAV data-chunk slicer (SampleImportManager.sliceWavData) — header stripped
//   3. putSampleFile INIT frame carries {"channels":..,"samplerate":..} metadata

@MainActor
final class RawPcmFormatTests: XCTestCase {

    // shortArrayToLeBytes and sliceWavData are pure — only need a repo to construct the manager.
    private var manager: SampleImportManager {
        SampleImportManager(
            SampleImportFakeMIDIRepo(
                spy: SampleImportSpyMIDIPort(connected: false), connected: false))
    }

    // ── 1. [Int16] → little-endian bytes ────────────────────────────────────

    func testShortArrayToLeBytes_emptyArray_returnsEmpty() {
        let result = manager.shortArrayToLeBytes([])
        XCTAssertEqual(0, result.count, "Empty [Int16] must produce empty [UInt8]")
    }

    func testShortArrayToLeBytes_singleZero_returnsTwoZeroBytes() {
        let result = manager.shortArrayToLeBytes([0])
        XCTAssertEqual([0x00, 0x00], result, "Int16 0 must encode as [0x00, 0x00]")
    }

    func testShortArrayToLeBytes_knownValues_correctLittleEndian() {
        // 0x0102 LE → [0x02, 0x01]; 0x8000 LE → [0x00, 0x80]; -1 (0xFFFF) → [0xFF, 0xFF]
        let samples: [Int16] = [0x0102, Int16(bitPattern: 0x8000), -1]
        let result = manager.shortArrayToLeBytes(samples)
        XCTAssertEqual(6, result.count, "3 shorts → 6 bytes")
        // 0x0102: low byte = 0x02, high byte = 0x01
        XCTAssertEqual(0x02, result[0], "samples[0] low byte")
        XCTAssertEqual(0x01, result[1], "samples[0] high byte")
        // 0x8000: low byte = 0x00, high byte = 0x80
        XCTAssertEqual(0x00, result[2], "samples[1] low byte")
        XCTAssertEqual(0x80, result[3], "samples[1] high byte")
        // -1 (0xFFFF): both bytes = 0xFF
        XCTAssertEqual(0xFF, result[4], "samples[2] low byte")
        XCTAssertEqual(0xFF, result[5], "samples[2] high byte")
    }

    func testShortArrayToLeBytes_roundTrip_matchesMemoryLayout() {
        // Verify the output matches the platform's native little-endian Int16 layout
        // (the Kotlin test compares against java.nio.ByteBuffer LE as the canonical
        // reference; Apple platforms are little-endian, so raw memory is that reference).
        let samples: [Int16] = (0..<100).map { Int16($0 * 317 - 15000) }
        let result = manager.shortArrayToLeBytes(samples)
        let reference = samples.withUnsafeBytes { [UInt8]($0) }
        XCTAssertEqual(reference, result, "shortArrayToLeBytes must match canonical LE encoding")
    }

    // ── 2. WAV data-chunk slicer ──────────────────────────────────────────────

    /// Build a canonical 44-byte WAV (WavEncoder format) from `pcmShorts` at 46875 Hz.
    /// Using WavEncoder directly ensures the helper and slicer agree on the header layout.
    private func buildWav(_ pcmShorts: [Int16], channels: Int = 1, sampleRate: Int = 46875) -> Data {
        WavEncoder.encodeWav(pcmShorts, sampleRate: sampleRate, channels: channels)
    }

    func testSliceWavData_canonicalWav_stripsHeader() {
        let pcm: [Int16] = [1, 2, 3, -1, 32767]
        let wav = buildWav(pcm, channels: 1, sampleRate: 46875)
        let result = manager.sliceWavData(wav)
        XCTAssertNotNil(result, "sliceWavData must return non-nil for a valid WAV")
        guard let result else { return }
        // PCM bytes = pcm.count * 2 (2 bytes per short, LE)
        XCTAssertEqual(pcm.count * 2, result.pcm.count, "PCM byte count must be pcm.count * 2")
        XCTAssertEqual(1, result.channels, "channels must be read from fmt  chunk")
        XCTAssertEqual(46875, result.sampleRate, "sampleRate must be read from fmt  chunk")
        // Verify the raw bytes match the expected LE encoding
        let expected = manager.shortArrayToLeBytes(pcm)
        XCTAssertEqual(
            expected, result.pcm, "Sliced PCM must match raw LE encoding of original shorts")
    }

    func testSliceWavData_stereoWav_correctChannels() {
        let pcm: [Int16] = (0..<20).map { Int16($0) }  // interleaved stereo
        let wav = buildWav(pcm, channels: 2, sampleRate: 46875)
        let result = manager.sliceWavData(wav)
        XCTAssertNotNil(result, "sliceWavData must handle stereo WAV")
        guard let result else { return }
        XCTAssertEqual(2, result.channels, "channels must be 2 for stereo")
        XCTAssertEqual(pcm.count * 2, result.pcm.count, "PCM bytes = pcm.count * 2")
    }

    func testSliceWavData_tooShort_returnsNil() {
        let result = manager.sliceWavData(Data(count: 10))
        XCTAssertNil(result, "sliceWavData must return nil for files shorter than 36 bytes")
    }

    func testSliceWavData_emptyData_returnsNil() {
        let result = manager.sliceWavData(Data())
        XCTAssertNil(result, "sliceWavData must return nil for empty input")
    }

    func testSliceWavData_notWav_returnsNil() {
        // Random bytes with no RIFF/WAVE magic
        let garbage = Data((0..<100).map { UInt8($0 & 0xFF) })
        let result = manager.sliceWavData(garbage)
        XCTAssertNil(result, "sliceWavData must return nil when fmt  chunk not found")
    }

    // ── 3. putSampleFile INIT frame carries metadata JSON ─────────────────────

    func testPutSampleFile_initFrameCarriesMetadataJson_mono() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true)

        let pcm = [UInt8](repeating: 0, count: 200)  // raw PCM bytes (no RIFF header)
        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        XCTAssertNotNil(repo.lastMetadataJson, "lastMetadataJson must be set")
        // Exact key names from data/index.js prepareTeenageMeta: "channels", "samplerate"
        XCTAssertTrue(
            repo.lastMetadataJson!.contains("\"channels\":1"),
            "metadata must contain \"channels\":1")
        XCTAssertTrue(
            repo.lastMetadataJson!.contains("\"samplerate\":46875"),
            "metadata must contain \"samplerate\":46875")
    }

    func testPutSampleFile_initFrameCarriesMetadataJson_stereo() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true)

        let pcm = [UInt8](repeating: 0, count: 400)
        _ = try await repo.putSampleFile(
            name: "loop.wav", pcmBytes: pcm, channels: 2, sampleRate: 46875)

        XCTAssertNotNil(repo.lastMetadataJson, "lastMetadataJson must be set for stereo")
        XCTAssertTrue(
            repo.lastMetadataJson!.contains("\"channels\":2"),
            "metadata must contain \"channels\":2")
    }

    func testPutSampleFile_initFrameMetadataAppearsInSentFrame() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = SampleImportFakeMIDIRepo(spy: spy, connected: true)

        let pcm = [UInt8](repeating: 0, count: 100)
        _ = try await repo.putSampleFile(
            name: "snare.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        // Verify the metadata JSON actually appears in the packed INIT frame bytes.
        // The INIT frame is frames[0]. Unpack its payload and scan for the JSON bytes.
        XCTAssertFalse(spy.sent.isEmpty, "At least one frame must be sent")
        let initPayload = unpackPayload(spy.sent[0])
        let payloadText = SysExProtocol.asciiString(initPayload)
        XCTAssertTrue(
            payloadText.contains("channels"),
            "INIT payload must contain 'channels' key from metadata JSON; payload=\(payloadText)")
        XCTAssertTrue(
            payloadText.contains("samplerate"),
            "INIT payload must contain 'samplerate' key from metadata JSON; payload=\(payloadText)")
    }
}

// ── Chunk-size computation tests ──────────────────────────────────────────────
//
// Verifies computeSampleChunkSize against the reference formula from data/index.js
// calculateMaxPayloadLength. The formula must produce chunk sizes that fit within the
// device's negotiated USB packet budget so DATA pages are not rejected as "unexpected page".

@MainActor
final class ChunkSizeComputationTests: XCTestCase {

    // Use a throwaway repo just to call computeSampleChunkSize — it is internal/testable.
    private var repo: MIDIRepository {
        MIDIRepository(SampleImportSpyMIDIPort(connected: false))
    }

    func testComputeSampleChunkSize_deviceChunkSize512_returns427() {
        // Reference calculation:
        //   s = 512 - 6 = 506; inner = 506 - 1 - 11 = 494
        //   maxPayload = 494 - (494 / 8) = 494 - 61 = 433
        //   raw = 433 - 6 = 427; clamp [64, 440] → 427
        XCTAssertEqual(
            427, repo.computeSampleChunkSize(512),
            "computeSampleChunkSize(512) must return 427 (reference formula from data/index.js)")
    }

    func testComputeSampleChunkSize_zero_returns256() {
        // deviceChunkSize=0 means unknown/unset — fall back to safe default 256.
        XCTAssertEqual(
            256, repo.computeSampleChunkSize(0),
            "computeSampleChunkSize(0) must return fallback 256")
    }

    func testComputeSampleChunkSize_negative_returns256() {
        XCTAssertEqual(
            256, repo.computeSampleChunkSize(-1),
            "computeSampleChunkSize(-1) must return fallback 256")
    }

    func testComputeSampleChunkSize_tinyChunkSize_clampsToMinimum() {
        // A very small chunkSize (e.g. 20) would produce a negative or tiny raw chunk.
        // Must be clamped to at least 64.
        XCTAssertTrue(
            repo.computeSampleChunkSize(20) >= 64,
            "computeSampleChunkSize(20) must be >= 64 (minimum clamp)")
    }

    func testComputeSampleChunkSize_largeChunkSize_clampsToMaximum() {
        // A very large chunkSize must never exceed 440.
        XCTAssertTrue(
            repo.computeSampleChunkSize(65536) <= 440,
            "computeSampleChunkSize(65536) must be <= 440 (maximum clamp)")
    }
}

// ── Per-page ack gating tests ──────────────────────────────────────────────────
//
// Verifies that putSampleFile sends ceil(size/chunkSize) DATA frames plus a zero-length
// terminator. A fake repo replicates the paging logic with a custom rawChunk size and
// records all sent frames via the spy port (mirrors the Kotlin ChunkFakeRepo).

/// A standalone fake repo (not inheriting from SampleImportFakeMIDIRepo) that exercises
/// putSampleFile with a custom rawChunk size. Overrides resolveNodeId without hardware
/// round-trips and overrides putSampleFile to send frames using rawChunk slicing,
/// recording all sent frames via the spy port.
@MainActor
private final class ChunkFakeRepo: MIDIRepository {
    private let spy: SampleImportSpyMIDIPort
    private let rawChunk: Int
    private let soundsNodeId: Int

    init(spy: SampleImportSpyMIDIPort, rawChunk: Int, soundsNodeId: Int = 7) {
        self.spy = spy
        self.rawChunk = rawChunk
        self.soundsNodeId = soundsNodeId
        super.init(spy)
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func resolveNodeId(_ path: String) async throws -> Int? {
        path == "/sounds" ? soundsNodeId : nil
    }

    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        guard let parent = try await resolveNodeId("/sounds") else { return nil }

        let metaJson = "{\"channels\":\(channels),\"samplerate\":\(sampleRate)}"
        let initFrame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: parent,
            fileSize: pcmBytes.count,
            filename: name,
            requestId: 30,
            metadataJson: metaJson)
        spy.sendMIDI(to: portId, data: initFrame)

        var page = 0
        var offset = 0
        while offset < pcmBytes.count {
            let end = min(offset + rawChunk, pcmBytes.count)
            let chunk = Array(pcmBytes[offset..<end])
            spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
                deviceId: 0, page: page, chunk: chunk, requestId: 31))
            offset = end
            page = (page + 1) & 0xFFFF
        }
        spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
            deviceId: 0, page: page, chunk: [], requestId: 31))
        return soundsNodeId  // fake node ID
    }
}

@MainActor
final class PerPageAckGatingTests: XCTestCase {

    func testPutSampleFile_chunkSize427_correctPageCountFor1000bytes() async throws {
        let chunkSize = 427
        let payloadSize = 1000
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = ChunkFakeRepo(spy: spy, rawChunk: chunkSize)

        let pcm = (0..<payloadSize).map { UInt8($0 & 0xFF) }
        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        let frames = spy.sent
        let expectedDataPages = (payloadSize + chunkSize - 1) / chunkSize  // ceil div = 3
        let expectedTotal = 1 + expectedDataPages + 1  // INIT + DATA pages + terminator
        XCTAssertEqual(
            expectedTotal, frames.count,
            "1 INIT + \(expectedDataPages) DATA pages (chunk=\(chunkSize), size=\(payloadSize)) + 1 terminator")
        // Last frame must be the zero-length terminator (body size = 4: PUT, DATA, pageHi, pageLo)
        let termPayload = unpackPayload(frames.last!)
        XCTAssertEqual(
            4, termPayload.count, "Terminator body must be exactly 4 bytes (no chunk data)")
    }

    func testPutSampleFile_chunkSize427_exactlyOnePage_for427bytes() async throws {
        let chunkSize = 427
        let payloadSize = chunkSize  // exactly one page
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = ChunkFakeRepo(spy: spy, rawChunk: chunkSize)

        let pcm = [UInt8](repeating: 0, count: payloadSize)
        _ = try await repo.putSampleFile(
            name: "snare.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        let expectedTotal = 1 + 1 + 1  // INIT + 1 DATA + terminator
        XCTAssertEqual(
            expectedTotal, spy.sent.count,
            "Exactly one DATA page when size == chunkSize")
    }

    func testPutSampleFile_chunkSize427_oneByteOverOnePage_twoPages() async throws {
        let chunkSize = 427
        let payloadSize = chunkSize + 1  // one byte into second page
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = ChunkFakeRepo(spy: spy, rawChunk: chunkSize)

        let pcm = [UInt8](repeating: 0, count: payloadSize)
        _ = try await repo.putSampleFile(
            name: "hat.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        let expectedTotal = 1 + 2 + 1  // INIT + 2 DATA + terminator
        XCTAssertEqual(
            expectedTotal, spy.sent.count,
            "Two DATA pages when size == chunkSize + 1")
    }
}

// ── reqId uniqueness tests ─────────────────────────────────────────────────────
//
// Hardware-proven (2026-06-24): the device echoes the request reqId in each PUT response.
// putSampleFile must assign a UNIQUE reqId to every frame (INIT, each DATA page, terminator)
// so the dispatcher can match responses to the correct in-flight deferred.
//
// reqId is encoded in frame[6] (high bits) and frame[7] (low 7 bits):
//   reqId = ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F)
//
// The test decodes reqId from each frame sent by a fake repo (putSampleFile overridden to
// use the real unique-reqId logic without hardware ack waits) and asserts:
//  1. No two frames share the same reqId.
//  2. reqIds form a strictly incrementing sequence starting at PUT_INIT_REQUEST_ID.

private func decodeReqId(_ frame: [UInt8]) -> Int {
    ((Int(frame[6]) & 0x0F) << 7) | (Int(frame[7]) & 0x7F)
}

/// Fake repo that replicates the unique-reqId frame emission of the real putSampleFile
/// WITHOUT awaiting device acks (so tests are synchronous and require no fake device).
///
/// Uses the real SysExProtocol frame builders and the same reqId counter logic as the
/// production code. Data chunk size is fixed at `rawChunk` for deterministic page counts.
@MainActor
private final class UniqueReqIdFakeRepo: MIDIRepository {
    private let spy: SampleImportSpyMIDIPort
    private let rawChunk: Int

    init(spy: SampleImportSpyMIDIPort, rawChunk: Int = 427) {
        self.spy = spy
        self.rawChunk = rawChunk
        super.init(spy)
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func resolveNodeId(_ path: String) async throws -> Int? {
        path == "/sounds" ? 7 : nil
    }

    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        guard let portId = deviceState.outputPortId else {
            throw MIDIRepositoryError.noOutputPort
        }
        guard let parent = try await resolveNodeId("/sounds") else { return nil }
        let metaJson = "{\"channels\":\(channels),\"samplerate\":\(sampleRate)}"

        // Mirror the real putSampleFile reqId scheme: start at PUT_INIT_REQUEST_ID,
        // increment for each frame, mask to 14-bit.
        var nextReqId = MIDIRepository.PUT_INIT_REQUEST_ID

        // INIT frame
        spy.sendMIDI(to: portId, data: SysExProtocol.buildFileCreatePutInitFrame(
            deviceId: 0,
            parentNodeId: parent,
            fileSize: pcmBytes.count,
            filename: name,
            requestId: nextReqId,
            metadataJson: metaJson))
        nextReqId = (nextReqId + 1) & 0x3FFF

        // DATA pages
        var page = 0
        var offset = 0
        while offset < pcmBytes.count {
            let end = min(offset + rawChunk, pcmBytes.count)
            let chunk = Array(pcmBytes[offset..<end])
            spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
                deviceId: 0, page: page, chunk: chunk, requestId: nextReqId))
            nextReqId = (nextReqId + 1) & 0x3FFF
            offset = end
            page = (page + 1) & 0xFFFF
        }

        // Terminator
        spy.sendMIDI(to: portId, data: SysExProtocol.buildFilePutDataFrame(
            deviceId: 0, page: page, chunk: [], requestId: nextReqId))

        return 7  // fake node ID (matches resolveNodeId("/sounds") = 7)
    }
}

@MainActor
final class ReqIdUniquenessTests: XCTestCase {

    func testPutSampleFile_emitsUniqueIncrementingReqIds_acrossInitDataTerminator() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = UniqueReqIdFakeRepo(spy: spy, rawChunk: 427)

        // 1000 bytes → 3 DATA pages (ceil(1000/427) = 3) + 1 INIT + 1 terminator = 5 frames
        let pcm = (0..<1000).map { UInt8($0 & 0xFF) }
        _ = try await repo.putSampleFile(
            name: "kick.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        let frames = spy.sent
        XCTAssertTrue(
            frames.count >= 3,
            "At least 3 frames must be sent (INIT + ≥1 DATA + terminator)")

        let reqIds = frames.map(decodeReqId)

        // All reqIds must be unique
        XCTAssertEqual(
            reqIds.count, Set(reqIds).count,
            "All frame reqIds must be unique — got duplicates: \(reqIds)")

        // reqIds must be strictly increasing starting at PUT_INIT_REQUEST_ID
        XCTAssertEqual(
            MIDIRepository.PUT_INIT_REQUEST_ID, reqIds[0],
            "First frame (INIT) reqId must be PUT_INIT_REQUEST_ID (\(MIDIRepository.PUT_INIT_REQUEST_ID))")
        for i in 1..<reqIds.count {
            XCTAssertEqual(
                reqIds[i - 1] + 1, reqIds[i],
                "reqId must increment by 1 from frame \(i - 1) to \(i)")
        }
    }

    func testPutSampleFile_singlePage_hasUniqueReqIds() async throws {
        let spy = SampleImportSpyMIDIPort(connected: true)
        let repo = UniqueReqIdFakeRepo(spy: spy, rawChunk: 427)

        // 100 bytes → 1 DATA page: INIT(30) + DATA(31) + terminator(32) = 3 frames
        let pcm = [UInt8](repeating: 0, count: 100)
        _ = try await repo.putSampleFile(
            name: "snare.wav", pcmBytes: pcm, channels: 1, sampleRate: 46875)

        let frames = spy.sent
        XCTAssertEqual(3, frames.count, "1 INIT + 1 DATA + 1 terminator = 3 frames")

        let reqIds = frames.map(decodeReqId)
        XCTAssertEqual(MIDIRepository.PUT_INIT_REQUEST_ID, reqIds[0], "INIT reqId")
        XCTAssertEqual(MIDIRepository.PUT_INIT_REQUEST_ID + 1, reqIds[1], "DATA reqId")
        XCTAssertEqual(MIDIRepository.PUT_INIT_REQUEST_ID + 2, reqIds[2], "terminator reqId")
    }
}

// Mismatched / duplicate reqId rejection is enforced by FileWaiterRegistry and covered
// by FileWaiterRegistryTests (unknown reqId → Unmatched, duplicate → Unmatched, interleaved
// ops route to their own waiter) plus the end-to-end FileCorrelationDispatchTests.
