import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/device/DeviceScreen.kt` (screen + co-located
/// `DeviceViewModel`).
///
/// One adaptation from Android: backup/restore run inside the embedded web tool here (the
/// SwiftUI analog of `SampleManagerActivity`), presented full-screen from the BACKUP & RESTORE
/// section, instead of the SAF-based native flow — so the Kotlin VM's backup/restore/SAF state
/// is replaced by the `showWebTool` presentation flag.

// ── Firmware update state machine (FW-01 / FW-02) ─────────────────────────────
/// Idle     — initial state; no check has been requested yet
/// Checking — check in progress (catalog.latestVersion() in flight)
/// UpToDate — device firmware >= latest known version
/// UpdateAvailable — device firmware < latest; holds both versions for the banner
/// Unknown  — device firmware string unparseable, or catalog returned nil
enum FirmwareUpdateState: Equatable {
    case idle
    case checking
    case upToDate
    case updateAvailable(current: FirmwareVersion, latest: FirmwareVersion)
    case unknown
}

// ── ViewModel ─────────────────────────────────────────────────────────────────
@MainActor
@Observable
class DeviceViewModel {
    private let midi: MIDIRepository
    @ObservationIgnored private let catalog: FirmwareCatalog

    init(_ midi: MIDIRepository, catalog: FirmwareCatalog = TeFirmwareCatalog()) {
        self.midi = midi
        self.catalog = catalog
    }

    var deviceState: DeviceState { midi.deviceState }

    var selectedChannel: PadChannel = .A

    // D-17: scale state delegated to MIDIRepository (single source of truth)
    var selectedScale: Scale? { midi.selectedScale }
    var selectedRootNote: String { midi.selectedRootNote }

    var snackbarMessage: String?

    /// True while querying firmware/storage/sample-count, so the storage card shows a spinner
    /// only while loading (not forever when a value comes back empty).
    var statsLoading = false

    var firmwareUpdate: FirmwareUpdateState = .idle

    /// Presents the embedded web tool (backup / restore / sync / format) full-screen.
    var showWebTool = false

    // Firmware updater callback — set by AppShell (mirrors the MainActivity SAF/callback pattern)
    @ObservationIgnored var onOpenFirmwareUpdater: (() -> Void)?

    // Simulated EP-133 toggle — wired only when a simulator port provider exists (none on iOS
    // yet). Nil hides the toggle UI entirely, matching the Android release behavior.
    @ObservationIgnored var onSimToggle: ((Bool) -> Void)?
    var simToggleAvailable: Bool { onSimToggle != nil }
    var simModeActive = false

    /// The in-flight loadStats task — awaited by tests (the advanceUntilIdle seam).
    @ObservationIgnored private(set) var loadStatsTask: Task<Void, Never>?

    func openWebTool() { showWebTool = true }
    func dismissWebTool() { showWebTool = false }

    func dismissSnackbar() { snackbarMessage = nil }

    /// Surface a snackbar message from outside the ViewModel (e.g. the app shell).
    func showSnackbar(_ message: String) { snackbarMessage = message }

    func selectChannel(_ channel: PadChannel) {
        selectedChannel = channel
        // EP-133 uses MIDI ch 1 (index 0) for all groups by default
        midi.setChannel(0)
    }

    func selectScale(_ scale: Scale?) { midi.setScale(scale) }

    func selectRootNote(_ note: String) { midi.setRootNote(note) }

    func refreshDevices() { midi.refreshDeviceState() }

    /// Invokes the firmware updater callback wired by AppShell.
    func openFirmwareUpdater() { onOpenFirmwareUpdater?() }

    /// Flip between the real port and a simulated EP-133 (only when a provider is wired).
    /// loadStats() is called explicitly because swapPort resets deviceState synchronously,
    /// so the connected-edge observation can miss the false→true transition.
    func setSimulatedMode(_ enabled: Bool) {
        guard let toggle = onSimToggle else { return }
        if enabled == simModeActive { return }
        toggle(enabled)
        simModeActive = enabled
        loadStats()
    }

    /// Query firmware / storage / sample count. Called when the Device screen opens connected.
    func loadStats() {
        if statsLoading { return }
        loadStatsTask = Task { [weak self] in
            guard let self else { return }
            statsLoading = true
            _ = try? await midi.queryDeviceStats()
            statsLoading = false
            if deviceState.firmwareVersion != nil {
                await checkFirmwareUpdate()
            }
        }
    }

    /// Checks whether the device firmware is up to date by asking the catalog for the latest
    /// version. Emits Checking → UpdateAvailable / UpToDate / Unknown.
    ///
    /// Only called from loadStats() after a stats query with a non-nil firmwareVersion.
    /// The statsLoading guard on loadStats() prevents concurrent invocations.
    private func checkFirmwareUpdate() async {
        firmwareUpdate = .checking
        let rawFw = deviceState.firmwareVersion
        guard let current = FirmwareVersion.parse(rawFw) else {
            print("[EP133APP] FW check: unparseable firmware '\(rawFw ?? "nil")'")
            firmwareUpdate = .unknown
            return
        }
        guard let latest = await catalog.latestVersion() else {
            firmwareUpdate = .unknown
            return
        }
        firmwareUpdate = current >= latest
            ? .upToDate
            : .updateAvailable(current: current, latest: latest)
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/// Hard ~2–3pt faceplate corner (mirrors the design's `border-radius:2px/3px`).
private let panelRadius: CGFloat = 3

private func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    .system(size: size, weight: weight, design: .monospaced)
}

private extension View {
    func devicePanel(fill: Color, border: Color) -> some View {
        background(RoundedRectangle(cornerRadius: panelRadius).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: panelRadius).strokeBorder(border, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: panelRadius))
    }
}

struct DeviceScreen: View {
    @Environment(\.ep133Tokens) private var t
    @Bindable var viewModel: DeviceViewModel

    var body: some View {
        ZStack(alignment: .bottom) {
            t.bg.ignoresSafeArea()

            if !viewModel.deviceState.connected {
                VStack(spacing: 0) {
                    // Clean offline state — fills the body, plug-in guidance keyed off permission.
                    DeviceOfflineState(
                        permissionState: viewModel.deviceState.permissionState,
                        onGrantPermission: { viewModel.refreshDevices() }
                    )
                    .frame(maxHeight: .infinity)
                    // Critical path: with no hardware the screen is offline, and the sim toggle
                    // must be reachable here or the feature is unusable.
                    if viewModel.simToggleAvailable {
                        SimulatorModeSection(
                            active: viewModel.simModeActive,
                            onToggle: { viewModel.setSimulatedMode($0) }
                        )
                        .padding(.horizontal, 14)
                        .padding(.bottom, 14)
                    }
                }
            } else {
                ScrollView {
                    VStack(spacing: 11) {
                        ConnectionCard(state: viewModel.deviceState)
                        StorageBar(state: viewModel.deviceState, loading: viewModel.statsLoading)
                        StatsGrid(state: viewModel.deviceState)
                        FirmwareUpdateBanner(
                            state: viewModel.firmwareUpdate,
                            onOpenUpdater: { viewModel.openFirmwareUpdater() }
                        )
                        ChannelSelector(
                            selected: viewModel.selectedChannel,
                            onSelect: { viewModel.selectChannel($0) }
                        )
                        ScaleModeSelector(
                            selectedScale: viewModel.selectedScale,
                            onScaleSelect: { viewModel.selectScale($0) },
                            selectedRoot: viewModel.selectedRootNote,
                            onRootSelect: { viewModel.selectRootNote($0) }
                        )
                        WebToolSection(onOpen: { viewModel.openWebTool() })
                        // Rendered while connected too, so the user can flip back to the real
                        // port while the simulator is the active port.
                        if viewModel.simToggleAvailable {
                            SimulatorModeSection(
                                active: viewModel.simModeActive,
                                onToggle: { viewModel.setSimulatedMode($0) }
                            )
                        }
                    }
                    .padding(14)
                }
            }

            // Snackbar analog: bottom toast, auto-dismissed.
            if let message = viewModel.snackbarMessage {
                SnackbarToast(message: message)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 16)
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 2_500_000_000)
                        viewModel.dismissSnackbar()
                    }
            }
        }
        // Query firmware / storage / samples whenever we're connected and the screen is shown
        // (Compose's LaunchedEffect(deviceState.connected)).
        .onAppear {
            if viewModel.deviceState.connected { viewModel.loadStats() }
        }
        .onChange(of: viewModel.deviceState.connected) { _, connected in
            if connected { viewModel.loadStats() }
        }
        .fullScreenCover(isPresented: $viewModel.showWebTool) {
            WebToolSheet(onClose: { viewModel.dismissWebTool() })
        }
    }
}

// ── Firmware update banner — shows above ChannelSelector when UpdateAvailable; spinner on Checking ──
private struct FirmwareUpdateBanner: View {
    @Environment(\.ep133Tokens) private var t
    let state: FirmwareUpdateState
    let onOpenUpdater: () -> Void

    var body: some View {
        switch state {
        case .updateAvailable(_, let latest):
            HStack(spacing: 8) {
                Text("Firmware \(latest) available")
                    .font(mono(11, .bold))
                    .tracking(0.3)
                    .foregroundStyle(t.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onOpenUpdater) {
                    Text("Open updater")
                        .font(mono(10, .bold))
                        .tracking(0.4)
                        .foregroundStyle(t.accent)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 6)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 10)
            .devicePanel(fill: t.accent.opacity(0.12), border: t.accent.opacity(0.35))
            .accessibilityIdentifier(TestTags.DEVICE_FIRMWARE_BANNER)
        case .checking:
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.mini)
                    .tint(t.live)
                Text("CHECKING FIRMWARE…")
                    .font(mono(9))
                    .tracking(0.8)
                    .foregroundStyle(t.text3)
                Spacer()
            }
            .padding(.horizontal, 2)
            .padding(.vertical, 4)
        case .idle, .upToDate, .unknown:
            // Render nothing.
            EmptyView()
        }
    }
}

// ── Connection card (faceplate-black, mono eyebrow + device name + halo dot) ──
private struct ConnectionCard: View {
    @Environment(\.ep133Tokens) private var t
    let state: DeviceState

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text("CONNECTED OVER USB")
                    .font(mono(9))
                    .tracking(1.4)
                    .foregroundStyle(t.padName)
                Spacer().frame(height: 3)
                Text(state.deviceName.isEmpty ? "EP-133 K.O. II" : state.deviceName)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                Spacer().frame(height: 13)
                HStack(spacing: 16) {
                    MonoFact(label: "FW", value: state.firmwareVersion ?? "—", valueColor: t.live)
                    MonoFact(label: "CH", value: "01", valueColor: .white)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // teal halo "online" dot
            ZStack {
                Circle().fill(t.live.opacity(0.18)).frame(width: 19, height: 19)
                EP133StatusDot(color: t.live, size: 11)
            }
        }
        .padding(15)
        .devicePanel(fill: t.padFace, border: t.padEdge)
    }
}

/// A `LABEL value` pair on the dark connection card (mono).
private struct MonoFact: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let value: String
    let valueColor: Color

    var body: some View {
        HStack(spacing: 5) {
            Text(label)
                .font(mono(9.5))
                .tracking(0.6)
                .foregroundStyle(t.padName)
            Text(value)
                .font(mono(9.5, .bold))
                .tracking(0.6)
                .foregroundStyle(valueColor)
        }
    }
}

// ── Themed 6pt linear progress bar (LinearProgressIndicator analog) ───────────
private struct EP133ProgressBar: View {
    @Environment(\.ep133Tokens) private var t
    let progress: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2).fill(t.inset)
                RoundedRectangle(cornerRadius: 2)
                    .fill(t.accent)
                    .frame(width: geo.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: 6)
    }
}

// ── Storage progress (accent track, mono readout) ─────────────────────────────
private struct StorageBar: View {
    @Environment(\.ep133Tokens) private var t
    let state: DeviceState
    let loading: Bool

    var body: some View {
        let haveBytes = state.storageUsedBytes != nil && state.storageTotalBytes != nil
        let progress: Double = {
            guard let used = state.storageUsedBytes, let total = state.storageTotalBytes,
                  total > 0 else { return 0 }
            return Double(used) / Double(total)
        }()
        let readout: String? = {
            guard haveBytes, let used = state.storageUsedBytes,
                  let total = state.storageTotalBytes else { return nil }
            let usedMb = Double(used) / 1_048_576
            let totalMb = Double(total) / 1_048_576
            return String(format: "%.1f / %.1f MB", usedMb, totalMb)
        }()

        VStack(spacing: 9) {
            HStack {
                Text("STORAGE")
                    .font(mono(9.5))
                    .tracking(0.6)
                    .foregroundStyle(t.text2)
                Spacer()
                if let readout {
                    Text(readout)
                        .font(mono(9.5, .bold))
                        .tracking(0.4)
                        .foregroundStyle(t.text)
                } else if loading {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(t.text3)
                } else {
                    Text("—")
                        .font(mono(9.5, .bold))
                        .foregroundStyle(t.text3)
                }
            }
            EP133ProgressBar(progress: progress)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 12)
        .devicePanel(fill: t.panel2, border: t.rule)
    }
}

// ── Stats grid — samples / storage / firmware as mono readouts + MIDI CH ──────
private struct StatsGrid: View {
    let state: DeviceState

    var body: some View {
        let samplesValue = state.sampleCount.map(String.init) ?? "—"
        let storageValue: String = {
            guard let used = state.storageUsedBytes, let total = state.storageTotalBytes else {
                return "—"
            }
            return "\(used / 1_048_576)/\(total / 1_048_576)MB"
        }()
        let firmwareValue = state.firmwareVersion ?? "—"

        VStack(spacing: 7) {
            HStack(spacing: 7) {
                EP133StatReadout(label: "SAMPLES", value: samplesValue)
                EP133StatReadout(label: "STORAGE", value: storageValue)
            }
            HStack(spacing: 7) {
                EP133StatReadout(label: "FIRMWARE", value: firmwareValue)
                EP133StatReadout(label: "MIDI CH", value: "01")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(TestTags.DEVICE_STATS_GRID)
    }
}

// ── MIDI channel (pad-group selector A/B/C/D) ─────────────────────────────────
private struct ChannelSelector: View {
    let selected: PadChannel
    let onSelect: (PadChannel) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EP133SectionLabel(text: "MIDI CHANNEL")
            HStack(spacing: 7) {
                ForEach(PadChannel.allCases, id: \.self) { channel in
                    EP133GroupChip(
                        label: channel.rawValue,
                        selected: selected == channel,
                        onClick: { onSelect(channel) }
                    )
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// ── Scale + root (themed dropdowns — Menu is the ExposedDropdownMenu analog) ──
private struct ScaleModeSelector: View {
    let selectedScale: Scale?
    let onScaleSelect: (Scale?) -> Void
    let selectedRoot: String
    let onRootSelect: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EP133SectionLabel(text: "SCALE MODE")
            HStack(alignment: .top, spacing: 7) {
                ScaleDropdown(
                    label: "SCALE",
                    selectedText: selectedScale?.name ?? "None",
                    options: ["None"] + EP133Scales.ALL.map(\.name),
                    onSelect: { name in
                        if name == "None" {
                            onScaleSelect(nil)
                        } else if let scale = EP133Scales.ALL.first(where: { $0.name == name }) {
                            onScaleSelect(scale)
                        }
                    }
                )
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier(TestTags.DEVICE_SCALE_DROPDOWN)

                ScaleDropdown(
                    label: "ROOT",
                    selectedText: selectedRoot,
                    options: EP133Scales.ROOT_NOTES,
                    onSelect: onRootSelect
                )
                .frame(width: 108)
                .accessibilityIdentifier(TestTags.DEVICE_ROOT_DROPDOWN)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ScaleDropdown: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let selectedText: String
    let options: [String]
    let onSelect: (String) -> Void

    var body: some View {
        Menu {
            ForEach(options, id: \.self) { option in
                Button(option) { onSelect(option) }
            }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(mono(9))
                    .tracking(1.2)
                    .foregroundStyle(t.text3)
                HStack(spacing: 4) {
                    Text(selectedText)
                        .font(mono(13, .bold))
                        .foregroundStyle(t.text)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(t.text3)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .devicePanel(fill: t.panel2, border: t.rule)
        }
        .buttonStyle(.plain)
    }
}

// ── Backup & restore — entry point to the embedded web tool ───────────────────
private struct WebToolSection: View {
    @Environment(\.ep133Tokens) private var t
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EP133SectionLabel(text: "BACKUP & RESTORE")
            EP133PrimaryButton(label: "Open Sample Tool", onClick: onOpen)
            Text("backup, restore and format run in the embedded web tool")
                .font(mono(8.5))
                .tracking(0.3)
                .foregroundStyle(t.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Full-screen presentation of the compiled web tool (the SampleManagerActivity analog).
private struct WebToolSheet: View {
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            EP133WebView()
                .ignoresSafeArea(edges: .bottom)
            Button(action: onClose) {
                Text("CLOSE")
                    .font(mono(10, .bold))
                    .tracking(0.8)
                    .foregroundStyle(.white)
                    .padding(.vertical, 6)
                    .padding(.horizontal, 10)
                    .background(RoundedRectangle(cornerRadius: panelRadius).fill(.white.opacity(0.15)))
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
            .padding(.trailing, 12)
        }
        .preferredColorScheme(.dark)
    }
}

// ── Simulated EP-133 toggle (rendered only behind simToggleAvailable) ─────────
private struct SimulatorModeSection: View {
    @Environment(\.ep133Tokens) private var t
    let active: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        HStack {
            Text("SIMULATED EP-133")
                .font(mono(9.5))
                .tracking(0.6)
                .foregroundStyle(t.text2)
            Spacer()
            Toggle("", isOn: Binding(get: { active }, set: onToggle))
                .labelsHidden()
                .tint(t.accent)
                .accessibilityIdentifier(TestTags.DEVICE_SIM_TOGGLE)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 4)
        .devicePanel(fill: t.panel2, border: t.rule)
    }
}

// ── Clean "NO DEVICE" offline state, keyed off permission ─────────────────────
private struct DeviceOfflineState: View {
    @Environment(\.ep133Tokens) private var t
    @Environment(\.openURL) private var openURL
    let permissionState: PermissionState
    let onGrantPermission: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // eject glyph in a bordered square
            Text("⏏")
                .font(mono(26))
                .foregroundStyle(t.text3)
                .frame(width: 74, height: 74)
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(t.rule, lineWidth: 1))
            Spacer().frame(height: 16)
            Text("no device")
                .font(.system(size: 18, weight: .bold))
                .tracking(-0.3)
                .foregroundStyle(t.text)
            Spacer().frame(height: 7)

            switch permissionState {
            case .awaiting:
                Text("waiting for USB permission…")
                    .font(mono(11))
                    .tracking(0.3)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(t.text2)
                Spacer().frame(height: 16)
                ProgressView()
                    .controlSize(.regular)
                    .tint(t.text3)
            case .denied:
                Text("USB permission required.\nallow USB access in Settings")
                    .font(mono(11))
                    .tracking(0.3)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(t.text2)
                Spacer().frame(height: 16)
                EP133GhostButton(label: "Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                }
                .accessibilityIdentifier(TestTags.DEVICE_PERMISSION_ACTION)
            case .unknown, .granted:
                // Device present but not yet enumerated.
                Text("plug in your K.O. II\nover USB")
                    .font(mono(11))
                    .tracking(0.3)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(t.text2)
                Spacer().frame(height: 16)
                HStack(spacing: 8) {
                    EP133StatusDot(color: t.text3, size: 7)
                    Text("SCANNING PORTS…")
                        .font(mono(9))
                        .tracking(0.8)
                        .foregroundStyle(t.text3)
                }
                Spacer().frame(height: 14)
                EP133GhostButton(label: "Grant Permission", onClick: onGrantPermission)
                    .accessibilityIdentifier(TestTags.DEVICE_PERMISSION_ACTION)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// ── Snackbar analog — bottom toast panel ──────────────────────────────────────
private struct SnackbarToast: View {
    @Environment(\.ep133Tokens) private var t
    let message: String

    var body: some View {
        Text(message)
            .font(mono(10.5, .medium))
            .tracking(0.3)
            .foregroundStyle(t.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 13)
            .padding(.vertical, 11)
            .devicePanel(fill: t.panel, border: t.rule)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
