package com.ep133.sampletool.ui.beats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SeqState
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.ui.theme.TEColors

class BeatsViewModel(
    private val sequencer: SequencerEngine,
    @Suppress("unused") private val midi: MIDIRepository,
) : ViewModel() {

    val state = sequencer.state

    fun play() = sequencer.play()
    fun pause() = sequencer.pause()
    fun stop() = sequencer.stop()
    fun toggleStep(track: Int, step: Int) = sequencer.toggleStep(track, step)
    fun adjustBpm(delta: Int) = sequencer.adjustBpm(delta)
    fun selectTrack(index: Int) = sequencer.selectTrack(index)
    fun clearTrack() = sequencer.clearTrack(state.value.selectedTrack)
}

@Composable
fun BeatsScreen(viewModel: BeatsViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        TransportBar(
            playing = state.playing,
            bpm = state.bpm,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onStop = viewModel::stop,
            onBpmAdjust = viewModel::adjustBpm,
            onClear = viewModel::clearTrack,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Sequencer grid fills all remaining space
        SequencerGrid(
            state = state,
            onToggleStep = viewModel::toggleStep,
            onSelectTrack = viewModel::selectTrack,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.height(8.dp))

        TrackInfoBar(state = state)
    }
}

@Composable
private fun TransportBar(
    playing: Boolean,
    bpm: Int,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBpmAdjust: (Int) -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Play/Pause — large touch target
            FilledIconButton(
                onClick = if (playing) onPause else onPlay,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = TEColors.Orange,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                )
            }

            // Stop
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // BPM control cluster
            OutlinedIconButton(
                onClick = { onBpmAdjust(-1) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease BPM")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$bpm",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.width(52.dp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedIconButton(
                onClick = { onBpmAdjust(1) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase BPM")
            }

            Spacer(modifier = Modifier.weight(1f))

            // Clear
            OutlinedIconButton(
                onClick = onClear,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear track")
            }
        }
    }
}

@Composable
private fun SequencerGrid(
    state: SeqState,
    onToggleStep: (track: Int, step: Int) -> Unit,
    onSelectTrack: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.tracks.forEachIndexed { trackIndex, track ->
            val isSelected = trackIndex == state.selectedTrack

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackLabel(
                    name = track.name,
                    isSelected = isSelected,
                    onClick = { onSelectTrack(trackIndex) },
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                ) {
                    repeat(16) { stepIndex ->
                        val isActive = track.steps[stepIndex] > 0
                        val isPlayhead = state.playing && stepIndex == state.currentStep
                        val isBeatBoundary = stepIndex % 4 == 0

                        StepCell(
                            isActive = isActive,
                            isPlayhead = isPlayhead,
                            isBeatBoundary = isBeatBoundary,
                            onClick = { onToggleStep(trackIndex, stepIndex) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackLabel(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val labelBg by animateColorAsState(
        targetValue = if (isSelected) TEColors.OrangeContainer else Color.Transparent,
        animationSpec = tween(150),
        label = "trackLabelBg",
    )

    Box(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(labelBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) TEColors.Orange else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepCell(
    isActive: Boolean,
    isPlayhead: Boolean,
    isBeatBoundary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val fillColor by animateColorAsState(
        targetValue = when {
            isActive && isPlayhead -> TEColors.Teal
            isActive -> TEColors.Orange
            isPlayhead -> TEColors.TealContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(60),
        label = "stepFill",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isPlayhead -> TEColors.Teal
            isBeatBoundary -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(60),
        label = "stepBorder",
    )

    val borderWidth = when {
        isPlayhead -> 2.dp
        isBeatBoundary -> 1.dp
        else -> 0.5.dp
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
            .background(fillColor)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    )
}

@Composable
private fun TrackInfoBar(state: SeqState) {
    val selectedTrack by remember(state.selectedTrack, state.tracks) {
        derivedStateOf { state.tracks.getOrNull(state.selectedTrack) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedTrack?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = TEColors.Orange,
            )

            Spacer(modifier = Modifier.weight(1f))

            if (selectedTrack != null) {
                Text(
                    text = "VEL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${selectedTrack!!.velocity}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
