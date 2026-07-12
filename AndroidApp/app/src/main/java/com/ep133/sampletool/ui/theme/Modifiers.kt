package com.ep133.sampletool.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Shared decoration modifiers for the EP-133 faceplate UI.
 *
 * These were duplicated verbatim across several screens; they live here so a change to the
 * dashed drop-zone outline or the accent rail happens in one place.
 */

/** A dashed rounded outline (the design's drop-zone hint), matching [PanelRadius]'s corner. */
internal fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
    )
    val radius = 3.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = stroke,
    )
}

/** A 3dp colored left rail (the design's `border-left:3px solid`), drawn inside the rounded clip. */
internal fun Modifier.accentRail(color: Color): Modifier = this.drawBehind {
    drawRect(color = color, size = Size(3.dp.toPx(), size.height))
}
