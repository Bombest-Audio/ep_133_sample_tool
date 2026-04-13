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
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.ui.theme.TEColors
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged

class PadsViewModel(private val midi: MIDIRepository) : ViewModel() {

    private val _selectedChannel = MutableStateFlow(PadChannel.A)
    val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

    private val _pressedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val pressedIndices: StateFlow<Set<Int>> = _pressedIndices.asStateFlow()

    init {
        // Listen for incoming MIDI: auto-switch group + flash the matching pad
        viewModelScope.launch {
            midi.incomingMidi.collect { event ->
                when {
                    event.status == 0x90 && event.velocity > 0 -> {
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
                    event.status == 0xC0 -> {
                        // KO-II group button: Program Change 0=A, 1=B, 2=C, 3=D
                        val group = PadChannel.entries.getOrNull(event.note) ?: return@collect
                        if (group != _selectedChannel.value) {
                            _selectedChannel.value = group
                            _pressedIndices.value = emptySet()
                        }
                    }
                }
            }
        }
    }

    fun selectChannel(channel: PadChannel) {
        _selectedChannel.value = channel
        _pressedIndices.value = emptySet()
    }

    // D-17: scale state delegated from MIDIRepository (single source of truth)
    val selectedScale: StateFlow<Scale?> = midi.selectedScale
    val selectedRootNote: StateFlow<String> = midi.selectedRootNote

    /** Send noteOn with velocity (D-19). Default velocity is 100 for backward compatibility. */
    fun padDown(index: Int, velocity: Int = 100) {
        val pad = EP133Pads.padsForChannel(_selectedChannel.value).getOrNull(index) ?: return
        _pressedIndices.value = _pressedIndices.value + index
        midi.noteOn(pad.note, velocity.coerceIn(1, 127), pad.midiChannel)
    }

    fun padUp(index: Int) {
        val pad = EP133Pads.padsForChannel(_selectedChannel.value).getOrNull(index) ?: return
        _pressedIndices.value = _pressedIndices.value - index
        midi.noteOff(pad.note, pad.midiChannel)
    }
}

/**
 * Compute the set of pitch classes (0-11) in the given scale starting at [rootNoteName].
 * Returns empty set if root note is not recognized.
 */
fun computeInScaleSet(scale: Scale, rootNoteName: String): Set<Int> {
    val rootIndex = com.ep133.sampletool.domain.model.EP133Scales.ROOT_NOTES.indexOf(rootNoteName)
    if (rootIndex < 0) return emptySet()
    return scale.intervals.map { (rootIndex + it) % 12 }.toSet()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PadsScreen(viewModel: PadsViewModel) {
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val pressedIndices by viewModel.pressedIndices.collectAsState()
    val selectedScale by viewModel.selectedScale.collectAsState()
    val selectedRootNote by viewModel.selectedRootNote.collectAsState()
    val pads by remember(selectedChannel) {
        derivedStateOf { EP133Pads.padsForChannel(selectedChannel) }
    }
    // 3 columns × 4 rows — matches physical EP-133 calculator-style pad layout
    val columns = 3

    // Scale lock: compute set of in-scale pitch classes
    val inScaleSet by remember(selectedScale, selectedRootNote) {
        derivedStateOf {
            val scale = selectedScale
            if (scale == null) emptySet() else computeInScaleSet(scale, selectedRootNote)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        ChannelIndicator(selected = selectedChannel, onSelect = viewModel::selectChannel)

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-touch grid using grid-level pointerInteropFilter (D-18, D-20, RESEARCH.md Pattern 5)
        val rows = pads.chunked(columns)
        val rowCount = rows.size
        var gridWidthPx by remember { mutableStateOf(0f) }
        var gridHeightPx by remember { mutableStateOf(0f) }
        val pointerToPad = remember { mutableMapOf<Int, Int>() }

        Column(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { size ->
                    gridWidthPx = size.width.toFloat()
                    gridHeightPx = size.height.toFloat()
                }
                .pointerInteropFilter { event ->
                    fun coordToIndex(x: Float, y: Float): Int? {
                        if (gridWidthPx <= 0f || gridHeightPx <= 0f) return null
                        val col = (x / (gridWidthPx / columns)).toInt().coerceIn(0, columns - 1)
                        val row = (y / (gridHeightPx / rowCount)).toInt().coerceIn(0, rowCount - 1)
                        val idx = row * columns + col
                        return idx.takeIf { it < pads.size }
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val idx = coordToIndex(event.x, event.y)
                                ?: return@pointerInteropFilter false
                            val vel = (event.pressure.coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)
                            pointerToPad[event.getPointerId(0)] = idx
                            viewModel.padDown(idx, vel)
                            true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            val ptrIdx = event.actionIndex
                            val idx = coordToIndex(event.getX(ptrIdx), event.getY(ptrIdx))
                                ?: return@pointerInteropFilter false
                            val vel = (event.getPressure(ptrIdx).coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)
                            pointerToPad[event.getPointerId(ptrIdx)] = idx
                            viewModel.padDown(idx, vel)
                            true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val ptrIdx = event.actionIndex
                            val padIdx = pointerToPad.remove(event.getPointerId(ptrIdx))
                                ?: return@pointerInteropFilter false
                            viewModel.padUp(padIdx)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pointerToPad.forEach { (_, padIdx) -> viewModel.padUp(padIdx) }
                            pointerToPad.clear()
                            true
                        }
                        else -> false
                    }
                },
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
                        val isInScale = inScaleSet.isEmpty() || (pad.note % 12) in inScaleSet
                        PadCell(
                            pad = pad,
                            isPressed = index in pressedIndices,
                            isInScale = isInScale,
                            scaleLockActive = inScaleSet.isNotEmpty(),
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

/** Tappable group selector — also auto-switches via incoming MIDI. */
@Composable
private fun ChannelIndicator(selected: PadChannel, onSelect: (PadChannel) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PadChannel.entries.forEach { channel ->
            val isSelected = channel == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(channel) },
                label = {
                    Text(
                        text = channel.name,
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
}

@Composable
private fun PadCell(
    pad: Pad,
    isPressed: Boolean,
    isInScale: Boolean = true,
    scaleLockActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
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

    // Scale lock border: in-scale pads get a teal border when a scale is active
    val scaleBorderModifier = if (scaleLockActive && isInScale) {
        Modifier.border(1.5.dp, TEColors.Teal, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(8.dp),
            )
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .then(scaleBorderModifier)
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
