package com.ep133.sampletool.ui.projects

import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.BackupItem
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.midi.ProjectBackupProgress
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.ui.theme.TEColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MIME type used for sharing a project backup (T-04-09: opaque octet-stream). */
const val SHARE_MIME = "application/octet-stream"

/**
 * Until the hardware backup→restore round-trip (Open Q2 / UAT-3) passes, the destructive
 * restore action stays disabled. The AlertDialog confirmation is wired regardless; flipping
 * this to `true` after the round-trip is the only change needed to enable the button.
 *
 * HARDWARE-GATE (Open Q2): enable restore after the hardware backup→restore round-trip.
 */
private const val RESTORE_ENABLED = false

/**
 * ViewModel for the Projects browser + backup library (PROJ-01 / PROJ-03 / PROJ-04).
 *
 * Mirrors [com.ep133.sampletool.ui.device.DeviceViewModel]: StateFlow encapsulation with
 * underscore-prefixed `MutableStateFlow` backing fields and public `asStateFlow()` accessors,
 * `viewModelScope` launches, and a snackbar message channel. Backup/restore are driven by
 * collecting [ProjectBackupManager] flows and mapping progress → UI state.
 */
class ProjectsViewModel(
    private val midi: MIDIRepository,
    private val backupManager: ProjectBackupManager,
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = midi.deviceState

    private val _slots = MutableStateFlow<List<MIDIRepository.ProjectSlot>>(emptyList())
    val slots: StateFlow<List<MIDIRepository.ProjectSlot>> = _slots.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupItem>>(emptyList())
    val backups: StateFlow<List<BackupItem>> = _backups.asStateFlow()

    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _backupProgress = MutableStateFlow(0f)
    val backupProgress: StateFlow<Float> = _backupProgress.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var _pendingRestoreFile: File? = null
    private val _showRestoreConfirm = MutableStateFlow(false)
    val showRestoreConfirm: StateFlow<Boolean> = _showRestoreConfirm.asStateFlow()

    /** Enumerate the 9 device project slots (PROJ-01). No-op when offline → empty list. */
    fun loadProjects() {
        viewModelScope.launch {
            try {
                _slots.value = midi.listProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = "Could not list projects: ${e.message ?: e}"
            }
        }
    }

    /** Enumerate the on-disk backup library, newest-first (PROJ-03). Browsable offline. */
    fun loadBackups(context: Context) {
        viewModelScope.launch {
            try {
                _backups.value = backupManager.listBackups(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbarMessage.value = "Could not list backups: ${e.message ?: e}"
            }
        }
    }

    /** Back up one slot to app storage, surfacing progress and refreshing the library on done. */
    fun backupSlot(slot: MIDIRepository.ProjectSlot, context: Context) {
        if (_isBackupInProgress.value) return
        viewModelScope.launch {
            _isBackupInProgress.value = true
            _backupProgress.value = 0f
            backupManager.backupProject(slot, context).collect { progress ->
                when (progress) {
                    is ProjectBackupProgress.Progress -> {
                        if (progress.total > 0) {
                            _backupProgress.value = progress.current.toFloat() / progress.total
                        }
                    }
                    is ProjectBackupProgress.Done -> {
                        _isBackupInProgress.value = false
                        _backupProgress.value = 0f
                        _snackbarMessage.value = "Backup complete: ${progress.file.name}"
                        loadBackups(context)
                    }
                    is ProjectBackupProgress.Error -> {
                        _isBackupInProgress.value = false
                        _backupProgress.value = 0f
                        _snackbarMessage.value = "Backup failed: ${progress.message}"
                    }
                }
            }
        }
    }

    /** Stage a restore behind the destructive-action confirmation dialog (T-04-10). */
    fun requestRestore(file: File) {
        if (_isBackupInProgress.value) return
        _pendingRestoreFile = file
        _showRestoreConfirm.value = true
    }

    /** Run the staged restore after the user confirms (gated by [RESTORE_ENABLED]). */
    fun confirmRestore(context: Context) {
        val file = _pendingRestoreFile ?: return
        _showRestoreConfirm.value = false
        _pendingRestoreFile = null
        viewModelScope.launch {
            backupManager.restoreProject(file, context).collect { progress ->
                when (progress) {
                    is ProjectBackupProgress.Progress -> { /* progress surfaced via snackbar terminal states */ }
                    is ProjectBackupProgress.Done ->
                        _snackbarMessage.value = "Restore complete. Your EP-133 will restart."
                    is ProjectBackupProgress.Error ->
                        _snackbarMessage.value = "Restore failed: ${progress.message}"
                }
            }
        }
    }

    fun cancelRestore() {
        _showRestoreConfirm.value = false
        _pendingRestoreFile = null
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }
}

/** Format a backup timestamp (epoch millis) for the library row. */
private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

/**
 * Build and launch the Android share sheet for a backup file via a FileProvider `content://`
 * URI (PROJ-04). Never uses `Uri.fromFile` / `file://` — T-04-09 / Pitfall 4.
 */
private fun shareBackup(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    ShareCompat.IntentBuilder(context)
        .setType(SHARE_MIME)
        .setStream(uri)
        .setChooserTitle("Share EP-133 project backup")
        .startChooser()
}

@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel) {
    val context = LocalContext.current
    val deviceState by viewModel.deviceState.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val showRestoreConfirm by viewModel.showRestoreConfirm.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
        viewModel.loadBackups(context)
    }

    LaunchedEffect(deviceState.connected) {
        if (deviceState.connected) viewModel.loadProjects()
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            title = { Text("Restore project?") },
            text = { Text("This will overwrite the matching slot on your EP-133. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRestore(context) }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRestore() }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Project slots (PROJ-01) ──
            item {
                Text(
                    text = "PROJECTS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!deviceState.connected) {
                item { NotConnectedPanel() }
            } else if (slots.isEmpty()) {
                item {
                    Text(
                        text = "No projects found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(slots, key = { it.nodeId }) { slot ->
                    SlotCard(
                        slot = slot,
                        isBackupInProgress = isBackupInProgress,
                        backupProgress = backupProgress,
                        onBackup = { viewModel.backupSlot(slot, context) },
                    )
                }
            }

            // ── Backup library (PROJ-03) ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "BACKUP LIBRARY",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (backups.isEmpty()) {
                item {
                    Text(
                        text = "No backups yet. Back up a slot to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(backups, key = { it.file.absolutePath }) { backup ->
                    BackupRow(
                        backup = backup,
                        onShare = { shareBackup(context, backup.file) },
                        onRestore = { viewModel.requestRestore(backup.file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotConnectedPanel() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "Connect your EP-133 via USB to browse and back up projects.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SlotCard(
    slot: MIDIRepository.ProjectSlot,
    isBackupInProgress: Boolean,
    backupProgress: Float,
    onBackup: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (slot.isActive) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = "Active project",
                        modifier = Modifier.size(10.dp),
                        tint = TEColors.Teal,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = slot.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (slot.isActive) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = TEColors.Teal,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = projectSummary(slot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onBackup,
                enabled = !isBackupInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Backup")
            }
            if (isBackupInProgress) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { backupProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Lightweight slot summary (RESEARCH Open Q3: v1 = name + active marker + lightweight summary,
 * no full-archive download). Groups A–D + an approximate size, never inspecting the archive.
 */
private fun projectSummary(slot: MIDIRepository.ProjectSlot): String {
    val kb = slot.sizeBytes / 1024
    return if (kb > 0) "Groups A–D · ${kb} KB" else "Groups A–D"
}

@Composable
private fun BackupRow(
    backup: BackupItem,
    onShare: () -> Unit,
    onRestore: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = formatTimestamp(backup.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // HARDWARE-GATE (Open Q2): enable restore button after hardware backup→restore round-trip
            IconButton(onClick = onRestore, enabled = RESTORE_ENABLED) {
                Icon(
                    imageVector = Icons.Filled.Restore,
                    contentDescription = "Restore backup",
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share backup",
                )
            }
        }
    }
}
