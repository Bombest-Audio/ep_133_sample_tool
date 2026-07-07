import XCTest
@testable import EP133SampleTool

/// Mirrors AndroidApp/app/src/androidTest/java/com/ep133/sampletool/SimulatedDeviceTest.kt.
///
/// Full-stack E2E against the wire-level device simulator: a REAL MIDIRepository speaking
/// real SysEx frames (7-bit packing, reqId routing, FILE_INIT session, paged PUT with acks,
/// metadata drill-downs) to `EP133DeviceSimulator`. Nothing above the MIDIPort seam is faked.
///
/// The Android originals drive the Compose UI through robots; here each test drives the same
/// repository/import entry points the SwiftUI screens call (the screens' own branch logic is
/// pinned by their ViewModel tests), asserting the identical wire-level outcomes.
@MainActor
final class SimulatedDeviceTests: XCTestCase {

    // Each connect() wires a simulator into a live repository; close them all in tearDown so
    // runs don't accumulate live ports test-over-test (pinned behavior from the Android
    // review fix "close simulator repos in SimulatedDeviceTest teardown" — repo.close()
    // closes its current port, i.e. the simulator).
    private var openRepos: [MIDIRepository] = []

    override func tearDown() async throws {
        for repo in openRepos { repo.close() }
        openRepos.removeAll()
        try await super.tearDown()
    }

    /// Real repository over the simulator, connected the way the app shell would.
    private func connect(_ sim: EP133DeviceSimulator) -> MIDIRepository {
        let repo = MIDIRepository(sim)
        repo.refreshDeviceState() // enumerates the sim's ports → connected=true, outputPortId set
        openRepos.append(repo)
        return repo
    }

    /// Drain an import stream to completion and return every emitted event.
    private func runImport(
        _ manager: SampleImportManager, name: String, bytes: [UInt8]
    ) async -> [SampleImportProgress] {
        var events: [SampleImportProgress] = []
        for await event in manager.importSampleBytes(rawName: name, wavBytes: Data(bytes)) {
            events.append(event)
        }
        return events
    }

    /// True when the last import event is an error (the Android robots' ERROR row state).
    private func endedInError(_ events: [SampleImportProgress]) -> Bool {
        if case .error = events.last { return true }
        return false
    }

    func testDeviceScreen_showsStatsQueriedOverTheWire() async throws {
        // Arrange: two seeded samples; stats must come from GREET + /sounds LIST + metadata
        let sim = EP133DeviceSimulator(firmwareVersion: "2.0.5")
        sim.seedSound([UInt8](repeating: 0, count: 1_048_576)) // 1 MB
        sim.seedSound([UInt8](repeating: 0, count: 1_048_576))
        let repo = connect(sim)

        // Act: the Device screen's loadStats() runs exactly this query pipeline
        let ok = try await repo.queryDeviceStats()

        // Assert: firmware from the greet, sample count from FILE_LIST, storage from metadata
        XCTAssertTrue(ok, "stats query must succeed over the simulated wire")
        XCTAssertEqual("2.0.5", repo.deviceState.firmwareVersion)
        XCTAssertEqual(2, repo.deviceState.sampleCount)
        // used = 2 MB of max_capacity (62,853,120 B ≈ 59 MB — the Android "2/59MB" readout)
        XCTAssertEqual(Int64(2_097_152), repo.deviceState.storageUsedBytes)
        XCTAssertEqual(EP133DeviceSimulator.MAX_CAPACITY, repo.deviceState.storageTotalBytes)
        XCTAssertTrue(repo.deviceState.connected, "the Android CONNECTED label state")
    }

    func testProjectsScreen_listsSlotsResolvedByNodeWalk() async throws {
        // Arrange: default tree = projects 01..09, project 02 active
        let sim = EP133DeviceSimulator()
        sim.setActiveProject(4000)
        let repo = connect(sim)

        // Act: the Projects screen walks / → /projects, reads the active pointer, lists children
        let slots = try await repo.listProjects()

        // Assert: all 9 slots resolved, slot names 01..09 at 3000..11000, ACTIVE on slot 02
        XCTAssertEqual(9, slots.count)
        XCTAssertTrue(
            slots.contains { $0.nodeId == 3000 && $0.name == "01" && !$0.isActive })
        XCTAssertTrue(
            slots.contains { $0.nodeId == 4000 && $0.name == "02" && $0.isActive },
            "slot 02 must carry the Android ACTIVE badge state")
        XCTAssertTrue(
            slots.contains { $0.nodeId == 11000 && $0.name == "09" })
        XCTAssertEqual(1, slots.filter(\.isActive).count)
    }

    func testSampleImport_runsTheRealPagedPutProtocol() async throws {
        // Arrange
        let sim = EP133DeviceSimulator()
        let repo = connect(sim)
        let manager = SampleImportManager(repo)

        // Act: 2000 bytes → FILE_INIT, /sounds node walk, PUT INIT, 5 DATA pages + terminator
        let payload = (0..<2000).map { UInt8($0 % 251) }
        let events = await runImport(manager, name: "KICK9", bytes: payload)

        // Assert: the import reached DONE and the file landed on the "device" byte-for-byte
        XCTAssertEqual(.done(name: "KICK9.wav"), events.last)
        let uploaded = sim.soundFiles().filter { $0.name == "KICK9.wav" }
        XCTAssertEqual(1, uploaded.count)
        XCTAssertEqual(2000, uploaded[0].data.count)
        XCTAssertEqual(payload, uploaded[0].data, "uploaded bytes must match the staged payload")
    }

    func testSampleImport_pageFailure_forceClosesTheTransfer() async throws {
        // Arrange: the device rejects DATA page 2 — the app must abort AND terminate the
        // transfer (an unterminated PUT wedges real hardware until power-cycle)
        let sim = EP133DeviceSimulator()
        sim.failPutDataAtPage = 2
        let repo = connect(sim)
        let manager = SampleImportManager(repo)

        // Act
        let events = await runImport(manager, name: "SNARE9", bytes: [UInt8](repeating: 0, count: 2000))

        // Assert: the import errored, no partial file committed, and — critically — the
        // app's forceCloseTransfer terminator cleared the wedge
        XCTAssertTrue(endedInError(events), "page failure must surface as an import error")
        XCTAssertTrue(
            sim.soundFiles().allSatisfy { $0.name != "SNARE9.wav" },
            "no partial file may be committed")
        XCTAssertFalse(sim.wedge, "forceCloseTransfer must clear the device wedge")
    }

    func testWedgedDevice_importFailsWithoutCommitting() async throws {
        // Arrange: device already wedged by a previous unterminated PUT (HW-verified behavior:
        // it silently ignores new PUT INITs until power-cycled)
        let sim = EP133DeviceSimulator()
        sim.wedge = true
        let repo = connect(sim)
        // The PUT INIT gets no ack; shorten the 15 s hardware ack timeout so the suite stays
        // fast (the Android original waits out the full timeout under a 25 s robot deadline).
        repo.putAckTimeout = 1.0
        let manager = SampleImportManager(repo)

        // Act
        let events = await runImport(manager, name: "HAT9", bytes: [UInt8](repeating: 0, count: 500))

        // Assert: PUT INIT ack timeout → upload errors out; nothing committed
        XCTAssertTrue(endedInError(events), "a wedged device must produce an import error")
        XCTAssertTrue(sim.soundFiles().allSatisfy { $0.name != "HAT9.wav" })
    }

    func testPadsScreen_autoSyncsActiveGroupFromDevice() async throws {
        // Arrange: hardware group buttons emit no MIDI — the app polls the metadata chain
        // /projects active → <project>/groups active → group name
        let sim = EP133DeviceSimulator()
        sim.setActiveGroup("B")
        let repo = connect(sim)

        // Act: this is the exact walk the Pads screen's 1.5 s poll runs while visible
        let index = try await repo.getActiveGroupIndex()

        // Assert: the poll resolves group B (index 1) without any tap
        XCTAssertEqual(1, index, "active group must resolve to B over the wire")
    }

    func testGroupChipTap_writesActiveGroupToDeviceMetadata() async throws {
        // Arrange — in the app the Pads screen's poll has already run the FILE_INIT
        // handshake before any chip tap (setActiveGroup itself assumes an open session,
        // exactly like the hardware flow the Android robot test drives).
        let sim = EP133DeviceSimulator()
        let repo = connect(sim)
        _ = try await repo.ensureFileSessionInit()

        // Act: selecting group C sends a real METADATA SET to the groups node
        let ok = try await repo.setActiveGroup(index: 2)

        // Assert
        XCTAssertTrue(ok, "the METADATA SET ack must come back over the wire")
        XCTAssertEqual(Character("C"), sim.activeGroupLetter())
    }

    func testPadTap_sendsRealNoteFramesToThePort() async throws {
        // Arrange
        let sim = EP133DeviceSimulator()
        let repo = connect(sim)

        // Act: pad index 9 on group A = "A." = note 36 (the Pads screen calls noteOn)
        repo.noteOn(note: 36)

        // Assert: an actual 0x90 frame crossed the wire
        let noteOns = sim.sentNoteOns()
        XCTAssertFalse(noteOns.isEmpty, "a note-on frame must reach the port")
        XCTAssertEqual(36, noteOns[0].note)
    }
}
