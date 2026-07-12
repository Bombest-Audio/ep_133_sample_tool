import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/theme/Color.kt` (TEColors).
///
/// Teenage Engineering × Google color palette.
///
/// Hardware-derived: warm faceplate grays, rubber pad black,
/// signature orange accent, teal wave indicator.
///
/// Every value is the exact ARGB literal from the Kotlin source.
enum TEColors {
    // ── Primary accent — EP-133 signature orange ──
    static let Orange = Color(argb: 0xFFEF4E27)
    static let OrangeLight = Color(argb: 0xFFFF7E55)
    static let OrangeDark = Color(argb: 0xFFC03C18)
    static let OrangeContainer = Color(argb: 0x22EF4E27)

    // ── Secondary — EP-133 teal (wave/online indicator) ──
    static let Teal = Color(argb: 0xFF00A69C)
    static let TealLight = Color(argb: 0xFF33C4BB)
    static let TealDark = Color(argb: 0xFF007A72)
    static let TealContainer = Color(argb: 0x1F00A69C)

    // ── Faceplate — warm mid-grays from the hardware ──
    static let Faceplate = Color(argb: 0xFFD1D2D4)
    static let FaceplateLighter = Color(argb: 0xFFE2E3E5)
    static let FaceplateDarker = Color(argb: 0xFFC6C7C9)
    static let FaceplateButton = Color(argb: 0xFFD8D9DB)

    // ── Rubber pad surface ──
    static let PadBlack = Color(argb: 0xFF222323)
    static let PadHighlight = Color(argb: 0xFF2D2E2E)

    // ── Text / ink ──
    static let Ink = Color(argb: 0xFF191A1B)
    static let InkSecondary = Color(argb: 0xFF555657)
    static let InkTertiary = Color(argb: 0xFF8A8B8D)
    static let InkOnDark = Color(argb: 0xFFE2E3E4)
    static let InkOnDarkSecondary = Color(argb: 0x66E2E3E4)

    // ── Borders ──
    static let Border = Color(argb: 0xFFB0B1B3)
    static let BorderHighlight = Color(argb: 0xA6FFFFFF)
}

extension Color {
    /// ARGB literal init matching Compose's `Color(0xAARRGGBB)`, so hex values
    /// copy over from the Kotlin palette unchanged.
    init(argb: UInt32) {
        let a = Double((argb >> 24) & 0xFF) / 255.0
        let r = Double((argb >> 16) & 0xFF) / 255.0
        let g = Double((argb >> 8) & 0xFF) / 255.0
        let b = Double(argb & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
