import XCTest
@testable import EP133SampleTool

// ── Test doubles (mirror the Kotlin ProjectsViewModelTest doubles; renamed to avoid
//    redeclaration clashes across the shared test target) ──

private final class ProjectsSpyMIDIPort: MIDIPort {
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

/// MIDIRepository with controllable device state + a canned `listProjects` result — no hardware.
///
/// `listProjects` is overridden so the test can return a fixed 9-slot enumeration (one active)
/// without driving the real SysEx FILE_LIST request-response cycle.
@MainActor
private final class ProjectsFakeMIDIRepo: MIDIRepository {
    private let cannedSlots: [MIDIRepository.ProjectSlot]

    init(initialConnected: Bool = false, cannedSlots: [MIDIRepository.ProjectSlot] = []) {
        self.cannedSlots = cannedSlots
        super.init(ProjectsSpyMIDIPort(connected: initialConnected))
        deviceState = DeviceState(
            connected: initialConnected,
            outputPortId: initialConnected ? "out" : nil)
    }

    func setConnected(_ connected: Bool) {
        deviceState = DeviceState(connected: connected, outputPortId: connected ? "out" : nil)
    }

    override func listProjects() async throws -> [MIDIRepository.ProjectSlot] { cannedSlots }
}

private func nineSlots() -> [MIDIRepository.ProjectSlot] {
    var slots: [MIDIRepository.ProjectSlot] = []
    for i in 0...8 {
        let name = String(format: "P%02d", i)
        let size = Int64(i + 1) * 1024
        let active = (i == 3)   // exactly one active slot
        slots.append(MIDIRepository.ProjectSlot(
            nodeId: 100 + i, name: name, sizeBytes: size, isActive: active))
    }
    return slots
}

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/ProjectsViewModelTest.kt.
///
/// ProjectsViewModel unit tests (PROJ-01).
///
/// Asserts the VM maps the device enumeration into its `slots` state: 9 entries with exactly
/// one marked active. Uses `ProjectsFakeMIDIRepo` (the established VM-test seam) so no
/// hardware or real SysEx round-trip is required. Awaiting `loadProjectsTask` replaces
/// Kotlin's test dispatcher + advanceUntilIdle.
@MainActor
final class ProjectsViewModelTests: XCTestCase {

    func test_loadProjects_mapsNineSlots_oneActive() async {
        let repo = ProjectsFakeMIDIRepo(initialConnected: true, cannedSlots: nineSlots())
        let vm = ProjectsViewModel(repo, backupManager: ProjectBackupManager(repo))

        vm.loadProjects()
        await vm.loadProjectsTask?.value

        let slots = vm.slots
        XCTAssertEqual(9, slots.count)
        XCTAssertEqual(1, slots.filter(\.isActive).count)
        XCTAssertEqual("P03", slots.first(where: \.isActive)?.name)
    }

    func test_loadProjects_whenEmpty_leavesSlotsEmpty() async {
        let repo = ProjectsFakeMIDIRepo(initialConnected: false, cannedSlots: [])
        let vm = ProjectsViewModel(repo, backupManager: ProjectBackupManager(repo))

        vm.loadProjects()
        await vm.loadProjectsTask?.value

        XCTAssertTrue(vm.slots.isEmpty)
    }
}
