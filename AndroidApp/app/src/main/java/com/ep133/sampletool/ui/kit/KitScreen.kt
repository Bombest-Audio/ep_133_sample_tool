package com.ep133.sampletool.ui.kit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.audio.LoopSlicer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderScreen
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import com.ep133.sampletool.ui.theme.Ep133GroupChip
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "EP133APP"

// ── Domain constants ──────────────────────────────────────────────────────────

/** Maximum number of pads per group (12 physical pads). */
const val MAX_SLICES = 12

/** Default slice count for the loop-chopper mode (must be in 1..[MAX_SLICES]). */
const val DEFAULT_SLICE_COUNT = 8

/**
 * EP-133 per-sample cap: 20s stereo OR 40s mono @ 46875 (firmware 2.5).
 * Byte budget = 20 * 46875 * 2ch * 2bytes.
 *
 * The cap is a BYTE budget, not a flat duration — firmware 2.5 explicitly allows mono
 * samples up to 40s (and lower sample rates allow even longer). Guard on slice byte size,
 * never on a flat `frames > 20*sampleRate`, which would wrongly reject valid 20–40s mono.
 */
const val MAX_SAMPLE_BYTES = 3_750_000

/**
 * Pad fill order: slice/one-shot index `i` → device pad grid index, so pads populate in the
 * natural EP-133 numeric order **`. 0 ENT 1 2 3 4 5 6 7 8 9`** (bottom row up), not the raw
 * top-left-to-bottom-right grid order.
 *
 * The device pad-node index (0-based) maps to physical labels via `EP133Pads.GRID_ORDER`:
 * `0→7, 1→8, 2→9, 3→4, 4→5, 5→6, 6→1, 7→2, 8→3, 9→., 10→0, 11→ENT`
 * (hardware-verified: pad nodes 1–4 = labels 7,8,9,4). Inverting that for the desired label
 * order `[., 0, ENT, 1,2,3,4,5,6,7,8,9]` gives the grid indices below.
 */
val PAD_FILL_ORDER = listOf(9, 10, 11, 6, 7, 8, 3, 4, 5, 0, 1, 2)

// ── UI state model ────────────────────────────────────────────────────────────

/** Which mode the KitScreen is in. */
enum class KitMode { CHOP, KIT }

/**
 * A loop the user picked but hasn't chopped yet (chop mode) — staged so the slice count can be
 * adjusted before chopping.
 */
data class StagedLoop(val uri: Uri, val name: String)

/** State for a single item (one file / one slice assignment) in the result list. */
data class KitResultItem(
    val label: String,
    val state: KitItemState = KitItemState.Pending,
    val errorMessage: String? = null,
)

enum class KitItemState { Pending, Working, Done, Error }

/** All per-group working state for one A/B/C/D group (see `KitViewModel._groups`). */
data class GroupState(
    val mode: KitMode = KitMode.CHOP,
    val chokeGroup: Boolean = true,
    val sliceCountText: String = DEFAULT_SLICE_COUNT.toString(),
    val stagedLoop: StagedLoop? = null,
    val items: List<KitResultItem> = emptyList(),
)

/** Returns true iff the item completed successfully. */
fun KitResultItem.isDone(): Boolean = state == KitItemState.Done

/** Returns true iff the item failed. */
fun KitResultItem.isError(): Boolean = state == KitItemState.Error

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel for the kit screen (loop-chopper + drum-kit builder).
 *
 * Co-located with [KitScreen] per project conventions (see CLAUDE.md).
 *
 * **Chop mode:** one SAF file → slice PCM into N equal byte arrays → for each slice:
 * [putSampleFile] → [assignSampleToPad] with FULL trim (start=0, end=sliceFrameCount).
 * This mirrors kit mode's per-file upload path and respects the EP-133's per-sample size cap
 * ([MAX_SAMPLE_BYTES]): each short slice is well under the limit even when the source loop is not.
 *
 * Before any device write, a byte-budget guard fires: if the largest slice exceeds the device's
 * per-sample size cap ([MAX_SAMPLE_BYTES] — 20s stereo / 40s mono @ 46875), all rows are set to
 * Error and no bytes are sent. The cap is a BYTE budget, not a flat duration.
 *
 * The domain call sequence is:
 *   1. [SampleImportManager.convert] to decode/resample the file to s16/46875.
 *   2. [LoopSlicer.slicePcmBytes] to split PCM into N equal byte arrays.
 *   3. Byte-budget guard: if any slice > [MAX_SAMPLE_BYTES], error all rows and return.
 *   4. For i in 0 until sliceCount: [MIDIRepository.putSampleFile] with slice bytes → sliceNodeId,
 *      then [MIDIRepository.assignSampleToPad] with full trim (start=0, end=sliceFrameCount).
 *
 * **Kit mode:** N SAF files → for each: convert → putSampleFile → assignSampleToPad with
 * full-trim (start=0, end=frames). Device writes are serialized via [deviceMutex]; decode
 * can overlap across files.
 *
 * Entry points:
 * - [onLoopFilePicked]: SAF single-pick callback for chop mode.
 * - [onKitFilesPicked]: SAF multi-pick callback for kit mode.
 * - [onSliceCountChange], [onGroupChange], [onModeChange]: UI control callbacks.
 * - [chopFromPcm] / [kitFromPcm]: testability seams (no SAF, no AudioDecoder).
 */
class KitViewModel(
    private val midi: MIDIRepository,
    private val manager: SampleImportManager,
) : ViewModel() {

    // Which group's state the UI is showing/editing. Global (not per-group).
    private val _selectedGroup = MutableStateFlow(PadChannel.A)
    val selectedGroup: StateFlow<PadChannel> = _selectedGroup.asStateFlow()

    // Per-group working state: each A/B/C/D keeps its own mode, staged loop, slice count, choke, and
    // chop/kit progress. Starting a chop on A and switching to B leaves A running and persisted.
    private val _groups = MutableStateFlow(PadChannel.entries.associateWith { GroupState() })

    private fun groupOf(g: PadChannel): GroupState = _groups.value.getValue(g)
    private fun updateGroup(g: PadChannel, block: (GroupState) -> GroupState) {
        _groups.update { it + (g to block(it.getValue(g))) }
    }
    private fun updateCurrent(block: (GroupState) -> GroupState) = updateGroup(_selectedGroup.value, block)

    // Exposed state for the currently-selected group; re-emits when the selection or that group's
    // state changes. The UI collects these exactly as before — they just follow the selected group.
    private fun <T> derived(sel: (GroupState) -> T): StateFlow<T> =
        combine(_selectedGroup, _groups) { g, m -> sel(m.getValue(g)) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, sel(GroupState()))

    val mode: StateFlow<KitMode> = derived { it.mode }
    val chokeGroup: StateFlow<Boolean> = derived { it.chokeGroup }
    val sliceCountText: StateFlow<String> = derived { it.sliceCountText }
    val items: StateFlow<List<KitResultItem>> = derived { it.items }
    val stagedLoop: StateFlow<StagedLoop?> = derived { it.stagedLoop }

    // Transient toast — global (not per-group).
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /**
     * Serializes device-upload + pad-assign calls so concurrent kit uploads don't race on the
     * single-in-flight transfer guard inside [MIDIRepository].
     */
    val deviceMutex = Mutex()

    /**
     * SAF callback — set by MainActivity before setContent.
     * Invoke to launch the single-document picker for chop mode.
     */
    var onRequestLoopPick: (() -> Unit)? = null

    /**
     * SAF callback — set by MainActivity before setContent.
     * Invoke to launch the multi-document picker for kit mode.
     */
    var onRequestKitPick: (() -> Unit)? = null

    fun onModeChange(mode: KitMode) = updateCurrent { it.copy(mode = mode) }
    fun onGroupChange(group: PadChannel) { _selectedGroup.value = group }
    fun onChokeGroupChange(on: Boolean) = updateCurrent { it.copy(chokeGroup = on) }
    fun onSliceCountChange(text: String) = updateCurrent { it.copy(sliceCountText = text) }
    fun dismissSnackbar() { _snackbarMessage.value = null }

    /** Trigger the appropriate SAF picker based on the current group's mode. */
    fun triggerPick() {
        when (groupOf(_selectedGroup.value).mode) {
            KitMode.CHOP -> onRequestLoopPick?.invoke()
            KitMode.KIT  -> onRequestKitPick?.invoke()
        }
    }

    /**
     * SAF single-document callback (chop mode): stage the picked loop. Chopping is deferred to
     * [chopStagedLoop] so the user can change the slice count after picking. The OpenDocument read
     * grant lasts the activity session, so the staged URI stays readable until they tap CHOP.
     */
    fun onLoopFilePicked(uri: Uri) {
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "loop.wav"
        updateCurrent { it.copy(stagedLoop = StagedLoop(uri, rawName), items = emptyList()) }
    }

    /** Chop the selected group's staged loop into its slice count. No-op if nothing is staged. */
    fun chopStagedLoop(context: Context) {
        val group = _selectedGroup.value
        val staged = groupOf(group).stagedLoop ?: return
        runChop(group, staged.uri, staged.name, context)
    }

    /**
     * Convert → slice → per-slice upload+assign the staged loop.
     *
     * Steps:
     * 1. [SampleImportManager.convert] to decode/resample.
     * 2. Downmix to mono, then [LoopSlicer.slicePcmBytes] to split PCM into N equal byte arrays.
     * 3. Byte-budget + storage preflight guards.
     * 4. For each slice: [MIDIRepository.putSampleFile] → [MIDIRepository.assignSampleToPad]
     *    (fill order via [PAD_FILL_ORDER], full trim).
     */
    private fun runChop(group: PadChannel, uri: Uri, rawName: String, context: Context) {
        val sliceCount = resolvedSliceCountFor(group)
        val chokeOn = groupOf(group).chokeGroup
        val safeName = manager.sanitizeName(rawName) ?: rawName

        // Seed N rows — one per slice (no separate upload row; each row covers its own upload+assign).
        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }
        setItems(group, sliceLabels.map { KitResultItem(it) })

        viewModelScope.launch {
            // Decode/convert inside the picker-callback grant (Landmine 7).
            val converted = try {
                // enforceMaxLength = false: a loop is meant to exceed the single-sample cap; we
                // slice the full PCM and size-check each slice against the device budget instead.
                withContext(Dispatchers.IO) { manager.convert(context, uri, enforceMaxLength = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitViewModel chop: convert failed for $safeName", e)
                val msg = e.message ?: "Convert failed"
                setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
                _snackbarMessage.value = "Convert failed: ${e.message ?: e}"
                return@launch
            }

            // Chop uploads MONO to halve the device sample-memory footprint (a drum-kit slice
            // rarely needs stereo). The whole-loop import path keeps the source channels; only
            // chop downmixes here.
            val chopPcm = if (converted.channels == 2) {
                LoopSlicer.downmixStereoToMono(converted.pcm)
            } else {
                converted.pcm
            }
            val chopChannels = if (converted.channels == 2) 1 else converted.channels

            // Slice the PCM bytes into N equal pieces on frame boundaries.
            val slices = LoopSlicer.slicePcmBytes(chopPcm, chopChannels, sliceCount)
            val bytesPerFrame = chopChannels * 2

            // Byte-budget guard: reject before any device write if the largest slice exceeds the
            // device's per-sample size cap (a BYTE budget, not a flat duration — see MAX_SAMPLE_BYTES).
            // The slice ByteArray length IS its byte size (sliceFrames * channels * 2).
            val maxSliceBytes = slices.maxOfOrNull { it.size } ?: 0
            if (maxSliceBytes > MAX_SAMPLE_BYTES) {
                val msg = "slice exceeds the device's per-sample size limit (~20s stereo / 40s mono) — " +
                    "add more slices or shorten the loop"
                setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
                _snackbarMessage.value = msg
                return@launch
            }

            // Storage preflight: the device rejects with "not enough space" once /sounds fills.
            // Sum all slices and fail fast with a clear message rather than uploading a partial
            // batch (availableStorageBytes() is null when device stats are unknown → skip the check).
            val totalUploadBytes = slices.sumOf { it.size.toLong() }
            val availBytes = midi.availableStorageBytes()
            if (availBytes != null && totalUploadBytes > availBytes) {
                val msg = "not enough space on device — chop needs ${totalUploadBytes / 1024} KB, " +
                    "${availBytes / 1024} KB free. Delete samples on the device or use fewer slices."
                setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
                _snackbarMessage.value = msg
                return@launch
            }

            // Per-slice upload + assign — same path as kit mode.
            for (i in slices.indices) {
                val slicePcm = slices[i]
                val sliceFrames = slicePcm.size / bytesPerFrame
                val sliceName = "${safeName.substringBeforeLast('.')}_${i + 1}.wav"
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                val sliceNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(sliceName, slicePcm, chopChannels, converted.sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chop: putSampleFile failed for slice $i ($sliceName)", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Slice ${i + 1} upload failed: ${e.message ?: e}"
                    continue
                }

                if (sliceNodeId == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    continue
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, PAD_FILL_ORDER.getOrElse(i) { i }, sliceNodeId, 0, sliceFrames, muteGroup = chokeOn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chop: assignSampleToPad slice $i failed", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    _snackbarMessage.value = "Slice ${i + 1} assign failed: ${e.message ?: e}"
                    continue
                }

                if (ok) {
                    updateItem(group, i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }

            val doneCount = groupOf(group).items.count { it.isDone() }
            _snackbarMessage.value = "Assigned $doneCount / $sliceCount slices to group ${group.name}"
        }
    }

    /**
     * Called by MainActivity when the user completes the multi-document SAF picker (kit mode).
     *
     * For each file: convert → putSampleFile → assignSampleToPad(full trim, start=0, end=frames).
     * Device writes are serialized via [deviceMutex]; decode can overlap across files.
     *
     * @param uris    List of audio file URIs (valid for picker-callback grant; capped to MAX_SLICES).
     * @param context Activity context for contentResolver.
     */
    fun onKitFilesPicked(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        val capped = uris.take(MAX_SLICES)
        val group = _selectedGroup.value
        val chokeOn = groupOf(group).chokeGroup
        val names = capped.map { uri ->
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "sample.wav"
        }
        setItems(group, names.map { KitResultItem(it) })

        capped.forEachIndexed { i, uri ->
            val rawName = names[i]
            viewModelScope.launch {
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                // Decode — can overlap with other files' decodes.
                val converted = try {
                    withContext(Dispatchers.IO) { manager.convert(context, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kit: convert failed for $rawName", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Convert failed") }
                    _snackbarMessage.value = "Convert failed for $rawName: ${e.message ?: e}"
                    return@launch
                }

                val safeName = manager.sanitizeName(rawName) ?: rawName
                val frames = converted.pcm.size / 2 / converted.channels

                // Upload + assign serialized via deviceMutex.
                val sampleNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(safeName, converted.pcm, converted.channels, converted.sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kit: putSampleFile failed for $safeName", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Upload failed for $safeName: ${e.message ?: e}"
                    return@launch
                }

                if (sampleNodeId == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Upload rejected for $safeName — reconnect and retry"
                    return@launch
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, PAD_FILL_ORDER.getOrElse(i) { i }, sampleNodeId, 0, frames, muteGroup = chokeOn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kit: assignSampleToPad $i failed", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    _snackbarMessage.value = "Assign failed for $safeName: ${e.message ?: e}"
                    return@launch
                }

                if (ok) {
                    updateItem(group, i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }
        }
    }

    /**
     * Testability seam for chop mode: exercise the ViewModel state-machine without SAF or
     * AudioDecoder. Pre-converted PCM is passed directly to the per-slice upload + assign pipeline.
     *
     * Mirrors [onLoopFilePicked] exactly: slices PCM via [LoopSlicer.slicePcmBytes], applies the
     * byte-budget guard ([MAX_SAMPLE_BYTES]), then for each slice calls [MIDIRepository.putSampleFile]
     * → [MIDIRepository.assignSampleToPad] with full trim (start=0, end=sliceFrameCount).
     *
     * @param name       Sample name (will be sanitized).
     * @param pcm        Raw s16 LE PCM bytes (no RIFF header).
     * @param channels   1 or 2.
     * @param sampleRate Device sample rate (default 46875).
     */
    fun chopFromPcm(name: String, pcm: ByteArray, channels: Int = 1, sampleRate: Int = 46875) {
        val group = _selectedGroup.value
        val sliceCount = resolvedSliceCountFor(group)
        val chokeOn = groupOf(group).chokeGroup
        val safeName = manager.sanitizeName(name) ?: name

        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }
        setItems(group, sliceLabels.map { KitResultItem(it) })

        viewModelScope.launch {
            // Slice the PCM bytes into N equal pieces on frame boundaries.
            val slices = LoopSlicer.slicePcmBytes(pcm, channels, sliceCount)
            val bytesPerFrame = channels * 2

            // Byte-budget guard: reject before any device write if the largest slice exceeds the
            // device's per-sample size cap (a BYTE budget, not a flat duration — see MAX_SAMPLE_BYTES).
            // The slice ByteArray length IS its byte size (sliceFrames * channels * 2).
            val maxSliceBytes = slices.maxOfOrNull { it.size } ?: 0
            if (maxSliceBytes > MAX_SAMPLE_BYTES) {
                val msg = "slice exceeds the device's per-sample size limit (~20s stereo / 40s mono) — " +
                    "add more slices or shorten the loop"
                setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
                _snackbarMessage.value = msg
                return@launch
            }

            // Per-slice upload + assign.
            for (i in slices.indices) {
                val slicePcm = slices[i]
                val sliceFrames = slicePcm.size / bytesPerFrame
                val sliceName = "${safeName.substringBeforeLast('.')}_${i + 1}.wav"
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                val sliceNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(sliceName, slicePcm, channels, sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chopFromPcm: putSampleFile failed for slice $i ($sliceName)", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Slice ${i + 1} upload failed: ${e.message ?: e}"
                    continue
                }

                if (sliceNodeId == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    continue
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, PAD_FILL_ORDER.getOrElse(i) { i }, sliceNodeId, 0, sliceFrames, muteGroup = chokeOn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chopFromPcm: assignSampleToPad slice $i failed", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    continue
                }
                if (ok) {
                    updateItem(group, i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }
        }
    }

    /**
     * Testability seam for kit mode: exercise the ViewModel state-machine without SAF or
     * AudioDecoder. Pre-converted PCM list is passed directly to the upload + assign pipeline.
     *
     * @param files      List of (name, pcm, channels).
     * @param sampleRate Device sample rate (default 46875).
     */
    fun kitFromPcm(files: List<Triple<String, ByteArray, Int>>, sampleRate: Int = 46875) {
        if (files.isEmpty()) return
        val capped = files.take(MAX_SLICES)
        val group = _selectedGroup.value
        val chokeOn = groupOf(group).chokeGroup
        setItems(group, capped.map { (name, _, _) -> KitResultItem(name) })

        capped.forEachIndexed { i, triple ->
            val (rawName, pcm, channels) = triple
            val safeName = manager.sanitizeName(rawName) ?: rawName
            val frames = pcm.size / 2 / channels
            viewModelScope.launch {
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                val sampleNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(safeName, pcm, channels, sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kitFromPcm: putSampleFile failed for $safeName", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    return@launch
                }

                if (sampleNodeId == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    return@launch
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, PAD_FILL_ORDER.getOrElse(i) { i }, sampleNodeId, 0, frames, muteGroup = chokeOn)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kitFromPcm: assignSampleToPad $i failed", e)
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    return@launch
                }

                if (ok) {
                    updateItem(group, i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }
        }
    }

    /** Slice count for [group], clamped to [1, MAX_SLICES]. MAX_SLICES on parse failure. */
    private fun resolvedSliceCountFor(group: PadChannel): Int =
        groupOf(group).sliceCountText.toIntOrNull()?.coerceIn(1, MAX_SLICES) ?: MAX_SLICES

    /** Slice count for the currently-selected group (used by the UI labels). */
    fun resolvedSliceCount(): Int = resolvedSliceCountFor(_selectedGroup.value)

    /** Replace [group]'s progress items. */
    private fun setItems(group: PadChannel, items: List<KitResultItem>) =
        updateGroup(group) { it.copy(items = items) }

    /** Update a single item of [group] by index (immutable list swap). */
    internal fun updateItem(group: PadChannel, index: Int, transform: (KitResultItem) -> KitResultItem) {
        updateGroup(group) { gs ->
            if (index !in gs.items.indices) gs
            else gs.copy(items = gs.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable
// ─────────────────────────────────────────────────────────────────────────────

private val PanelRadius = RoundedCornerShape(3.dp)
private val Mono = FontFamily.Monospace

/** One pad in the slice selector: its printed label and its 1-based fill-order rank. */
private data class SlicePad(val label: String, val order: Int)

/**
 * The EP-133 pad cluster in device layout (top→bottom, left→right), each pad tagged with its
 * position in the fill order — ranks 1..12 = `. 0 ENT 1 2 3 4 5 6 7 8 9`, matching [PAD_FILL_ORDER].
 * Tapping a pad sets the slice count to its rank, so the grid previews exactly which pads the
 * slices land on and in what order.
 */
private val SLICE_PAD_GRID = listOf(
    SlicePad("7", 10), SlicePad("8", 11), SlicePad("9", 12),
    SlicePad("4", 7),  SlicePad("5", 8),  SlicePad("6", 9),
    SlicePad("1", 4),  SlicePad("2", 5),  SlicePad("3", 6),
    SlicePad(".", 1),  SlicePad("0", 2),  SlicePad("ENT", 3),
)

// Ink on a filled (accent) pad — a warm near-black for contrast, regardless of app theme.
private val PadFilledInk = Color(0xFF1A1206)
// Label on an empty (dark) pad — a fixed light, since the pad face is always dark like the device.
private val PadEmptyInk = Color(0xFFE2E3E4)

/**
 * Visual slice-count selector (implements the "Slice Pad Selector" design). A big count readout
 * with ± steppers plus a 4×3 mock of the device pads: pads whose fill-order rank ≤ [count] render
 * "filled" (accent), the pad at rank == count gets a teal edge ring, and tapping any pad sets the
 * count to its rank. Range is 1..[MAX_SLICES].
 */
@Composable
private fun SlicePadSelector(
    count: Int,
    onCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = modifier
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Count readout + ± steppers.
        Text("SLICE COUNT", fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.6.sp, color = t.text3)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                count.toString().padStart(2, '0'),
                fontFamily = Mono, fontSize = 54.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp, color = t.accent,
            )
            Text(
                "SLICES", fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.6.sp,
                color = t.text, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SliceStepperButton("−", enabled = count > 1) { onCountChange((count - 1).coerceAtLeast(1)) }
            SliceStepperButton("+", enabled = count < MAX_SLICES) { onCountChange((count + 1).coerceAtMost(MAX_SLICES)) }
            Text(
                "TAP A PAD OR USE ±\nRANGE 1–$MAX_SLICES",
                fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp, color = t.text3,
            )
        }

        // 4×3 pad-cluster preview, housed like the device's pad panel.
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(t.inset, RoundedCornerShape(8.dp))
                .border(1.dp, t.ruleSoft, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SLICE_PAD_GRID.chunked(3).forEach { rowPads ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPads.forEach { pad ->
                        SlicePadCell(
                            pad = pad,
                            count = count,
                            modifier = Modifier.weight(1f),
                            onTap = { onCountChange(pad.order) },
                        )
                    }
                }
            }
        }
        Text(
            "PADS FILL ↖ FROM BOTTOM-LEFT",
            fontFamily = Mono, fontSize = 8.sp, letterSpacing = 1.2.sp, color = t.text3,
        )
    }
}

@Composable
private fun SlicePadCell(pad: SlicePad, count: Int, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val t = LocalEP133Tokens.current
    val filled = pad.order <= count
    val isEdge = pad.order == count
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (filled) t.accent else t.padFace, shape)
            .then(if (isEdge) Modifier.border(2.dp, t.live, shape) else Modifier)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = pad.label,
            fontFamily = Mono,
            fontSize = if (pad.label.length > 1) 16.sp else 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = if (filled) PadFilledInk else PadEmptyInk,
        )
        if (filled) {
            Text(
                text = pad.order.toString(),
                fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = PadFilledInk.copy(alpha = 0.62f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun SliceStepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 42.dp)
            .clip(shape)
            .background(t.padFace, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Mono, fontSize = 20.sp,
            color = if (enabled) PadEmptyInk else PadEmptyInk.copy(alpha = 0.4f),
        )
    }
}

// ── Chop/kit upload progress (the pad grid as a single progress indicator) ──────

/** Upload state of one pad in the progress grid. */
private enum class PadProgressState { Inactive, Queued, Uploading, Done, Error }

private fun KitItemState.toProgress(): PadProgressState = when (this) {
    KitItemState.Pending -> PadProgressState.Queued
    KitItemState.Working -> PadProgressState.Uploading
    KitItemState.Done    -> PadProgressState.Done
    KitItemState.Error   -> PadProgressState.Error
}

// Progress-mode colors from the "Chop Progress" design (device-accurate, theme-independent).
private val ProgTealCore = Color(0xFF141B1B)
private val ProgError = Color(0xFFD0021B)
private val ProgErrorInk = Color(0xFFFFE8E5)
private val ProgInactiveInk = Color(0xFF4A4B4C)

/**
 * The pad grid as a single upload-progress indicator (implements the "Chop Progress" design).
 * Reuses [SLICE_PAD_GRID]: slice i lands on the pad at fill-order rank i+1, so item i's state drives
 * that pad; pads beyond the slice count are inactive. A status line summarizes the run.
 */
@Composable
private fun ChopProgress(items: List<KitResultItem>, modifier: Modifier = Modifier) {
    val t = LocalEP133Tokens.current
    val total = items.size
    val done = items.count { it.state == KitItemState.Done }
    val working = items.count { it.state == KitItemState.Working }
    val errors = items.count { it.state == KitItemState.Error }
    val inFlight = items.any { it.state == KitItemState.Working || it.state == KitItemState.Pending }
    fun pad2(n: Int) = n.toString().padStart(2, '0')
    val statusText: String
    val statusColor: Color
    when {
        inFlight   -> { statusText = "UPLOADING · ${pad2(done + working)} / $total"; statusColor = t.live }
        errors > 0 -> { statusText = "${pad2(errors)} FAILED · $done OK"; statusColor = ProgError }
        else       -> { statusText = "DONE · ${pad2(done)} / $total"; statusColor = t.accent }
    }

    // One infinite transition drives both the live-pad sweep and the status-dot blink.
    val anim = rememberInfiniteTransition(label = "cp")
    val sweepAngle by anim.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep",
    )
    val dotAlpha by anim.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1000), repeatMode = RepeatMode.Reverse), label = "dot",
    )

    Column(
        modifier = modifier
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Status line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(t.inset, RoundedCornerShape(5.dp))
                .border(1.dp, t.ruleSoft, RoundedCornerShape(5.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = if (inFlight) dotAlpha else 1f }
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor),
            )
            Text(
                statusText,
                fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp, color = statusColor,
            )
        }

        // Pad grid — each pad's state from its slice (fill-order rank r → item r-1), else inactive.
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(t.inset, RoundedCornerShape(8.dp))
                .border(1.dp, t.ruleSoft, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SLICE_PAD_GRID.chunked(3).forEach { rowPads ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowPads.forEach { pad ->
                        val state = if (pad.order > total) PadProgressState.Inactive
                            else items[pad.order - 1].state.toProgress()
                        SliceProgressPadCell(
                            pad = pad, state = state, sweepAngle = sweepAngle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SliceProgressPadCell(
    pad: SlicePad,
    state: PadProgressState,
    sweepAngle: Float,
    modifier: Modifier,
) {
    val t = LocalEP133Tokens.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier.aspectRatio(1f).clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (state == PadProgressState.Uploading) {
            // Rotating teal sweep (a ~95° arc) behind a dark core — a spinner on the live pad.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = sweepAngle }
                    .background(
                        Brush.sweepGradient(
                            0.00f to t.live,
                            0.26f to t.live,
                            0.27f to t.live.copy(alpha = 0.12f),
                            1.00f to t.live.copy(alpha = 0.12f),
                        ),
                        shape,
                    ),
            )
            Box(
                Modifier.matchParentSize().padding(3.dp)
                    .clip(RoundedCornerShape(11.dp)).background(ProgTealCore),
            )
        } else {
            val bg = when (state) {
                PadProgressState.Done  -> t.accent
                PadProgressState.Error -> ProgError
                else                   -> t.padFace   // inactive / queued
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(bg, shape)
                    .then(
                        if (state == PadProgressState.Queued)
                            Modifier.border(1.5.dp, t.accent.copy(alpha = 0.5f), shape) else Modifier,
                    ),
            )
        }

        Text(
            text = pad.label,
            fontFamily = Mono,
            fontSize = if (pad.label.length > 1) 16.sp else 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = when (state) {
                PadProgressState.Done     -> PadFilledInk
                PadProgressState.Error    -> ProgErrorInk
                PadProgressState.Inactive -> ProgInactiveInk
                else                      -> PadEmptyInk
            },
        )

        val glyph = when (state) {
            PadProgressState.Done  -> "✓"
            PadProgressState.Error -> "✕"
            else                   -> null
        }
        if (glyph != null) {
            Text(
                text = glyph,
                fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (state == PadProgressState.Error) ProgErrorInk else PadFilledInk.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

/**
 * Kit screen: loop chopper and drum-kit builder, two modes behind a segmented control.
 *
 * The app shell owns the header + bottom nav; this renders the body only.
 */
@Composable
fun KitScreen(viewModel: KitViewModel, builderViewModel: KitBuilderViewModel) {
    val t = LocalEP133Tokens.current
    val mode by viewModel.mode.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val sliceCountText by viewModel.sliceCountText.collectAsState()
    val items by viewModel.items.collectAsState()
    val stagedLoop by viewModel.stagedLoop.collectAsState()
    val chokeGroup by viewModel.chokeGroup.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    val doneCount = items.count { it.isDone() }
    val errorCount = items.count { it.isError() }
    val headLabel = when {
        items.isEmpty() -> "IDLE"
        errorCount > 0 -> "$errorCount ERR · $doneCount OK"
        doneCount == items.size -> "ALL DONE"
        else -> "$doneCount / ${items.size} OK"
    }
    val headColor = when {
        errorCount > 0 -> t.accent
        items.isNotEmpty() && doneCount == items.size -> t.live
        else -> t.text3
    }

    Box(modifier = Modifier.fillMaxSize().background(t.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
          // Header + mode switch stay pinned above both modes.
          Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
          ) {
            // Section header + batch status (chop-only status).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ep133SectionLabel(
                    if (mode == KitMode.CHOP) "Loop Chopper" else "Kit Builder",
                    modifier = Modifier.weight(1f),
                )
                if (mode == KitMode.CHOP) {
                    Text(
                        text = headLabel,
                        color = headColor,
                        fontFamily = Mono,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                    )
                }
            }

            // Mode segmented control: CHOP | KIT.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KitMode.entries.forEach { m ->
                    val selected = mode == m
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PanelRadius)
                            .background(if (selected) t.accent else t.inset, PanelRadius)
                            .border(1.dp, if (selected) t.accent else t.rule, PanelRadius)
                            .clickable { viewModel.onModeChange(m) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (m == KitMode.CHOP) "CHOP" else "KIT",
                            color = if (selected) t.onAccent else t.text2,
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
          }

          if (mode == KitMode.KIT) {
            // KIT mode = the Kit Builder (pack browser + kit canvas + load), embedded edge-to-edge.
            KitBuilderScreen(
                viewModel = builderViewModel,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 11.dp),
            )
          } else {
          // Scrollable chop content; the PICK button below stays pinned so it's always reachable.
          Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .verticalScroll(scrollState)
                .padding(top = 11.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
          ) {

            // Group picker: A | B | C | D.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PadChannel.entries.forEach { ch ->
                    Ep133GroupChip(
                        label = ch.name,
                        selected = selectedGroup == ch,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.onGroupChange(ch) },
                    )
                }
            }

            // Choke-group toggle: write sound.mutegroup=true so pads in the group cut each other off.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelRadius)
                    .background(t.inset, PanelRadius)
                    .border(1.dp, t.rule, PanelRadius)
                    .clickable { viewModel.onChokeGroupChange(!chokeGroup) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CHOKE GROUP",
                        fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp, color = t.text,
                    )
                    Text(
                        "pads in the group cut each other off",
                        fontFamily = Mono, fontSize = 9.sp, color = t.text3,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(PanelRadius)
                        .background(if (chokeGroup) t.accent else t.chrome, PanelRadius)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (chokeGroup) "ON" else "OFF",
                        fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = if (chokeGroup) t.onAccent else t.text2,
                    )
                }
            }

            // The pad grid is both the chop slice-count SELECTOR (idle) and the upload PROGRESS
            // indicator (once a chop or kit build is running/done). One grid, two modes.
            when {
                items.isNotEmpty() -> {
                    ChopProgress(items = items, modifier = Modifier.fillMaxWidth())
                }
                mode == KitMode.CHOP -> {
                    val count = sliceCountText.toIntOrNull()?.coerceIn(1, MAX_SLICES) ?: DEFAULT_SLICE_COUNT
                    SlicePadSelector(
                        count = count,
                        onCountChange = { viewModel.onSliceCountChange(it.toString()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Drop / pick hint panel — tappable (it reads as a button), triggers the same pick.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelRadius)
                    .background(t.inset, PanelRadius)
                    .dashedBorder(t.rule)
                    .clickable { viewModel.triggerPick() }
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        mode == KitMode.CHOP && stagedLoop != null ->
                            "READY · CHOP INTO ${viewModel.resolvedSliceCount()} SLICES → GROUP ${selectedGroup.name}"
                        mode == KitMode.CHOP ->
                            "PICK ONE LOOP · CHOP INTO ${viewModel.resolvedSliceCount()} SLICES → GROUP ${selectedGroup.name}"
                        else ->
                            "PICK UP TO $MAX_SLICES ONE-SHOTS → GROUP ${selectedGroup.name}"
                    },
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when {
                        mode == KitMode.CHOP && stagedLoop != null -> stagedLoop!!.name
                        mode == KitMode.CHOP -> "pick loop to chop"
                        else -> "pick one-shots to build kit"
                    },
                    color = t.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (mode == KitMode.CHOP && stagedLoop != null) {
                    Text(
                        text = "TAP TO PICK A DIFFERENT LOOP",
                        color = t.text3, fontFamily = Mono, fontSize = 8.sp,
                        letterSpacing = 1.sp, textAlign = TextAlign.Center,
                    )
                }
            }

          }

            // Pick button — pinned below the scroll so it's always reachable. Once a loop is
            // staged the primary action becomes CHOP (so the count can be set first).
            val chopReady = stagedLoop != null
            Ep133PrimaryButton(
                label = if (chopReady) {
                    "CHOP INTO ${viewModel.resolvedSliceCount()} SLICES → ${selectedGroup.name}"
                } else {
                    "PICK LOOP"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 11.dp, bottom = 14.dp),
                onClick = { if (chopReady) viewModel.chopStagedLoop(context) else viewModel.triggerPick() },
            )
          }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 70.dp),
        )
    }
}

// ── Decoration modifier ───────────────────────────────────────────────────────

private fun Modifier.dashedBorder(color: Color): Modifier = this.drawBehind {
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
    )
    val radius = 3.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = stroke,
    )
}
