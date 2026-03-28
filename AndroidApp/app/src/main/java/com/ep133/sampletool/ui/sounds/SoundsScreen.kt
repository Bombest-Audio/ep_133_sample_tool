package com.ep133.sampletool.ui.sounds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.EP133Sound
import com.ep133.sampletool.domain.model.EP133Sounds
import com.ep133.sampletool.domain.model.Pad
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.SoundCategory
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
}

private val FILTER_CHIPS: List<Pair<String?, String>> = buildList {
    add(null to "ALL")
    EP133Sounds.CATEGORIES.forEach { add(it.id to it.name.uppercase()) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoundsScreen(viewModel: SoundsViewModel) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = viewModel::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            CategoryFilterRow(
                selected = selectedCategory,
                onSelect = viewModel::selectCategory,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                grouped.forEach { (category, categorySounds) ->
                    stickyHeader(key = category.id) {
                        CategoryHeader(category)
                    }
                    items(categorySounds, key = { it.number }) { sound ->
                        SoundRow(
                            sound = sound,
                            onAssign = { viewModel.selectSoundForAssign(sound) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
        )

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
                        text = "Connect EP-133 to use SOUNDS",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search sounds...") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = "Search")
        },
        singleLine = true,
    )
}

@Composable
private fun CategoryFilterRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(FILTER_CHIPS, key = { it.first ?: "all" }) { (categoryId, label) ->
            FilterChip(
                selected = selected == categoryId,
                onClick = { onSelect(categoryId) },
                label = {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                },
            )
        }
    }
}

@Composable
private fun CategoryHeader(category: SoundCategory) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = category.name.uppercase(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoundRow(
    sound: EP133Sound,
    onAssign: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sound.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
            )

            Spacer(modifier = Modifier.height(2.dp))

            val categoryLabel = EP133Sounds.CATEGORIES
                .firstOrNull { it.id == sound.category }?.name
                ?: sound.category

            Text(
                text = categoryLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onAssign) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Assign to pad",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
