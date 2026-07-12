import XCTest
@testable import EP133SampleTool

// ── Test doubles ──────────────────────────────────────────────────────────────

/// Fake FirmwareCatalog — returns a caller-specified result (or nil) and records call count.
private final class FakeFirmwareCatalog: FirmwareCatalog {
    private let result: FirmwareVersion?
    var callCount = 0

    init(result: FirmwareVersion?) {
        self.result = result
    }

    func latestVersion() async -> FirmwareVersion? {
        callCount += 1
        return result
    }
}

/// Builds a MIDIRepository pre-seeded with a known firmwareVersion (the Kotlin
/// FirmwareTestMIDIRepo analog — here the internal deviceState setter is the seam).
///
/// outputPortId is intentionally nil — this causes queryDeviceStats() to return false
/// immediately (the real guard: `guard let portId = deviceState.outputPortId else { return
/// false }`). The pre-seeded firmwareVersion survives the no-op queryDeviceStats call, so
/// checkFirmwareUpdate() receives the expected value.
@MainActor
private func firmwareTestMIDIRepo(firmwareVersion: String?) -> MIDIRepository {
    let repo = MIDIRepository(FakeMIDIPort())
    repo.deviceState = DeviceState(
        connected: true,
        outputPortId: nil,  // no port → queryDeviceStats returns false immediately
        firmwareVersion: firmwareVersion
    )
    return repo
}

// ── Tests ─────────────────────────────────────────────────────────────────────

/// Mirrors AndroidApp/app/src/test/java/com/ep133/sampletool/DeviceViewModelFirmwareTest.kt.
///
/// Unit tests for DeviceViewModel firmware update state machine (FW-01 / FW-02).
///
/// All tests use a fake FirmwareCatalog (no network) and a pre-seeded MIDIRepository (no MIDI
/// hardware). Awaiting `loadStatsTask` replaces Kotlin's test dispatcher + advanceUntilIdle.
@MainActor
final class DeviceViewModelFirmwareTests: XCTestCase {

    // ── Case 1: outdated firmware → UpdateAvailable ───────────────────────────

    func testLoadStats_outdatedFirmware_emitsUpdateAvailable() async {
        let catalog = FakeFirmwareCatalog(result: FirmwareVersion.parse("2.5"))
        let repo = firmwareTestMIDIRepo(firmwareVersion: "2.0.5")
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        guard case let .updateAvailable(current, latest) = vm.firmwareUpdate else {
            XCTFail("Expected UpdateAvailable but got \(vm.firmwareUpdate)")
            return
        }
        XCTAssertEqual(FirmwareVersion.parse("2.0.5"), current, "current must be 2.0.5")
        XCTAssertEqual(FirmwareVersion.parse("2.5"), latest, "latest must be 2.5")
    }

    // ── Case 2: current firmware == latest → UpToDate ─────────────────────────

    func testLoadStats_currentFirmware_emitsUpToDate() async {
        let catalog = FakeFirmwareCatalog(result: FirmwareVersion.parse("2.5"))
        let repo = firmwareTestMIDIRepo(firmwareVersion: "2.5")
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        XCTAssertEqual(
            FirmwareUpdateState.upToDate, vm.firmwareUpdate,
            "Expected UpToDate when device firmware == latest"
        )
    }

    // ── Case 3: nil firmwareVersion → Idle, catalog not called ────────────────

    func testLoadStats_nilFirmwareVersion_staysIdle_catalogNotCalled() async {
        let catalog = FakeFirmwareCatalog(result: FirmwareVersion.parse("2.5"))
        let repo = firmwareTestMIDIRepo(firmwareVersion: nil)
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        XCTAssertEqual(
            FirmwareUpdateState.idle, vm.firmwareUpdate,
            "Expected Idle (no firmware version → checkFirmwareUpdate not triggered)"
        )
        XCTAssertEqual(0, catalog.callCount, "catalog must not be called when firmwareVersion is nil")
    }

    // ── Case 4: unparseable firmware string → Unknown, catalog not called ─────

    func testLoadStats_unparseableFirmware_emitsUnknown_catalogNotCalled() async {
        let catalog = FakeFirmwareCatalog(result: FirmwareVersion.parse("2.5"))
        let repo = firmwareTestMIDIRepo(firmwareVersion: "not-a-version")
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        XCTAssertEqual(
            FirmwareUpdateState.unknown, vm.firmwareUpdate,
            "Expected Unknown for unparseable firmware string"
        )
        XCTAssertEqual(0, catalog.callCount, "catalog must not be called for unparseable firmware")
    }

    // ── Case 5: fake catalog returns nil (defensive path) → Unknown ───────────
    //
    // Note: the real TeFirmwareCatalog floors to LATEST_KNOWN_FIRMWARE and never returns nil.
    // This test covers the defensive code path for fake/nil catalog results only.

    func testLoadStats_catalogReturnsNil_emitsUnknown() async {
        let catalog = FakeFirmwareCatalog(result: nil)  // fake-nil path only
        let repo = firmwareTestMIDIRepo(firmwareVersion: "2.0")
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        XCTAssertEqual(
            FirmwareUpdateState.unknown, vm.firmwareUpdate,
            "Expected Unknown when catalog returns nil"
        )
    }

    // ── Case 6: checkFirmwareUpdate triggered after loadStats → catalog called once ──

    func testLoadStats_withFirmwareVersion_triggersCheckAndCallsCatalogOnce() async {
        let catalog = FakeFirmwareCatalog(result: FirmwareVersion.parse("2.5"))
        let repo = firmwareTestMIDIRepo(firmwareVersion: "2.0.5")
        let vm = DeviceViewModel(repo, catalog: catalog)

        vm.loadStats()
        await vm.loadStatsTask?.value

        XCTAssertEqual(1, catalog.callCount, "catalog must be called exactly once after loadStats with firmware")
    }
}
