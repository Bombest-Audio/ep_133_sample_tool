package com.ep133.sampletool.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The EP-133 redesign component kit — the Compose mirror of the Claude Design system's component
 * sheet (pads, chips, group selector, status dots, buttons, section labels, app chrome). Every
 * redesigned screen composes from these so the look stays consistent. All colors come from
 * [LocalEP133Tokens]; the hardware aesthetic (flat faceplate, hard ~2dp corners, mono labels) is
 * intentionally NOT Material — Material 3 components are used only where they fit (forms, sliders).
 */

/** Small hard-corner radius used across the faceplate UI. */
private val Radius = RoundedCornerShape(3.dp)

/** Mono labels/codes (the design uses JetBrains Mono; Monospace is the safe on-device fallback). */
private val Mono = FontFamily.Monospace

// ── Section label — uppercase mono eyebrow over a block ───────────────────────
@Composable
fun Ep133SectionLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = t.text3,
        fontFamily = Mono,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
    )
}

// ── Status dot (teal=ok, accent=active, text3=idle) ───────────────────────────
@Composable
fun Ep133StatusDot(color: Color, size: Int = 8, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).clip(CircleShape).background(color))
}

// ── Pad cell ──────────────────────────────────────────────────────────────────
enum class PadState { Empty, Loaded, Pressed, InScale }

/**
 * A rubber pad cell: faceplate-black face, hard corners, mono id + name. [state] drives the id
 * color and the pressed glow / in-scale teal hairline, matching the design's pad variants.
 */
@Composable
fun Ep133Pad(
    id: String,
    name: String,
    state: PadState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalEP133Tokens.current
    val idColor = when (state) {
        PadState.Empty -> Color(0xFF5D5E5F)
        PadState.Loaded -> Color(0xFFC9CACB)
        PadState.Pressed -> t.accent
        PadState.InScale -> t.live
    }
    val border = when (state) {
        PadState.InScale -> t.live
        PadState.Pressed -> t.accent
        else -> t.padEdge
    }
    val face = if (state == PadState.Pressed) t.padTop else t.padFace
    Column(
        modifier = modifier
            .clip(Radius)
            .background(face, Radius)
            .border(1.dp, border, Radius)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(id, color = idColor, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
        Text(name.uppercase(), color = t.padName, fontFamily = Mono, fontSize = 8.sp, letterSpacing = 0.3.sp)
    }
}

// ── Group selector chip (A / B / C / D, etc.) ─────────────────────────────────
@Composable
fun Ep133GroupChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = modifier
            .clip(Radius)
            .background(if (selected) t.accent else t.inset, Radius)
            .border(1.dp, if (selected) t.accent else t.rule, Radius)
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) t.onAccent else t.text2,
            fontFamily = Mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Chips — filter (base) and action (solid) + live badge ─────────────────────
@Composable
fun Ep133Chip(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = modifier
            .clip(Radius)
            .background(if (selected) t.accent else Color.Transparent, Radius)
            .border(1.dp, if (selected) t.accent else t.rule, Radius)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp, horizontal = 10.dp),
    ) {
        Text(
            label.uppercase(),
            color = if (selected) t.onAccent else t.text2,
            fontFamily = Mono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
fun Ep133LiveBadge(label: String = "LIVE", modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = modifier
            .clip(Radius)
            .background(t.live.copy(alpha = 0.14f), Radius)
            .padding(vertical = 5.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Ep133StatusDot(t.live, size = 6)
        Text(label.uppercase(), color = t.liveInk, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

// ── Buttons — primary (solid accent) / ghost (outline) ────────────────────────
@Composable
fun Ep133PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = modifier
            .clip(Radius)
            .background(if (enabled) t.accent else t.rule, Radius)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = if (enabled) t.onAccent else t.text3,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

@Composable
fun Ep133GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = modifier
            .clip(Radius)
            .border(1.dp, t.rule, Radius)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = if (enabled) t.text else t.text3.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

// ── Mono stat readout — LABEL over a big mono VALUE, on an inset panel ─────────
@Composable
fun Ep133StatReadout(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = modifier
            .clip(Radius)
            .background(t.inset, Radius)
            .border(1.dp, t.rule, Radius)
            .padding(vertical = 9.dp, horizontal = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label.uppercase(), color = t.text3, fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 1.2.sp)
        Text(value, color = t.text, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// ── App chrome — header + body + bottom tab nav ───────────────────────────────
/** The six bottom-nav destinations, in order. */
enum class Ep133Tab(val label: String) {
    Pads("PADS"), Beats("BEATS"), Sounds("SOUNDS"), Chords("CHORDS"), Scale("SCALE"), Device("DEVICE")
}

/**
 * App shell: faceplate header (EP·133 badge + screen title + connection dot), the screen [content],
 * and the bottom tab nav. Mirrors the design's chrome. [title] is the current screen name.
 */
@Composable
fun Ep133Scaffold(
    title: String,
    connected: Boolean,
    currentTab: Ep133Tab,
    onTab: (Ep133Tab) -> Unit,
    modifier: Modifier = Modifier,
    onToggleConnected: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(modifier.fillMaxSize().background(t.bg)) {
        // header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.panel2)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EP·133",
                modifier = Modifier.clip(Radius).background(t.accent, Radius).padding(horizontal = 7.dp, vertical = 4.dp),
                color = t.onAccent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                title.uppercase(),
                modifier = Modifier.padding(start = 9.dp).weight(1f),
                color = t.text2, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp,
            )
            val dot = if (connected) t.live else t.text3
            Row(
                modifier = if (onToggleConnected != null) Modifier.clickable { onToggleConnected() } else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Ep133StatusDot(dot, size = 8)
                Text(
                    if (connected) "CONNECTED" else "NO DEVICE",
                    color = dot, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.7.sp,
                )
            }
        }
        // body
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        // bottom tab nav
        Row(
            modifier = Modifier.fillMaxWidth().background(t.panel2),
        ) {
            Ep133Tab.entries.forEach { tab ->
                val active = tab == currentTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTab(tab) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) t.accent else t.text3,
                        fontFamily = Mono,
                        fontSize = 9.5.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
        }
    }
}
