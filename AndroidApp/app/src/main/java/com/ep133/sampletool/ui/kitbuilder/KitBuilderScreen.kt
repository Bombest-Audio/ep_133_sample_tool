package com.ep133.sampletool.ui.kitbuilder

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.pack.KitPack
import com.ep133.sampletool.domain.pack.KitSample
import com.ep133.sampletool.domain.pack.SamplePackLoader
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
)

/** The pack-browser globals (shared across groups — a pack is a browsing resource, not kit state). */
private data class KbGlobals(
    val pack: KitPack? = null,
    val packLoading: Boolean = false,
    val category: String? = null,
    val auditioningUri: Uri? = null,
)

class KitBuilderViewModel(
    private val midi: MIDIRepository,
    private val manager: SampleImportManager,
    private val session: GroupSession = GroupSession(),
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
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, KitBuilderState())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /** SAF callback — set by MainActivity; launches the OpenDocumentTree picker. */
    var onRequestPackPick: (() -> Unit)? = null

    private val deviceMutex = Mutex()
    private var player: MediaPlayer? = null

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
            if (pack.isEmpty) {
                _snackbarMessage.value = "No audio files found in that folder"
                _globals.update { it.copy(packLoading = false) }
                return@launch
            }
            _globals.update {
                it.copy(pack = pack, packLoading = false, category = pack.categories.first().id)
            }
            updateCurrent { it.copy(loadStates = emptyMap(), loadedBanner = null) }
        }
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

    /** Toggle audition playback of [sample] via MediaPlayer (local preview, not the device). */
    fun onAudition(sample: KitSample, context: Context) {
        val cur = _globals.value.auditioningUri
        stopAudition()
        if (cur == sample.uri) return   // tapped the playing row's button → just stop
        try {
            player = MediaPlayer().apply {
                setDataSource(context, sample.uri)
                setOnCompletionListener { stopAudition() }
                setOnErrorListener { _, _, _ -> stopAudition(); true }
                prepare()
                start()
            }
            _globals.update { it.copy(auditioningUri = sample.uri) }
        } catch (e: Exception) {
            Log.e(TAG, "KitBuilder: audition failed for ${sample.name}", e)
            _snackbarMessage.value = "Can't play ${sample.name}"
            stopAudition()
        }
    }

    private fun stopAudition() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        _globals.update { it.copy(auditioningUri = null) }
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
                    val nodeId = deviceMutex.withLock {
                        midi.putSampleFile(safeName, converted.pcm, converted.channels, converted.sampleRate)
                    }
                    if (nodeId == null) {
                        false
                    } else {
                        deviceMutex.withLock {
                            midi.assignSampleToPad(group, padIdx, nodeId, 0, frames, muteGroup = chokeOn)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitBuilder: load failed for ${sample.name}", e)
                    _snackbarMessage.value = "${sample.name}: ${e.message ?: "failed"}"
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

    override fun onCleared() {
        stopAudition()
        super.onCleared()
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────────

private val PanelRadius = RoundedCornerShape(3.dp)
private val Mono = FontFamily.Monospace
private val PadFilledInk = androidx.compose.ui.graphics.Color(0xFF1A1206)
private val PadEmptyInk = androidx.compose.ui.graphics.Color(0xFFE2E3E4)
private val KbError = androidx.compose.ui.graphics.Color(0xFFD0021B)

/**
 * Kit Builder (implements the "Kit Builder" design): pick a pack folder, browse categories,
 * audition one-shots, assign them pad-first onto the 4×3 canvas, and LOAD the kit to a group.
 */
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
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = t.panel,
            titleContentColor = t.text,
            textContentColor = t.text2,
            shape = PanelRadius,
            title = { Text("Clear pad $padLabel?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (padSampleName != null) {
                        "Removes \"$padSampleName\" from pad $padLabel of group ${s.group.name} on the EP-133."
                    } else {
                        "Unbinds whatever sample is on pad $padLabel of group ${s.group.name} on the EP-133."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onClearPad(); confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.accent),
                ) {
                    Text("Clear", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.text2),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Box(modifier.background(t.bg)) {
        Column(Modifier.fillMaxSize()) {

            // ── Kit canvas ──
            Column(
                Modifier.fillMaxWidth().background(t.bg).padding(14.dp, 12.dp, 14.dp, 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("KIT CANVAS · TAP A PAD", fontFamily = Mono, fontSize = 9.sp,
                            letterSpacing = 1.4.sp, color = t.text3)
                        Text(s.pack?.name ?: "no pack loaded", fontFamily = Mono, fontSize = 9.sp,
                            color = t.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val clearing = s.clearingPad != null
                    Box(
                        Modifier.clip(PanelRadius).background(t.panel, PanelRadius)
                            .border(1.dp, t.rule, PanelRadius)
                            .clickable(enabled = !clearing) { confirmClear = true }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            if (clearing) "CLEARING…" else "⌫ CLEAR PAD ${KB_PAD_LABELS[s.selectedPad]}",
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
                                sample = s.assignments[idx],
                                deviceName = s.devicePads[idx],
                                selected = idx == s.selectedPad,
                                loadState = s.loadStates[idx],
                                modifier = Modifier.weight(1f),
                                onTap = { viewModel.onPadSelected(idx) },
                            )
                        }
                    }
                }
            }

            // ── Category tabs ──
            val pack = s.pack
            if (pack != null) {
                Row(
                    Modifier.fillMaxWidth().background(t.panel)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pack.categories.forEach { cat ->
                        val on = cat.id == s.category
                        Row(
                            Modifier.clip(PanelRadius)
                                .background(if (on) t.accent else t.inset, PanelRadius)
                                .border(1.dp, if (on) t.accent else t.rule, PanelRadius)
                                .clickable { viewModel.onCategoryChange(cat.id) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
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

                // ── Sample list ──
                val samples = pack.categories.firstOrNull { it.id == s.category }?.samples.orEmpty()
                val uriToPad = s.assignments.entries.associate { (idx, smp) -> smp.uri to KB_PAD_LABELS[idx] }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(t.panel)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 14.dp, 4.dp)) {
                            Text("${s.category} · ${samples.size}", fontFamily = Mono, fontSize = 9.sp,
                                letterSpacing = 1.2.sp, color = t.text3, modifier = Modifier.weight(1f))
                            Text("TAP ROW → PAD ${KB_PAD_LABELS[s.selectedPad]}", fontFamily = Mono,
                                fontSize = 9.sp, letterSpacing = 0.8.sp, color = t.text3)
                        }
                    }
                    items(samples, key = { it.uri }) { sample ->
                        val playing = s.auditioningUri == sample.uri
                        val padLabel = uriToPad[sample.uri]
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (playing) t.inset else t.panel)
                                .clickable { viewModel.onAssign(sample) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape)
                                    .background(if (playing) t.live else t.inset)
                                    .border(1.dp, if (playing) t.live else t.rule, CircleShape)
                                    .clickable { viewModel.onAudition(sample, context) },
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
                        }
                    }
                }
            } else {
                // Empty state — no pack loaded yet.
                Column(
                    Modifier.weight(1f).fillMaxWidth().background(t.panel).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (s.packLoading) "reading pack…" else "pick a sample-pack folder to start",
                        fontFamily = Mono, fontSize = 11.sp, color = t.text2, textAlign = TextAlign.Center,
                    )
                    Text(
                        "a pack is a folder of category subfolders (KICKS, SNARES, HATS…)",
                        fontFamily = Mono, fontSize = 9.sp, color = t.text3,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // ── Footer — fill meter + pack switcher. GROUP + CHOKE live in the pinned header
            // shared with CHOP; the push action is the shared PUSH TO DEVICE button below. ──
            if (s.pack != null) {
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
                            Text(s.assignments.size.toString().padStart(2, '0'),
                                fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                color = t.accent)
                            Text("/12", fontFamily = Mono, fontSize = 11.sp, color = t.text3,
                                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(KB_PAD_COUNT) { i ->
                                Box(Modifier.size(width = 5.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(if (i < s.assignments.size) t.accent else t.rule))
                            }
                        }
                    }
                    Text(
                        "STAGED FOR GROUP ${s.group.name}",
                        fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = t.text3,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "PICK A DIFFERENT PACK",
                        fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 1.sp, color = t.text2,
                        modifier = Modifier
                            .clickable { if (!s.loading) viewModel.triggerPackPick() }
                            .padding(2.dp),
                    )
                }
            }
        }

        // ── Load banner ──
        val banner = s.loadedBanner
        if (banner != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(13.dp)
                    .clip(PanelRadius)
                    .background(if (banner.contains("FAILED")) KbError else t.accent, PanelRadius)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(banner, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = t.onAccent, modifier = Modifier.weight(1f))
                Text("OK", fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = t.onAccent,
                    modifier = Modifier.clickable { viewModel.dismissBanner() }.padding(6.dp))
            }
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp))
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
        loadState == KbLoadState.Error -> KbError
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
