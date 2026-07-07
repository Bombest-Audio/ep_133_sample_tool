package com.ep133.sampletool.ui.projects

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.BackupItem
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.midi.ProjectBackupProgress
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.project.InMemoryProjectNameStore
import com.ep133.sampletool.domain.project.ProjectNameStore
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.Ep133StatusDot
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.PanelRadius
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
    private val nameStore: ProjectNameStore = InMemoryProjectNameStore(),
) : ViewModel() {

    val deviceState: StateFlow<DeviceState> = midi.deviceState

    /** Slot number (1..9) → app-side custom project name (see [ProjectNameStore]). */
    val projectNames: StateFlow<Map<Int, String>> = nameStore.names

    /** Set (or clear, when blank) the app-side custom name for a slot. */
    fun setProjectName(slot: MIDIRepository.ProjectSlot, name: String) {
        val n = slotNumberOf(slot) ?: return
        nameStore.setName(n, name)
    }

    private fun slotNumberOf(slot: MIDIRepository.ProjectSlot): Int? =
        projectSlotNumber(slot.name)

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

    // Node id of the slot currently being cleared (null = none), so its card shows a busy state.
    private val _clearingSlot = MutableStateFlow<Int?>(null)
    val clearingSlot: StateFlow<Int?> = _clearingSlot.asStateFlow()

    /** Empty every pad in all four groups of [slot] (the slot itself survives). */
    fun clearProject(slot: MIDIRepository.ProjectSlot) {
        if (_clearingSlot.value != null) return
        _clearingSlot.value = slot.nodeId
        viewModelScope.launch {
            val cleared = midi.clearProject(slot.name)
            _clearingSlot.value = null
            _snackbarMessage.value = if (cleared >= 0) {
                "Cleared project ${slot.name} — $cleared pads emptied"
            } else {
                "Couldn't clear project ${slot.name} — is the EP-133 connected?"
            }
            loadProjects()
        }
    }

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
        val customName = slotNumberOf(slot)?.let { nameStore.nameFor(it) }
        viewModelScope.launch {
            _isBackupInProgress.value = true
            _backupProgress.value = 0f
            backupManager.backupProject(slot, context, customName).collect { progress ->
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

/**
 * Parse a device project-slot number (1..9) from its name. Device names are "01".."09", but
 * mirrors [ProjectBackupManager]'s digit-anywhere match so "P03"-style names still resolve to 3.
 */
internal fun projectSlotNumber(name: String): Int? =
    Regex("""(\d{1,2})""").find(name)?.groupValues?.get(1)?.toIntOrNull()

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

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable — the app shell ([Ep133Scaffold]) owns header + nav; this is the body.
// ─────────────────────────────────────────────────────────────────────────────

private val Mono = FontFamily.Monospace

/** Deterministic 4-bar mini "fill" meter per project/backup — purely decorative, keyed off a seed. */
private fun miniBars(seed: Int): List<Float> =
    (0 until 4).map { i ->
        val v = ((seed * 37 + i * 41) % 60) + 40  // 40..99
        v / 100f
    }

@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel) {
    val t = LocalEP133Tokens.current
    val context = LocalContext.current
    val deviceState by viewModel.deviceState.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val showRestoreConfirm by viewModel.showRestoreConfirm.collectAsState()
    val clearingSlot by viewModel.clearingSlot.collectAsState()
    val projectNames by viewModel.projectNames.collectAsState()
    var slotToClear by remember { mutableStateOf<MIDIRepository.ProjectSlot?>(null) }
    var slotToRename by remember { mutableStateOf<MIDIRepository.ProjectSlot?>(null) }
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

    slotToClear?.let { slot ->
        AlertDialog(
            onDismissRequest = { slotToClear = null },
            containerColor = t.panel,
            titleContentColor = t.text,
            textContentColor = t.text2,
            shape = PanelRadius,
            title = { Text("Clear project ${slot.name}?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Empties every pad in all four groups of project ${slot.name} on the EP-133. " +
                        "The project slot itself stays. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearProject(slot); slotToClear = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
                ) { Text("Clear", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { slotToClear = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
                ) { Text("Cancel") }
            },
        )
    }

    slotToRename?.let { slot ->
        val slotNum = projectSlotNumber(slot.name)
        var draft by remember(slot.nodeId) {
            mutableStateOf(slotNum?.let { projectNames[it] }.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { slotToRename = null },
            containerColor = t.panel,
            titleContentColor = t.text,
            textContentColor = t.text2,
            shape = PanelRadius,
            title = { Text("Name project ${slot.name}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The name lives in the app and is used as the export/backup filename. " +
                            "The device slot number (${slot.name}) doesn't change.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(40) },
                        singleLine = true,
                        placeholder = { Text("e.g. Summer Beat Tape") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.setProjectName(slot, draft); slotToRename = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { slotToRename = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
                ) { Text("Cancel") }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            modifier = Modifier.testTag(TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG),
            containerColor = t.panel,
            titleContentColor = t.text,
            textContentColor = t.text2,
            shape = PanelRadius,
            title = { Text("Restore project?", fontWeight = FontWeight.Bold) },
            text = { Text("This will overwrite the matching slot on your EP-133. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRestore(context) },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
                ) { Text("Restore", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelRestore() },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
                ) { Text("Cancel") }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(t.bg)) {
        LazyColumn(
            modifier = Modifier
                .testTag(TestTags.PROJECTS_SLOT_LIST)
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // ── Project slots (PROJ-01) ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ep133SectionLabel("Projects", modifier = Modifier.weight(1f))
                    Text(
                        text = if (deviceState.connected) "${slots.size} SLOTS" else "OFFLINE",
                        color = if (deviceState.connected) t.text3 else t.accent,
                        fontFamily = Mono,
                        fontSize = 9.5.sp,
                        letterSpacing = 0.6.sp,
                    )
                }
            }

            if (!deviceState.connected) {
                item { NotConnectedPanel() }
            } else if (slots.isEmpty()) {
                item { EmptyNote("No projects found.") }
            } else {
                items(slots, key = { it.nodeId }) { slot ->
                    SlotCard(
                        slot = slot,
                        customName = projectSlotNumber(slot.name)?.let { projectNames[it] },
                        isBackupInProgress = isBackupInProgress,
                        backupProgress = backupProgress,
                        isClearing = clearingSlot == slot.nodeId,
                        onBackup = { viewModel.backupSlot(slot, context) },
                        onClear = { slotToClear = slot },
                        onRename = { slotToRename = slot },
                    )
                }
            }

            // ── Backup library (PROJ-03) ──
            item {
                Ep133SectionLabel(
                    "Backup Library",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                )
            }

            if (backups.isEmpty()) {
                item { EmptyNote("No backups yet. Back up a slot to add one.") }
            } else {
                items(backups, key = { it.file.absolutePath }) { backup ->
                    BackupCard(
                        backup = backup,
                        onShare = { shareBackup(context, backup.file) },
                        onRestore = { viewModel.requestRestore(backup.file) },
                    )
                }
            }

            // ── Restore-gated note (RESTORE_ENABLED) ──
            item { RestoreGateNote() }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 70.dp),
        )
    }
}

/** Small mono note on an inset panel — used for empty states. */
@Composable
private fun EmptyNote(text: String) {
    val t = LocalEP133Tokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.inset, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(14.dp),
    ) {
        Text(
            text = text,
            color = t.text3,
            fontFamily = Mono,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp,
        )
    }
}

/** "Connect EP-133" inset panel shown when no device is attached. */
@Composable
private fun NotConnectedPanel() {
    val t = LocalEP133Tokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.inset, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Usb,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = t.text3,
        )
        Text(
            text = "Connect your EP-133 via USB to browse and back up projects.",
            color = t.text2,
            fontFamily = Mono,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A device project slot — faceplate card: name + node id, active dot/badge, a lightweight
 * groups/size summary as mono, a decorative fill meter, and a Backup action.
 */
@Composable
private fun SlotCard(
    slot: MIDIRepository.ProjectSlot,
    customName: String?,
    isBackupInProgress: Boolean,
    backupProgress: Float,
    isClearing: Boolean,
    onBackup: () -> Unit,
    onClear: () -> Unit,
    onRename: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = Modifier
            .testTag(TestTags.projectSlot(slot.nodeId))
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, if (slot.isActive) t.live else t.rule, PanelRadius)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Name + active badge + slot tag + rename affordance.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (slot.isActive) {
                Ep133StatusDot(t.live, size = 7)
            }
            Text(
                text = customName?.takeIf { it.isNotBlank() } ?: "PROJECT ${slot.name}",
                modifier = Modifier.weight(1f),
                color = t.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Device slot tag — shown when a custom name replaces the raw slot number in the title.
            if (!customName.isNullOrBlank()) {
                Text(
                    text = "P${slot.name}",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                )
            }
            if (slot.isActive) {
                Text(
                    text = "ACTIVE",
                    color = t.live,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
            }
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Rename project ${slot.name}",
                modifier = Modifier
                    .size(26.dp)
                    .clip(PanelRadius)
                    .clickable(enabled = !isClearing) { onRename() }
                    .padding(5.dp),
                tint = t.text3,
            )
        }

        // Lightweight summary (RESEARCH Open Q3 — name + summary, no archive inspection).
        Text(
            text = projectSummary(slot),
            color = t.text3,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 0.3.sp,
        )

        // Decorative 4-bar fill meter, keyed off the slot node id.
        BarMeter(seed = slot.nodeId)

        // Backup + Clear actions — outline buttons; backup converts to a progress strip while running.
        if (isBackupInProgress) {
            ProgressStrip(progress = backupProgress)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(PanelRadius)
                        .border(1.dp, t.rule, PanelRadius)
                        .clickable(enabled = !isClearing) { onBackup() }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = t.accent,
                    )
                    Text(
                        text = "BACKUP",
                        color = t.accent,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(PanelRadius)
                        .border(1.dp, t.rule, PanelRadius)
                        .clickable(enabled = !isClearing) { onClear() }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isClearing) "CLEARING…" else "CLEAR",
                        color = t.text2,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                }
            }
        }
    }
}

/**
 * A saved backup — faceplate card: name + restore tag, timestamp · size as mono, decorative bar
 * meter, and share / restore (restore stays disabled until [RESTORE_ENABLED]).
 */
@Composable
private fun BackupCard(
    backup: BackupItem,
    onShare: () -> Unit,
    onRestore: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = Modifier
            .testTag(TestTags.backupCard(backup.name))
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = backup.name,
                modifier = Modifier.weight(1f),
                color = t.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "BACKUP",
                color = t.accent,
                fontFamily = Mono,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }

        Text(
            text = formatTimestamp(backup.timestamp),
            color = t.text3,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 0.3.sp,
        )

        BarMeter(seed = backup.name.hashCode())

        // Actions — share (enabled) + restore (gated/disabled until hardware round-trip passes).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ActionButton(
                label = "SHARE",
                icon = Icons.Filled.Share,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onShare,
            )
            ActionButton(
                label = if (RESTORE_ENABLED) "RESTORE" else "RESTORE · SOON",
                icon = Icons.Filled.Restore,
                enabled = RESTORE_ENABLED,
                modifier = Modifier.weight(1f),
                onClick = onRestore,
            )
        }
    }
}

/** Outline action button — mono label + leading icon; dims its content when disabled. */
@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val content = if (enabled) t.text else t.text3.copy(alpha = 0.5f)
    Row(
        modifier = modifier
            .clip(PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = content,
        )
        Text(
            text = label,
            color = content,
            fontFamily = Mono,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Lightweight slot summary (RESEARCH Open Q3: v1 = name + active marker + lightweight summary,
 * no full-archive download). Groups A–D + an approximate size, never inspecting the archive.
 */
private fun projectSummary(slot: MIDIRepository.ProjectSlot): String {
    val kb = slot.sizeBytes / 1024
    return if (kb > 0) "GROUPS A–D · $kb KB" else "GROUPS A–D"
}

/** Decorative 4-bar fill meter drawn from a deterministic seed. */
@Composable
private fun BarMeter(seed: Int) {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        miniBars(seed).forEach { frac ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(t.inset),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(t.accent.copy(alpha = 0.55f)),
                )
            }
        }
    }
}

/** Determinate 12-cell paged progress strip — shown in place of the Backup button while running. */
@Composable
private fun ProgressStrip(progress: Float) {
    val t = LocalEP133Tokens.current
    val filled = (progress * 12).toInt().coerceIn(0, 12)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(12) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < filled) t.accent else t.inset),
            )
        }
    }
}

/** The restore-gated explainer — inset panel with an accent left rail and a mono "!" marker. */
@Composable
private fun RestoreGateNote() {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.inset, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .accentRail(t.accent)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = "!",
            color = t.accent,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "restore is coming.",
                color = t.text,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "it's gated until we finish hardware testing — we won't ship a feature that " +
                    "can brick your unit.",
                color = t.text2,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/** A 3dp colored left rail (the design's `border-left:3px solid`), drawn inside the rounded clip. */
private fun Modifier.accentRail(color: Color): Modifier = this.drawBehind {
    drawRect(color = color, size = Size(3.dp.toPx(), size.height))
}
