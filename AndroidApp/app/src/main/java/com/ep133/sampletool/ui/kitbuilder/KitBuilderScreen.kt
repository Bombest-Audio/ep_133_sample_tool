package com.ep133.sampletool.ui.kitbuilder

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.PadUploadResult
import com.ep133.sampletool.domain.PadUploadService
import com.ep133.sampletool.domain.SliceUpload
import com.ep133.sampletool.domain.midi.BatchImportEvent
import com.ep133.sampletool.domain.midi.BatchImportItem
import com.ep133.sampletool.domain.midi.BatchPackImporter
import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.midi.SamplePrep
import com.ep133.sampletool.domain.midi.SamplePrepOptions
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.staging.SampleStagingStore
import com.ep133.sampletool.domain.staging.StagedSample
import java.io.File
import com.ep133.sampletool.domain.pack.KitPack
import com.ep133.sampletool.domain.pack.KitSample
import com.ep133.sampletool.domain.pack.SamplePackLoader
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.theme.Ep133ConfirmDialog
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.Mono
import com.ep133.sampletool.ui.theme.PadEmptyInk
import com.ep133.sampletool.ui.theme.PadFilledInk
import com.ep133.sampletool.ui.theme.PanelRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "EP133APP"

/** Pads per group — the kit canvas is the full 4×3 grid. */
const val KB_PAD_COUNT = 12

/**
 * The pad cluster in visual order (top→bottom, left→right). The visual index IS the device
 * gridIndex used by [MIDIRepository.assignSampleToPad] (pad node = "%02d".format(index + 1)).
 */
private val KB_PAD_LABELS = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", ".", "0", "ENT")

/**
 * Visual index → fill-order rank 1..12 (`. 0 ENT 1 2 3 4 5 6 7 8 9`). Used by auto-advance to
 * walk to the next empty pad in the EP-133's natural numeric order.
 */
private val KB_FILL_RANK = intArrayOf(10, 11, 12, 7, 8, 9, 4, 5, 6, 1, 2, 3)

/** Per-pad load status while a kit upload runs. */
enum class KbLoadState { Idle, Uploading, Done, Error }

/** Outcome of one file in a pack batch import: [ok] with an optional error [message]. */
data class KbImportResult(val name: String, val ok: Boolean, val message: String? = null)

/**
 * State of the pack batch import panel. [running] while the batch job is alive; [finished]
 * once it completed (or was blocked/cancelled) so the panel shows results until dismissed.
 * [processed] counts terminal per-file outcomes (done + failed) out of [total].
 */
data class KbImportState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val currentName: String? = null,
    val phase: String? = null,          // "CONVERTING" or "UPLOADING"
    val results: List<KbImportResult> = emptyList(),
    val blocked: String? = null,
) {
    val active: Boolean get() = running || finished
}

/**
 * Per-group Kit Builder working state: each A/B/C/D group keeps its own staged kit, pad selection,
 * upload progress, and device mirror. Switching groups never discards a staged kit.
 */
data class KbGroupState(
    val selectedPad: Int = 9,                       // visual idx of '.' — first pad in fill order
    val assignments: Map<Int, KitSample> = emptyMap(),
    val loading: Boolean = false,
    val loadStates: Map<Int, KbLoadState> = emptyMap(),
    val loadedBanner: String? = null,
    val clearingPad: Int? = null,
    // gridIndex → sample name currently bound on the DEVICE for this group (read back from
    // hardware). Distinct from [assignments], which are staged locally but not yet uploaded.
    val devicePads: Map<Int, String> = emptyMap(),
)

/**
 * Flattened UI state the Kit Builder screen renders: the pack browser globals plus the SELECTED
 * group's [KbGroupState], with group + choke sourced from the shared [GroupSession].
 */
data class KitBuilderState(
    val pack: KitPack? = null,
    val packLoading: Boolean = false,
    val category: String? = null,
    val auditioningUri: Uri? = null,
    val group: PadChannel = PadChannel.A,
    val chokeGroup: Boolean = true,
    val selectedPad: Int = 9,
    val assignments: Map<Int, KitSample> = emptyMap(),
    val loading: Boolean = false,
    val loadStates: Map<Int, KbLoadState> = emptyMap(),
    val loadedBanner: String? = null,
    val clearingPad: Int? = null,
    val devicePads: Map<Int, String> = emptyMap(),
    val selectedForImport: Set<Uri> = emptySet(),
    val importState: KbImportState = KbImportState(),
    val prep: SamplePrepOptions = SamplePrepOptions(),
    val staged: List<StagedSample> = emptyList(),
    val stagedPanelVisible: Boolean = false,
    val auditioningStagedName: String? = null,
)

/** The pack-browser globals (shared across groups — a pack is a browsing resource, not kit state). */
private data class KbGlobals(
    val pack: KitPack? = null,
    val packLoading: Boolean = false,
    val category: String? = null,
    val auditioningUri: Uri? = null,
    val selectedForImport: Set<Uri> = emptySet(),
    val importState: KbImportState = KbImportState(),
    val prep: SamplePrepOptions = SamplePrepOptions(),
    val staged: List<StagedSample> = emptyList(),
    val stagedPanelVisible: Boolean = false,
    val auditioningStagedName: String? = null,
)

class KitBuilderViewModel(
    private val midi: MIDIRepository,
    private val manager: SampleImportManager,
    private val session: GroupSession = GroupSession(),
    private val importer: BatchPackImporter = BatchPackImporter(midi, manager),
) : ViewModel() {

    private val _globals = MutableStateFlow(KbGlobals())
    private val _groups = MutableStateFlow(PadChannel.entries.associateWith { KbGroupState() })

    /** Flattened state for the UI: globals + the selected group's state + session group/choke. */
    val state: StateFlow<KitBuilderState> =
        combine(_globals, _groups, session.selected, session.choke) { g, groups, sel, choke ->
            val gs = groups.getValue(sel)
            KitBuilderState(
                pack = g.pack, packLoading = g.packLoading, category = g.category,
                auditioningUri = g.auditioningUri,
                group = sel, chokeGroup = choke.getValue(sel),
                selectedPad = gs.selectedPad, assignments = gs.assignments,
                loading = gs.loading, loadStates = gs.loadStates, loadedBanner = gs.loadedBanner,
                clearingPad = gs.clearingPad, devicePads = gs.devicePads,
                selectedForImport = g.selectedForImport, importState = g.importState,
                prep = g.prep, staged = g.staged, stagedPanelVisible = g.stagedPanelVisible,
                auditioningStagedName = g.auditioningStagedName,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, KitBuilderState())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /** SAF callback — set by MainActivity; launches the OpenDocumentTree picker. */
    var onRequestPackPick: (() -> Unit)? = null

    private val deviceMutex = Mutex()
    private val uploads = PadUploadService(midi, deviceMutex)
    private var player: MediaPlayer? = null
    private var importJob: Job? = null

    private fun updateGroup(g: PadChannel, block: (KbGroupState) -> KbGroupState) {
        _groups.update { it + (g to block(it.getValue(g))) }
    }
    private fun updateCurrent(block: (KbGroupState) -> KbGroupState) =
        updateGroup(session.selected.value, block)

    init {
        // Hydrate the canvas from the device whenever a unit connects (and clear the mirrors on
        // unplug), so the pads reflect what's actually loaded instead of a blank staging grid.
        viewModelScope.launch {
            midi.deviceState
                .map { it.connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) refreshDevicePads()
                    else _groups.update { m -> m.mapValues { (_, gs) -> gs.copy(devicePads = emptyMap()) } }
                }
        }
        // Re-read whenever the shared selection moves to a group we haven't mirrored yet.
        viewModelScope.launch {
            session.selected.collect { refreshDevicePads(it) }
        }
    }

    fun dismissSnackbar() { _snackbarMessage.value = null }
    fun dismissBanner() = updateCurrent { it.copy(loadedBanner = null) }

    fun triggerPackPick() { onRequestPackPick?.invoke() }

    /**
     * Read [group]'s current pad bindings off the device and mirror them on that group's canvas.
     * Results are keyed by the group they were read for, so a slow read can never land on the
     * wrong group even if the selection moves mid-flight.
     */
    fun refreshDevicePads(group: PadChannel = session.selected.value) {
        viewModelScope.launch {
            val pads = midi.readGroupPadState(group)
            updateGroup(group) { it.copy(devicePads = pads) }
        }
    }

    /** SAF OpenDocumentTree callback: parse the pack folder into categories. */
    fun onPackPicked(treeUri: Uri, context: Context) {
        _globals.update { it.copy(packLoading = true) }
        viewModelScope.launch {
            val pack = try {
                SamplePackLoader.load(context, treeUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitBuilder: pack load failed", e)
                _snackbarMessage.value = "Couldn't read pack: ${e.message ?: e}"
                _globals.update { it.copy(packLoading = false) }
                return@launch
            }
            applyLoadedPack(pack)
        }
    }

    /** Testability seam: apply an already-parsed pack directly, bypassing SAF + SamplePackLoader. */
    fun loadPack(pack: KitPack) = applyLoadedPack(pack)

    private fun applyLoadedPack(pack: KitPack) {
        if (pack.isEmpty) {
            _snackbarMessage.value = "No audio files found in that folder"
            _globals.update { it.copy(packLoading = false) }
            return
        }
        _globals.update {
            it.copy(pack = pack, packLoading = false, category = pack.categories.first().id,
                selectedForImport = emptySet())
        }
        updateCurrent { it.copy(loadStates = emptyMap(), loadedBanner = null) }
    }

    fun onCategoryChange(id: String) = _globals.update { it.copy(category = id) }

    /** Switch the shared group selection (device re-read happens via the session observer). */
    fun onGroupChange(group: PadChannel) = session.select(group)

    fun onChokeGroupChange(on: Boolean) = session.setChoke(session.selected.value, on)
    fun onPadSelected(index: Int) = updateCurrent { it.copy(selectedPad = index) }

    /**
     * Clear the selected pad ON THE DEVICE (write `{"sym":0}`), then drop it from the local canvas.
     * The device may hold a sample the canvas never showed (it's a staging view, not a live mirror),
     * so this always targets the hardware pad rather than the local map.
     */
    fun onClearPad() {
        val group = session.selected.value
        val pad = _groups.value.getValue(group).selectedPad
        updateGroup(group) { it.copy(clearingPad = pad) }
        viewModelScope.launch {
            val ok = deviceMutex.withLock { midi.clearPad(group, pad) }
            updateGroup(group) {
                it.copy(
                    clearingPad = null,
                    assignments = if (ok) it.assignments - pad else it.assignments,
                )
            }
            _snackbarMessage.value = if (ok) {
                "Cleared pad ${KB_PAD_LABELS[pad]} on group ${group.name}"
            } else {
                "Couldn't clear pad ${KB_PAD_LABELS[pad]} — is the EP-133 connected?"
            }
            if (ok) refreshDevicePads(group)
        }
    }

    /** Assign [sample] to the selected pad, then auto-advance to the next empty pad in fill order. */
    fun onAssign(sample: KitSample) = updateCurrent { s ->
        val assignments = s.assignments + (s.selectedPad to sample)
        var next = s.selectedPad
        val curRank = KB_FILL_RANK[s.selectedPad]
        for (step in 1..KB_PAD_COUNT) {
            val rank = ((curRank - 1 + step) % KB_PAD_COUNT) + 1
            val idx = KB_FILL_RANK.indexOf(rank)
            if (!assignments.containsKey(idx)) { next = idx; break }
        }
        s.copy(assignments = assignments, selectedPad = next, loadStates = emptyMap(), loadedBanner = null)
    }

    /**
     * Toggle audition playback of [sample] via MediaPlayer (local preview, not the device).
     *
     * With no prep toggles on this plays the source URI directly. With prep enabled it
     * previews the PROCESSED result: the sample is converted, prepped, staged as a local
     * WAV (see [SampleStagingStore]), and that staged file is played — what you hear is
     * exactly what an import would upload. The source file is never modified.
     */
    fun onAudition(sample: KitSample, context: Context) {
        val cur = _globals.value.auditioningUri
        stopAudition()
        if (cur == sample.uri) return   // tapped the playing row's button → just stop

        val prep = _globals.value.prep
        if (!prep.enabled) {
            playSource(sample, context)
            return
        }

        // Prepped preview: convert + prep + stage off the main thread, then play the staged WAV.
        _globals.update { it.copy(auditioningUri = sample.uri) }
        viewModelScope.launch {
            val staged = try {
                withContext(Dispatchers.IO) {
                    val converted = manager.convert(context, sample.uri, enforceMaxLength = false)
                    val prepped = SamplePrep.apply(converted, prep)
                    val name = manager.sanitizeName(sample.name + ".wav") ?: "sample.wav"
                    store(context).stage(name, prepped)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitBuilder: prep preview failed for ${sample.name}", e)
                _snackbarMessage.value = "Can't preview ${sample.name}: ${e.message ?: e}"
                stopAudition()
                return@launch
            }
            refreshStaged(context)
            // Only start playback if this row is still the one being auditioned.
            if (_globals.value.auditioningUri == sample.uri) {
                playFile(staged.file) { _globals.update { it.copy(auditioningUri = sample.uri) } }
            }
        }
    }

    /** Play [sample]'s source URI (the untouched pack file). */
    private fun playSource(sample: KitSample, context: Context) {
        try {
            player = MediaPlayer().apply {
                setDataSource(context, sample.uri)
                setOnCompletionListener { stopAudition() }
                setOnErrorListener { _, _, _ -> stopAudition(); true }
                // prepare() blocks — SAF documents can be slow (cloud-backed providers), so
                // prepare off the main thread and start from the callback.
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            _globals.update { it.copy(auditioningUri = sample.uri) }
        } catch (e: Exception) {
            Log.e(TAG, "KitBuilder: audition failed for ${sample.name}", e)
            _snackbarMessage.value = "Can't play ${sample.name}"
            stopAudition()
        }
    }

    /** Play a local file via MediaPlayer; [onStarted] updates the playing indicator. */
    private fun playFile(file: File, onStarted: () -> Unit) {
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopAudition() }
                setOnErrorListener { _, _, _ -> stopAudition(); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            onStarted()
        } catch (e: Exception) {
            Log.e(TAG, "KitBuilder: file audition failed for ${file.name}", e)
            _snackbarMessage.value = "Can't play ${file.name}"
            stopAudition()
        }
    }

    private fun stopAudition() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        _globals.update { it.copy(auditioningUri = null, auditioningStagedName = null) }
    }

    // ── Prep toggles + staged-sample management (issue #52: prep before upload) ──

    /** Staging area in app-private storage; prepped copies live here, never the sources. */
    private fun store(context: Context) =
        SampleStagingStore(File(context.filesDir, "staged_samples"))

    fun onTogglePrepNormalize() = updatePrep { it.copy(normalize = !it.normalize) }
    fun onTogglePrepTrimSilence() = updatePrep { it.copy(trimSilence = !it.trimSilence) }
    fun onTogglePrepMono() = updatePrep { it.copy(toMono = !it.toMono) }

    private fun updatePrep(block: (SamplePrepOptions) -> SamplePrepOptions) =
        _globals.update { it.copy(prep = block(it.prep)) }

    /** Show/hide the staged-samples panel (refreshes the list when opening). */
    fun onToggleStagedPanel(context: Context) {
        val show = !_globals.value.stagedPanelVisible
        _globals.update { it.copy(stagedPanelVisible = show) }
        if (show) refreshStaged(context)
    }

    /** Re-read the staging directory into state. */
    fun refreshStaged(context: Context) {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { store(context).list() }
            _globals.update { it.copy(staged = items) }
        }
    }

    /** Toggle audition of a staged (already processed) WAV. */
    fun onAuditionStaged(item: StagedSample) {
        val cur = _globals.value.auditioningStagedName
        stopAudition()
        if (cur == item.name) return
        playFile(item.file) { _globals.update { it.copy(auditioningStagedName = item.name) } }
    }

    /** Rename a staged sample. [rawTo] is sanitized before it touches the filesystem. */
    fun onRenameStaged(from: String, rawTo: String, context: Context) {
        val to = manager.sanitizeName(rawTo)
        if (to == null) {
            _snackbarMessage.value = "Invalid name: $rawTo"
            return
        }
        stagingOp(context, "Couldn't rename $from") {
            requireNotNull(store(context).rename(from, to)) { "name already staged or file missing" }
        }
    }

    /** Delete a staged sample (the local processed copy only — never the source file). */
    fun onDeleteStaged(name: String, context: Context) =
        stagingOp(context, "Couldn't delete $name") {
            require(store(context).delete(name)) { "file missing" }
        }

    /** Duplicate a staged sample as "<name> copy.wav". */
    fun onDuplicateStaged(name: String, context: Context) =
        stagingOp(context, "Couldn't duplicate $name") {
            requireNotNull(store(context).duplicate(name)) { "file missing" }
        }

    /** Run a staging file op on IO, surface failures as a snackbar, then refresh the list. */
    private fun stagingOp(context: Context, failureLabel: String, op: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { op() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitBuilder: staging op failed", e)
                _snackbarMessage.value = "$failureLabel: ${e.message ?: e}"
            }
            refreshStaged(context)
        }
    }

    /**
     * Upload every staged sample and bind it to its pad on the group that was selected when the
     * push started (captured up front — switching groups mid-push must not redirect writes).
     * Serial (device transfers are single-in-flight); per-pad status drives that group's canvas.
     * Pads are written with the group's choke setting as `sound.mutegroup`.
     */
    fun onLoadKit(context: Context) {
        val group = session.selected.value
        val chokeOn = session.chokeFor(group)
        val gs = _groups.value.getValue(group)
        if (gs.loading || gs.assignments.isEmpty()) return
        val entries = gs.assignments.toSortedMap(compareBy { KB_FILL_RANK[it] })
        updateGroup(group) {
            it.copy(loading = true, loadedBanner = null,
                loadStates = entries.keys.associateWith { KbLoadState.Idle })
        }

        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for ((padIdx, sample) in entries) {
                updateGroup(group) { it.copy(loadStates = it.loadStates + (padIdx to KbLoadState.Uploading)) }
                val success = try {
                    val converted = withContext(Dispatchers.IO) { manager.convert(context, sample.uri) }
                    val frames = converted.pcm.size / 2 / converted.channels
                    val safeName = (manager.sanitizeName(sample.name + ".wav") ?: "sample.wav")
                    val upload = SliceUpload(safeName, converted.pcm, converted.channels, converted.sampleRate, frames)
                    when (val r = uploads.uploadAndAssign(group, padIdx, upload, chokeOn)) {
                        PadUploadResult.Done -> true
                        is PadUploadResult.UploadFailed -> { reportLoadFailure(sample, r.error); false }
                        is PadUploadResult.AssignFailed -> { reportLoadFailure(sample, r.error); false }
                        // UploadRejected / AssignRejected: device said no without an error — no snackbar.
                        else -> false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportLoadFailure(sample, e)
                    false
                }
                if (success) ok++ else failed++
                updateGroup(group) {
                    it.copy(loadStates = it.loadStates +
                        (padIdx to if (success) KbLoadState.Done else KbLoadState.Error))
                }
            }
            updateGroup(group) {
                it.copy(loading = false,
                    loadedBanner = if (failed == 0) "KIT LOADED → GROUP ${group.name} · $ok pads written"
                    else "$failed FAILED · $ok loaded → GROUP ${group.name}")
            }
            // The just-written pads are now on the device — re-read so the canvas reflects them.
            refreshDevicePads(group)
        }
    }

    // ── Pack batch import (Phase 11): multi-select + IMPORT ALL / IMPORT SELECTED ──

    /**
     * Toggle a sample's membership in the batch-import selection. No-op while a batch is
     * running: the selection was snapshotted into the batch at launch, and mutating it
     * mid-run would desync the panel's results from what IMPORT SELECTED actually sent.
     */
    fun onToggleImportSelect(sample: KitSample) = _globals.update {
        if (it.importState.running) return@update it
        val sel = if (sample.uri in it.selectedForImport) it.selectedForImport - sample.uri
        else it.selectedForImport + sample.uri
        it.copy(selectedForImport = sel)
    }

    /** Import every sample in the loaded pack (all categories) to /sounds. */
    fun onImportAll(context: Context) {
        val pack = _globals.value.pack ?: return
        importPack(pack.categories.flatMap { it.samples }, context)
    }

    /** Import only the multi-selected samples to /sounds. */
    fun onImportSelected(context: Context) {
        val g = _globals.value
        val pack = g.pack ?: return
        val samples = pack.categories.flatMap { it.samples }.filter { it.uri in g.selectedForImport }
        importPack(samples, context)
    }

    private fun importPack(samples: List<KitSample>, context: Context) {
        val items = samples.map { BatchImportItem("${it.name}.wav", it.uri) }
        importPack(items) {
            manager.convert(context, requireNotNull(it.uri), enforceMaxLength = true)
        }
    }

    /**
     * Drive a batch import and mirror its events into [KbImportState].
     *
     * Testability seam: the [convert] lambda lets unit tests run the real
     * [BatchPackImporter] state machine with canned [ConvertedSample]s (no SAF/AudioDecoder).
     *
     * Guarded triggers: no-ops while a batch is already [KbImportState.running], on an empty
     * item list, and (fail-fast, mirroring the importer's own guard) when no device is
     * connected. `running` is reset in a `finally` so cancellation and failure both restore
     * the buttons.
     *
     * The active [SamplePrepOptions] are snapshotted at launch and applied to every
     * converted sample (post-decode, pre-upload) — toggling prep mid-batch doesn't
     * change what an in-flight batch uploads. Defaults-off prep is a no-op passthrough.
     */
    internal fun importPack(
        items: List<BatchImportItem>,
        convert: suspend (BatchImportItem) -> ConvertedSample,
    ) {
        if (_globals.value.importState.running || items.isEmpty()) return
        if (midi.deviceState.value.outputPortId == null) {
            _snackbarMessage.value = "Connect the EP-133 before importing"
            return
        }
        val prep = _globals.value.prep
        _globals.update {
            it.copy(importState = KbImportState(running = true, total = items.size))
        }
        importJob = viewModelScope.launch {
            try {
                importer.import(items) { SamplePrep.apply(convert(it), prep) }.collect { event ->
                    updateImport { st ->
                        when (event) {
                            is BatchImportEvent.Blocked ->
                                st.copy(blocked = event.message)
                            is BatchImportEvent.Converting ->
                                st.copy(currentName = event.name, phase = "CONVERTING")
                            is BatchImportEvent.Uploading ->
                                st.copy(currentName = event.name, phase = "UPLOADING")
                            is BatchImportEvent.FileDone ->
                                st.copy(processed = st.processed + 1,
                                    results = st.results + KbImportResult(event.name, ok = true))
                            is BatchImportEvent.FileFailed ->
                                st.copy(processed = st.processed + 1,
                                    results = st.results +
                                        KbImportResult(event.name, ok = false, message = event.message))
                            is BatchImportEvent.BatchComplete ->
                                st.copy(currentName = null, phase = null)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitBuilder: pack import failed", e)
                updateImport { it.copy(blocked = "Import failed: ${e.message ?: e}") }
            } finally {
                // Runs on success, failure, AND cancellation: restore the trigger and keep the
                // panel up (finished) so partial results stay visible until dismissed.
                updateImport { it.copy(running = false, finished = true, currentName = null, phase = null) }
            }
        }
    }

    /** Cancel the in-flight batch. FTC's terminator unwind closes any in-flight PUT cleanly. */
    fun onCancelImport() { importJob?.cancel() }

    /** Dismiss the import results panel and clear the selection that was imported. */
    fun dismissImportPanel() = _globals.update {
        it.copy(importState = KbImportState(), selectedForImport = emptySet())
    }

    private fun updateImport(block: (KbImportState) -> KbImportState) =
        _globals.update { it.copy(importState = block(it.importState)) }

    /** Log a per-sample load failure and surface it as a snackbar (device errors + convert errors). */
    private fun reportLoadFailure(sample: KitSample, error: Throwable) {
        Log.e(TAG, "KitBuilder: load failed for ${sample.name}", error)
        _snackbarMessage.value = "${sample.name}: ${error.message ?: "failed"}"
    }

    override fun onCleared() {
        stopAudition()
        super.onCleared()
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────────


/**
 * Kit Builder (implements the "Kit Builder" design): pick a pack folder, browse categories,
 * audition one-shots, assign them pad-first onto the 4×3 canvas, and LOAD the kit to a group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KitBuilderScreen(viewModel: KitBuilderViewModel, modifier: Modifier = Modifier.fillMaxSize()) {
    val t = LocalEP133Tokens.current
    val s by viewModel.state.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { snackbarHostState.showSnackbar(it); viewModel.dismissSnackbar() }
    }

    // Re-read the device pads whenever the Kit Builder becomes visible, so a kit loaded/edited
    // elsewhere (or before this screen opened) shows up on the canvas.
    LaunchedEffect(Unit) { viewModel.refreshDevicePads() }

    // Clear-pad confirmation. Always available: the pad may hold a sample on the DEVICE that the
    // canvas (a staging view) never showed, so clearing writes {"sym":0} to the hardware pad.
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        val padLabel = KB_PAD_LABELS[s.selectedPad]
        val padSampleName = s.assignments[s.selectedPad]?.name ?: s.devicePads[s.selectedPad]
        Ep133ConfirmDialog(
            title = "Clear pad $padLabel?",
            message = if (padSampleName != null) {
                "Removes \"$padSampleName\" from pad $padLabel of group ${s.group.name} on the EP-133."
            } else {
                "Unbinds whatever sample is on pad $padLabel of group ${s.group.name} on the EP-133."
            },
            confirmLabel = "Clear",
            onConfirm = { viewModel.onClearPad(); confirmClear = false },
            onDismiss = { confirmClear = false },
            modifier = Modifier.testTag(TestTags.KB_CLEAR_CONFIRM_DIALOG),
        )
    }

    Box(modifier.background(t.bg)) {
        Column(Modifier.fillMaxSize()) {
          // One lazy scroll surface for canvas + tabs + samples: Compose forbids nesting a lazy
          // list inside a scrollable column, so the whole page IS the list — the canvas scrolls
          // away and the sample browser gets the full height. Category tabs pin as a sticky header.
          LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(TestTags.KB_SAMPLE_LIST)) {

            // ── Kit canvas ──
            item {
                KbKitCanvas(
                    state = s,
                    onClearPad = { confirmClear = true },
                    onPadSelected = viewModel::onPadSelected,
                )
            }

            // ── Category tabs — sticky so the browser keeps context while the samples scroll ──
            val pack = s.pack
            if (pack != null) {
                stickyHeader {
                    KbCategoryTabs(
                        pack = pack,
                        selectedCategory = s.category,
                        onCategoryChange = viewModel::onCategoryChange,
                    )
                }

                // ── Sample list — flattened into the page-level LazyColumn ──
                val samples = pack.categories.firstOrNull { it.id == s.category }?.samples.orEmpty()
                val uriToPad = s.assignments.entries.associate { (idx, smp) -> smp.uri to KB_PAD_LABELS[idx] }
                    item {
                        Row(Modifier.fillMaxWidth().background(t.panel).padding(14.dp, 8.dp, 14.dp, 4.dp)) {
                            Text("${s.category} · ${samples.size}", fontFamily = Mono, fontSize = 9.sp,
                                letterSpacing = 1.2.sp, color = t.text3, modifier = Modifier.weight(1f))
                            Text("TAP ROW → PAD ${KB_PAD_LABELS[s.selectedPad]}", fontFamily = Mono,
                                fontSize = 9.sp, letterSpacing = 0.8.sp, color = t.text3)
                        }
                    }
                    items(samples, key = { it.uri }) { sample ->
                        KbSampleRow(
                            sample = sample,
                            playing = s.auditioningUri == sample.uri,
                            padLabel = uriToPad[sample.uri],
                            selectedForImport = sample.uri in s.selectedForImport,
                            onAssign = { viewModel.onAssign(sample) },
                            onAudition = { viewModel.onAudition(sample, context) },
                            onToggleSelect = { viewModel.onToggleImportSelect(sample) },
                        )
                    }
            } else {
                // Empty state — no pack loaded yet.
                item {
                    KbEmptyState(
                        packLoading = s.packLoading,
                        modifier = Modifier.fillParentMaxHeight(0.5f),
                    )
                }
            }
          }

            // ── Prep bar — per-batch processing toggles + staged-samples panel access ──
            if (s.pack != null) {
                KbPrepBar(
                    prep = s.prep,
                    stagedCount = s.staged.size,
                    onToggleNormalize = viewModel::onTogglePrepNormalize,
                    onToggleTrimSilence = viewModel::onTogglePrepTrimSilence,
                    onToggleMono = viewModel::onTogglePrepMono,
                    onToggleStagedPanel = { viewModel.onToggleStagedPanel(context) },
                )
            }

            // ── Import bar — batch upload of pack one-shots to /sounds (Phase 11) ──
            if (s.pack != null) {
                KbImportBar(
                    selectedCount = s.selectedForImport.size,
                    running = s.importState.running,
                    onImportAll = { viewModel.onImportAll(context) },
                    onImportSelected = { viewModel.onImportSelected(context) },
                )
            }

            // ── Footer — fill meter + pack switcher. GROUP + CHOKE live in the pinned header
            // shared with CHOP; the push action is the shared PUSH TO DEVICE button below. ──
            if (s.pack != null) {
                KbFooter(
                    assignedCount = s.assignments.size,
                    groupName = s.group.name,
                    loading = s.loading,
                    onSwitchPack = viewModel::triggerPackPick,
                )
            }
        }

        // ── Staged-samples panel — local file management for prepped copies ──
        if (s.stagedPanelVisible) {
            KbStagedPanel(
                staged = s.staged,
                auditioningName = s.auditioningStagedName,
                onAudition = viewModel::onAuditionStaged,
                onDuplicate = { viewModel.onDuplicateStaged(it, context) },
                onDelete = { viewModel.onDeleteStaged(it, context) },
                onRename = { from, to -> viewModel.onRenameStaged(from, to, context) },
                onDismiss = { viewModel.onToggleStagedPanel(context) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ── Batch import progress / results panel ──
        if (s.importState.active) {
            KbImportPanel(
                state = s.importState,
                onCancel = viewModel::onCancelImport,
                onDismiss = viewModel::dismissImportPanel,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ── Load banner ──
        s.loadedBanner?.let { banner ->
            KbLoadBanner(
                banner = banner,
                onDismiss = viewModel::dismissBanner,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
    }
}

// ── Kit canvas — pack title + clear-pad control + the 12-pad staging grid ─────
@Composable
private fun KbKitCanvas(
    state: KitBuilderState,
    onClearPad: () -> Unit,
    onPadSelected: (Int) -> Unit,
) {
    val t = LocalEP133Tokens.current
    Column(
        Modifier.fillMaxWidth().background(t.bg).padding(14.dp, 12.dp, 14.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("KIT CANVAS · TAP A PAD", fontFamily = Mono, fontSize = 9.sp,
                    letterSpacing = 1.4.sp, color = t.text3)
                Text(state.pack?.name ?: "no pack loaded", fontFamily = Mono, fontSize = 9.sp,
                    color = t.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val clearing = state.clearingPad != null
            Box(
                Modifier.clip(PanelRadius).background(t.panel, PanelRadius)
                    .border(1.dp, t.rule, PanelRadius)
                    .clickable(enabled = !clearing) { onClearPad() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag(TestTags.KB_CLEAR_PAD_BUTTON),
            ) {
                Text(
                    if (clearing) "CLEARING…" else "⌫ CLEAR PAD ${KB_PAD_LABELS[state.selectedPad]}",
                    fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = t.accentInk,
                )
            }
        }

        KB_PAD_LABELS.chunked(3).forEachIndexed { row, labels ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { col, label ->
                    val idx = row * 3 + col
                    KbPadCell(
                        label = label,
                        sample = state.assignments[idx],
                        deviceName = state.devicePads[idx],
                        selected = idx == state.selectedPad,
                        loadState = state.loadStates[idx],
                        modifier = Modifier.weight(1f).testTag(TestTags.kbPadCell(idx)),
                        onTap = { onPadSelected(idx) },
                    )
                }
            }
        }
    }
}

// ── Category tabs — horizontally scrolling pack-category selector (sticky header) ─
@Composable
private fun KbCategoryTabs(
    pack: KitPack,
    selectedCategory: String?,
    onCategoryChange: (String) -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        Modifier.fillMaxWidth().background(t.panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pack.categories.forEach { cat ->
            val on = cat.id == selectedCategory
            Row(
                Modifier.clip(PanelRadius)
                    .background(if (on) t.accent else t.inset, PanelRadius)
                    .border(1.dp, if (on) t.accent else t.rule, PanelRadius)
                    .clickable { onCategoryChange(cat.id) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag(TestTags.kbCategoryTab(cat.id)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(cat.id, fontFamily = Mono, fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    color = if (on) t.onAccent else t.text2)
                Text("${cat.samples.size}", fontFamily = Mono, fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) t.onAccent.copy(alpha = 0.75f) else t.text3)
            }
        }
    }
}

// ── Sample row — audition toggle + name/meta + pad chip + batch-import select box ──
@Composable
private fun KbSampleRow(
    sample: KitSample,
    playing: Boolean,
    padLabel: String?,
    selectedForImport: Boolean,
    onAssign: () -> Unit,
    onAudition: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (playing) t.inset else t.panel)
            .clickable { onAssign() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(TestTags.kbSampleRow(sample.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (playing) t.live else t.inset)
                .border(1.dp, if (playing) t.live else t.rule, CircleShape)
                .clickable { onAudition() }
                .testTag(TestTags.kbAuditionButton(sample.name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (playing) "■" else "▶", fontSize = 10.sp,
                color = if (playing) t.onAccent else t.text2)
        }
        Column(Modifier.weight(1f)) {
            Text(sample.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sample.meta, fontFamily = Mono, fontSize = 9.sp, color = t.text3)
        }
        if (padLabel != null) {
            Box(
                Modifier.clip(PanelRadius).background(t.live, PanelRadius)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text("◉ $padLabel", fontFamily = Mono, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, color = t.onAccent)
            }
        }
        // Batch-import select box: ✓ when this sample is queued for IMPORT SELECTED.
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (selectedForImport) t.accent else t.inset)
                .border(1.dp, if (selectedForImport) t.accent else t.rule, RoundedCornerShape(5.dp))
                .clickable { onToggleSelect() }
                .testTag(TestTags.kbImportSelect(sample.name)),
            contentAlignment = Alignment.Center,
        ) {
            if (selectedForImport) {
                Text("✓", fontFamily = Mono, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = t.onAccent)
            }
        }
    }
}

// ── Import bar — IMPORT ALL / IMPORT SELECTED actions for the loaded pack ─────
@Composable
private fun KbImportBar(
    selectedCount: Int,
    running: Boolean,
    onImportAll: () -> Unit,
    onImportSelected: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        Modifier.fillMaxWidth().background(t.panel2).padding(14.dp, 8.dp, 14.dp, 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("→ /SOUNDS", fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp,
            color = t.text3, modifier = Modifier.weight(1f))
        KbImportAction(
            label = "IMPORT ALL",
            enabled = !running,
            emphasized = false,
            onClick = onImportAll,
            modifier = Modifier.testTag(TestTags.KB_IMPORT_ALL_BUTTON),
        )
        KbImportAction(
            label = "IMPORT SELECTED ($selectedCount)",
            enabled = !running && selectedCount > 0,
            emphasized = selectedCount > 0,
            onClick = onImportSelected,
            modifier = Modifier.testTag(TestTags.KB_IMPORT_SELECTED_BUTTON),
        )
    }
}

@Composable
private fun KbImportAction(
    label: String,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    Box(
        modifier.clip(PanelRadius)
            .background(if (emphasized && enabled) t.accent else t.panel, PanelRadius)
            .border(1.dp, if (emphasized && enabled) t.accent else t.rule, PanelRadius)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(label, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = when {
                !enabled -> t.text3
                emphasized -> t.onAccent
                else -> t.accentInk
            })
    }
}

// ── Prep bar — batch processing toggles applied before upload/staging ─────────
@Composable
private fun KbPrepBar(
    prep: SamplePrepOptions,
    stagedCount: Int,
    onToggleNormalize: () -> Unit,
    onToggleTrimSilence: () -> Unit,
    onToggleMono: () -> Unit,
    onToggleStagedPanel: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        Modifier.fillMaxWidth().background(t.panel2).padding(14.dp, 8.dp, 14.dp, 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PREP", fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp,
            color = t.text3, modifier = Modifier.weight(1f))
        KbImportAction(
            label = "NORMALIZE",
            enabled = true,
            emphasized = prep.normalize,
            onClick = onToggleNormalize,
            modifier = Modifier.testTag(TestTags.KB_PREP_NORMALIZE_TOGGLE),
        )
        KbImportAction(
            label = "TRIM SILENCE",
            enabled = true,
            emphasized = prep.trimSilence,
            onClick = onToggleTrimSilence,
            modifier = Modifier.testTag(TestTags.KB_PREP_TRIM_TOGGLE),
        )
        KbImportAction(
            label = "MONO",
            enabled = true,
            emphasized = prep.toMono,
            onClick = onToggleMono,
            modifier = Modifier.testTag(TestTags.KB_PREP_MONO_TOGGLE),
        )
        KbImportAction(
            label = "STAGED ($stagedCount)",
            enabled = true,
            emphasized = false,
            onClick = onToggleStagedPanel,
            modifier = Modifier.testTag(TestTags.KB_STAGED_BUTTON),
        )
    }
}

// ── Staged panel — rename / duplicate / delete the local processed copies ─────
@Composable
private fun KbStagedPanel(
    staged: List<StagedSample>,
    auditioningName: String?,
    onAudition: (StagedSample) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    var renaming by remember { mutableStateOf<String?>(null) }

    renaming?.let { from ->
        KbRenameDialog(
            from = from,
            onConfirm = { to -> onRename(from, to); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    Column(
        modifier.fillMaxWidth().padding(13.dp)
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(12.dp)
            .testTag(TestTags.KB_STAGED_PANEL),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("STAGED SAMPLES · LOCAL COPIES ONLY", fontFamily = Mono, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, color = t.text, modifier = Modifier.weight(1f))
            Text("OK", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = t.accentInk,
                modifier = Modifier.clickable { onDismiss() }.padding(6.dp))
        }
        if (staged.isEmpty()) {
            Text("nothing staged yet — audition a sample with a prep toggle on",
                fontFamily = Mono, fontSize = 9.sp, color = t.text3)
        }
        staged.forEach { item ->
            Row(
                Modifier.fillMaxWidth().testTag(TestTags.kbStagedRow(item.name)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playing = auditioningName == item.name
                Text(if (playing) "■" else "▶", fontFamily = Mono, fontSize = 10.sp,
                    color = if (playing) t.live else t.text2,
                    modifier = Modifier.clickable { onAudition(item) }.padding(4.dp)
                        .testTag(TestTags.kbStagedAudition(item.name)))
                Text(item.name, fontFamily = Mono, fontSize = 9.sp, color = t.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("${item.sizeBytes / 1024} KB", fontFamily = Mono, fontSize = 9.sp,
                    color = t.text3)
                Text("DUP", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = t.accentInk,
                    modifier = Modifier.clickable { onDuplicate(item.name) }.padding(4.dp)
                        .testTag(TestTags.kbStagedDuplicate(item.name)))
                Text("REN", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = t.accentInk,
                    modifier = Modifier.clickable { renaming = item.name }.padding(4.dp)
                        .testTag(TestTags.kbStagedRename(item.name)))
                Text("DEL", fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = t.error,
                    modifier = Modifier.clickable { onDelete(item.name) }.padding(4.dp)
                        .testTag(TestTags.kbStagedDelete(item.name)))
            }
        }
    }
}

// ── Rename dialog — new name for a staged sample (sanitized by the ViewModel) ─
@Composable
private fun KbRenameDialog(
    from: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    var value by remember(from) { mutableStateOf(from.removeSuffix(".wav")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.KB_STAGED_RENAME_DIALOG),
        containerColor = t.panel,
        title = {
            Text("Rename $from", fontFamily = Mono, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = t.text)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.KB_STAGED_RENAME_FIELD),
            )
        },
        confirmButton = {
            Text("RENAME", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = t.accentInk,
                modifier = Modifier.clickable { onConfirm(value) }.padding(8.dp)
                    .testTag(TestTags.KB_STAGED_RENAME_CONFIRM))
        },
        dismissButton = {
            Text("CANCEL", fontFamily = Mono, fontSize = 10.sp, color = t.text2,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        },
    )
}

// ── Import panel — per-file batch progress + success/fail list ────────────────
@Composable
private fun KbImportPanel(
    state: KbImportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier.fillMaxWidth().padding(13.dp)
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(12.dp)
            .testTag(TestTags.KB_IMPORT_PANEL),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val okCount = state.results.count { it.ok }
        val failCount = state.results.size - okCount
        val headline = when {
            state.blocked != null -> state.blocked
            state.running -> {
                val cur = state.currentName?.let { " · $it" } ?: ""
                "${state.phase ?: "IMPORTING"} ${state.processed}/${state.total}$cur"
            }
            else -> "IMPORT DONE · $okCount OK" + if (failCount > 0) " · $failCount FAILED" else ""
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(headline, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = if (state.blocked != null || failCount > 0) t.error else t.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(TestTags.KB_IMPORT_PROGRESS_TEXT))
            if (state.running) {
                Text("CANCEL", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = t.accentInk,
                    modifier = Modifier.clickable { onCancel() }.padding(6.dp)
                        .testTag(TestTags.KB_IMPORT_CANCEL_BUTTON))
            } else {
                Text("OK", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = t.accentInk,
                    modifier = Modifier.clickable { onDismiss() }.padding(6.dp)
                        .testTag(TestTags.KB_IMPORT_DISMISS_BUTTON))
            }
        }
        // Progress bar — filled by terminal per-file outcomes.
        if (state.total > 0 && state.blocked == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(state.total) { i ->
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (i < state.processed) t.accent else t.inset))
                }
            }
        }
        // Success/fail list — most recent last; failures show their error text.
        state.results.forEach { r ->
            Row(
                Modifier.fillMaxWidth().testTag(TestTags.kbImportResultRow(r.name)),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(if (r.ok) "✓" else "✕", fontFamily = Mono, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, color = if (r.ok) t.live else t.error)
                Text(r.name, fontFamily = Mono, fontSize = 9.sp, color = t.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (!r.ok && r.message != null) {
                    Text(r.message, fontFamily = Mono, fontSize = 9.sp, color = t.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Empty state — shown until a sample pack is picked ─────────────────────────
@Composable
private fun KbEmptyState(packLoading: Boolean, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Column(
        modifier.fillMaxWidth().background(t.panel).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (packLoading) "reading pack…" else "pick a sample-pack folder to start",
            fontFamily = Mono, fontSize = 11.sp, color = t.text2, textAlign = TextAlign.Center,
        )
        Text(
            "a pack is a folder of category subfolders (KICKS, SNARES, HATS…)",
            fontFamily = Mono, fontSize = 9.sp, color = t.text3,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ── Footer — assigned-count fill meter + "pick a different pack" ──────────────
@Composable
private fun KbFooter(
    assignedCount: Int,
    groupName: String,
    loading: Boolean,
    onSwitchPack: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    Row(
        Modifier.fillMaxWidth().background(t.panel2).padding(14.dp, 10.dp, 14.dp, 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fill count + bars
        Column(
            Modifier.clip(PanelRadius).background(t.panel, PanelRadius)
                .border(1.dp, t.rule, PanelRadius).padding(10.dp, 6.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(assignedCount.toString().padStart(2, '0'),
                    fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = t.accent,
                    modifier = Modifier.testTag(TestTags.KB_ASSIGNED_COUNT))
                Text("/12", fontFamily = Mono, fontSize = 11.sp, color = t.text3,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(KB_PAD_COUNT) { i ->
                    Box(Modifier.size(width = 5.dp, height = 4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i < assignedCount) t.accent else t.rule))
                }
            }
        }
        Text(
            "STAGED FOR GROUP $groupName",
            fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = t.text3,
            modifier = Modifier.weight(1f),
        )
        Text(
            "PICK A DIFFERENT PACK",
            fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 1.sp, color = t.text2,
            modifier = Modifier
                .clickable { if (!loading) onSwitchPack() }
                .padding(2.dp)
                .testTag(TestTags.KB_SWITCH_PACK_BUTTON),
        )
    }
}

// ── Load banner — bottom overlay confirming a kit load / surfacing a failure ──
@Composable
private fun KbLoadBanner(banner: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    Row(
        modifier.fillMaxWidth().padding(13.dp)
            .clip(PanelRadius)
            .background(if (banner.contains("FAILED")) t.error else t.accent, PanelRadius)
            .padding(12.dp)
            .testTag(TestTags.KB_LOAD_BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(banner, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = t.onAccent, modifier = Modifier.weight(1f))
        Text("OK", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = t.onAccent,
            modifier = Modifier.clickable { onDismiss() }.padding(6.dp))
    }
}

@Composable
private fun KbPadCell(
    label: String,
    sample: KitSample?,
    deviceName: String?,
    selected: Boolean,
    loadState: KbLoadState?,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val t = LocalEP133Tokens.current
    val shape = RoundedCornerShape(12.dp)
    val staged = sample != null
    // A pad already holding a device sample (and not being restaged) reads as "on device": a dark
    // pad face like empty, but with the sample name — distinct from the bright accent of a staged
    // pack sample about to be uploaded.
    val onDevice = !staged && deviceName != null
    val bg = when {
        loadState == KbLoadState.Error -> t.error
        staged -> t.accent
        else -> t.padFace
    }
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(bg, shape)
            .then(if (selected) Modifier.border(2.dp, t.live, shape) else Modifier)
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = when {
                    staged -> PadFilledInk
                    onDevice -> t.text2
                    selected -> t.live
                    else -> t.text3
                },
                modifier = Modifier.weight(1f))
            when {
                loadState == KbLoadState.Uploading -> Text("…", fontFamily = Mono, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = PadFilledInk)
                loadState == KbLoadState.Done -> Text("✓", fontFamily = Mono, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = PadFilledInk.copy(alpha = 0.7f))
                loadState == KbLoadState.Error -> Text("✕", fontFamily = Mono, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = PadEmptyInk)
                // Marker for a pad that's occupied on the device but not (re)staged from a pack.
                onDevice -> Text("◉", fontFamily = Mono, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, color = t.text3)
                else -> {}
            }
        }
        Text(
            text = when {
                sample != null -> sample.name
                deviceName != null -> deviceName
                selected -> "assign →"
                else -> "empty"
            },
            fontFamily = Mono, fontSize = 9.sp,
            fontWeight = if (staged || onDevice) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                loadState == KbLoadState.Error -> PadEmptyInk
                staged -> PadFilledInk
                onDevice -> t.text2
                selected -> t.live
                else -> t.text3
            },
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 10.sp,
        )
    }
}
