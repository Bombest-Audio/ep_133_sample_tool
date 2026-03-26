package com.ep133.sampletool.ui.device

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ep133.sampletool.webview.EP133WebViewSetup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.EP133Scales
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.ui.theme.TEColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceViewModel(private val midi: MIDIRepository) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = midi.deviceState

    private val _selectedChannel = MutableStateFlow(PadChannel.A)
    val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

    private val _selectedScale = MutableStateFlow(EP133Scales.ALL.first())
    val selectedScale: StateFlow<Scale> = _selectedScale.asStateFlow()

    private val _selectedRootNote = MutableStateFlow("C")
    val selectedRootNote: StateFlow<String> = _selectedRootNote.asStateFlow()

    fun selectChannel(channel: PadChannel) {
        _selectedChannel.value = channel
        // EP-133 uses MIDI ch 1 (index 0) for all groups by default
        midi.setChannel(0)
    }

    fun selectScale(scale: Scale) {
        _selectedScale.value = scale
    }

    fun selectRootNote(note: String) {
        _selectedRootNote.value = note
    }

    fun refreshDevices() {
        midi.refreshDeviceState()
    }
}

@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel,
    onNavigateToWebView: () -> Unit = {},
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val selectedScale by viewModel.selectedScale.collectAsState()
    val selectedRootNote by viewModel.selectedRootNote.collectAsState()
    var showSampleManager by remember { mutableStateOf(false) }

    if (showSampleManager) {
        SampleManagerPanel(onDismiss = { showSampleManager = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceCard(deviceState)
        StatsRow(deviceState)
        ChannelSelector(
            selected = selectedChannel,
            onSelect = viewModel::selectChannel,
        )
        ScaleModeSelector(
            selectedScale = selectedScale,
            onScaleSelect = viewModel::selectScale,
            selectedRoot = selectedRootNote,
            onRootSelect = viewModel::selectRootNote,
        )
        ActionButtons(onOpenManager = { showSampleManager = true })
        RestoreFactoryButton(onOpen = { showSampleManager = true })
        FormatDeviceButton(onOpen = { showSampleManager = true })
    }
}

@Composable
private fun DeviceCard(state: DeviceState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = if (state.connected) TEColors.Teal else TEColors.InkTertiary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.connected) "ONLINE" else "OFFLINE",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.connected) TEColors.Teal else TEColors.InkTertiary,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "EP-133",
                style = MaterialTheme.typography.displayMedium,
            )

            Text(
                text = state.deviceName.ifBlank { "No device connected" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "STORAGE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (state.connected) 0.42f else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = if (state.connected) "42% used" else "--",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsRow(state: DeviceState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("SAMPLES", if (state.connected) "128" else "--", Modifier.weight(1f))
        StatCard("PROJECTS", if (state.connected) "8" else "--", Modifier.weight(1f))
        StatCard("FIRMWARE", if (state.connected) "v1.3.2" else "--", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChannelSelector(
    selected: PadChannel,
    onSelect: (PadChannel) -> Unit,
) {
    Column {
        Text(
            text = "MIDI CHANNEL",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PadChannel.entries.forEach { channel ->
                FilterChip(
                    selected = selected == channel,
                    onClick = { onSelect(channel) },
                    label = {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleModeSelector(
    selectedScale: Scale,
    onScaleSelect: (Scale) -> Unit,
    selectedRoot: String,
    onRootSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = "SCALE MODE",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScaleDropdown(
                label = "Scale",
                selectedText = selectedScale.name,
                options = EP133Scales.ALL.map { it.name },
                onSelect = { name ->
                    EP133Scales.ALL.firstOrNull { it.name == name }
                        ?.let(onScaleSelect)
                },
                modifier = Modifier.weight(2f),
            )

            ScaleDropdown(
                label = "Root",
                selectedText = selectedRoot,
                options = EP133Scales.ROOT_NOTES,
                onSelect = onRootSelect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleDropdown(
    label: String,
    selectedText: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        TextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(onOpenManager: () -> Unit) {
    Column {
        Text(
            text = "ACTIONS",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                ActionRow(
                    icon = Icons.Default.SaveAlt,
                    label = "Backup Device",
                    onClick = onOpenManager,
                )
                HorizontalDivider()
                ActionRow(
                    icon = Icons.Default.CloudSync,
                    label = "Sync Samples",
                    onClick = onOpenManager,
                )
                HorizontalDivider()
                ActionRow(
                    icon = Icons.Default.Web,
                    label = "Sample Manager",
                    onClick = onOpenManager,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RestoreFactoryButton(onOpen: () -> Unit) {
    Column {
        Text(
            text = "FACTORY RESET",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            ActionRow(
                icon = Icons.Default.Restore,
                label = "Restore Factory Sounds",
                onClick = onOpen,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Opens Sample Manager to restore the 559 factory sounds bundled with the EP-133.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun FormatDeviceButton(onOpen: () -> Unit) {
    Button(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.DeleteForever,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "FORMAT DEVICE",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SampleManagerPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Back",
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onDismiss),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAMPLE MANAGER", style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    webViewClient = WebViewClient()
                    setBackgroundColor(android.graphics.Color.BLACK)
                    loadUrl("https://appassets.androidplatform.net/assets/data/index.html")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        )
    }
}
