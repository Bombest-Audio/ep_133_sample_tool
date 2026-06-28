package com.ep133.sampletool.ui.pads

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.ui.theme.Ep133GroupChip
import com.ep133.sampletool.ui.theme.Ep133Pad
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.PadState
import androidx.compose.material3.Text as M3Text

class PadsViewModel(private val midi: MIDIRepository) : ViewModel() {

    private val _selectedChannel = MutableStateFlow(PadChannel.A)
    val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

    private val _pressedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val pressedIndices: StateFlow<Set<Int>> = _pressedIndices.asStateFlow()

    init {
        // Listen for incoming MIDI: auto-switch group + flash the matching pad.
        // The 0xC0 Program-Change group branch has been removed — the device does not
        // send Program Change for group changes; real group sync goes through FILE_METADATA.
        // The 0x90 noteOn auto-switch is retained (confirmed on hardware).
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
                }
            }
        }
    }

    fun selectChannel(channel: PadChannel) {
        // Optimistic: update UI immediately so the tap feels instant.
        val changed = channel != _selectedChannel.value
        _selectedChannel.value = channel
        _pressedIndices.value = emptySet()
        // Async: propagate to device via FILE_METADATA SET — only when the group
        // actually changed, so re-tapping the active chip doesn't spam redundant writes.
        if (changed) viewModelScope.launch { midi.setActiveGroup(channel.ordinal) }
    }

    /**
     * Poll the device for the current active group and reconcile with the UI.
     * Called from the PadsScreen RESUMED lifecycle loop every 1500 ms.
     * No-op if the device is not connected or returns null.
     */
    fun refreshActiveGroupFromDevice() {
        viewModelScope.launch {
            android.util.Log.d("EP133APP", "MIDI META: poll tick → refreshActiveGroupFromDevice")
            val idx = midi.getActiveGroupIndex() ?: return@launch
            val deviceGroup = PadChannel.entries.getOrNull(idx) ?: return@launch
            if (deviceGroup != _selectedChannel.value) {
                _selectedChannel.value = deviceGroup
                _pressedIndices.value = emptySet()
            }
        }
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
    val t = LocalEP133Tokens.current
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

    // Device→app active-group sync: while the screen is RESUMED, poll the device every 1500 ms
    // and reconcile the selected group with the hardware. Unblocked by the reqId→waiter dispatcher
    // refactor (backlog 999.4) — file responses now route by reqId, so the metadata drill-down
    // (resolve /projects → active project → groups) no longer drops replies or stalls imports.
    // The repo guards re-entrancy with activeGroupPollInFlight, so a slow tick can't pile up.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1500)
                viewModel.refreshActiveGroupFromDevice()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // ── Group selector — A / B / C / D ────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PadChannel.entries.forEach { channel ->
                Ep133GroupChip(
                    label = channel.name,
                    selected = channel == selectedChannel,
                    onClick = { viewModel.selectChannel(channel) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Pad grid (multi-touch via grid-level pointerInteropFilter) ────────
        // D-18, D-20, RESEARCH.md Pattern 5. Grid coordinate math is unchanged.
        val rows = pads.chunked(columns)
        val rowCount = rows.size
        var gridWidthPx by remember { mutableStateOf(0f) }
        var gridHeightPx by remember { mutableStateOf(0f) }
        val pointerToPad = remember { mutableMapOf<Int, Int>() }

        Column(
            modifier = Modifier
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPads.forEachIndexed { colIdx, pad ->
                        val index = rowIdx * columns + colIdx
                        val isPressed = index in pressedIndices
                        val scaleLockActive = inScaleSet.isNotEmpty()
                        val isInScale = inScaleSet.isEmpty() || (pad.note % 12) in inScaleSet
                        val state = when {
                            isPressed -> PadState.Pressed
                            scaleLockActive && isInScale -> PadState.InScale
                            pad.defaultSound != null -> PadState.Loaded
                            else -> PadState.Empty
                        }
                        Ep133Pad(
                            id = pad.label,
                            name = pad.defaultSound ?: "—",
                            state = state,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                        )
                    }
                    // Fill remaining columns if row is short
                    repeat(columns - rowPads.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ── Legend ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            LegendItem(dotColor = t.live, label = "IN SCALE = TEAL HAIRLINE")
            LegendItem(dotColor = t.accent, label = "PRESSED = GLOW")
        }
    }
}

@Composable
private fun LegendItem(dotColor: Color, label: String) {
    val t = LocalEP133Tokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Ep133StatusDot(dotColor, size = 7)
        M3Text(
            label,
            color = t.text3,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

