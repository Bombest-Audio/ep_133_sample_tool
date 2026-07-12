import XCTest
@testable import EP133SampleTool

// ── Test doubles ──────────────────────────────────────────────────────────────

/// Silent MIDIPort — no devices, records nothing (the Kotlin SilentPort analog).
private final class SilentPort: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    func setup() {}
    func close() {}
    func getUSBDevices() -> MIDIDeviceList { MIDIDeviceList(inputs: [], outputs: []) }
    func sendMIDI(to portId: String, data: [UInt8]) {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
}

/// Fake repo whose `readGroupPadState` returns canned per-group pad maps and records reads.
@MainActor
private final class PadStateFakeRepo: MIDIRepository {
    private let padsByGroup: [PadChannel: [Int: String]]
    var reads: [PadChannel] = []

    init(padsByGroup: [PadChannel: [Int: String]] = [:]) {
        self.padsByGroup = padsByGroup
        super.init(SilentPort())
        deviceState = DeviceState(connected: true, outputPortId: "out")
    }

    override func readGroupPadState(group: PadChannel) async throws -> [Int: String] {
        reads.append(group)
        return padsByGroup[group] ?? [:]
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/GroupSessionTest.kt.
///
/// The `GroupSession` contract and its sharing between `KitViewModel` (Loop Chopper) and
/// `KitBuilderViewModel` (Kit Builder): one selection, per-group CHOP/KIT designation, per-group
/// choke — a single source of truth driving both workflows.
///
/// Awaiting `awaitIdle()` replaces Kotlin's test dispatcher + advanceUntilIdle. UserDefaults
/// persistence uses a unique suite per test, wiped in tearDown.
@MainActor
final class GroupSessionTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "GroupSessionTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    private func buildVms(
        session: GroupSession? = nil,
        repo: MIDIRepository? = nil
    ) -> (KitViewModel, KitBuilderViewModel, GroupSession) {
        let session = session ?? GroupSession()
        let repo = repo ?? PadStateFakeRepo()
        let manager = SampleImportManager(repo)
        return (
            KitViewModel(repo, manager, session: session),
            KitBuilderViewModel(repo, manager, session: session),
            session
        )
    }

    // ── GroupSession contract ────────────────────────────────────────────────

    func testDefaults_allGroupsChopWithChokeOn() {
        let session = GroupSession()
        XCTAssertEqual(PadChannel.A, session.selected)
        for g in PadChannel.allCases {
            XCTAssertEqual(KitMode.chop, session.designationFor(g), "group \(g) defaults to CHOP")
            XCTAssertTrue(session.chokeFor(g), "group \(g) choke defaults on")
        }
    }

    func testDesignation_isPerGroup() {
        let session = GroupSession()
        session.designate(.B, .kit)
        XCTAssertEqual(KitMode.kit, session.designationFor(.B))
        XCTAssertEqual(KitMode.chop, session.designationFor(.A), "other groups untouched")
        XCTAssertEqual(KitMode.chop, session.designationFor(.C))
    }

    func testChoke_isPerGroup() {
        let session = GroupSession()
        session.setChoke(.C, false)
        XCTAssertFalse(session.chokeFor(.C))
        XCTAssertTrue(session.chokeFor(.A), "other groups untouched")
    }

    // ── UserDefaults persistence (the SharedPreferences port) ────────────────

    func testDesignationAndChoke_persistAcrossInstances() {
        let first = GroupSession(defaults: defaults)
        first.designate(.B, .kit)
        first.setChoke(.C, false)

        // A fresh session over the same suite hydrates the persisted values; untouched
        // groups keep their defaults.
        let second = GroupSession(defaults: defaults)
        XCTAssertEqual(KitMode.kit, second.designationFor(.B))
        XCTAssertFalse(second.chokeFor(.C))
        XCTAssertEqual(KitMode.chop, second.designationFor(.A))
        XCTAssertTrue(second.chokeFor(.A))
        // Selection is session-scoped, never persisted.
        XCTAssertEqual(PadChannel.A, second.selected)
    }

    // ── Designation drives the page mode through KitViewModel ────────────────

    func testSelectingAGroup_flipsPageModeToItsDesignation() async {
        let (kitVm, _, _) = buildVms()

        // Designate B as a KIT group while A stays CHOP.
        kitVm.onGroupChange(.B)
        kitVm.onModeChange(.kit)
        await kitVm.awaitIdle()
        XCTAssertEqual(KitMode.kit, kitVm.mode)

        // Selecting A flips the page back to CHOP; back to B → KIT again.
        kitVm.onGroupChange(.A)
        await kitVm.awaitIdle()
        XCTAssertEqual(KitMode.chop, kitVm.mode)

        kitVm.onGroupChange(.B)
        await kitVm.awaitIdle()
        XCTAssertEqual(KitMode.kit, kitVm.mode, "B remembered its KIT designation")
    }

    // ── One session, two ViewModels ──────────────────────────────────────────

    func testGroupSelection_isSharedBetweenChopperAndBuilder() async {
        let (kitVm, builderVm, session) = buildVms()
        await builderVm.awaitIdle()

        builderVm.onGroupChange(.D)
        await builderVm.awaitIdle()
        XCTAssertEqual(PadChannel.D, kitVm.selectedGroup, "chopper follows the builder's selection")
        XCTAssertEqual(PadChannel.D, builderVm.state.group)
        XCTAssertEqual(PadChannel.D, session.selected)

        kitVm.onGroupChange(.B)
        await builderVm.awaitIdle()
        XCTAssertEqual(PadChannel.B, builderVm.state.group, "builder follows the chopper's selection")
    }

    func testChoke_isSharedBetweenChopperAndBuilder() async {
        let (kitVm, builderVm, _) = buildVms()
        await builderVm.awaitIdle()

        kitVm.onChokeGroupChange(false)
        await kitVm.awaitIdle()
        XCTAssertFalse(builderVm.state.chokeGroup, "builder sees the chopper's choke change")

        builderVm.onChokeGroupChange(true)
        await builderVm.awaitIdle()
        XCTAssertTrue(kitVm.chokeGroup, "chopper sees the builder's choke change")
    }

    // ── Kit Builder per-group state ──────────────────────────────────────────

    func testBuilder_padSelection_isPerGroup() async {
        let (_, builderVm, _) = buildVms()
        await builderVm.awaitIdle()

        builderVm.onPadSelected(2)
        await builderVm.awaitIdle()
        XCTAssertEqual(2, builderVm.state.selectedPad)

        builderVm.onGroupChange(.B)
        await builderVm.awaitIdle()
        XCTAssertEqual(9, builderVm.state.selectedPad, "B has its own default pad selection")

        builderVm.onGroupChange(.A)
        await builderVm.awaitIdle()
        XCTAssertEqual(2, builderVm.state.selectedPad, "A's pad selection persisted")
    }

    func testBuilder_devicePads_areMirroredPerGroup() async {
        let repo = PadStateFakeRepo(
            padsByGroup: [
                .A: [0: "kick", 1: "snare"],
                .B: [5: "hat"],
            ]
        )
        let (_, builderVm, _) = buildVms(repo: repo)
        await builderVm.awaitIdle()   // init observers fire: connected + initial selection → read A

        XCTAssertEqual([0: "kick", 1: "snare"], builderVm.state.devicePads, "A's mirror is A's pads")

        builderVm.onGroupChange(.B)
        await builderVm.awaitIdle()
        XCTAssertEqual([5: "hat"], builderVm.state.devicePads, "B's mirror is B's pads")
        XCTAssertTrue(repo.reads.contains(.B), "the device was actually re-read for B")

        builderVm.onGroupChange(.A)
        await builderVm.awaitIdle()
        XCTAssertEqual([0: "kick", 1: "snare"], builderVm.state.devicePads, "back on A, A's pads again")
    }
}
