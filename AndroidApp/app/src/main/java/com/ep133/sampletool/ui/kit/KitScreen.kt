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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.kitbuilder.KitBuilderScreen
import com.ep133.sampletool.ui.kitbuilder.KitBuilderState
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import com.ep133.sampletool.ui.theme.Ep133GroupChokeBar
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.Mono
import com.ep133.sampletool.ui.theme.dashedBorder
import com.ep133.sampletool.ui.theme.PadEmptyInk
import com.ep133.sampletool.ui.theme.PadFilledInk
import com.ep133.sampletool.ui.theme.PanelRadius
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

/**
 * Per-group chop working state (see `KitViewModel._groups`). The group's designation (CHOP/KIT)
 * and choke setting live in [GroupSession] — the source of truth shared with the Kit Builder.
 */
data class GroupState(
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
    val session: GroupSession = GroupSession(),
) : ViewModel() {

    // Which group's state the UI is showing/editing — owned by the shared GroupSession so the
    // Kit Builder sees the same selection.
    val selectedGroup: StateFlow<PadChannel> = session.selected

    // Per-group chop working state: each A/B/C/D keeps its own staged loop, slice count, and
    // chop progress. Starting a chop on A and switching to B leaves A running and persisted.
    private val _groups = MutableStateFlow(PadChannel.entries.associateWith { GroupState() })

    private fun groupOf(g: PadChannel): GroupState = _groups.value.getValue(g)
    private fun updateGroup(g: PadChannel, block: (GroupState) -> GroupState) {
        _groups.update { it + (g to block(it.getValue(g))) }
    }
    private fun updateCurrent(block: (GroupState) -> GroupState) = updateGroup(session.selected.value, block)

    // Exposed state for the currently-selected group; re-emits when the selection or that group's
    // state changes. The UI collects these exactly as before — they just follow the selected group.
    private fun <T> derived(sel: (GroupState) -> T): StateFlow<T> =
        combine(session.selected, _groups) { g, m -> sel(m.getValue(g)) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, sel(GroupState()))

    /** The selected group's designation (CHOP/KIT) — drives which workflow the page shows. */
    val mode: StateFlow<KitMode> =
        combine(session.selected, session.designations) { g, d -> d.getValue(g) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, session.designationFor(session.selected.value))

    /** The selected group's choke setting. */
    val chokeGroup: StateFlow<Boolean> =
        combine(session.selected, session.choke) { g, c -> c.getValue(g) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, session.chokeFor(session.selected.value))

    /** Per-group designations for the whole bar (so each A/B/C/D chip can show its mode tag). */
    val designations: StateFlow<Map<PadChannel, KitMode>> = session.designations

    val sliceCountText: StateFlow<String> = derived { it.sliceCountText }
    val items: StateFlow<List<KitResultItem>> = derived { it.items }
    val stagedLoop: StateFlow<StagedLoop?> = derived { it.stagedLoop }

    /**
     * Canonical resolved slice count for the selected group. The selector grid AND the labels
     * collect this one flow, so the highlighted pad count can never disagree with what the device
     * actually slices (they previously resolved the raw text independently, with different fallbacks).
     */
    val resolvedSliceCount: StateFlow<Int> = derived { resolveSliceCount(it) }

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

    /** Re-designate the SELECTED group as a CHOP or KIT group. */
    fun onModeChange(mode: KitMode) = session.designate(session.selected.value, mode)
    fun onGroupChange(group: PadChannel) = session.select(group)
    fun onChokeGroupChange(on: Boolean) = session.setChoke(session.selected.value, on)
    fun onSliceCountChange(text: String) = updateCurrent { it.copy(sliceCountText = text) }
    fun dismissSnackbar() { _snackbarMessage.value = null }

    /** Trigger the appropriate SAF picker based on the current group's designation. */
    fun triggerPick() {
        when (session.designationFor(session.selected.value)) {
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
        val group = session.selected.value
        val staged = groupOf(group).stagedLoop ?: return
        runChop(group, staged.uri, staged.name, context)
    }

    /** Result of [uploadAndAssign] for one row. Cancellation is rethrown, never returned; failures
     *  carry the [Throwable] so callers can format their own (differing) snackbar text exactly. */
    private sealed interface UploadOutcome {
        object Done : UploadOutcome
        object UploadRejected : UploadOutcome
        object AssignRejected : UploadOutcome
        data class UploadFailed(val error: Throwable) : UploadOutcome
        data class AssignFailed(val error: Throwable) : UploadOutcome
    }

    /** One converted slice ready for the device leg: the sample payload plus its frame count. */
    private class SliceUpload(
        val name: String,
        val pcm: ByteArray,
        val channels: Int,
        val sampleRate: Int,
        val frames: Int,
    )

    /**
     * The shared device leg of every kit/chop upload: put [upload] as a new sample, then assign the
     * returned node to [padIndex] of [group], driving row [i] to Done or Error. Serialized on
     * [deviceMutex].
     *
     * This is the one copy of a sequence that was duplicated across [runChop], [onKitFilesPicked],
     * [chopFromPcm] and [kitFromPcm]. It owns the item state transitions (which the tests assert);
     * the caller owns control flow (loop vs per-file launch) and snackbar messaging (which differ per
     * entry point), driving off the returned [UploadOutcome]. Rethrows CancellationException.
     */
    private suspend fun uploadAndAssign(
        group: PadChannel,
        i: Int,
        padIndex: Int,
        upload: SliceUpload,
        chokeOn: Boolean,
        logLabel: String,
    ): UploadOutcome {
        val nodeId: Int? = try {
            deviceMutex.withLock { midi.putSampleFile(upload.name, upload.pcm, upload.channels, upload.sampleRate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "KitViewModel $logLabel: putSampleFile failed for ${upload.name}", e)
            updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
            return UploadOutcome.UploadFailed(e)
        }

        if (nodeId == null) {
            updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
            return UploadOutcome.UploadRejected
        }

        val ok = try {
            deviceMutex.withLock {
                midi.assignSampleToPad(group, padIndex, nodeId, 0, upload.frames, muteGroup = chokeOn)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "KitViewModel $logLabel: assignSampleToPad $i failed", e)
            updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
            return UploadOutcome.AssignFailed(e)
        }

        return if (ok) {
            updateItem(group, i) { it.copy(state = KitItemState.Done) }
            UploadOutcome.Done
        } else {
            updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
            UploadOutcome.AssignRejected
        }
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
        val chokeOn = session.chokeFor(group)

        // Seed N rows — one per slice (no separate upload row; each row covers its own upload+assign).
        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }

        // A null sanitized name means no safe ASCII device filename can be derived — fail up
        // front rather than feeding the raw name into putSampleFile (same contract as
        // SampleImportManager.importSample).
        val safeName = manager.sanitizeName(rawName)
        if (safeName == null) {
            val msg = "Unusable file name — rename the file using letters or digits"
            setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
            _snackbarMessage.value = msg
            return
        }

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

            // Per-slice upload + assign — same device path as kit mode (see uploadAndAssign).
            for (i in slices.indices) {
                val slicePcm = slices[i]
                val sliceFrames = slicePcm.size / bytesPerFrame
                val sliceName = "${safeName.substringBeforeLast('.')}_${i + 1}.wav"
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                when (val outcome = uploadAndAssign(
                    group, i, PAD_FILL_ORDER.getOrElse(i) { i },
                    SliceUpload(sliceName, slicePcm, chopChannels, converted.sampleRate, sliceFrames), chokeOn, "chop",
                )) {
                    is UploadOutcome.UploadFailed ->
                        _snackbarMessage.value = "Slice ${i + 1} upload failed: ${outcome.error.message ?: outcome.error}"
                    UploadOutcome.UploadRejected ->
                        _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    is UploadOutcome.AssignFailed ->
                        _snackbarMessage.value = "Slice ${i + 1} assign failed: ${outcome.error.message ?: outcome.error}"
                    UploadOutcome.AssignRejected -> {}
                    UploadOutcome.Done -> {}
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
        val group = session.selected.value
        val chokeOn = session.chokeFor(group)
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

                // Null sanitized name = no safe device filename derivable — error this item
                // rather than uploading the raw name.
                val safeName = manager.sanitizeName(rawName)
                if (safeName == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Unusable file name") }
                    _snackbarMessage.value = "Unusable file name: $rawName — rename it using letters or digits"
                    return@launch
                }
                val frames = converted.pcm.size / 2 / converted.channels

                // Upload + assign serialized via deviceMutex (see uploadAndAssign).
                when (val outcome = uploadAndAssign(
                    group, i, PAD_FILL_ORDER.getOrElse(i) { i },
                    SliceUpload(safeName, converted.pcm, converted.channels, converted.sampleRate, frames), chokeOn, "kit",
                )) {
                    is UploadOutcome.UploadFailed ->
                        _snackbarMessage.value = "Upload failed for $safeName: ${outcome.error.message ?: outcome.error}"
                    UploadOutcome.UploadRejected ->
                        _snackbarMessage.value = "Upload rejected for $safeName — reconnect and retry"
                    is UploadOutcome.AssignFailed ->
                        _snackbarMessage.value = "Assign failed for $safeName: ${outcome.error.message ?: outcome.error}"
                    UploadOutcome.AssignRejected -> {}
                    UploadOutcome.Done -> {}
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
        val group = session.selected.value
        val sliceCount = resolvedSliceCountFor(group)
        val chokeOn = session.chokeFor(group)

        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }

        // Same contract as runChop: a null sanitized name is an error, not a fallback.
        val safeName = manager.sanitizeName(name)
        if (safeName == null) {
            val msg = "Unusable file name — rename the file using letters or digits"
            setItems(group, sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) })
            _snackbarMessage.value = msg
            return
        }

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

                when (val outcome = uploadAndAssign(
                    group, i, PAD_FILL_ORDER.getOrElse(i) { i },
                    SliceUpload(sliceName, slicePcm, channels, sampleRate, sliceFrames), chokeOn, "chopFromPcm",
                )) {
                    is UploadOutcome.UploadFailed ->
                        _snackbarMessage.value = "Slice ${i + 1} upload failed: ${outcome.error.message ?: outcome.error}"
                    UploadOutcome.UploadRejected ->
                        _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    // chopFromPcm (test seam) emits no snackbar on the assign paths.
                    is UploadOutcome.AssignFailed -> {}
                    UploadOutcome.AssignRejected -> {}
                    UploadOutcome.Done -> {}
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
        val group = session.selected.value
        val chokeOn = session.chokeFor(group)
        setItems(group, capped.map { (name, _, _) -> KitResultItem(name) })

        capped.forEachIndexed { i, triple ->
            val (rawName, pcm, channels) = triple
            val frames = pcm.size / 2 / channels
            viewModelScope.launch {
                // Same contract as onKitFilesPicked: a null sanitized name is an error, not a fallback.
                val safeName = manager.sanitizeName(rawName)
                if (safeName == null) {
                    updateItem(group, i) { it.copy(state = KitItemState.Error, errorMessage = "Unusable file name") }
                    return@launch
                }
                updateItem(group, i) { it.copy(state = KitItemState.Working) }

                // Test seam: no snackbars; uploadAndAssign drives the row's Working→Done/Error state.
                uploadAndAssign(
                    group, i, PAD_FILL_ORDER.getOrElse(i) { i },
                    SliceUpload(safeName, pcm, channels, sampleRate, frames), chokeOn, "kitFromPcm",
                )
            }
        }
    }

    /** The one slice-count formula: parse [GroupState.sliceCountText], clamp to [1, MAX_SLICES],
     *  MAX_SLICES on parse failure. Both [resolvedSliceCountFor] and the [resolvedSliceCount] flow
     *  route through this so they can't drift apart. */
    private fun resolveSliceCount(state: GroupState): Int =
        state.sliceCountText.toIntOrNull()?.coerceIn(1, MAX_SLICES) ?: MAX_SLICES

    /** Slice count for [group]. */
    private fun resolvedSliceCountFor(group: PadChannel): Int = resolveSliceCount(groupOf(group))

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
                modifier = Modifier.testTag(TestTags.KIT_SLICE_COUNT_READOUT),
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
            SliceStepperButton(
                "−",
                enabled = count > 1,
                modifier = Modifier.testTag(TestTags.KIT_SLICE_COUNT_DEC),
            ) { onCountChange((count - 1).coerceAtLeast(1)) }
            SliceStepperButton(
                "+",
                enabled = count < MAX_SLICES,
                modifier = Modifier.testTag(TestTags.KIT_SLICE_COUNT_INC),
            ) { onCountChange((count + 1).coerceAtMost(MAX_SLICES)) }
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
                            modifier = Modifier.weight(1f).testTag(TestTags.kitSlicePad(pad.order)),
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
private fun SliceStepperButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalEP133Tokens.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
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
        errors > 0 -> { statusText = "${pad2(errors)} FAILED · $done OK"; statusColor = t.error }
        else       -> { statusText = "DONE · ${pad2(done)} / $total"; statusColor = t.accent }
    }

    // One infinite transition drives both the live-pad sweep and the status-dot blink.
    // Compose it ONLY while a batch is in flight — an unconditional infinite transition
    // keeps the frame clock ticking (and recomposing this grid) forever after the run ends.
    val sweepAngle: Float
    val dotAlpha: Float
    if (inFlight) {
        val anim = rememberInfiniteTransition(label = "cp")
        sweepAngle = anim.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep",
        ).value
        dotAlpha = anim.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1000), repeatMode = RepeatMode.Reverse), label = "dot",
        ).value
    } else {
        sweepAngle = 0f
        dotAlpha = 1f
    }

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
                .padding(horizontal = 11.dp, vertical = 9.dp)
                .testTag(TestTags.KIT_PROGRESS_STATUS),
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
                            modifier = Modifier.weight(1f).testTag(TestTags.kitProgressPad(pad.order)),
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
                PadProgressState.Error -> t.error
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
    val resolvedSliceCount by viewModel.resolvedSliceCount.collectAsState()
    val items by viewModel.items.collectAsState()
    val stagedLoop by viewModel.stagedLoop.collectAsState()
    val chokeGroup by viewModel.chokeGroup.collectAsState()
    val designations by viewModel.designations.collectAsState()
    val builderState by builderViewModel.state.collectAsState()
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
            KitHeader(mode = mode, items = items)

            // Designation segmented control: CHOP | KIT — sets what the SELECTED group is.
            ModeSegmentedControl(mode = mode, onModeChange = viewModel::onModeChange)

            // Shared group + choke bar — ONE control backed by the shared GroupSession, so both
            // workflows always agree on the selected group and its choke. Each chip is tagged with
            // its group's designation; selecting a group flips the page into that group's mode.
            Ep133GroupChokeBar(
                group = selectedGroup,
                onGroupChange = { viewModel.onGroupChange(it) },
                chokeOn = chokeGroup,
                onChokeChange = { viewModel.onChokeGroupChange(it) },
                tagFor = { g -> designations[g]?.name },
                testTagFor = { g -> TestTags.groupChip(g.name) },
            )
          }

          if (mode == KitMode.KIT) {
            // KIT mode = the Kit Builder (pack browser + kit canvas), embedded edge-to-edge.
            // The push action lives in the shared PUSH TO DEVICE button below.
            KitBuilderScreen(
                viewModel = builderViewModel,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 11.dp),
            )
          } else {
            // Scrollable chop content; the PUSH button below stays pinned so it's always reachable.
            ChopBody(
                items = items,
                mode = mode,
                resolvedSliceCount = resolvedSliceCount,
                stagedLoop = stagedLoop,
                selectedGroupName = selectedGroup.name,
                scrollState = scrollState,
                onSliceCountChange = { viewModel.onSliceCountChange(it.toString()) },
                onPick = viewModel::triggerPick,
                modifier = Modifier.weight(1f),
            )
          }

            // Shared PUSH TO DEVICE button — pinned below both workflows. In a CHOP group it picks
            // then chops the staged loop; in a KIT group it picks a pack then loads the staged kit.
            KitPushButton(
                mode = mode,
                chopReady = stagedLoop != null,
                resolvedSliceCount = resolvedSliceCount,
                selectedGroupName = selectedGroup.name,
                builderState = builderState,
                onChopPush = { viewModel.chopStagedLoop(context) },
                onPickLoop = viewModel::triggerPick,
                onPickPack = builderViewModel::triggerPackPick,
                onLoadKit = { builderViewModel.onLoadKit(context) },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 70.dp),
        )
    }
}

// ── Header — section label + (chop-only) batch status summary ─────────────────
@Composable
private fun KitHeader(mode: KitMode, items: List<KitResultItem>) {
    val t = LocalEP133Tokens.current
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ep133SectionLabel(
            if (mode == KitMode.CHOP) "Loop Chopper" else "Kit Builder",
            modifier = Modifier.weight(1f),
        )
        if (mode == KitMode.CHOP) {
            Text(
                text = headLabel, color = headColor, fontFamily = Mono,
                fontSize = 9.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp,
            )
        }
    }
}

// ── CHOP | KIT segmented control — sets the selected group's designation ──────
@Composable
private fun ModeSegmentedControl(mode: KitMode, onModeChange: (KitMode) -> Unit) {
    val t = LocalEP133Tokens.current
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
                    .clickable { onModeChange(m) }
                    .padding(vertical = 9.dp)
                    .testTag(if (m == KitMode.CHOP) TestTags.KIT_MODE_CHOP else TestTags.KIT_MODE_KIT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (m == KitMode.CHOP) "CHOP" else "KIT",
                    color = if (selected) t.onAccent else t.text2,
                    fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Chop body — the scrollable slice grid (selector or progress) + pick panel ─
@Composable
private fun ChopBody(
    items: List<KitResultItem>,
    mode: KitMode,
    resolvedSliceCount: Int,
    stagedLoop: StagedLoop?,
    selectedGroupName: String,
    scrollState: ScrollState,
    onSliceCountChange: (Int) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalEP133Tokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .verticalScroll(scrollState)
            .padding(top = 11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // The pad grid is both the chop slice-count SELECTOR (idle) and the upload PROGRESS
        // indicator (once a chop or kit build is running/done). One grid, two modes.
        when {
            items.isNotEmpty() -> ChopProgress(items = items, modifier = Modifier.fillMaxWidth())
            mode == KitMode.CHOP -> SlicePadSelector(
                count = resolvedSliceCount,
                onCountChange = { onSliceCountChange(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Drop / pick hint panel — tappable (it reads as a button), triggers the pick.
        // Capture the staged loop into a local so the null-checks below smart-cast.
        val loop = stagedLoop
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PanelRadius)
                .background(t.inset, PanelRadius)
                .dashedBorder(t.rule)
                .clickable { onPick() }
                .padding(16.dp)
                .testTag(TestTags.KIT_PICK_PANEL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when {
                    mode == KitMode.CHOP && loop != null ->
                        "READY · CHOP INTO $resolvedSliceCount SLICES → GROUP $selectedGroupName"
                    mode == KitMode.CHOP ->
                        "PICK ONE LOOP · CHOP INTO $resolvedSliceCount SLICES → GROUP $selectedGroupName"
                    else ->
                        "PICK UP TO $MAX_SLICES ONE-SHOTS → GROUP $selectedGroupName"
                },
                color = t.text3, fontFamily = Mono, fontSize = 10.sp,
                letterSpacing = 0.4.sp, textAlign = TextAlign.Center,
            )
            Text(
                text = when {
                    mode == KitMode.CHOP && loop != null -> loop.name
                    mode == KitMode.CHOP -> "pick loop to chop"
                    else -> "pick one-shots to build kit"
                },
                color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (mode == KitMode.CHOP && loop != null) {
                Text(
                    text = "TAP TO PICK A DIFFERENT LOOP",
                    color = t.text3, fontFamily = Mono, fontSize = 8.sp,
                    letterSpacing = 1.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── Shared PUSH TO DEVICE button — label + dispatch differ per CHOP/KIT mode ──
@Composable
private fun KitPushButton(
    mode: KitMode,
    chopReady: Boolean,
    resolvedSliceCount: Int,
    selectedGroupName: String,
    builderState: KitBuilderState,
    onChopPush: () -> Unit,
    onPickLoop: () -> Unit,
    onPickPack: () -> Unit,
    onLoadKit: () -> Unit,
) {
    val pushLabel = if (mode == KitMode.CHOP) {
        if (chopReady) "PUSH TO DEVICE · $resolvedSliceCount SLICES → $selectedGroupName"
        else "PICK LOOP"
    } else {
        when {
            builderState.packLoading -> "READING PACK…"
            builderState.pack == null -> "PICK PACK FOLDER"
            builderState.loading -> "PUSHING…"
            builderState.assignments.isEmpty() -> "ASSIGN PADS FIRST"
            else -> "PUSH TO DEVICE · ${builderState.assignments.size} PADS → $selectedGroupName"
        }
    }
    Ep133PrimaryButton(
        label = pushLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 11.dp, bottom = 14.dp)
            .testTag(TestTags.KIT_PUSH_BUTTON),
        onClick = {
            if (mode == KitMode.CHOP) {
                if (chopReady) onChopPush() else onPickLoop()
            } else {
                when {
                    builderState.packLoading || builderState.loading -> {}
                    builderState.pack == null -> onPickPack()
                    builderState.assignments.isNotEmpty() -> onLoadKit()
                }
            }
        },
    )
}

