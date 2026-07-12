import XCTest
@testable import EP133SampleTool

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/// Spy MIDIPort for KitViewModel tests.
private final class KitSpyMIDIPort: MIDIPort {
    private let connected: Bool
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?
    var sent: [[UInt8]] = []

    init(connected: Bool = false) {
        self.connected = connected
    }

    func setup() {}
    func close() {}

    func getUSBDevices() -> MIDIDeviceList {
        if connected {
            return MIDIDeviceList(
                inputs: [MIDIDevice(id: "in", name: "EP-133")],
                outputs: [MIDIDevice(id: "out", name: "EP-133")]
            )
        }
        return MIDIDeviceList(inputs: [], outputs: [])
    }

    func sendMIDI(to portId: String, data: [UInt8]) { sent.append(data) }
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
}

/// Fake MIDIRepository for KitViewModel tests.
///
/// Records all `putSampleFile` calls as `PutCall` and all `assignSampleToPad` calls as
/// `AssignCall` so tests can assert on count, order, and per-call arguments.
///
/// `putSampleFile` returns a synthetic nodeId (the call index, 1-based) when connected,
/// nil when disconnected — matching the MIDIRepository contract.
@MainActor
private final class KitFakeMIDIRepo: MIDIRepository {

    struct PutCall {
        let name: String
        let pcmBytes: [UInt8]
        let channels: Int
        let sampleRate: Int
    }

    struct AssignCall {
        let group: PadChannel
        let gridIndex: Int
        let sampleNodeId: Int
        let sampleStart: Int
        let sampleEnd: Int
        let muteGroup: Bool
    }

    private var putCallCount = 0
    var putCalls: [PutCall] = []
    var assignCalls: [AssignCall] = []

    init(_ spy: KitSpyMIDIPort, connected: Bool = false) {
        super.init(spy)
        deviceState = DeviceState(
            connected: connected,
            outputPortId: connected ? "out" : nil
        )
    }

    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        putCalls.append(PutCall(name: name, pcmBytes: pcmBytes, channels: channels, sampleRate: sampleRate))
        if deviceState.connected {
            putCallCount += 1   // returns 1, 2, 3, … (never 0 or nil when connected)
            return putCallCount
        }
        return nil
    }

    override func assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
        muteGroup: Bool
    ) async throws -> Bool {
        assignCalls.append(AssignCall(
            group: group, gridIndex: gridIndex, sampleNodeId: sampleNodeId,
            sampleStart: sampleStart, sampleEnd: sampleEnd, muteGroup: muteGroup))
        return deviceState.connected
    }
}

// SampleImportManager is used as a real instance — chopFromPcm / kitFromPcm only call
// manager.sanitizeName() (pure string logic), so no hardware or decoder is touched.

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/KitViewModelTest.kt.
///
/// Unit tests for `KitViewModel`.
///
/// All tests use the picker-free seams `KitViewModel.chopFromPcm` and
/// `KitViewModel.kitFromPcm` so they run without files or hardware. Awaiting
/// `awaitIdle()` replaces Kotlin's test dispatcher + advanceUntilIdle.
@MainActor
final class KitViewModelTests: XCTestCase {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /// Build `frames` PCM frames as raw s16 LE bytes: 2 bytes per sample × `channels`.
    /// Total byte count = frames * channels * 2.
    private func pcm(frames: Int, channels: Int = 1) -> Data {
        var out = Data(count: frames * 2 * channels)
        for i in 0..<out.count { out[i] = UInt8(i % 256) }
        return out
    }

    private func makeVm(connected: Bool) -> (KitFakeMIDIRepo, KitViewModel) {
        let spy = KitSpyMIDIPort(connected: connected)
        let repo = KitFakeMIDIRepo(spy, connected: connected)
        let manager = SampleImportManager(repo)
        let vm = KitViewModel(repo, manager)
        return (repo, vm)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chop mode — per-slice upload contract
    // ─────────────────────────────────────────────────────────────────────────

    // ── putSampleFile called N times (once per slice, not once total) ─────────

    func testChop_putSampleFileCalled_nTimesPerSlice() async {
        let (repo, vm) = makeVm(connected: true)

        let sliceCount = 4
        vm.onSliceCountChange(String(sliceCount))

        let frames = 1000
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: frames))

        await vm.awaitIdle()

        XCTAssertEqual(
            sliceCount, repo.putCalls.count,
            "putSampleFile must be called once per slice (\(sliceCount) times total)"
        )
    }

    // ── assignSampleToPad called N times with full trim (0, sliceFrameCount) ──

    func testChop_assignCalledN_timesWithFullTrim() async {
        let (repo, vm) = makeVm(connected: true)

        let sliceCount = 4
        vm.onSliceCountChange(String(sliceCount))

        let frames = 1000
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: frames))

        await vm.awaitIdle()

        XCTAssertEqual(sliceCount, repo.assignCalls.count,
                       "assignSampleToPad must be called \(sliceCount) times")

        // Each slice uses full trim: start=0, end=sliceFrameCount.
        // Slice i covers frames [ i*frames/N .. (i+1)*frames/N ).
        for i in 0..<sliceCount {
            let call = repo.assignCalls[i]
            let sliceFrameCount = Int(Int64(i + 1) * Int64(frames) / Int64(sliceCount))
                - Int(Int64(i) * Int64(frames) / Int64(sliceCount))
            // Pads fill in EP-133 numeric order (. 0 ENT 1-9), so slice i lands on PAD_FILL_ORDER[i].
            XCTAssertEqual(PAD_FILL_ORDER[i], call.gridIndex, "slice \(i): gridIndex")
            // nodeId is the call-index (1-based) — slice 0 is the first put call → nodeId 1.
            XCTAssertEqual(i + 1, call.sampleNodeId, "slice \(i): sampleNodeId")
            XCTAssertEqual(0, call.sampleStart, "slice \(i): sampleStart must be 0 (full trim on slice)")
            XCTAssertEqual(sliceFrameCount, call.sampleEnd, "slice \(i): sampleEnd must equal sliceFrameCount")
        }
    }

    /// Pin PAD_FILL_ORDER to its hardware-derived literal. The chop tests assert
    /// `PAD_FILL_ORDER[i] == gridIndex`, which proves the fill *iterates* in that order but not
    /// that the order's *values* are the correct EP-133 pad mapping (. 0 ENT then 1-9). This
    /// anchors the values themselves, so changing the constant is a deliberate two-place edit
    /// that trips a test rather than silently re-mapping every chopped slice.
    func test_padFillOrder_matchesHardwareNumericOrder() {
        XCTAssertEqual([9, 10, 11, 6, 7, 8, 3, 4, 5, 0, 1, 2], PAD_FILL_ORDER)
    }

    // ── Per-group state is independent and persists across group switches ──────

    func testPerGroup_stateIsIndependentAndPersists() async {
        let (_, vm) = makeVm(connected: true)

        // Group A: chop 3 slices.
        vm.onGroupChange(.A)
        vm.onSliceCountChange("3")
        vm.chopFromPcm(name: "a.wav", pcm: pcm(frames: 600))
        await vm.awaitIdle()
        XCTAssertEqual(3, vm.items.count, "A has 3 items")

        // Switch to B — a clean slate with its own defaults.
        vm.onGroupChange(.B)
        await vm.awaitIdle()
        XCTAssertEqual(0, vm.items.count, "B starts empty")
        XCTAssertEqual(String(DEFAULT_SLICE_COUNT), vm.sliceCountText,
                       "B keeps its own default slice count")

        // B: chop 5 slices.
        vm.onSliceCountChange("5")
        vm.chopFromPcm(name: "b.wav", pcm: pcm(frames: 1000))
        await vm.awaitIdle()
        XCTAssertEqual(5, vm.items.count, "B has 5 items")

        // Back to A — A's state persisted.
        vm.onGroupChange(.A)
        await vm.awaitIdle()
        XCTAssertEqual(3, vm.items.count, "A still has its 3 items")
        XCTAssertEqual("3", vm.sliceCountText, "A's slice count persisted")
    }

    // ── Choke group (sound.mutegroup) reaches assignSampleToPad ────────────────

    func testChop_chokeGroup_defaultsOn_writesMuteGroupTrue() async {
        let (repo, vm) = makeVm(connected: true)
        vm.onSliceCountChange("4")
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: 1000))
        await vm.awaitIdle()

        XCTAssertEqual(4, repo.assignCalls.count, "assignSampleToPad called per slice")
        XCTAssertTrue(repo.assignCalls.allSatisfy(\.muteGroup),
                      "choke group defaults on → every pad muteGroup=true")
    }

    func testChop_chokeGroupOff_writesMuteGroupFalse() async {
        let (repo, vm) = makeVm(connected: true)
        vm.onChokeGroupChange(false)
        vm.onSliceCountChange("4")
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: 1000))
        await vm.awaitIdle()

        XCTAssertEqual(4, repo.assignCalls.count, "assignSampleToPad called per slice")
        XCTAssertTrue(repo.assignCalls.allSatisfy { !$0.muteGroup },
                      "choke off → no pad has muteGroup=true")
    }

    // ── Per-slice byte content tiles exactly with no dropped samples ───────────

    func testChop_sliceByteContent_tilesExactly() async {
        let (repo, vm) = makeVm(connected: true)

        let sliceCount = 3
        vm.onSliceCountChange(String(sliceCount))

        let frames = 10          // 10 frames mono → 20 bytes
        let pcmData = pcm(frames: frames)
        vm.chopFromPcm(name: "loop.wav", pcm: pcmData)

        await vm.awaitIdle()

        XCTAssertEqual(sliceCount, repo.putCalls.count, "putSampleFile called \(sliceCount) times")

        // Stepped boundaries: i*10/3, (i+1)*10/3 — same formula as LoopSlicer.slicePcmBytes.
        let bytesPerFrame = 2   // mono, 2 bytes/frame
        for i in 0..<sliceCount {
            let startFrame = Int(Int64(i) * Int64(frames) / Int64(sliceCount))
            let endFrame = Int(Int64(i + 1) * Int64(frames) / Int64(sliceCount))
            let expectedBytes = [UInt8](pcmData[(startFrame * bytesPerFrame)..<(endFrame * bytesPerFrame)])
            XCTAssertEqual(
                expectedBytes, repo.putCalls[i].pcmBytes,
                "slice \(i) PCM content must match stepped frame range [\(startFrame)..\(endFrame))"
            )
        }

        // Slices must tile exactly — concatenation must equal the original PCM.
        let reassembled = Data(repo.putCalls.flatMap(\.pcmBytes))
        XCTAssertEqual(pcmData, reassembled, "concatenated slices must equal original PCM")
    }

    // ── All N rows reach Done state ───────────────────────────────────────────

    func testChop_allItemsDone_whenConnected() async {
        let (_, vm) = makeVm(connected: true)

        let sliceCount = 4
        vm.onSliceCountChange(String(sliceCount))
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: 1000))

        await vm.awaitIdle()

        let items = vm.items
        // N rows (one per slice) — no separate upload row.
        XCTAssertEqual(sliceCount, items.count)
        for item in items {
            XCTAssertEqual(KitItemState.done, item.state,
                           "Item '\(item.label)' should be Done; got \(item.state)")
        }
    }

    // ── Byte-budget guard: STEREO slice over 20 s → rejected, no device writes ──

    func testChop_byteBudgetGuard_stereoOver20s_rejected() async {
        let (repo, vm) = makeVm(connected: true)

        // MAX_SAMPLE_BYTES = 3_750_000 = 20s stereo @ 46875 (937500 frames * 2ch * 2bytes).
        // One slice, stereo, just over the byte budget: 937501 frames * 2ch * 2bytes = 3_750_004 bytes.
        let sliceCount = 1
        vm.onSliceCountChange(String(sliceCount))

        let sampleRate = 46875
        let channels = 2
        let frames = 20 * sampleRate + 1   // 937501 frames → 3_750_004 bytes stereo

        vm.chopFromPcm(name: "bigstereo.wav", pcm: pcm(frames: frames, channels: channels),
                       channels: channels, sampleRate: sampleRate)

        await vm.awaitIdle()

        // No device writes — the byte-budget guard must fire before any upload.
        XCTAssertEqual(0, repo.putCalls.count,
                       "putSampleFile must NOT be called when byte-budget guard fires")
        XCTAssertEqual(0, repo.assignCalls.count,
                       "assignSampleToPad must NOT be called when byte-budget guard fires")

        // All rows must be in Error state.
        let items = vm.items
        XCTAssertEqual(sliceCount, items.count, "items list must have \(sliceCount) rows")
        for item in items {
            XCTAssertEqual(KitItemState.error, item.state,
                           "Item '\(item.label)' must be Error; got \(item.state)")
        }
    }

    // ── Byte-budget guard: MONO slice 20–40 s → allowed (upload proceeds) ──────

    func testChop_byteBudgetGuard_mono20to40s_allowed() async {
        let (repo, vm) = makeVm(connected: true)

        // A flat "frames > 20*sampleRate" guard would WRONGLY reject this mono slice.
        // 20s mono = 937500 frames = 1_875_000 bytes; 40s mono = 1_875_000 frames = 3_750_000 bytes.
        // Pick ~30s mono: 1_400_000 frames * 1ch * 2bytes = 2_800_000 bytes < MAX_SAMPLE_BYTES (3_750_000).
        let sliceCount = 1
        vm.onSliceCountChange(String(sliceCount))

        let sampleRate = 46875
        let channels = 1
        let frames = 1_400_000   // ~29.9s mono → 2_800_000 bytes, under the byte budget

        vm.chopFromPcm(name: "longmono.wav", pcm: pcm(frames: frames, channels: channels),
                       channels: channels, sampleRate: sampleRate)

        await vm.awaitIdle()

        // Upload must proceed — the mono slice is under the byte budget despite being >20 s.
        XCTAssertEqual(sliceCount, repo.putCalls.count,
                       "putSampleFile must be called once (mono 20–40s is allowed)")
        XCTAssertEqual(sliceCount, repo.assignCalls.count, "assignSampleToPad must be called once")
        XCTAssertEqual(frames * channels * 2, repo.putCalls[0].pcmBytes.count, "slice byte size")

        // Row must reach Done.
        let items = vm.items
        XCTAssertEqual(sliceCount, items.count)
        for item in items {
            XCTAssertEqual(KitItemState.done, item.state,
                           "Item '\(item.label)' should be Done; got \(item.state)")
        }
    }

    // ── Disconnected device: first item is Error, no device writes ─────────────

    func testChop_uploadRowError_whenDisconnected() async {
        let (_, vm) = makeVm(connected: false)

        vm.onSliceCountChange("4")
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: 1000))

        await vm.awaitIdle()

        let items = vm.items
        XCTAssertFalse(items.isEmpty, "items must not be empty after chop")
        // First slice row must be Error (putSampleFile returns nil → disconnected path).
        XCTAssertEqual(KitItemState.error, items[0].state,
                       "first slice row must be Error when disconnected")
    }

    // ── Group selection is forwarded to assignSampleToPad ─────────────────────

    func testChop_groupSelection_forwardedToAssign() async {
        let (repo, vm) = makeVm(connected: true)

        vm.onGroupChange(.C)
        vm.onSliceCountChange("2")
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: 500))

        await vm.awaitIdle()

        XCTAssertFalse(repo.assignCalls.isEmpty, "At least one assign call expected")
        for call in repo.assignCalls {
            XCTAssertEqual(PadChannel.C, call.group, "All assign calls must use group C")
        }
    }

    // ── sliceCount clamped to MAX_SLICES ──────────────────────────────────────

    func testChop_sliceCountClampedToMaxSlices() async {
        let (repo, vm) = makeVm(connected: true)

        // Request more than MAX_SLICES — resolvedSliceCount() must clamp to MAX_SLICES.
        vm.onSliceCountChange("99")
        XCTAssertEqual(MAX_SLICES, vm.resolvedSliceCount(),
                       "resolvedSliceCount() must clamp to \(MAX_SLICES)")

        let frames = 4800
        vm.chopFromPcm(name: "loop.wav", pcm: pcm(frames: frames))

        await vm.awaitIdle()

        XCTAssertEqual(MAX_SLICES, repo.putCalls.count, "Exactly MAX_SLICES put calls")
        XCTAssertEqual(MAX_SLICES, repo.assignCalls.count, "Exactly MAX_SLICES assign calls")
    }

    // ── Stereo PCM: frame count derived correctly, slice bytes are stereo ──────

    func testChop_stereoFrameCount_derivedCorrectly() async {
        let (repo, vm) = makeVm(connected: true)

        let sliceCount = 2
        vm.onSliceCountChange(String(sliceCount))

        // 800 frames stereo → 800 * 2 channels * 2 bytes = 3200 bytes.
        let frames = 800
        let channels = 2
        let pcmData = pcm(frames: frames, channels: channels)
        vm.chopFromPcm(name: "loop.wav", pcm: pcmData, channels: channels)

        await vm.awaitIdle()

        XCTAssertEqual(sliceCount, repo.assignCalls.count)
        let bytesPerFrame = channels * 2

        let call0 = repo.assignCalls[0]
        let call1 = repo.assignCalls[1]
        // Full trim: start=0, end=sliceFrameCount.
        // slice 0: frames [0..400) → 400 frames
        XCTAssertEqual(0, call0.sampleStart, "stereo chop slice 0 start")
        XCTAssertEqual(frames / sliceCount, call0.sampleEnd, "stereo chop slice 0 end")
        // slice 1: frames [400..800) → 400 frames
        XCTAssertEqual(0, call1.sampleStart, "stereo chop slice 1 start")
        XCTAssertEqual(frames / sliceCount, call1.sampleEnd, "stereo chop slice 1 end")

        // Byte lengths: each slice should be sliceFrameCount * channels * 2 bytes.
        let expectedSliceBytes = (frames / sliceCount) * bytesPerFrame
        XCTAssertEqual(expectedSliceBytes, repo.putCalls[0].pcmBytes.count, "stereo slice 0 byte count")
        XCTAssertEqual(expectedSliceBytes, repo.putCalls[1].pcmBytes.count, "stereo slice 1 byte count")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kit mode — unchanged contract
    // ─────────────────────────────────────────────────────────────────────────

    // ── Kit mode: putSampleFile called once per file ──────────────────────────

    func testKit_putSampleFileCalled_oncePerFile() async {
        let (repo, vm) = makeVm(connected: true)

        let files: [(String, Data, Int)] = [
            ("kick.wav", pcm(frames: 300), 1),
            ("snare.wav", pcm(frames: 300), 1),
            ("hihat.wav", pcm(frames: 300), 1),
        ]
        vm.kitFromPcm(files)

        await vm.awaitIdle()

        XCTAssertEqual(files.count, repo.putCalls.count, "putSampleFile called once per file")
    }

    // ── Kit mode: assignSampleToPad called with full trim (0, frames) ─────────

    func testKit_assignCalledWithFullTrim() async {
        let (repo, vm) = makeVm(connected: true)

        let framesList = [200, 400, 600]
        let files: [(String, Data, Int)] = framesList.enumerated().map { i, frames in
            ("s\(i).wav", pcm(frames: frames), 1)
        }
        vm.kitFromPcm(files)

        await vm.awaitIdle()

        XCTAssertEqual(files.count, repo.assignCalls.count, "One assign call per file")
        // File i lands on pad PAD_FILL_ORDER[i] (. 0 ENT 1-9 order); concurrent tasks may
        // arrive out-of-order, so correlate each call by its expected gridIndex, not call order.
        for (i, frames) in framesList.enumerated() {
            let expectedGrid = PAD_FILL_ORDER[i]
            guard let call = repo.assignCalls.first(where: { $0.gridIndex == expectedGrid }) else {
                XCTFail("no assign call for gridIndex \(expectedGrid)")
                continue
            }
            XCTAssertEqual(0, call.sampleStart, "file \(i): sampleStart must be 0 (full trim)")
            XCTAssertEqual(frames, call.sampleEnd, "file \(i): sampleEnd must be total frames")
        }
    }

    // ── Kit mode: all items Done when connected ───────────────────────────────

    func testKit_allItemsDone_whenConnected() async {
        let (_, vm) = makeVm(connected: true)

        let files: [(String, Data, Int)] = [
            ("a.wav", pcm(frames: 100), 1),
            ("b.wav", pcm(frames: 200), 1),
        ]
        vm.kitFromPcm(files)

        await vm.awaitIdle()

        let items = vm.items
        XCTAssertEqual(files.count, items.count)
        for item in items {
            XCTAssertEqual(KitItemState.done, item.state,
                           "Item '\(item.label)' should be Done; got \(item.state)")
        }
    }

    // ── Kit mode: items reach Error when disconnected ─────────────────────────

    func testKit_itemsError_whenDisconnected() async {
        let (_, vm) = makeVm(connected: false)

        let files: [(String, Data, Int)] = [
            ("a.wav", pcm(frames: 100), 1),
            ("b.wav", pcm(frames: 200), 1),
        ]
        vm.kitFromPcm(files)

        await vm.awaitIdle()

        let items = vm.items
        XCTAssertEqual(files.count, items.count)
        for item in items {
            XCTAssertEqual(KitItemState.error, item.state,
                           "Item '\(item.label)' should be Error when disconnected; got \(item.state)")
        }
    }

    // ── Kit mode: files capped to MAX_SLICES ──────────────────────────────────

    func testKit_filesCappedToMaxSlices() async {
        let (repo, vm) = makeVm(connected: true)

        // Provide more than MAX_SLICES files.
        let files: [(String, Data, Int)] = (0...(MAX_SLICES + 3)).map { i in
            ("s\(i).wav", pcm(frames: 100), 1)
        }
        vm.kitFromPcm(files)

        await vm.awaitIdle()

        XCTAssertEqual(MAX_SLICES, vm.items.count, "items list capped at MAX_SLICES")
        XCTAssertEqual(MAX_SLICES, repo.putCalls.count, "putSampleFile capped at MAX_SLICES")
        XCTAssertEqual(MAX_SLICES, repo.assignCalls.count, "assignSampleToPad capped at MAX_SLICES")
    }
}
