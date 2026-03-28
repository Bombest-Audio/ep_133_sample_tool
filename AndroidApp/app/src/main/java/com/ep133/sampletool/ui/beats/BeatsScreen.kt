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
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.lifecycle.viewModelScope

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.sequencer.BeatsMode
import com.ep133.sampletool.domain.sequencer.SeqState
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.ui.theme.TEColors
import kotlinx.coroutines.launch

class BeatsViewModel(
    private val sequencer: SequencerEngine,
    private val midi: MIDIRepository,
) : ViewModel() {

    val state = sequencer.state
    val deviceState = midi.deviceState

    init {
        // Route incoming MIDI notes to live capture
        viewModelScope.launch {
            midi.incomingMidi.collect { event ->
                if (event.status == 0x90 && event.velocity > 0) {
                    sequencer.recordIncomingNote(event.note)
                }
            }
        }
    }

    fun play() = sequencer.play()
    fun pause() = sequencer.pause()
    fun stop() = sequencer.stop()
    fun toggleStep(track: Int, step: Int) = sequencer.toggleStep(track, step)
    fun adjustBpm(delta: Int) = sequencer.adjustBpm(delta)
    fun selectTrack(index: Int) = sequencer.selectTrack(index)
    fun clearTrack() = sequencer.clearTrack(state.value.selectedTrack)

    fun setMode(mode: BeatsMode) {
        sequencer.setMode(mode)
        if (mode == BeatsMode.LIVE) sequencer.startLiveCapture()
        else sequencer.stopLiveCapture()
    }

    fun clearLiveGrid() = sequencer.clearLiveGrid()
}

@Composable
fun BeatsScreen(viewModel: BeatsViewModel) {
    val state by viewModel.state.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            TransportBar(
                playing = state.playing,
                bpm = state.bpm,
                mode = state.mode,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
                onBpmAdjust = viewModel::adjustBpm,
                onClear = if (state.mode == BeatsMode.EDIT) viewModel::clearTrack
                          else viewModel::clearLiveGrid,
                onModeChange = viewModel::setMode,
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (state.mode) {
                BeatsMode.EDIT -> SequencerGrid(
                    state = state,
                    onToggleStep = viewModel::toggleStep,
                    onSelectTrack = viewModel::selectTrack,
                    modifier = Modifier.weight(1f),
                )
                BeatsMode.LIVE -> LiveSequencerGrid(
                    state = state,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TrackInfoBar(state = state)
        }

        // Disconnected overlay — does not navigate away (D-18)
        if (!deviceState.connected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Connect EP-133 to use BEATS",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportBar(
    playing: Boolean,
    bpm: Int,
    mode: BeatsMode,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBpmAdjust: (Int) -> Unit,
    onClear: () -> Unit,
    onModeChange: (BeatsMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Mode toggle
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BeatsMode.entries.forEach { m ->
                    FilterChip(
                        selected = m == mode,
                        onClick = { onModeChange(m) },
                        label = { Text(m.name, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TEColors.Orange,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Transport controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

                OutlinedIconButton(onClick = { onBpmAdjust(-1) }, modifier = Modifier.size(40.dp)) {
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

                OutlinedIconButton(onClick = { onBpmAdjust(1) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase BPM")
                }

                Spacer(modifier = Modifier.weight(1f))

                OutlinedIconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                }
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
                        StepCell(
                            isActive = track.steps[stepIndex] > 0,
                            isPlayhead = state.playing && stepIndex == state.currentStep,
                            isBeatBoundary = stepIndex % 4 == 0,
                            onClick = { onToggleStep(trackIndex, stepIndex) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** LIVE mode grid — shows incoming MIDI notes captured from EP-133 playback. */
@Composable
private fun LiveSequencerGrid(
    state: SeqState,
    modifier: Modifier = Modifier,
) {
    val liveNotes = remember(state.liveGrid) { state.liveGrid.keys.sorted() }

    if (liveNotes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LISTENING",
                    style = MaterialTheme.typography.titleMedium,
                    color = TEColors.Teal,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Play a pattern on the EP-133",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        liveNotes.forEach { note ->
            val activeSteps = state.liveGrid[note] ?: emptySet()
            val padInfo = EP133Pads.resolveIncoming(note, 0)
            val label = padInfo?.let { (group, idx) ->
                EP133Pads.padsForChannel(group).getOrNull(idx)?.label
            } ?: "N$note"

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackLabel(name = label, isSelected = false, onClick = {})

                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                ) {
                    repeat(16) { stepIndex ->
                        StepCell(
                            isActive = stepIndex in activeSteps,
                            isPlayhead = stepIndex == state.liveCurrentStep,
                            isBeatBoundary = stepIndex % 4 == 0,
                            onClick = {},
                            modifier = Modifier.weight(1f).fillMaxHeight(),
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
    if (state.mode == BeatsMode.LIVE) {
        val noteCount = state.liveGrid.size
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
                    text = "LIVE CAPTURE",
                    style = MaterialTheme.typography.titleMedium,
                    color = TEColors.Teal,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "$noteCount notes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

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
