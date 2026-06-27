package com.ep133.sampletool.ui.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.ep133.sampletool.webview.EP133WebViewSetup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.BackupManager
import com.ep133.sampletool.domain.midi.BackupProgress
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.RestoreProgress
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.EP133Scales
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.ui.theme.TEColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeviceViewModel(private val midi: MIDIRepository) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = midi.deviceState

    // D-16: channel shared from MIDIRepository
    val channelFlow: StateFlow<Int> = midi.channelFlow

    private val _selectedChannel = MutableStateFlow(PadChannel.A)
    val selectedChannel: StateFlow<PadChannel> = _selectedChannel.asStateFlow()

    // D-17: scale state delegated to MIDIRepository (single source of truth)
    val selectedScale: StateFlow<Scale?> = midi.selectedScale
    val selectedRootNote: StateFlow<String> = midi.selectedRootNote

    // ── Backup/Restore state ──
    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _isRestoreInProgress = MutableStateFlow(false)
    val isRestoreInProgress: StateFlow<Boolean> = _isRestoreInProgress.asStateFlow()

    private val _backupProgress = MutableStateFlow(0f)
    val backupProgress: StateFlow<Float> = _backupProgress.asStateFlow()

    private val _restoreProgress = MutableStateFlow(0f)
    val restoreProgress: StateFlow<Float> = _restoreProgress.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var _pendingRestoreBytes: ByteArray? = null
    private val _showRestoreConfirm = MutableStateFlow(false)
    val showRestoreConfirm: StateFlow<Boolean> = _showRestoreConfirm.asStateFlow()

    // SAF callbacks — set by MainActivity.onCreate() (cannot register ActivityResult inside ViewModel)
    var onRequestBackup: ((suggestedName: String) -> Unit)? = null
    var onRequestRestore: (() -> Unit)? = null

    fun triggerBackup() {
        if (_isBackupInProgress.value || _isRestoreInProgress.value) return
        val name = BackupManager(midi).suggestedBackupFilename()
        onRequestBackup?.invoke(name)
    }

    fun triggerRestore() {
        if (_isBackupInProgress.value || _isRestoreInProgress.value) return
        onRequestRestore?.invoke()
    }

    fun onBackupUriSelected(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            _isBackupInProgress.value = true
            _backupProgress.value = 0f
            val backupManager = BackupManager(midi)
            backupManager.createBackup(deviceId = 0).collect { progress ->
                when (progress) {
                    is BackupProgress.Progress -> {
                        if (progress.total > 0) {
                            _backupProgress.value = progress.current.toFloat() / progress.total
                        }
                    }
                    is BackupProgress.Done -> {
                        val wrote = withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openOutputStream(uri)
                            if (stream == null) {
                                false
                            } else {
                                stream.use { it.write(progress.pakBytes) }
                                true
                            }
                        }
                        _isBackupInProgress.value = false
                        _backupProgress.value = 0f
                        _snackbarMessage.value =
                            if (wrote) "Backup complete" else "Backup failed: could not write file"
                    }
                    is BackupProgress.Error -> {
                        _isBackupInProgress.value = false
                        _backupProgress.value = 0f
                        _snackbarMessage.value = "Backup failed: ${progress.message}"
                    }
                }
            }
        }
    }

    fun onRestoreUriSelected(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.readBytes()
            } ?: return@launch
            _pendingRestoreBytes = bytes
            _showRestoreConfirm.value = true
        }
    }

    fun confirmRestore() {
        val bytes = _pendingRestoreBytes ?: return
        _showRestoreConfirm.value = false
        viewModelScope.launch {
            _isRestoreInProgress.value = true
            _restoreProgress.value = 0f
            BackupManager(midi).restore(bytes, deviceId = 0).collect { progress ->
                when (progress) {
                    is RestoreProgress.Progress -> {
                        if (progress.total > 0) {
                            _restoreProgress.value = progress.current.toFloat() / progress.total
                        }
                    }
                    is RestoreProgress.Done -> {
                        _isRestoreInProgress.value = false
                        _restoreProgress.value = 0f
                        _snackbarMessage.value = "Restore complete. Your EP-133 will restart."
                    }
                    is RestoreProgress.Error -> {
                        _isRestoreInProgress.value = false
                        _restoreProgress.value = 0f
                        _snackbarMessage.value = "Restore failed: ${progress.message}"
                    }
                }
            }
        }
    }

    fun cancelRestore() {
        _showRestoreConfirm.value = false
        _pendingRestoreBytes = null
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    fun selectChannel(channel: PadChannel) {
        _selectedChannel.value = channel
        // EP-133 uses MIDI ch 1 (index 0) for all groups by default
        midi.setChannel(0)
    }

    fun selectScale(scale: Scale?) {
        midi.setScale(scale)
    }

    fun selectRootNote(note: String) {
        midi.setRootNote(note)
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
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsState()
    val isRestoreInProgress by viewModel.isRestoreInProgress.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    val restoreProgress by viewModel.restoreProgress.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val showRestoreConfirm by viewModel.showRestoreConfirm.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Show snackbar when message appears
    androidx.compose.runtime.LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    // Restore confirmation dialog
    if (showRestoreConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            title = { Text("Restore EP-133?") },
            text = { Text("This will overwrite all content on your EP-133. This cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.confirmRestore() }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.cancelRestore() }) {
                    Text("Cancel")
                }
            },
        )
    }

    val context = LocalContext.current

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!deviceState.connected) {
                DeviceConnectionState(
                    permissionState = deviceState.permissionState,
                    onGrantPermission = { viewModel.refreshDevices() },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    },
                )
            }
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
            BackupRestoreSection(
                isBackupInProgress = isBackupInProgress,
                isRestoreInProgress = isRestoreInProgress,
                backupProgress = backupProgress,
                restoreProgress = restoreProgress,
                onBackup = { viewModel.triggerBackup() },
                onRestore = { viewModel.triggerRestore() },
            )
            RestoreFactoryButton(onOpen = onNavigateToWebView)
            FormatDeviceButton(onOpen = onNavigateToWebView)
        }
    }
}

/**
 * Three-state connection guidance shown when no device is connected (D-19, CONN-04).
 *
 * - UNKNOWN / GRANTED (no device detected): "Connect your EP-133" + Grant Permission button
 * - AWAITING: spinner + waiting text
 * - DENIED: actionable message + Open Settings button
 */
@Composable
private fun DeviceConnectionState(
    permissionState: PermissionState,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (permissionState) {
                PermissionState.AWAITING -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(
                        text = "Waiting for USB permission…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                PermissionState.DENIED -> {
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "USB permission required. Go to Settings to allow USB access.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onOpenSettings) {
                        Text("Open Settings")
                    }
                }
                else -> {
                    // UNKNOWN or GRANTED — device present but not yet enumerated
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "Connect your EP-133 via USB",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onGrantPermission) {
                        Text("Grant Permission")
                    }
                }
            }
        }
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
            val storageProgress = if (state.connected && state.storageUsedBytes != null && state.storageTotalBytes != null && state.storageTotalBytes > 0) {
                (state.storageUsedBytes.toFloat() / state.storageTotalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val storageLabel = when {
                !state.connected -> "--"
                state.storageUsedBytes != null && state.storageTotalBytes != null && state.storageTotalBytes > 0 ->
                    "${(storageProgress * 100).toInt()}% used"
                else -> null  // null = loading
            }
            LinearProgressIndicator(
                progress = { storageProgress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (storageLabel != null) {
                Text(
                    text = storageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.connected) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(state: DeviceState) {
    val samplesValue = when {
        !state.connected -> "--"
        state.sampleCount != null -> state.sampleCount.toString()
        else -> null  // null = loading
    }
    val storageValue = when {
        !state.connected -> "--"
        state.storageUsedBytes != null && state.storageTotalBytes != null -> {
            val usedMb = state.storageUsedBytes / 1_048_576
            val totalMb = state.storageTotalBytes / 1_048_576
            "${usedMb}MB / ${totalMb}MB"
        }
        else -> null  // null = loading
    }
    val firmwareValue = when {
        !state.connected -> "--"
        state.firmwareVersion != null -> state.firmwareVersion
        else -> null  // null = loading
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard("SAMPLES", samplesValue, isLoading = state.connected && samplesValue == null, Modifier.weight(1f))
        StatCard("STORAGE", storageValue, isLoading = state.connected && storageValue == null, Modifier.weight(1f))
        StatCard("FIRMWARE", firmwareValue, isLoading = state.connected && firmwareValue == null, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String?,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = value ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                )
            }
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
    selectedScale: Scale?,
    onScaleSelect: (Scale?) -> Unit,
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
                selectedText = selectedScale?.name ?: "None",
                options = listOf("None") + EP133Scales.ALL.map { it.name },
                onSelect = { name ->
                    if (name == "None") {
                        onScaleSelect(null)
                    } else {
                        EP133Scales.ALL.firstOrNull { it.name == name }
                            ?.let(onScaleSelect)
                    }
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
private fun BackupRestoreSection(
    isBackupInProgress: Boolean,
    isRestoreInProgress: Boolean,
    backupProgress: Float,
    restoreProgress: Float,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val busy = isBackupInProgress || isRestoreInProgress
    Column {
        Text(
            text = "BACKUP & RESTORE",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onBackup,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Backup")
            }
            Button(
                onClick = onRestore,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restore")
            }
        }
        if (isBackupInProgress) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Backup in progress…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { backupProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isRestoreInProgress) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Restore in progress…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { restoreProgress },
                modifier = Modifier.fillMaxWidth(),
            )
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

