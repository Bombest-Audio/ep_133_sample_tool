package com.ep133.sampletool.ui.chords

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.Vibe
import com.ep133.sampletool.domain.model.resolveChordName
import com.ep133.sampletool.ui.theme.TEColors

private val KEY_OPTIONS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Sound selector row
        SoundSelectorRow(
            sound = selectedSound,
            onClick = viewModel::openSoundPicker,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Offline notice — shown when no EP-133 connected
        if (!deviceState.connected) {
            OfflineNotice()
            Spacer(modifier = Modifier.height(4.dp))
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

        Text(
            text = "KEY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(KEY_OPTIONS) { key ->
                FilterChip(
                    selected = key == keyRoot,
                    onClick = { viewModel.setKey(key) },
                    label = {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TEColors.Orange,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "VIBES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(Vibe.entries.toList()) { vibe ->
                val selected = vibe in selectedVibes
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.toggleVibe(vibe) },
                    label = {
                        Text(
                            text = vibe.label,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TEColors.Orange,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                FilledTonalButton(
                    onClick = { viewModel.selectProgression(ChordProgression("custom", "My Progression", emptyList(), emptySet())) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "+ BUILD YOUR OWN",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressionCard(
    progression: ChordProgression,
    keyRoot: String,
    isThisPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelect: () -> Unit,
) {
    ElevatedCard(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progression.name,
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = progression.degrees.joinToString(" \u2192 ") { it.roman },
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = progression.degrees.joinToString(" \u2192 ") { resolveChordName(it, keyRoot) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    progression.vibes.forEach { vibe ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = vibe.label,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = TEColors.OrangeContainer,
                                labelColor = TEColors.Orange,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = if (isThisPlaying) onStop else onPlay,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isThisPlaying) TEColors.Teal else TEColors.Orange,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (isThisPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isThisPlaying) "Stop" else "Play",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
