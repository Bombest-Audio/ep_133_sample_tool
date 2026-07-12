import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/EP133App.kt`.
///
/// App shell for the redesign: the EP-133 faceplate chrome (header with SKU badge + current
/// screen title + theme toggle + connection dot, and a mono bottom tab nav) wrapping the four
/// screens. Theme state lives here — above the token provider — so the header controls can
/// re-theme the whole app, exactly like Android's `themeMode`/`sku` state above `EP133Theme`.
struct AppShell: View {
    private let midi: MIDIRepository
    @State private var deviceViewModel: DeviceViewModel

    /// 0 = follow system, 1 = light, 2 = dark (mirrors Android's `themeMode`).
    @State private var themeMode = 0
    @State private var sku: EP133Sku = .ep133
    @State private var currentTab: EP133Tab = .pads

    @Environment(\.openURL) private var openURL

    init(midi: MIDIRepository, catalog: FirmwareCatalog? = nil) {
        self.midi = midi
        // catalog is nil in production → DeviceViewModel falls back to TeFirmwareCatalog; a
        // UI-test launch passes a StubFirmwareCatalog through here.
        if let catalog {
            _deviceViewModel = State(initialValue: DeviceViewModel(midi, catalog: catalog))
        } else {
            _deviceViewModel = State(initialValue: DeviceViewModel(midi))
        }
    }

    /// Compose's `themeMode` glyph: ☀ light, ☾ dark, ◐ follow system.
    private var themeGlyph: String {
        switch themeMode {
        case 1: "☀"
        case 2: "☾"
        default: "◐"
        }
    }

    /// The explicit scheme override; nil follows the system (drives `preferredColorScheme`,
    /// which feeds `.ep133Theme`'s colorScheme read — the SwiftUI analog of Android's
    /// `EP133Theme(darkTheme = dark, …)`).
    private var schemeOverride: ColorScheme? {
        switch themeMode {
        case 1: .light
        case 2: .dark
        default: nil
        }
    }

    var body: some View {
        EP133Scaffold(
            title: currentTab.label,
            connected: midi.deviceState.connected,
            currentTab: currentTab,
            onTab: { currentTab = $0 },
            sku: sku,
            onSkuToggle: { sku = sku == .ep133 ? .ep1320 : .ep133 },
            themeGlyph: themeGlyph,
            onThemeToggle: { themeMode = (themeMode + 1) % 3 }
        ) {
            // All tabs stay mounted (hidden, not removed) so screen-owned @State — including
            // each screen's ViewModel — survives tab switches, matching Android's
            // activity-scoped ViewModels. A `switch` here would tear the screen down and
            // reset kit slices, pack browsing, etc. on every tab change.
            ZStack {
                PadsScreen()
                    .opacity(currentTab == .pads ? 1 : 0)
                    .allowsHitTesting(currentTab == .pads)
                    .accessibilityHidden(currentTab != .pads)
                KitScreen()
                    .opacity(currentTab == .kit ? 1 : 0)
                    .allowsHitTesting(currentTab == .kit)
                    .accessibilityHidden(currentTab != .kit)
                ProjectsScreen()
                    .opacity(currentTab == .projects ? 1 : 0)
                    .allowsHitTesting(currentTab == .projects)
                    .accessibilityHidden(currentTab != .projects)
                // Device mounts per visit: its ViewModel already lives at shell scope, and
                // remounting refires the screen's onAppear stats query like Android's
                // LaunchedEffect on navigation.
                if currentTab == .device {
                    DeviceScreen(viewModel: deviceViewModel)
                }
            }
        }
        .ep133Theme(sku: sku)
        .preferredColorScheme(schemeOverride)
        .onAppear {
            // MainActivity wires the firmware-updater Custom Tab; here the SwiftUI openURL
            // action fills the same seam.
            deviceViewModel.onOpenFirmwareUpdater = {
                openURL(URL(string: "https://teenage.engineering/apps/update")!)
            }
        }
    }
}

// ── Shared placeholder body for screens not yet ported ────────────────────────
/// Theme-styled "coming soon" panel used by the placeholder screens (Pads, Samples, Projects,
/// Kit Builder, Import) until each is ported from its Compose counterpart.
struct EP133ComingSoonPanel: View {
    @Environment(\.ep133Tokens) private var t
    let section: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EP133SectionLabel(text: section)
            VStack(spacing: 6) {
                Text("COMING SOON")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .tracking(1.2)
                    .foregroundStyle(t.text2)
                Text("this screen is being ported from the Android app")
                    .font(.system(size: 9, design: .monospaced))
                    .tracking(0.3)
                    .foregroundStyle(t.text3)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 28)
            .background(RoundedRectangle(cornerRadius: 3).fill(t.inset))
            .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(t.rule, lineWidth: 1))
            Spacer()
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(t.bg)
    }
}
