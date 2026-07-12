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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.ui.TestTags

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
    subLabel: String? = null,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = modifier
            .clip(Radius)
            .background(if (selected) t.accent else t.inset, Radius)
            .border(1.dp, if (selected) t.accent else t.rule, Radius)
            .clickable { onClick() }
            .padding(vertical = if (subLabel != null) 6.dp else 9.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label,
            color = if (selected) t.onAccent else t.text2,
            fontFamily = Mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        if (subLabel != null) {
            Text(
                subLabel,
                color = if (selected) t.onAccent.copy(alpha = 0.75f) else t.text3,
                fontFamily = Mono,
                fontSize = 7.5.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Group + choke bar — the shared A/B/C/D picker + choke toggle ───────────────
/**
 * The A/B/C/D group picker plus the CHOKE GROUP toggle, as one block. Both the Loop Chopper and
 * the Kit Builder target a group and can choke it, so they share this control (identical placement
 * and styling) rather than each rolling its own. The choke toggle writes `sound.mutegroup` so pads
 * in the group cut each other off.
 */
@Composable
fun Ep133GroupChokeBar(
    group: PadChannel,
    onGroupChange: (PadChannel) -> Unit,
    chokeOn: Boolean,
    onChokeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tagFor: ((PadChannel) -> String?)? = null,
    testTagFor: ((PadChannel) -> String)? = null,
) {
    val t = LocalEP133Tokens.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // A | B | C | D — one chip per group, equal width, each tagged with its designation.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PadChannel.entries.forEach { ch ->
                Ep133GroupChip(
                    label = ch.name,
                    selected = group == ch,
                    modifier = Modifier.weight(1f).then(
                        testTagFor?.let { Modifier.testTag(it(ch)) } ?: Modifier,
                    ),
                    subLabel = tagFor?.invoke(ch),
                    onClick = { onGroupChange(ch) },
                )
            }
        }
        // Choke toggle — full-width row, tap anywhere to flip.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Radius)
                .background(t.inset, Radius)
                .border(1.dp, t.rule, Radius)
                .clickable { onChokeChange(!chokeOn) }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag(TestTags.GROUP_CHOKE_TOGGLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "CHOKE GROUP",
                    fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, color = t.text,
                )
                Text(
                    "pads in the group cut each other off",
                    fontFamily = Mono, fontSize = 8.5.sp, color = t.text3,
                )
            }
            Box(
                Modifier
                    .clip(Radius)
                    .background(if (chokeOn) t.accent else t.chrome, Radius)
                    .padding(horizontal = 13.dp, vertical = 5.dp),
            ) {
                Text(
                    if (chokeOn) "ON" else "OFF",
                    fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = if (chokeOn) t.onAccent else t.text2,
                )
            }
        }
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

// ── Confirm dialog — themed AlertDialog with an accent confirm + plain cancel ─
/**
 * A faceplate-themed confirmation dialog: bold [title], body [message], an accent [confirmLabel]
 * action, and a [dismissLabel] cancel. Used for destructive/irreversible actions (clear, restore).
 */
@Composable
fun Ep133ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
) {
    val t = LocalEP133Tokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = t.panel,
        titleContentColor = t.text,
        textContentColor = t.text2,
        shape = PanelRadius,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
            ) { Text(dismissLabel) }
        },
    )
}
