import SwiftUI

/// SwiftUI port of `AndroidApp/.../ui/theme/Type.kt` (EP133Typography).
///
/// EP-133 × Google typography.
///
/// Monospace variants for hardware-display feel (pad labels, BPM, levels).
/// System font for body and navigation — clean readability.
///
/// Compose's `Typography` slots become named `EP133TextStyle`s; sp → points 1:1. Android's
/// `FontFamily.Monospace` maps to the system monospaced design (SF Mono on iOS). Apply a style
/// with `.ep133TextStyle(_:)` so both the font and the letter spacing land.
struct EP133TextStyle {
    let size: CGFloat
    let weight: Font.Weight
    let tracking: CGFloat
    let mono: Bool

    var font: Font {
        Font.system(size: size, weight: weight, design: mono ? .monospaced : .default)
    }
}

enum EP133Typography {
    // Large display — BPM counter, device model name
    static let displayLarge = EP133TextStyle(size: 32, weight: .bold, tracking: 3, mono: true)
    static let displayMedium = EP133TextStyle(size: 24, weight: .bold, tracking: 2, mono: true)
    static let displaySmall = EP133TextStyle(size: 18, weight: .bold, tracking: 1.5, mono: true)
    // Headlines — section headers
    static let headlineLarge = EP133TextStyle(size: 16, weight: .bold, tracking: 2, mono: false)
    static let headlineMedium = EP133TextStyle(size: 13, weight: .bold, tracking: 2.5, mono: false)
    static let headlineSmall = EP133TextStyle(size: 11, weight: .bold, tracking: 3, mono: false)
    // Titles — card titles, nav labels
    static let titleLarge = EP133TextStyle(size: 14, weight: .bold, tracking: 1, mono: false)
    static let titleMedium = EP133TextStyle(size: 12, weight: .bold, tracking: 1.5, mono: false)
    static let titleSmall = EP133TextStyle(size: 10, weight: .bold, tracking: 2, mono: true)
    // Body
    static let bodyLarge = EP133TextStyle(size: 14, weight: .regular, tracking: 0.5, mono: false)
    static let bodyMedium = EP133TextStyle(size: 12, weight: .regular, tracking: 0.5, mono: false)
    static let bodySmall = EP133TextStyle(size: 10, weight: .regular, tracking: 1, mono: false)
    // Labels — pad IDs, step numbers, tiny metadata
    static let labelLarge = EP133TextStyle(size: 11, weight: .bold, tracking: 1.5, mono: true)
    static let labelMedium = EP133TextStyle(size: 9, weight: .bold, tracking: 2, mono: true)
    static let labelSmall = EP133TextStyle(size: 7, weight: .regular, tracking: 1, mono: true)
}

extension View {
    func ep133TextStyle(_ style: EP133TextStyle) -> some View {
        font(style.font).tracking(style.tracking)
    }
}

/// Ad-hoc monospaced system font at an explicit size/weight — the hardware-display face the
/// screens use for one-off labels that don't map to a named `EP133TextStyle`.
func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    .system(size: size, weight: weight, design: .monospaced)
}
