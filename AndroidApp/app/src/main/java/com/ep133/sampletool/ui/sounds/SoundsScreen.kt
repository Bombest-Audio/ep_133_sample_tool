package com.ep133.sampletool.ui.sounds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.model.EP133Sound
import com.ep133.sampletool.domain.model.EP133Sounds
import com.ep133.sampletool.domain.model.Pad
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.SoundCategory
import com.ep133.sampletool.ui.theme.Ep133Chip
import com.ep133.sampletool.ui.theme.Ep133Pad
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.PadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SoundsViewModel(
    private val midi: MIDIRepository,
) : ViewModel() {

    val deviceState = midi.deviceState

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    private val _selectedSound = MutableStateFlow<EP133Sound?>(null)
    val selectedSound: StateFlow<EP133Sound?> = _selectedSound

    /** Current group — updated from PadsViewModel's incoming MIDI auto-switch. */
    private val _currentGroup = MutableStateFlow(PadChannel.A)
    val currentGroup: StateFlow<PadChannel> = _currentGroup

    /** Active preview noteOff job — cancelled when a new preview starts (D-21). */
    private var previewJob: kotlinx.coroutines.Job? = null

    val filteredSounds: StateFlow<List<EP133Sound>> =
        combine(_query, _selectedCategory) { query, category ->
            EP133Sounds.ALL.filter { sound ->
                val matchesCategory = category == null || sound.category == category
                val matchesQuery = query.isBlank() ||
                    sound.name.contains(query, ignoreCase = true) ||
                    sound.number.toString().contains(query)
                matchesCategory && matchesQuery
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EP133Sounds.ALL)

    fun updateQuery(value: String) { _query.value = value }
    fun selectCategory(categoryId: String?) { _selectedCategory.value = categoryId }

    fun selectSoundForAssign(sound: EP133Sound) { _selectedSound.value = sound }
    fun dismissPadPicker() { _selectedSound.value = null }

    fun setGroup(group: PadChannel) { _currentGroup.value = group }

    fun loadSoundToPad(sound: EP133Sound, pad: Pad) {
        midi.loadSoundToPad(sound.number, padNote = pad.note, padChannel = pad.midiChannel)
        _selectedSound.value = null
    }

    /**
     * Preview a sound by sending noteOn on MIDI channel 9 (EP-133 preview channel).
     * Cancels any ongoing preview, fires noteOn immediately, then noteOff after 500 ms.
     *
     * Uses sound.number as the note (clamped to 0-127) on channel 9 (D-21).
     */
    fun previewSound(sound: EP133Sound) {
        previewJob?.cancel()
        val note = (sound.number - 1).coerceIn(0, 127)
        val previewChannel = 9  // MIDI channel 10 = index 9
        midi.noteOn(note, velocity = 100, ch = previewChannel)
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            midi.noteOff(note, ch = previewChannel)
        }
    }
}

private val FILTER_CHIPS: List<Pair<String?, String>> = buildList {
    add(null to "ALL")
    EP133Sounds.CATEGORIES.forEach { add(it.id to it.name.uppercase()) }
}

/** Hard 2–3dp corner used across the faceplate UI (mirrors the design's `border-radius:2px`). */
private val PanelRadius = RoundedCornerShape(3.dp)
private val Mono = FontFamily.Monospace

/** Deterministic 7-bar mini-waveform per sound — purely decorative, keyed off the sound number. */
private fun miniWaveform(seed: Int): List<Float> =
    (0 until 7).map { i ->
        val v = ((seed * 31 + i * 53) % 70) + 22  // 22..91
        v / 100f
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoundsScreen(viewModel: SoundsViewModel) {
    val t = LocalEP133Tokens.current
    val query by viewModel.query.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val sounds by viewModel.filteredSounds.collectAsState()
    val selectedSound by viewModel.selectedSound.collectAsState()
    val currentGroup by viewModel.currentGroup.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val grouped = sounds.groupBy { sound ->
        EP133Sounds.CATEGORIES
            .firstOrNull { it.id == sound.category }
            ?: SoundCategory(sound.category, sound.category)
    }

    val activeCategoryLabel = selectedCategory
        ?.let { id -> EP133Sounds.CATEGORIES.firstOrNull { it.id == id }?.name }
        ?: "ALL SOUNDS"

    Box(modifier = Modifier.fillMaxSize().background(t.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            SearchField(
                query = query,
                onQueryChange = viewModel::updateQuery,
                count = sounds.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            )

            CategoryFilterRow(
                selected = selectedCategory,
                onSelect = viewModel::selectCategory,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(PanelRadius)
                    .border(1.dp, t.rule, PanelRadius),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                grouped.forEach { (category, categorySounds) ->
                    stickyHeader(key = category.id) {
                        CategoryHeader(category)
                    }
                    items(categorySounds, key = { it.number }) { sound ->
                        SoundRow(
                            sound = sound,
                            onPreview = { viewModel.previewSound(sound) },
                            onAssign = { viewModel.selectSoundForAssign(sound) },
                        )
                    }
                }
            }

            // Footer status chips — active category + live preview indicator.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusPill(
                    modifier = Modifier.weight(1f),
                    label = activeCategoryLabel,
                )
                StatusPill(
                    modifier = Modifier.weight(1f),
                    label = "PREVIEW ON",
                    leading = { Ep133StatusDot(t.live, size = 7) },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 70.dp),
        )

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
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = t.text3,
                    )
                    Text(
                        text = "Connect EP-133 to use SOUNDS",
                        color = t.text2,
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        letterSpacing = 0.4.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    // Pad picker bottom sheet
    selectedSound?.let { sound ->
        PadPickerSheet(
            sound = sound,
            group = currentGroup,
            onPadSelected = { pad ->
                viewModel.loadSoundToPad(sound, pad)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "${sound.name} → ${pad.label}"
                    )
                }
            },
            onDismiss = viewModel::dismissPadPicker,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        shape = PanelRadius,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = Mono,
            color = t.text,
        ),
        placeholder = {
            Text(
                "search ${EP133Sounds.ALL.size} sounds…",
                color = t.text3,
                fontFamily = Mono,
                fontSize = 12.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = t.text3,
            )
        },
        trailingIcon = {
            Text(
                text = "$count",
                modifier = Modifier.padding(end = 12.dp),
                color = t.text3,
                fontFamily = Mono,
                fontSize = 10.sp,
            )
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = t.inset,
            unfocusedContainerColor = t.inset,
            focusedBorderColor = t.accent,
            unfocusedBorderColor = t.rule,
            cursorColor = t.accent,
        ),
    )
}

@Composable
private fun CategoryFilterRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(FILTER_CHIPS, key = { it.first ?: "all" }) { (categoryId, label) ->
            Ep133Chip(
                label = label,
                selected = selected == categoryId,
                onClick = { onSelect(categoryId) },
            )
        }
    }
}

@Composable
private fun CategoryHeader(category: SoundCategory) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel2)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Ep133SectionLabel(category.name)
    }
}

@Composable
private fun SoundRow(
    sound: EP133Sound,
    onPreview: () -> Unit,
    onAssign: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val categoryLabel = EP133Sounds.CATEGORIES
        .firstOrNull { it.id == sound.category }?.name
        ?: sound.category

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.panel)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 1) Mono sample number
        Text(
            text = sound.number.toString(),
            modifier = Modifier.size(width = 30.dp, height = 22.dp),
            color = t.text3,
            fontFamily = Mono,
            fontSize = 10.sp,
            textAlign = TextAlign.Start,
        )

        // 2) Small static waveform thumbnail
        WaveformThumb(
            seed = sound.number,
            modifier = Modifier.size(width = 26.dp, height = 22.dp),
        )

        // 3) Name + meta (takes remaining space)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sound.name,
                color = t.text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.1).sp,
            )
            Text(
                text = categoryLabel.uppercase(),
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.2.sp,
            )
        }

        // 4) Preview ▶ — plays noteOn on channel 9 for 500ms (D-21)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(PanelRadius)
                .background(t.live, PanelRadius)
                .clickable { onPreview() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Preview sound",
                tint = t.onAccent,
                modifier = Modifier.size(15.dp),
            )
        }

        // 5) + PAD assign — opens the pad picker sheet
        Box(
            modifier = Modifier
                .clip(PanelRadius)
                .border(1.dp, t.rule, PanelRadius)
                .clickable { onAssign() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+ PAD",
                color = t.accent,
                fontFamily = Mono,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

/** Decorative static mini-waveform (7 bars) drawn from a deterministic seed. */
@Composable
private fun WaveformThumb(seed: Int, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        miniWaveform(seed).forEach { frac ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height((22 * frac).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(t.text3),
            )
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = modifier
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(vertical = 6.dp, horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text = label.uppercase(),
            color = t.text2,
            fontFamily = Mono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PadPickerSheet(
    sound: EP133Sound,
    group: PadChannel,
    onPadSelected: (Pad) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val sheetState = rememberModalBottomSheetState()
    val pads = remember(group) { EP133Pads.padsForChannel(group) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.panel,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "assign to pad",
                        color = t.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${sound.name} → pick a slot in group ${group.name}",
                        modifier = Modifier.padding(top = 2.dp),
                        color = t.text3,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        color = t.text3,
                        fontFamily = Mono,
                        fontSize = 14.sp,
                    )
                }
            }

            // 12-pad grid, 4 columns (mirrors the design's repeat(4,1fr)).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pads.chunked(4).forEach { rowPads ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPads.forEach { pad ->
                            Ep133Pad(
                                id = pad.label,
                                name = pad.defaultSound ?: "—",
                                state = if (pad.defaultSound != null) PadState.Loaded else PadState.Empty,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 56.dp),
                                onClick = { onPadSelected(pad) },
                            )
                        }
                    }
                }
            }
        }
    }
}
