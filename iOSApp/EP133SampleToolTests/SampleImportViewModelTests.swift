import XCTest
@testable import EP133SampleTool

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles (renamed from ProjectsViewModelTests' doubles to avoid redeclaration
// clashes across the shared test target — mirrors the Kotlin STATE.md Phase 4 note).
// ─────────────────────────────────────────────────────────────────────────────

/// Spy MIDIPort that records all sendMIDI calls and can simulate connected/disconnected state.
/// Named to avoid clashing with ProjectsSpyMIDIPort in ProjectsViewModelTests.swift.
private final class SampleImportSpyPort: MIDIPort {
    private let connected: Bool

    init(connected: Bool = false) {
        self.connected = connected
    }

    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?
    private(set) var sent: [[UInt8]] = []

    func setup() {}
    func close() {}

    func getUSBDevices() -> MIDIDeviceList {
        connected
            ? MIDIDeviceList(
                inputs: [MIDIDevice(id: "in", name: "EP-133")],
                outputs: [MIDIDevice(id: "out", name: "EP-133")])
            : MIDIDeviceList(inputs: [], outputs: [])
    }

    func sendMIDI(to portId: String, data: [UInt8]) { sent.append(data) }
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
}

/// Fake MIDIRepository with controllable device state for ViewModel testing.
/// Sets `deviceState` (the internal-setter seam replacing Kotlin's protected `_deviceState`)
/// so the import manager can observe connection status.
/// Named to avoid clashing with ProjectsFakeMIDIRepo in ProjectsViewModelTests.swift.
@MainActor
private final class SampleImportFakeRepo: MIDIRepository {
    let spy: SampleImportSpyPort

    init(_ spy: SampleImportSpyPort, connected: Bool = false) {
        self.spy = spy
        super.init(spy)
        setConnected(connected)
    }

    func setConnected(_ connected: Bool) {
        deviceState = connected
            ? DeviceState(connected: true, outputPortId: "out")
            : DeviceState(connected: false, outputPortId: nil)
    }

    // putSampleFile returns a fake node ID when connected, nil when disconnected — matches
    // SampleImportManager's corrected fail-closed contract (Codex fix #2).
    override func putSampleFile(
        name: String,
        pcmBytes: [UInt8],
        channels: Int,
        sampleRate: Int
    ) async throws -> Int? {
        deviceState.connected ? 42 : nil
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/SampleImportViewModelTest.kt.
///
/// Asserts the SampleImportViewModel + SampleImportManager state-machine contract.
///
/// Note: real picker URL reads (security-scoped file access) are hardware/UI-test-only. These
/// tests cover the state-machine via `importStagedBytes(name:wavBytes:)` — the testability
/// seam the VM exposes for pre-read byte buffers (per 05-VALIDATION Manual-Only section).
/// Awaiting `importTasks` replaces Kotlin's test dispatcher + advanceUntilIdle.
@MainActor
final class SampleImportViewModelTests: XCTestCase {

    /// The advanceUntilIdle analog: await every in-flight import task.
    private func advanceUntilIdle(_ vm: SampleImportViewModel) async {
        for task in vm.importTasks {
            await task.value
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: importStagedBytes maps to stagedSamples in Pending state initially
    // ──────────────────────────────────────────────────────────────────────────

    func test_importStagedBytes_mapsToStagedList_pendingInitially() async {
        let spy = SampleImportSpyPort(connected: true)
        let repo = SampleImportFakeRepo(spy, connected: true)
        let manager = SampleImportManager(repo)
        let vm = SampleImportViewModel(repo, manager: manager)

        // Each call to importStagedBytes should add an item to stagedSamples in Pending state
        vm.importStagedBytes(name: "kick.wav", wavBytes: Data(repeating: 0, count: 100))

        await advanceUntilIdle(vm)

        let staged = vm.stagedSamples
        XCTAssertEqual(1, staged.count, "One staged entry per importStagedBytes call")
        XCTAssertEqual("kick.wav", staged[0].name, "Filename must match")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Connected import advances from Pending → Done
    // ──────────────────────────────────────────────────────────────────────────

    func test_importStagedBytes_whenConnected_advancesToDone() async {
        let spy = SampleImportSpyPort(connected: true)
        let repo = SampleImportFakeRepo(spy, connected: true)
        let manager = SampleImportManager(repo)
        let vm = SampleImportViewModel(repo, manager: manager)

        vm.importStagedBytes(
            name: "snare.wav",
            wavBytes: Data((0..<200).map { UInt8($0 % 256) }))

        await advanceUntilIdle(vm)

        let staged = vm.stagedSamples
        XCTAssertEqual(1, staged.count, "One staged item")
        // The final state must be Done (import acknowledged) — not Pending or Error
        XCTAssertTrue(
            staged[0].isDone(),
            "Staged item must advance to Done after connected import; got: \(staged[0])")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: Disconnected import advances to Error + sets snackbarMessage
    // ──────────────────────────────────────────────────────────────────────────

    func test_importStagedBytes_whenDisconnected_advancesToErrorWithMessage() async {
        let spy = SampleImportSpyPort(connected: false)
        let repo = SampleImportFakeRepo(spy, connected: false)
        let manager = SampleImportManager(repo)
        let vm = SampleImportViewModel(repo, manager: manager)

        vm.importStagedBytes(name: "hihat.wav", wavBytes: Data(repeating: 0, count: 200))

        await advanceUntilIdle(vm)

        let staged = vm.stagedSamples
        XCTAssertEqual(1, staged.count, "One staged item")
        // Must advance to Error state (device not connected)
        XCTAssertTrue(
            staged[0].isError(),
            "Staged item must advance to Error when disconnected; got: \(staged[0])")
        // Must emit a snackbar message mentioning "EP-133" or "connected"
        let msg = vm.snackbarMessage
        XCTAssertNotNil(msg, "snackbarMessage must be set on disconnected import error")
        XCTAssertTrue(
            msg!.localizedCaseInsensitiveContains("EP-133")
                || msg!.localizedCaseInsensitiveContains("connect"),
            "snackbarMessage must mention connection state; got: \(msg!)")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: Multiple staged bytes map to multiple list entries
    // ──────────────────────────────────────────────────────────────────────────

    func test_importStagedBytes_multipleFiles_producesMultipleEntries() async {
        let spy = SampleImportSpyPort(connected: false)
        let repo = SampleImportFakeRepo(spy, connected: false)
        let manager = SampleImportManager(repo)
        let vm = SampleImportViewModel(repo, manager: manager)

        vm.importStagedBytes(name: "kick.wav", wavBytes: Data(repeating: 0, count: 100))
        vm.importStagedBytes(name: "snare.wav", wavBytes: Data(repeating: 0, count: 100))
        vm.importStagedBytes(name: "hihat.wav", wavBytes: Data(repeating: 0, count: 100))

        await advanceUntilIdle(vm)

        let staged = vm.stagedSamples
        XCTAssertEqual(3, staged.count, "Three staged entries (one per importStagedBytes call)")
        XCTAssertEqual("kick.wav", staged[0].name)
        XCTAssertEqual("snare.wav", staged[1].name)
        XCTAssertEqual("hihat.wav", staged[2].name)
    }
}
