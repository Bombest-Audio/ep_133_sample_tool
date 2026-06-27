package com.ep133.sampletool.ui.chords

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.Vibe
import com.ep133.sampletool.domain.model.resolveChordName
import com.ep133.sampletool.ui.theme.Ep133Chip
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens

private val KEY_OPTIONS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

/** Hard-corner radius mirroring the design's faceplate UI. */
private val Radius = RoundedCornerShape(3.dp)

/** Mono labels/codes (the design uses JetBrains Mono; Monospace is the on-device fallback). */
private val Mono = FontFamily.Monospace

@Composable
fun ChordsScreen(
    viewModel: ChordsViewModel,
    onSendToBeats: () -> Unit = {},
) {
    val selectedProgression by viewModel.selectedProgression.collectAsState()

    if (selectedProgression != null) {
        ChordBuilderScreen(
            viewModel = viewModel,
            onSendToBeats = onSendToBeats,
        )
        return
    }

    val progressions by viewModel.filteredProgressions.collectAsState()
    val selectedVibes by viewModel.selectedVibes.collectAsState()
    val keyRoot by viewModel.keyRoot.collectAsState()
    val playingId by viewModel.playingProgressionId.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val selectedSound by viewModel.selectedSound.collectAsState()
    val showSoundPicker by viewModel.showSoundPicker.collectAsState()

    val t = LocalEP133Tokens.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Sound selector row (shared composable — behavior preserved)
        SoundSelectorRow(
            sound = selectedSound,
            onClick = viewModel::openSoundPicker,
        )

        // Offline notice — shown when no EP-133 connected (shared composable)
        if (!deviceState.connected) {
            OfflineNotice()
        }

        // Key selector — horizontal chip row
        Ep133SectionLabel("KEY")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(KEY_OPTIONS) { key ->
                Ep133Chip(
                    label = key,
                    selected = key == keyRoot,
                    onClick = { viewModel.setKey(key) },
                )
            }
        }

        // Vibe filter chips — horizontal scroll
        Ep133SectionLabel("VIBES")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(Vibe.entries.toList()) { vibe ->
                Ep133Chip(
                    label = vibe.label,
                    selected = vibe in selectedVibes,
                    onClick = { viewModel.toggleVibe(vibe) },
                )
            }
        }

        // Progression cards
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(progressions, key = { it.id }) { progression ->
                ProgressionCard(
                    progression = progression,
                    keyRoot = keyRoot,
                    isThisPlaying = playingId == progression.id,
                    onPlay = { viewModel.playProgression(progression) },
                    onStop = { viewModel.stopPlayback() },
                    onSelect = { viewModel.selectProgression(progression) },
                )
            }

            item {
                BuildCustomButton(
                    onClick = {
                        viewModel.selectProgression(
                            ChordProgression("custom", "My Progression", emptyList(), emptySet()),
                        )
                    },
                )
            }
        }
    }

    // Bottom sheets
    if (showSoundPicker) {
        SoundPickerSheet(
            onSoundSelected = viewModel::selectSound,
            onDismiss = viewModel::dismissSoundPicker,
        )
    }
}

/**
 * One progression row: faceplate panel card with a play/stop circle, the progression name, a mono
 * roman-numeral → chord-name line, and a bar-count tag. Mirrors the design's progression cards.
 */
@Composable
private fun ProgressionCard(
    progression: ChordProgression,
    keyRoot: String,
    isThisPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelect: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(t.panel2, Radius)
            .border(1.dp, t.rule, Radius)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Play / stop circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isThisPlaying) t.live else t.accent)
                .clickable(onClick = if (isThisPlaying) onStop else onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isThisPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isThisPlaying) "Stop" else "Play",
                tint = if (isThisPlaying) t.liveInk else t.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }

        // Name + roman / chord-name line
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = progression.name,
                color = t.text,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = (-0.1).sp,
            )
            val line = if (progression.degrees.isEmpty()) {
                "EMPTY"
            } else {
                progression.degrees.joinToString("  →  ") { resolveChordName(it, keyRoot) }
            }
            Text(
                text = line,
                color = t.text2,
                fontFamily = Mono,
                fontSize = 9.5.sp,
                letterSpacing = 0.2.sp,
            )
        }

        // Bar-count tag
        Text(
            text = "${progression.degrees.size} BAR",
            color = t.text3,
            fontFamily = Mono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Dashed "build custom" entry that opens the chord builder. Mirrors the design's dashed CTA. */
@Composable
private fun BuildCustomButton(onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(Radius)
            .background(t.inset, Radius)
            .border(
                width = 1.dp,
                color = t.rule,
                shape = Radius,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "build custom",
            color = t.text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CHORD BUILDER",
                color = t.accent,
                fontFamily = Mono,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "→",
                color = t.accent,
                fontFamily = Mono,
                fontSize = 10.sp,
            )
        }
    }
}
