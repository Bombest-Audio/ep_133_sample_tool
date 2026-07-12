#if DEBUG
import Foundation

/// DEBUG-only bridge between the out-of-process XCUITest runner and the app.
///
/// XCUITest cannot inject Swift fakes the way Android's in-process instrumented tests hand a
/// `TestMIDIRepository`/`FakeFirmwareCatalog` straight to the composition. Instead the UI-test
/// target launches the app with launch arguments / environment, and this file reads them at
/// startup to compose the same test seams the Android robots rely on:
///
/// - `-EP133UITest`            gates every other hook. Nothing here activates without it, so a
///                            normally launched debug build is unaffected.
/// - `-EP133UITestSimPort`     boots the repository against a fresh `EP133DeviceSimulator`
///                            (a connected EP-133 over the wire protocol) instead of the
///                            CoreMIDI `MIDIManager`. This is the out-of-process analog of
///                            Android's `TestMIDIRepository.connectedState()`.
/// - `EP133_PERMISSION`        seeds the offline `PermissionState` (unknown | awaiting | denied |
///                            granted) so the DeviceScreen offline branches are reachable without
///                            hardware — the `ScriptedMIDIRepository(initialStateFor(...))` analog.
/// - `EP133_FW_DEVICE`         overrides the simulator's reported firmware version (default 2.0.5),
///                            standing in for `connectedState(firmwareVersion:)`.
/// - `EP133_FW_LATEST`         drives a `StubFirmwareCatalog` (the `FakeFirmwareCatalog` analog):
///                            a parseable version → that latest; "none"/unset → nil (catalog
///                            unavailable → no banner). Injected into every `DeviceViewModel`.
///
/// Everything is `#if DEBUG`: the Release build compiles with zero test-hook code (the simulator
/// and stub catalog are never referenced), matching Android's src/debug variant isolation.
///
/// EXTENDING THIS (for the Pads / Kit / Projects / Import UI tests a second agent ports):
/// add new launch keys as `static let` accessors on `UITestConfig` and read them where the
/// relevant ViewModel is composed — the sim-port path already gives those screens a live
/// simulated device, so most extensions are new env knobs (e.g. a seeded project list) plus a
/// branch in `UITestSupport.makeRepository()` or the owning screen's init.
enum UITestConfig {

    /// Master gate — no hook fires unless the runner passed `-EP133UITest`.
    static var isActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-EP133UITest")
    }

    /// Boot against a simulated EP-133 (connected device) rather than CoreMIDI.
    static var useSimPort: Bool {
        isActive && ProcessInfo.processInfo.arguments.contains("-EP133UITestSimPort")
    }

    /// Offline permission state to seed (only meaningful when not using the sim port).
    static var permissionOverride: PermissionState? {
        guard isActive else { return nil }
        switch ProcessInfo.processInfo.environment["EP133_PERMISSION"] {
        case "unknown": return .unknown
        case "awaiting": return .awaiting
        case "denied": return .denied
        case "granted": return .granted
        default: return nil
        }
    }

    /// Firmware version the simulated device should report (default matches the simulator's own).
    static var deviceFirmware: String? {
        guard isActive else { return nil }
        return ProcessInfo.processInfo.environment["EP133_FW_DEVICE"]
    }

    /// The catalog to inject into every `DeviceViewModel`, or nil to keep the production catalog.
    static func makeCatalog() -> FirmwareCatalog? {
        guard isActive else { return nil }
        guard let raw = ProcessInfo.processInfo.environment["EP133_FW_LATEST"] else { return nil }
        if raw == "none" { return StubFirmwareCatalog(latest: nil) }
        return StubFirmwareCatalog(latest: FirmwareVersion.parse(raw))
    }

    // ── SAMPLES screen (Kit + Kit Builder) knobs ──────────────────────────────

    /// The SAMPLES page persists its `GroupSession` (designation + choke) in the
    /// "ep133_group_session" defaults suite, so a prior run's KIT designation or choke flip would
    /// leak into the next launch and make the Kit/Kit-Builder UI tests non-deterministic. Under UI
    /// test we build a fresh in-memory `GroupSession` instead — the out-of-process analog of the
    /// Android tests' `GroupSession()` (no prefs). Always on when the master gate is active.
    static var useEphemeralGroupSession: Bool { isActive }

    /// When `-EP133UITestKbPack` is passed, seed the Kit Builder with a deterministic in-memory
    /// pack via its `loadPack` seam — the out-of-process analog of the Android
    /// `KitBuilderScreenTest`'s `viewModel.loadPack(testPack())`. The samples point at synthetic
    /// URLs that can't be decoded/played, so audition + upload naturally fail (matching the Kotlin
    /// "the fake genuinely can't do it" pattern), while browsing/assign/clear are fully reachable.
    static var seedKitBuilderPack: Bool {
        isActive && ProcessInfo.processInfo.arguments.contains("-EP133UITestKbPack")
    }

    // ── Projects screen knobs ─────────────────────────────────────────────────

    /// Prepare the app's backup library (`<Documents>/backups`) for a UI-test run: clear it so a
    /// prior run can't leak a card (the Android test's `@Before`/`@After` cleanup analog), then,
    /// when `-EP133UITestSeedBackup` is passed, write a small `P01.tar` so the backup-library card
    /// is reachable out-of-process (the analog of seeding a real `.tar` in the backups dir). No-op
    /// unless the master gate is active.
    static func seedBackupFileIfRequested() {
        guard isActive else { return }
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("backups", isDirectory: true)
        // Clear any leftover backups so the empty-state case stays deterministic across runs.
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if ProcessInfo.processInfo.arguments.contains("-EP133UITestSeedBackup") {
            let file = dir.appendingPathComponent("P01.tar")
            FileManager.default.createFile(atPath: file.path, contents: Data(count: 128))
        }
    }

    /// The canned pack the Kit Builder loads under `-EP133UITestKbPack`. Mirrors the Kotlin
    /// `testPack()` in `KitBuilderScreenTest`: "Drum Breaks" with KICKS (Kick 1, Kick 2) and
    /// SNARES (Snare 1). Synthetic file URLs (unreadable) so the failure paths reproduce.
    static func makeTestPack() -> KitPack {
        func sample(_ name: String, _ category: String) -> KitSample {
            KitSample(
                name: name,
                url: URL(string: "ep133-uitest://pack/\(name).wav")!,
                category: category,
                meta: "12 KB")
        }
        return KitPack(
            name: "Drum Breaks",
            categories: [
                KitCategory(id: "KICKS", samples: [sample("Kick 1", "KICKS"), sample("Kick 2", "KICKS")]),
                KitCategory(id: "SNARES", samples: [sample("Snare 1", "SNARES")]),
            ])
    }

    /// Build the repository the app runs against under UI test, or nil to use the real MIDI stack.
    /// Sim-port → a connected simulated EP-133; otherwise an inert port seeded with the requested
    /// offline permission state.
    @MainActor
    static func makeRepository() -> MIDIRepository? {
        guard isActive else { return nil }
        if useSimPort {
            let sim = EP133DeviceSimulator(firmwareVersion: deviceFirmware ?? "2.0.5")
            let repo = MIDIRepository(sim)
            repo.refreshDeviceState()
            return repo
        }
        // Offline path: an inert port with no devices, seeded with the requested permission state.
        let repo = MIDIRepository(UITestInertPort())
        if let permission = permissionOverride {
            repo.deviceState.permissionState = permission
        }
        return repo
    }
}

/// Canned `FirmwareCatalog` for UI tests — no network. Mirrors `FakeFirmwareCatalog` (Android) /
/// the private `FakeFirmwareCatalog` in `DeviceViewModelFirmwareTests`.
final class StubFirmwareCatalog: FirmwareCatalog {
    private let latest: FirmwareVersion?

    init(latest: FirmwareVersion?) { self.latest = latest }

    func latestVersion() async -> FirmwareVersion? { latest }
}

/// Inert `MIDIPort` for offline UI tests — reports no devices and drops every send. The
/// out-of-process counterpart of `NoOpMIDIPort` in the Android test support code.
final class UITestInertPort: MIDIPort {
    var onMIDIReceived: ((String, [UInt8]) -> Void)?
    var onDevicesChanged: (() -> Void)?

    func setup() {}
    func close() {}
    func getUSBDevices() -> MIDIDeviceList { MIDIDeviceList(inputs: [], outputs: []) }
    func sendMIDI(to portId: String, data: [UInt8]) {}
    func startListening(portId: String) {}
    func stopListening(portId: String) {}
}
#endif
