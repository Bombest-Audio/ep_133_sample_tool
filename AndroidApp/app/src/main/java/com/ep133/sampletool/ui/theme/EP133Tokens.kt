package com.ep133.sampletool.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The EP-133 / EP-1320 hardware palette as semantic design tokens — the Compose mirror of the
 * Claude Design system's CSS custom properties (`--bg`, `--panel`, `--accent`, …).
 *
 * Material 3's [androidx.compose.material3.ColorScheme] stays for stock M3 components, but the
 * faceplate grays, rubber-pad blacks and hardware accents are not Material colors, so screens read
 * them from here via [LocalEP133Tokens]. Resolve the right set with [ep133Tokens]; the theme
 * provides it based on light/dark + the connected SKU.
 */
@Immutable
data class EP133Tokens(
    // Surfaces — faceplate chrome
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val inset: Color,
    val chrome: Color,
    // Text / ink
    val text: Color,
    val text2: Color,
    val text3: Color,
    // Hairlines
    val rule: Color,
    val ruleSoft: Color,
    // Accents (SKU-specific)
    val accent: Color,
    val accentInk: Color,
    val live: Color,
    val liveInk: Color,
    val onAccent: Color,
    // Status
    val error: Color,      // destructive / failure state (reject, upload error)
    // Misc
    val dot: Color,        // PCB/graph-paper background dot
    // Rubber pads
    val padFace: Color,
    val padEdge: Color,
    val padTop: Color,
    val padGlow: Color,
    val padName: Color,
)

/** Which hardware the app is themed for. */
enum class Ep133Sku { EP133, EP1320 }

/**
 * Resolve the token set for a [dark] theme and a [sku]. Surfaces/ink come from the theme; the SKU
 * swaps the accent family (EP-1320 trades orange/teal for rust/tan), matching the design's
 * `data-theme` + `data-sku` layering.
 */
fun ep133Tokens(dark: Boolean, sku: Ep133Sku): EP133Tokens {
    val base = if (dark) Ep133Dark else Ep133Light
    return when (sku) {
        Ep133Sku.EP133 -> base
        // EP-1320: rust/tan accents (the design also forces white on-accent for this SKU).
        Ep133Sku.EP1320 -> base.copy(
            accent = Color(0xFFB22E20),
            accentInk = Color(0xFF8E2117),
            live = Color(0xFF999269),
            liveInk = Color(0xFF7C7654),
            onAccent = Color.White,
            padGlow = Color(0x8CB22E20),
        )
    }
}

private val Ep133Light = EP133Tokens(
    bg = Color(0xFFD1D2D4),
    panel = Color(0xFFE2E3E5),
    panel2 = Color(0xFFC6C7C9),
    inset = Color(0xFFECEDEE),
    chrome = Color(0xFFCACBCD),
    text = Color(0xFF191A1B),
    text2 = Color(0xFF555657),
    text3 = Color(0xFF8A8B8D),
    rule = Color(0xFFB0B1B3),
    ruleSoft = Color(0xFFBFC0C2),
    accent = Color(0xFFEF4E27),
    accentInk = Color(0xFFC53A18),
    live = Color(0xFF00A69C),
    liveInk = Color(0xFF00837B),
    onAccent = Color.White,
    error = Color(0xFFD0021B),
    dot = Color(0x1A191A1B),
    padFace = Color(0xFF222323),
    padEdge = Color(0xFF0E0F0F),
    padTop = Color(0xFF2D2E2E),
    padGlow = Color(0x8CEF4E27),
    padName = Color(0xFF76777A),
)

private val Ep133Dark = EP133Tokens(
    bg = Color(0xFF121314),
    panel = Color(0xFF1E1F20),
    panel2 = Color(0xFF2A2B2C),
    inset = Color(0xFF171818),
    chrome = Color(0xFF242526),
    text = Color(0xFFE2E3E4),
    text2 = Color(0xFFA6A7A9),
    text3 = Color(0xFF76777A),
    rule = Color(0xFF34353A),
    ruleSoft = Color(0xFF2A2B2E),
    accent = Color(0xFFEF4E27),
    accentInk = Color(0xFFC53A18),
    live = Color(0xFF00A69C),
    liveInk = Color(0xFF00837B),
    onAccent = Color(0xFF161617),
    error = Color(0xFFD0021B),
    dot = Color(0x14E2E3E4),
    padFace = Color(0xFF1A1B1B),
    padEdge = Color(0xFF050606),
    padTop = Color(0xFF2D2E2E),
    padGlow = Color(0x8CEF4E27),
    padName = Color(0xFF76777A),
)

/** Token set for the current theme. Provided by [EP133Theme]; defaults to EP-133 light. */
val LocalEP133Tokens = staticCompositionLocalOf { Ep133Light }
