import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/theme/EP133Components.kt`.
///
/// The EP-133 redesign component kit — the SwiftUI mirror of the Claude Design system's component
/// sheet (pads, chips, group selector, status dots, buttons, section labels, app chrome). Every
/// redesigned screen composes from these so the look stays consistent. All colors come from
/// `\.ep133Tokens`; the hardware aesthetic (flat faceplate, hard ~2pt corners, mono labels) is
/// intentionally NOT native chrome — system components are used only where they fit (forms,
/// sliders).

/// Small hard-corner radius used across the faceplate UI.
private let radius: CGFloat = 3

/// Mono labels/codes (the design uses JetBrains Mono; the system monospaced design — SF Mono —
/// is the safe on-device fallback).
private func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    .system(size: size, weight: weight, design: .monospaced)
}

private extension View {
    /// Compose's `.clip(Radius).background(color, Radius).border(1.dp, border, Radius)` stack.
    func ep133Panel(fill: Color, border: Color? = nil) -> some View {
        background(RoundedRectangle(cornerRadius: radius).fill(fill))
            .overlay {
                if let border {
                    RoundedRectangle(cornerRadius: radius).strokeBorder(border, lineWidth: 1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
            .contentShape(RoundedRectangle(cornerRadius: radius))
    }
}

// ── Section label — uppercase mono eyebrow over a block ───────────────────────
struct EP133SectionLabel: View {
    @Environment(\.ep133Tokens) private var t
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(mono(9, .medium))
            .tracking(1.6)
            .foregroundStyle(t.text3)
    }
}

// ── Status dot (teal=ok, accent=active, text3=idle) ───────────────────────────
struct EP133StatusDot: View {
    let color: Color
    var size: CGFloat = 8

    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

// ── Pad cell ──────────────────────────────────────────────────────────────────
enum PadState { case empty, loaded, pressed, inScale }

/// A rubber pad cell: faceplate-black face, hard corners, mono id + name. `state` drives the id
/// color and the pressed glow / in-scale teal hairline, matching the design's pad variants.
struct EP133Pad: View {
    @Environment(\.ep133Tokens) private var t
    let id: String
    let name: String
    let state: PadState
    var onClick: (() -> Void)? = nil

    var body: some View {
        let idColor: Color = switch state {
        case .empty: Color(argb: 0xFF5D5E5F)
        case .loaded: Color(argb: 0xFFC9CACB)
        case .pressed: t.accent
        case .inScale: t.live
        }
        let border: Color = switch state {
        case .inScale: t.live
        case .pressed: t.accent
        default: t.padEdge
        }
        let face = state == .pressed ? t.padTop : t.padFace

        VStack(alignment: .leading, spacing: 3) {
            Text(id)
                .font(mono(11, .bold))
                .tracking(0.3)
                .foregroundStyle(idColor)
            Text(name.uppercased())
                .font(mono(8))
                .tracking(0.3)
                .foregroundStyle(t.padName)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 11)
        .padding(.horizontal, 8)
        .ep133Panel(fill: face, border: border)
        .onTapGesture { onClick?() }
    }
}

// ── Group selector chip (A / B / C / D, etc.) ─────────────────────────────────
struct EP133GroupChip: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let selected: Bool
    var subLabel: String? = nil
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 1) {
                Text(label)
                    .font(mono(12, .bold))
                    .foregroundStyle(selected ? t.onAccent : t.text2)
                if let subLabel {
                    Text(subLabel)
                        .font(mono(7.5, .bold))
                        .tracking(0.8)
                        .foregroundStyle(selected ? t.onAccent.opacity(0.75) : t.text3)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, subLabel != nil ? 6 : 9)
            .padding(.horizontal, 14)
            .ep133Panel(fill: selected ? t.accent : t.inset, border: selected ? t.accent : t.rule)
        }
        .buttonStyle(.plain)
    }
}

// ── Group + choke bar — the shared A/B/C/D picker + choke toggle ───────────────
/// The A/B/C/D group picker plus the CHOKE GROUP toggle, as one block. Both the Loop Chopper and
/// the Kit Builder target a group and can choke it, so they share this control (identical placement
/// and styling) rather than each rolling its own. The choke toggle writes `sound.mutegroup` so pads
/// in the group cut each other off.
///
/// Generic over `Channel` (Android binds directly to `PadChannel`) so the component kit doesn't
/// depend on the domain layer; pass the channel list and a `label` closure.
struct EP133GroupChokeBar<Channel: Hashable>: View {
    @Environment(\.ep133Tokens) private var t
    let channels: [Channel]
    let group: Channel
    let onGroupChange: (Channel) -> Void
    let chokeOn: Bool
    let onChokeChange: (Bool) -> Void
    var label: (Channel) -> String
    var tagFor: ((Channel) -> String?)? = nil
    var testTagFor: ((Channel) -> String)? = nil

    var body: some View {
        VStack(spacing: 7) {
            // A | B | C | D — one chip per group, equal width, each tagged with its designation.
            HStack(spacing: 6) {
                ForEach(channels, id: \.self) { ch in
                    EP133GroupChip(
                        label: label(ch),
                        selected: group == ch,
                        subLabel: tagFor?(ch) ?? nil,
                        onClick: { onGroupChange(ch) }
                    )
                    .accessibilityIdentifier(testTagFor?(ch) ?? "")
                }
            }
            // Choke toggle — full-width row, tap anywhere to flip.
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("CHOKE GROUP")
                        .font(mono(10.5, .bold))
                        .tracking(0.6)
                        .foregroundStyle(t.text)
                    Text("pads in the group cut each other off")
                        .font(mono(8.5))
                        .foregroundStyle(t.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(chokeOn ? "ON" : "OFF")
                    .font(mono(10, .bold))
                    .tracking(0.8)
                    .foregroundStyle(chokeOn ? t.onAccent : t.text2)
                    .padding(.horizontal, 13)
                    .padding(.vertical, 5)
                    .ep133Panel(fill: chokeOn ? t.accent : t.chrome)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .ep133Panel(fill: t.inset, border: t.rule)
            .onTapGesture { onChokeChange(!chokeOn) }
            .accessibilityIdentifier(TestTags.GROUP_CHOKE_TOGGLE)
        }
    }
}

// ── Chips — filter (base) and action (solid) + live badge ─────────────────────
struct EP133Chip: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    var selected: Bool = false
    var onClick: (() -> Void)? = nil

    var body: some View {
        Text(label.uppercased())
            .font(mono(9.5, .medium))
            .tracking(0.6)
            .foregroundStyle(selected ? t.onAccent : t.text2)
            .padding(.vertical, 6)
            .padding(.horizontal, 10)
            .ep133Panel(fill: selected ? t.accent : .clear, border: selected ? t.accent : t.rule)
            .onTapGesture { onClick?() }
    }
}

struct EP133LiveBadge: View {
    @Environment(\.ep133Tokens) private var t
    var label: String = "LIVE"

    var body: some View {
        HStack(spacing: 5) {
            EP133StatusDot(color: t.live, size: 6)
            Text(label.uppercased())
                .font(mono(9, .bold))
                .tracking(0.8)
                .foregroundStyle(t.liveInk)
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .ep133Panel(fill: t.live.opacity(0.14))
    }
}

// ── Buttons — primary (solid accent) / ghost (outline) ────────────────────────
struct EP133PrimaryButton: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    var enabled: Bool = true
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Text(label.uppercased())
                .font(.system(size: 13, weight: .bold))
                .tracking(0.4)
                .foregroundStyle(enabled ? t.onAccent : t.text3)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .padding(.horizontal, 16)
                .ep133Panel(fill: enabled ? t.accent : t.rule)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

struct EP133GhostButton: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    var enabled: Bool = true
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Text(label.uppercased())
                .font(.system(size: 13, weight: .bold))
                .tracking(0.4)
                .foregroundStyle(enabled ? t.text : t.text3.opacity(0.5))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 11)
                .padding(.horizontal, 16)
                .ep133Panel(fill: .clear, border: t.rule)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// ── Mono stat readout — LABEL over a big mono VALUE, on an inset panel ─────────
struct EP133StatReadout: View {
    @Environment(\.ep133Tokens) private var t
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label.uppercased())
                .font(mono(8.5))
                .tracking(1.2)
                .foregroundStyle(t.text3)
            Text(value)
                .font(mono(15, .bold))
                .foregroundStyle(t.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 9)
        .padding(.horizontal, 11)
        .ep133Panel(fill: t.inset, border: t.rule)
    }
}

// ── App chrome — header + body + bottom tab nav ───────────────────────────────
/// The bottom-nav destinations, in order (mirrors `NavRoute` in EP133App.kt).
enum EP133Tab: CaseIterable {
    case pads, kit, projects, device

    var label: String {
        switch self {
        case .pads: "PADS"
        case .kit: "SAMPLES"
        case .projects: "PROJ"
        case .device: "DEVICE"
        }
    }

    var testTag: String {
        switch self {
        case .pads: TestTags.NAV_PADS
        case .kit: TestTags.NAV_KIT
        case .projects: TestTags.NAV_PROJECTS
        case .device: TestTags.NAV_DEVICE
        }
    }
}

/// App shell chrome, mirroring `EP133App` in EP133App.kt: faceplate header (tappable SKU badge +
/// screen title + theme-mode toggle + connection dot) wrapping the screen `content`, with the mono
/// bottom tab nav. Theme/SKU state lives in the caller (AppShell) so the header controls can
/// re-theme the whole app.
struct EP133Scaffold<Content: View>: View {
    @Environment(\.ep133Tokens) private var t
    let title: String
    let connected: Bool
    let currentTab: EP133Tab
    let onTab: (EP133Tab) -> Void
    var sku: EP133Sku = .ep133
    var onSkuToggle: (() -> Void)? = nil
    var themeGlyph: String? = nil
    var onThemeToggle: (() -> Void)? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            // ── Faceplate header ──
            HStack(spacing: 0) {
                // Tap the badge to switch SKU theme (EP-133 orange ↔ EP-1320 rust).
                Button(action: { onSkuToggle?() }) {
                    Text(sku == .ep133 ? "EP·133" : "EP·1320")
                        .font(mono(11, .bold))
                        .foregroundStyle(t.onAccent)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .ep133Panel(fill: t.accent)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(TestTags.HEADER_SKU_BADGE)
                .accessibilityLabel(
                    sku == .ep133
                        ? "Switch to the EP-1320 color theme"
                        : "Switch to the EP-133 color theme"
                )
                // Announce the currently selected SKU (VoiceOver reads it as the control's value,
                // and it's the stable read the UI tests assert on since the Button collapses its
                // child Text out of the accessibility tree).
                .accessibilityValue(sku == .ep133 ? "EP·133" : "EP·1320")
                Text(title.uppercased())
                    .font(mono(11, .semibold))
                    .tracking(1.1)
                    .foregroundStyle(t.text2)
                    .padding(.leading, 9)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier(TestTags.HEADER_TITLE)
                // Tap to cycle theme: follow system → light → dark.
                if let themeGlyph {
                    Button(action: { onThemeToggle?() }) {
                        Text(themeGlyph)
                            .font(.system(size: 14))
                            .foregroundStyle(t.text2)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier(TestTags.HEADER_THEME_TOGGLE)
                    Spacer().frame(width: 8)
                }
                let dot = connected ? t.live : t.text3
                HStack(spacing: 7) {
                    EP133StatusDot(color: dot, size: 8)
                    Text(connected ? "CONNECTED" : "NO DEVICE")
                        .font(mono(10))
                        .tracking(0.7)
                        .foregroundStyle(dot)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(TestTags.HEADER_CONNECTION)
            }
            .padding(.horizontal, 15)
            .padding(.vertical, 13)
            .background(t.panel2)
            // ── Screen body ──
            content()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            // ── Faceplate bottom tab nav ──
            HStack(spacing: 0) {
                ForEach(EP133Tab.allCases, id: \.self) { tab in
                    let active = tab == currentTab
                    Text(tab.label)
                        .font(mono(8.5, active ? .bold : .medium))
                        .tracking(0.3)
                        .foregroundStyle(active ? t.accent : t.text3)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .contentShape(Rectangle())
                        .onTapGesture { onTab(tab) }
                        .accessibilityIdentifier(tab.testTag)
                        .accessibilityAddTraits(active ? [.isButton, .isSelected] : .isButton)
                }
            }
            // Nav background bleeds under the home indicator (Android's navigationBarsPadding
            // inside the panel2 background); the status-bar area above shows bg, matching
            // Android's statusBarsPadding on the bg column.
            .background(t.panel2.ignoresSafeArea(edges: .bottom))
        }
        .background(t.bg.ignoresSafeArea())
    }
}
