package com.ep133.sampletool.ui.pads

import android.content.res.Configuration
import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.model.Pad
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.ui.theme.TEColors

class PadsViewModel(private val midi: MIDIRepository) : ViewModel() {

    private val _selectedChannel = MutableStateFlow(PadChannel.A)
    val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

    private val _pressedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val pressedIndices: StateFlow<Set<Int>> = _pressedIndices.asStateFlow()

    init {
        // Listen for incoming MIDI: auto-switch group + flash the matching pad
        viewModelScope.launch {
            midi.incomingMidi.collect { event ->
                if (event.status == 0x90 && event.velocity > 0) {
                    val resolved = EP133Pads.resolveIncoming(event.note, event.channel) ?: return@collect
                    val (group, index) = resolved

                    if (group != _selectedChannel.value) {
                        _selectedChannel.value = group
                        _pressedIndices.value = emptySet()
                    }

                    _pressedIndices.value = _pressedIndices.value + index
                    launch {
                        delay(120)
                        _pressedIndices.value = _pressedIndices.value - index
                    }
                }
            }
        }
    }

    fun selectChannel(channel: PadChannel) {
        _selectedChannel.value = channel
        _pressedIndices.value = emptySet()
    }

    fun padDown(index: Int) {
        val pad = EP133Pads.padsForChannel(_selectedChannel.value).getOrNull(index) ?: return
        _pressedIndices.value = _pressedIndices.value + index
        midi.noteOn(pad.note, 100, pad.midiChannel)
    }

    fun padUp(index: Int) {
        val pad = EP133Pads.padsForChannel(_selectedChannel.value).getOrNull(index) ?: return
        _pressedIndices.value = _pressedIndices.value - index
        midi.noteOff(pad.note, pad.midiChannel)
    }
}

@Composable
fun PadsScreen(viewModel: PadsViewModel) {
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val pressedIndices by viewModel.pressedIndices.collectAsState()
    val pads by remember(selectedChannel) {
        derivedStateOf { EP133Pads.padsForChannel(selectedChannel) }
    }
    val orientation = LocalConfiguration.current.orientation
    // 4 columns matches the physical EP-133 pad layout (4×3 grid)
    // 3 columns × 4 rows — matches physical EP-133 calculator-style pad layout
    val columns = 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        ChannelIndicator(selected = selectedChannel)

        Spacer(modifier = Modifier.height(8.dp))

        // Grid that fills ALL remaining space — no dead space
        val rows = pads.chunked(columns)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEachIndexed { rowIdx, rowPads ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPads.forEachIndexed { colIdx, pad ->
                        val index = rowIdx * columns + colIdx
                        PadCell(
                            pad = pad,
                            isPressed = index in pressedIndices,
                            onDown = { viewModel.padDown(index) },
                            onUp = { viewModel.padUp(index) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    // Fill remaining columns if row is short
                    repeat(columns - rowPads.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Display-only group indicator — auto-switches via incoming MIDI. */
@Composable
private fun ChannelIndicator(selected: PadChannel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PadChannel.entries.forEach { channel ->
            val isSelected = channel == selected
            FilterChip(
                selected = isSelected,
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    disabledSelectedContainerColor = TEColors.Orange,
                    disabledLabelColor = if (isSelected) Color.White else Color.Gray,
                ),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PadCell(
    pad: Pad,
    isPressed: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    val shadowElevation by animateDpAsState(
        targetValue = if (isPressed) 0.dp else 6.dp,
        animationSpec = tween(durationMillis = 60),
        label = "padShadow",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFF3A2018) else TEColors.PadBlack,
        animationSpec = tween(durationMillis = 40),
        label = "padBg",
    )

    // Rubber concavity gradient — top sheen
    val sheenBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.06f),
            Color.Transparent,
        ),
        startY = 0f,
        endY = 200f,
    )

    // Orange glow on press — radial from bottom center
    val glowAlpha by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 0.dp,
        animationSpec = tween(40),
        label = "glowAlpha",
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(8.dp),
            )
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .drawBehind {
                // Rubber sheen
                drawRect(brush = sheenBrush)
                // Orange glow on press
                if (glowAlpha.value > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                TEColors.Orange.copy(alpha = 0.5f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2, size.height * 0.8f),
                            radius = size.width * 0.7f,
                        ),
                    )
                }
            }
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDown()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onUp()
                        true
                    }
                    else -> false
                }
            }
            .padding(10.dp),
    ) {
        // Pad label — top right
        Text(
            text = pad.label,
            style = MaterialTheme.typography.labelLarge,
            color = TEColors.InkOnDarkSecondary,
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // Sound name — bottom left
        if (pad.defaultSound != null) {
            Text(
                text = pad.defaultSound,
                style = MaterialTheme.typography.titleSmall,
                color = TEColors.InkOnDark,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // Orange accent bar at bottom when pad has a sound
        if (pad.defaultSound != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(TEColors.Orange.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
            )
        }
    }
}
