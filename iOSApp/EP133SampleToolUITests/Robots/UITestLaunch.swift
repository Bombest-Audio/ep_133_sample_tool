import XCTest

/// Shared launch helpers for the XCUITest suite — the out-of-process analog of Android's
/// `launchEP133App(...)` / per-test `setContent { ... }` composition.
///
/// Each helper configures the DEBUG launch arguments/environment that `UITestConfig` reads at
/// startup (see `App/UITestSupport.swift`), then launches. Because the app is a separate process,
/// these knobs are how a test "injects" a fake repository or firmware catalog.
extension XCUIApplication {

    /// Launch with the master UI-test gate set. Optionally boot the simulated EP-133 (connected
    /// device), seed an offline permission state, and/or drive the stub firmware catalog.
    ///
    /// - Parameters:
    ///   - simPort: boot against `EP133DeviceSimulator` (connected) instead of an inert port.
    ///   - permission: offline `PermissionState` to seed ("unknown"|"awaiting"|"denied"|"granted").
    ///   - deviceFirmware: firmware version the simulated device reports (sim-port only).
    ///   - latestFirmware: stub-catalog latest version, or "none" for an unavailable catalog.
    ///   - kitBuilderPack: seed a deterministic in-memory pack into the Kit Builder (the
    ///     out-of-process analog of `KitBuilderScreenTest`'s `viewModel.loadPack(testPack())`).
    ///   - seedBackup: seed a `P01.tar` into the app's backup library (the Projects test's
    ///     seeded-`.tar` analog). The library is always cleared under UI test regardless.
    func launchForUITest(
        simPort: Bool = false,
        permission: String? = nil,
        deviceFirmware: String? = nil,
        latestFirmware: String? = nil,
        kitBuilderPack: Bool = false,
        seedBackup: Bool = false
    ) {
        launchArguments.append("-EP133UITest")
        if simPort { launchArguments.append("-EP133UITestSimPort") }
        if kitBuilderPack { launchArguments.append("-EP133UITestKbPack") }
        if seedBackup { launchArguments.append("-EP133UITestSeedBackup") }
        if let permission { launchEnvironment["EP133_PERMISSION"] = permission }
        if let deviceFirmware { launchEnvironment["EP133_FW_DEVICE"] = deviceFirmware }
        if let latestFirmware { launchEnvironment["EP133_FW_LATEST"] = latestFirmware }
        launch()
    }
}
