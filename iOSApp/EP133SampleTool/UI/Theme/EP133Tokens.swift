import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/theme/EP133Tokens.kt` (+ the token-provider half of
/// `Theme.kt`).
///
/// The EP-133 / EP-1320 hardware palette as semantic design tokens — the SwiftUI mirror of the
/// Claude Design system's CSS custom properties (`--bg`, `--panel`, `--accent`, …).
///
/// The faceplate grays, rubber-pad blacks and hardware accents are not system colors, so views
/// read them from here via the `\.ep133Tokens` environment value. Resolve the right set with
/// `ep133Tokens(dark:sku:)`; `.ep133Theme(sku:)` provides it based on light/dark + the connected
/// SKU. (Android's Material 3 ColorScheme half of `Theme.kt` has no SwiftUI counterpart — the
/// tokens carry the whole palette here.)
struct EP133Tokens: Equatable {
    // Surfaces — faceplate chrome
    let bg: Color
    let panel: Color
    let panel2: Color
    let inset: Color
    let chrome: Color
    // Text / ink
    let text: Color
    let text2: Color
    let text3: Color
    // Hairlines
    let rule: Color
    let ruleSoft: Color
    // Accents (SKU-specific)
    let accent: Color
    let accentInk: Color
    let live: Color
    let liveInk: Color
    let onAccent: Color
    // Misc
    let dot: Color        // PCB/graph-paper background dot
    // Rubber pads
    let padFace: Color
    let padEdge: Color
    let padTop: Color
    let padGlow: Color
    let padName: Color
}

/// Which hardware the app is themed for.
enum EP133Sku {
    case ep133, ep1320
}

/// Resolve the token set for a `dark` theme and a `sku`. Surfaces/ink come from the theme; the SKU
/// swaps the accent family (EP-1320 trades orange/teal for rust/tan), matching the design's
/// `data-theme` + `data-sku` layering.
func ep133Tokens(dark: Bool, sku: EP133Sku) -> EP133Tokens {
    let base = dark ? EP133Tokens.dark : EP133Tokens.light
    switch sku {
    case .ep133:
        return base
    // EP-1320: rust/tan accents (the design also forces white on-accent for this SKU).
    case .ep1320:
        return base.copying(
            accent: Color(argb: 0xFFB22E20),
            accentInk: Color(argb: 0xFF8E2117),
            live: Color(argb: 0xFF999269),
            liveInk: Color(argb: 0xFF7C7654),
            onAccent: .white,
            padGlow: Color(argb: 0x8CB22E20)
        )
    }
}

extension EP133Tokens {
    static let light = EP133Tokens(
        bg: Color(argb: 0xFFD1D2D4),
        panel: Color(argb: 0xFFE2E3E5),
        panel2: Color(argb: 0xFFC6C7C9),
        inset: Color(argb: 0xFFECEDEE),
        chrome: Color(argb: 0xFFCACBCD),
        text: Color(argb: 0xFF191A1B),
        text2: Color(argb: 0xFF555657),
        text3: Color(argb: 0xFF8A8B8D),
        rule: Color(argb: 0xFFB0B1B3),
        ruleSoft: Color(argb: 0xFFBFC0C2),
        accent: Color(argb: 0xFFEF4E27),
        accentInk: Color(argb: 0xFFC53A18),
        live: Color(argb: 0xFF00A69C),
        liveInk: Color(argb: 0xFF00837B),
        onAccent: .white,
        dot: Color(argb: 0x1A191A1B),
        padFace: Color(argb: 0xFF222323),
        padEdge: Color(argb: 0xFF0E0F0F),
        padTop: Color(argb: 0xFF2D2E2E),
        padGlow: Color(argb: 0x8CEF4E27),
        padName: Color(argb: 0xFF76777A)
    )

    static let dark = EP133Tokens(
        bg: Color(argb: 0xFF121314),
        panel: Color(argb: 0xFF1E1F20),
        panel2: Color(argb: 0xFF2A2B2C),
        inset: Color(argb: 0xFF171818),
        chrome: Color(argb: 0xFF242526),
        text: Color(argb: 0xFFE2E3E4),
        text2: Color(argb: 0xFFA6A7A9),
        text3: Color(argb: 0xFF76777A),
        rule: Color(argb: 0xFF34353A),
        ruleSoft: Color(argb: 0xFF2A2B2E),
        accent: Color(argb: 0xFFEF4E27),
        accentInk: Color(argb: 0xFFC53A18),
        live: Color(argb: 0xFF00A69C),
        liveInk: Color(argb: 0xFF00837B),
        onAccent: Color(argb: 0xFF161617),
        dot: Color(argb: 0x14E2E3E4),
        padFace: Color(argb: 0xFF1A1B1B),
        padEdge: Color(argb: 0xFF050606),
        padTop: Color(argb: 0xFF2D2E2E),
        padGlow: Color(argb: 0x8CEF4E27),
        padName: Color(argb: 0xFF76777A)
    )

    /// Swift stand-in for Kotlin's `data class copy()` — only the SKU-swapped fields.
    func copying(
        accent: Color,
        accentInk: Color,
        live: Color,
        liveInk: Color,
        onAccent: Color,
        padGlow: Color
    ) -> EP133Tokens {
        EP133Tokens(
            bg: bg, panel: panel, panel2: panel2, inset: inset, chrome: chrome,
            text: text, text2: text2, text3: text3,
            rule: rule, ruleSoft: ruleSoft,
            accent: accent, accentInk: accentInk, live: live, liveInk: liveInk,
            onAccent: onAccent,
            dot: dot,
            padFace: padFace, padEdge: padEdge, padTop: padTop, padGlow: padGlow,
            padName: padName
        )
    }
}

/// Token set for the current theme (Compose's `LocalEP133Tokens`). Provided by `.ep133Theme`;
/// defaults to EP-133 light.
private struct EP133TokensKey: EnvironmentKey {
    static let defaultValue = EP133Tokens.light
}

extension EnvironmentValues {
    var ep133Tokens: EP133Tokens {
        get { self[EP133TokensKey.self] }
        set { self[EP133TokensKey.self] = newValue }
    }
}

/// SwiftUI mirror of Compose's `EP133Theme` composable: resolves the token set from the system
/// color scheme + SKU and provides it to the subtree via `\.ep133Tokens`.
private struct EP133ThemeModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let sku: EP133Sku

    func body(content: Content) -> some View {
        content.environment(\.ep133Tokens, ep133Tokens(dark: colorScheme == .dark, sku: sku))
    }
}

extension View {
    func ep133Theme(sku: EP133Sku = .ep133) -> some View {
        modifier(EP133ThemeModifier(sku: sku))
    }
}
