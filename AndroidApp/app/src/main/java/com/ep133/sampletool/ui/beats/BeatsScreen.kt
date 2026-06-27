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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.sequencer.BeatsMode
import com.ep133.sampletool.domain.sequencer.SeqState
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.ui.theme.Ep133Chip
import com.ep133.sampletool.ui.theme.Ep133LiveBadge
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
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

/** Hard 3dp faceplate corner radius, matching the EP-133 component kit. */
private val Radius = RoundedCornerShape(3.dp)

/** Mono labels/codes — JetBrains Mono in the design, Monospace on device. */
private val Mono = FontFamily.Monospace

@Composable
fun BeatsScreen(viewModel: BeatsViewModel) {
    val t = LocalEP133Tokens.current
    val state by viewModel.state.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(t.bg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TransportBar(
                playing = state.playing,
                bpm = state.bpm,
                mode = state.mode,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
                onBpmAdjust = viewModel::adjustBpm,
                onModeChange = viewModel::setMode,
            )

            when (state.mode) {
                BeatsMode.EDIT -> SequencerGrid(
                    state = state,
                    onToggleStep = viewModel::toggleStep,
                    onSelectTrack = viewModel::selectTrack,
                    onClearTrack = viewModel::clearTrack,
                    modifier = Modifier.weight(1f),
                )
                BeatsMode.LIVE -> LiveSequencerGrid(
                    state = state,
                    modifier = Modifier.weight(1f),
                )
            }

            StatusChips(state = state)

            if (state.mode == BeatsMode.LIVE) {
                CaptureBanner()
            }
        }

        // Disconnected overlay — does not navigate away (D-18)
        if (!deviceState.connected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(t.bg.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Ep133StatusDot(t.text3, size = 12)
                    Text(
                        text = "CONNECT EP-133 TO USE BEATS",
                        color = t.text2,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ── Mode toggle + BPM + transport in one faceplate row ────────────────────────
@Composable
private fun TransportBar(
    playing: Boolean,
    bpm: Int,
    mode: BeatsMode,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBpmAdjust: (Int) -> Unit,
    onModeChange: (BeatsMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // EDIT / LIVE segmented toggle
        ModeTab(label = "EDIT", selected = mode == BeatsMode.EDIT) { onModeChange(BeatsMode.EDIT) }
        ModeTab(label = "LIVE", selected = mode == BeatsMode.LIVE) { onModeChange(BeatsMode.LIVE) }

        Spacer(modifier = Modifier.weight(1f))

        // BPM readout with steppers (mono)
        StepButton(glyph = "–", onClick = { onBpmAdjust(-1) })
        Text(
            text = "$bpm",
            modifier = Modifier.width(38.dp),
            color = LocalEP133Tokens.current.text,
            fontFamily = Mono,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        StepButton(glyph = "+", onClick = { onBpmAdjust(1) })

        // Stop + play/pause transport
        StepButton(glyph = "■", onClick = onStop)
        PlayButton(playing = playing, onClick = if (playing) onPause else onPlay)
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    val bg by animateColorAsState(
        targetValue = if (selected) t.accent else t.panel,
        animationSpec = tween(150),
        label = "modeTabBg",
    )
    Box(
        modifier = Modifier
            .clip(Radius)
            .background(bg, Radius)
            .border(1.dp, t.rule, Radius)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) t.onAccent else t.text2,
            fontFamily = Mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(Radius)
            .background(t.panel, Radius)
            .border(1.dp, t.rule, Radius)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = t.text, fontFamily = Mono, fontSize = 13.sp)
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(Radius)
            .background(t.live, Radius)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (playing) "❚❚" else "▶",
            color = t.onAccent,
            fontFamily = Mono,
            fontSize = 11.sp,
        )
    }
}

// ── EDIT grid — track rows of 16 step cells, mono track label + clear ─────────
@Composable
private fun SequencerGrid(
    state: SeqState,
    onToggleStep: (track: Int, step: Int) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onClearTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.tracks.forEachIndexed { trackIndex, track ->
            val isSelected = trackIndex == state.selectedTrack

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
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

                // Per-row clear — wired to the selected-track clear handler.
                ClearButton(
                    enabled = isSelected,
                    onClick = onClearTrack,
                )
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
    val t = LocalEP133Tokens.current
    val liveNotes = remember(state.liveGrid) { state.liveGrid.keys.sorted() }

    if (liveNotes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Ep133LiveBadge(label = "LISTENING")
                Text(
                    text = "PLAY A PATTERN ON THE EP-133",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.6.sp,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        liveNotes.forEach { note ->
            val activeSteps = state.liveGrid[note] ?: emptySet()
            val padInfo = EP133Pads.resolveIncoming(note, 0)
            val label = padInfo?.let { (group, idx) ->
                EP133Pads.padsForChannel(group).getOrNull(idx)?.label
            } ?: "N$note"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
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
    val t = LocalEP133Tokens.current
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) t.accent else t.text2,
        animationSpec = tween(150),
        label = "trackLabelColor",
    )

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = name.uppercase(),
            color = labelColor,
            fontFamily = Mono,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One sequencer step: hard-cornered (3dp) box. Off = inset; on = accent. The current playhead
 * gets a brighter accent fill plus a live ring; beat boundaries (every 4th) carry a firmer rule.
 */
@Composable
private fun StepCell(
    isActive: Boolean,
    isPlayhead: Boolean,
    isBeatBoundary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    val haptic = LocalHapticFeedback.current

    val fillColor by animateColorAsState(
        targetValue = when {
            isActive && isPlayhead -> t.live
            isActive -> t.accent
            isPlayhead -> t.live.copy(alpha = 0.22f)
            else -> t.inset
        },
        animationSpec = tween(60),
        label = "stepFill",
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isPlayhead -> t.live
            isBeatBoundary -> t.text3
            else -> t.rule
        },
        animationSpec = tween(60),
        label = "stepBorder",
    )

    val borderWidth = when {
        isPlayhead -> 2.dp
        else -> 1.dp
    }

    Box(
        modifier = modifier
            .clip(Radius)
            .background(fillColor, Radius)
            .border(borderWidth, borderColor, Radius)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    )
}

@Composable
private fun ClearButton(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⌫",
            color = if (enabled) t.text2 else t.text3,
            fontFamily = Mono,
            fontSize = 12.sp,
        )
    }
}

// ── Status chip row — page / steps / selected-track / mode badge ──────────────
@Composable
private fun StatusChips(state: SeqState) {
    val t = LocalEP133Tokens.current

    val selectedTrack by remember(state.selectedTrack, state.tracks) {
        derivedStateOf { state.tracks.getOrNull(state.selectedTrack) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Ep133Chip(label = "PAGE 1/1")
        Ep133Chip(label = "16 STEPS")

        if (state.mode == BeatsMode.EDIT) {
            selectedTrack?.let { track ->
                Ep133Chip(label = "${track.name} · ${track.velocity}", selected = true)
            }
        } else {
            Ep133Chip(label = "${state.liveGrid.size} NOTES", selected = true)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (state.mode == BeatsMode.LIVE) {
            Ep133LiveBadge(label = "LIVE")
        } else {
            Ep133SectionLabel(text = "EDIT")
        }
    }
}

// ── LIVE capture banner — pulsing dot + read-only note ────────────────────────
@Composable
private fun CaptureBanner() {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius)
            .background(t.inset, Radius)
            .border(1.dp, t.rule, Radius)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Ep133StatusDot(t.accent, size = 9)
        Text(
            text = "CAPTURE GRID — READ ONLY. play pads to record live; steps fill as they land.",
            color = t.text2,
            fontFamily = Mono,
            fontSize = 10.sp,
            letterSpacing = 0.3.sp,
        )
    }
}
