package com.ep133.sampletool.ui.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.firmware.FirmwareCatalog
import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.domain.firmware.TeFirmwareCatalog
import com.ep133.sampletool.domain.midi.BackupManager
import com.ep133.sampletool.domain.midi.BackupProgress
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.RestoreProgress
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.EP133Scales
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.theme.Ep133GhostButton
import com.ep133.sampletool.ui.theme.Ep133GroupChip
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.Ep133StatReadout
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State machine for the firmware update check on the Device screen.
 *
 * Idle     — initial state; no check has been requested yet
 * Checking — check in progress (catalog.latestVersion() in flight)
 * UpToDate — device firmware >= latest known version
 * UpdateAvailable — device firmware < latest; holds both versions for the banner
 * Unknown  — device firmware string unparseable, or catalog returned null
 */
sealed class FirmwareUpdateState {
    object Idle : FirmwareUpdateState()
    object Checking : FirmwareUpdateState()
    object UpToDate : FirmwareUpdateState()
    data class UpdateAvailable(
        val current: FirmwareVersion,
        val latest: FirmwareVersion,
    ) : FirmwareUpdateState()
    object Unknown : FirmwareUpdateState()
}

class DeviceViewModel(
    private val midi: MIDIRepository,
    private val catalog: FirmwareCatalog = TeFirmwareCatalog(),
) : ViewModel() {

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

    // True while querying firmware/storage/sample-count, so the storage card shows a spinner
    // only while loading (not forever when a value comes back empty).
    private val _statsLoading = MutableStateFlow(false)
    val statsLoading: StateFlow<Boolean> = _statsLoading.asStateFlow()

    // Firmware update detection state (FW-01 / FW-02)
    private val _firmwareUpdate = MutableStateFlow<FirmwareUpdateState>(FirmwareUpdateState.Idle)
    val firmwareUpdate: StateFlow<FirmwareUpdateState> = _firmwareUpdate.asStateFlow()

    // SAF callbacks — set by MainActivity.onCreate() (cannot register ActivityResult inside ViewModel)
    var onRequestBackup: ((suggestedName: String) -> Unit)? = null
    var onRequestRestore: (() -> Unit)? = null

    // Firmware updater callback — set by MainActivity (mirrors SAF pattern); Wave 3 wires the Custom Tab
    var onOpenFirmwareUpdater: (() -> Unit)? = null

    // Simulated EP-133 toggle — set by MainActivity ONLY when SimulatedPortProvider.available
    // (debug builds). Null in release, which hides the toggle UI entirely.
    var onSimToggle: ((Boolean) -> Unit)? = null
    val simToggleAvailable: Boolean get() = onSimToggle != null

    private val _simModeActive = MutableStateFlow(false)
    val simModeActive: StateFlow<Boolean> = _simModeActive.asStateFlow()

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
            backupManager.createBackup().collect { progress ->
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
            BackupManager(midi).restore(bytes).collect { progress ->
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

    /** Surface a snackbar message from outside the ViewModel (e.g. MainActivity). */
    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
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

    /** Invokes the firmware updater callback wired by MainActivity (Wave 3). */
    fun openFirmwareUpdater() {
        onOpenFirmwareUpdater?.invoke()
    }

    /**
     * Flip between the real USB port and the simulated EP-133 (debug builds only).
     *
     * loadStats() is called explicitly rather than relying on the connected LaunchedEffect:
     * swapPort resets and re-sets deviceState synchronously, so StateFlow conflation can
     * swallow the false→true edge. loadStats/queryDeviceStats early-return safely when
     * disconnected, so the disable path is harmless.
     */
    fun setSimulatedMode(enabled: Boolean) {
        val toggle = onSimToggle ?: return
        if (enabled == _simModeActive.value) return
        toggle(enabled)
        _simModeActive.value = enabled
        loadStats()
    }

    /** Query firmware / storage / sample count. Called when the Device screen opens connected. */
    fun loadStats() {
        if (_statsLoading.value) return
        viewModelScope.launch {
            _statsLoading.value = true
            try {
                midi.queryDeviceStats()
            } finally {
                _statsLoading.value = false
            }
            if (deviceState.value.firmwareVersion != null) {
                checkFirmwareUpdate()
            }
        }
    }

    /**
     * Checks whether the device firmware is up to date by asking the catalog for the latest version.
     * Emits Checking → UpdateAvailable / UpToDate / Unknown.
     *
     * Only called from loadStats() after a successful stats query with a non-null firmwareVersion.
     * The _statsLoading guard on loadStats() prevents concurrent invocations.
     */
    private fun checkFirmwareUpdate() {
        viewModelScope.launch {
            _firmwareUpdate.value = FirmwareUpdateState.Checking
            val rawFw = deviceState.value.firmwareVersion
            val current = FirmwareVersion.parse(rawFw)
            if (current == null) {
                Log.w("EP133APP", "FW check: unparseable firmware '$rawFw'")
                _firmwareUpdate.value = FirmwareUpdateState.Unknown
                return@launch
            }
            val latest = try {
                catalog.latestVersion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EP133APP", "FW check failed", e)
                null
            }
            if (latest == null) {
                _firmwareUpdate.value = FirmwareUpdateState.Unknown
                return@launch
            }
            _firmwareUpdate.value = if (current >= latest) {
                FirmwareUpdateState.UpToDate
            } else {
                FirmwareUpdateState.UpdateAvailable(current, latest)
            }
        }
    }
}

/** Hard ~2–3dp faceplate corner (mirrors the design's `border-radius:2px/3px`). */
private val PanelRadius = RoundedCornerShape(3.dp)
private val Mono = FontFamily.Monospace

@Composable
fun DeviceScreen(
    viewModel: DeviceViewModel,
) {
    val t = LocalEP133Tokens.current
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
    val statsLoading by viewModel.statsLoading.collectAsState()
    val firmwareUpdate by viewModel.firmwareUpdate.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Query firmware / storage / samples whenever we're connected and the screen is shown.
    LaunchedEffect(deviceState.connected) {
        if (deviceState.connected) viewModel.loadStats()
    }

    // Show snackbar when message appears
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    // Restore confirmation dialog
    if (showRestoreConfirm) {
        RestoreConfirmDialog(
            onConfirm = { viewModel.confirmRestore() },
            onDismiss = { viewModel.cancelRestore() },
        )
    }

    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(t.bg)) {
        if (!deviceState.connected) {
            // Clean offline state — fills the body, plug-in guidance keyed off permission.
            DeviceOfflineState(
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                ConnectionCard(deviceState)
                StorageBar(deviceState, loading = statsLoading)
                StatsGrid(deviceState, selectedChannel)
                FirmwareUpdateBanner(
                    state = firmwareUpdate,
                    onOpenUpdater = { viewModel.openFirmwareUpdater() },
                )
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
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ── Firmware update banner — shows above ChannelSelector when UpdateAvailable; spinner on Checking ──
@Composable
private fun FirmwareUpdateBanner(
    state: FirmwareUpdateState,
    onOpenUpdater: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    when (state) {
        is FirmwareUpdateState.UpdateAvailable -> {
            Row(
                modifier = Modifier
                    .testTag(TestTags.DEVICE_FIRMWARE_BANNER)
                    .fillMaxWidth()
                    .clip(PanelRadius)
                    .background(t.accent.copy(alpha = 0.12f), PanelRadius)
                    .border(1.dp, t.accent.copy(alpha = 0.35f), PanelRadius)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Firmware ${state.latest} available",
                    color = t.text,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onOpenUpdater,
                    colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
                ) {
                    Text(
                        text = "Open updater",
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }
        is FirmwareUpdateState.Checking -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = t.live,
                )
                Text(
                    text = "CHECKING FIRMWARE…",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
            }
        }
        else -> {
            // Idle, UpToDate, Unknown — render nothing
        }
    }
}

// ── Connection card (faceplate-black, mono eyebrow + device name + halo dot) ──
@Composable
private fun ConnectionCard(state: DeviceState) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.padFace, PanelRadius)
            .border(1.dp, t.padEdge, PanelRadius)
            .padding(15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CONNECTED OVER USB-OTG",
                color = t.padName,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = state.deviceName.ifBlank { "EP-133 K.O. II" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MonoFact("FW", state.firmwareVersion ?: "—", valueColor = t.live)
                MonoFact("CH", "01", valueColor = Color.White)
            }
        }
        // teal halo "online" dot
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(t.live.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Ep133StatusDot(t.live, size = 11)
        }
    }
}

/** A `LABEL value` pair on the dark connection card (mono). */
@Composable
private fun MonoFact(label: String, value: String, valueColor: Color) {
    val t = LocalEP133Tokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = t.padName, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
        Text(value, color = valueColor, fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

// ── Storage progress (M3 LinearProgressIndicator, accent track, mono readout) ──
@Composable
private fun StorageBar(state: DeviceState, loading: Boolean) {
    val t = LocalEP133Tokens.current
    val storageProgress = if (state.storageUsedBytes != null && state.storageTotalBytes != null && state.storageTotalBytes > 0) {
        (state.storageUsedBytes.toFloat() / state.storageTotalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val haveBytes = state.storageUsedBytes != null && state.storageTotalBytes != null
    val readout = if (haveBytes) {
        val usedMb = state.storageUsedBytes!!.toFloat() / 1_048_576f
        val totalMb = state.storageTotalBytes!!.toFloat() / 1_048_576f
        "%.1f / %.1f MB".format(usedMb, totalMb)
    } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.panel2, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("STORAGE", color = t.text2, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
            when {
                readout != null ->
                    Text(readout, color = t.text, fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                loading ->
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = t.text3)
                else ->
                    Text("—", color = t.text3, fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        LinearProgressIndicator(
            progress = { storageProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = t.accent,
            trackColor = t.inset,
        )
    }
}

// ── Stats grid — samples / storage / firmware as mono readouts + MIDI CH ──
@Composable
private fun StatsGrid(state: DeviceState, channel: PadChannel) {
    val samplesValue = state.sampleCount?.toString() ?: "—"
    val storageValue = if (state.storageUsedBytes != null && state.storageTotalBytes != null) {
        val usedMb = state.storageUsedBytes / 1_048_576
        val totalMb = state.storageTotalBytes / 1_048_576
        "${usedMb}/${totalMb}MB"
    } else "—"
    val firmwareValue = state.firmwareVersion ?: "—"

    Column(
        modifier = Modifier.testTag(TestTags.DEVICE_STATS_GRID),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Ep133StatReadout("SAMPLES", samplesValue, modifier = Modifier.weight(1f))
            Ep133StatReadout("STORAGE", storageValue, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Ep133StatReadout("FIRMWARE", firmwareValue, modifier = Modifier.weight(1f))
            Ep133StatReadout("MIDI CH", "01", modifier = Modifier.weight(1f))
        }
    }
}

// ── MIDI channel (pad-group selector A/B/C/D) ──
@Composable
private fun ChannelSelector(
    selected: PadChannel,
    onSelect: (PadChannel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Ep133SectionLabel("MIDI CHANNEL")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PadChannel.entries.forEach { channel ->
                Ep133GroupChip(
                    label = channel.name,
                    selected = selected == channel,
                    onClick = { onSelect(channel) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Scale + root (themed M3 exposed dropdowns) ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScaleModeSelector(
    selectedScale: Scale?,
    onScaleSelect: (Scale?) -> Unit,
    selectedRoot: String,
    onRootSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Ep133SectionLabel("SCALE MODE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ScaleDropdown(
                label = "SCALE",
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
                modifier = Modifier.weight(2f).testTag(TestTags.DEVICE_SCALE_DROPDOWN),
            )

            ScaleDropdown(
                label = "ROOT",
                selectedText = selectedRoot,
                options = EP133Scales.ROOT_NOTES,
                onSelect = onRootSelect,
                modifier = Modifier.weight(1f).testTag(TestTags.DEVICE_ROOT_DROPDOWN),
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
    val t = LocalEP133Tokens.current
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            shape = PanelRadius,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = Mono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = t.text,
            ),
            label = {
                Text(label, color = t.text3, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.2.sp)
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = t.panel2,
                unfocusedContainerColor = t.panel2,
                focusedBorderColor = t.accent,
                unfocusedBorderColor = t.rule,
                focusedTrailingIconColor = t.text2,
                unfocusedTrailingIconColor = t.text3,
                focusedLabelColor = t.text3,
                unfocusedLabelColor = t.text3,
            ),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(t.panel),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option, color = t.text, fontFamily = Mono, fontSize = 13.sp)
                    },
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

// ── Backup & restore (primary + ghost actions, themed progress) ──
@Composable
private fun BackupRestoreSection(
    isBackupInProgress: Boolean,
    isRestoreInProgress: Boolean,
    backupProgress: Float,
    restoreProgress: Float,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val busy = isBackupInProgress || isRestoreInProgress
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Ep133SectionLabel("BACKUP & RESTORE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Ep133PrimaryButton(
                label = "Backup",
                enabled = !busy,
                onClick = onBackup,
                modifier = Modifier.weight(1f),
            )
            Ep133GhostButton(
                label = "Restore",
                enabled = !busy,
                onClick = onRestore,
                modifier = Modifier.weight(1f),
            )
        }
        if (isBackupInProgress) {
            Text("BACKUP IN PROGRESS…", color = t.text2, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
            LinearProgressIndicator(
                progress = { backupProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = t.accent,
                trackColor = t.inset,
            )
        }
        if (isRestoreInProgress) {
            Text("RESTORE IN PROGRESS…", color = t.text2, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
            LinearProgressIndicator(
                progress = { restoreProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = t.accent,
                trackColor = t.inset,
            )
        }
    }
}

// ── Clean "NO DEVICE" offline state (design lines 304–313), keyed off permission ──
@Composable
private fun DeviceOfflineState(
    permissionState: PermissionState,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // eject glyph in a dashed square
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, t.rule, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⏏", color = t.text3, fontFamily = Mono, fontSize = 26.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "no device",
            color = t.text,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(7.dp))

        when (permissionState) {
            PermissionState.AWAITING -> {
                Text(
                    text = "waiting for USB permission…",
                    color = t.text2,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = t.text3)
            }
            PermissionState.DENIED -> {
                Text(
                    text = "USB permission required.\nallow USB access in Settings",
                    color = t.text2,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.height(16.dp))
                Ep133GhostButton(
                    label = "Open Settings",
                    modifier = Modifier.testTag(TestTags.DEVICE_PERMISSION_ACTION),
                    onClick = onOpenSettings,
                )
            }
            else -> {
                // UNKNOWN or GRANTED — device present but not yet enumerated
                Text(
                    text = "plug in your K.O. II\nover USB-OTG",
                    color = t.text2,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ep133StatusDot(t.text3, size = 7)
                    Text(
                        "SCANNING PORTS…",
                        color = t.text3,
                        fontFamily = Mono,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Ep133GhostButton(
                    label = "Grant Permission",
                    modifier = Modifier.testTag(TestTags.DEVICE_PERMISSION_ACTION),
                    onClick = onGrantPermission,
                )
            }
        }
    }
}

// ── Themed restore confirmation dialog ──
@Composable
private fun RestoreConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.DEVICE_RESTORE_CONFIRM_DIALOG),
        containerColor = t.panel,
        titleContentColor = t.text,
        textContentColor = t.text2,
        shape = PanelRadius,
        title = { Text("Restore EP-133?", fontWeight = FontWeight.Bold) },
        text = { Text("This will overwrite all content on your EP-133. This cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
            ) {
                Text("Restore", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
            ) {
                Text("Cancel")
            }
        },
    )
}
