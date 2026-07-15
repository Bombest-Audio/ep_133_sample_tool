package com.ep133.sampletool.ui.chords

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ep133.sampletool.domain.model.EP133Sound
import com.ep133.sampletool.domain.model.EP133Sounds
import com.ep133.sampletool.ui.TestTags
import androidx.compose.ui.platform.testTag

private val SYNTH_CATEGORIES = setOf("melodic", "bass")

/**
 * Bottom sheet for selecting an EP-133 melodic or bass sound for chord playback.
 *
 * Shows all sounds in the "melodic" and "bass" categories, searchable by name.
 * The selected sound will be used when pushing a progression to the KO-II.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPickerSheet(
    onSoundSelected: (EP133Sound) -> Unit,
    onDismiss: () -> Unit,
) {
    val synthSounds = remember {
        EP133Sounds.ALL.filter { it.category in SYNTH_CATEGORIES }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) synthSounds
        else synthSounds.filter { it.name.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.CHORDS_SOUND_PICKER_SHEET),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "SELECT SOUND",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search melodic / bass sounds…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it.number }) { sound ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = sound.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "#${sound.number} · ${sound.category}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.clickable { onSoundSelected(sound) },
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
