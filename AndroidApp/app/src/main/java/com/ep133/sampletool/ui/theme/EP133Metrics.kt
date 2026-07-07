package com.ep133.sampletool.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared layout/ink constants for the EP-133 faceplate UI.
 *
 * These were previously redeclared privately in every screen file (identical values), which meant a
 * change to the panel corner radius or the pad ink colors had to be made in five places. They live
 * here so there is a single source of truth; the color *tokens* (theme-dependent) stay in
 * [EP133Tokens], while these are theme-independent hardware constants.
 */

/** Corner radius for inset panels / cards across every screen. */
internal val PanelRadius = RoundedCornerShape(3.dp)

/** Ink drawn on a filled rubber pad (dark), and on an empty pad (light). Theme-independent. */
internal val PadFilledInk = Color(0xFF1A1206)
internal val PadEmptyInk = Color(0xFFE2E3E4)
