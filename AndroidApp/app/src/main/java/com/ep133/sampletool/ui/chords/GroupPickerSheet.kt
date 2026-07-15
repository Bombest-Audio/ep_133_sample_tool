package com.ep133.sampletool.ui.chords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.ui.TestTags
import androidx.compose.ui.platform.testTag

/**
 * Bottom sheet for choosing which hardware group (A-D) to push a chord progression to.
 *
 * After selection, the app loads the chosen sound onto all 12 pads of the group and
 * enables chord-map mode - pressing a group pad plays the matching chord via MIDI bridge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    soundName: String,
    progressionName: String,
    onGroupSelected: (PadChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.CHORDS_GROUP_PICKER_SHEET),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "PUSH TO GROUP",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "\"$progressionName\"  ·  $soundName",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Loads $soundName to the group pads. Press each pad to play the matching chord through the KO-II.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PadChannel.entries.forEach { group ->
                    FilledTonalButton(
                        onClick = { onGroupSelected(group) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
