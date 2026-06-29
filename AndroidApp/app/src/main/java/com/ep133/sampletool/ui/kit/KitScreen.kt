package com.ep133.sampletool.ui.kit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.ep133.sampletool.ui.theme.Ep133GroupChip
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "EP133APP"

// ── Domain constants ──────────────────────────────────────────────────────────

/** Maximum number of pads per group (12 physical pads). */
const val MAX_SLICES = 12

/** Default slice count for the loop-chopper mode. */
const val DEFAULT_SLICE_COUNT = 16

/**
 * EP-133 per-sample cap: 20s stereo OR 40s mono @ 46875 (firmware 2.5).
 * Byte budget = 20 * 46875 * 2ch * 2bytes.
 *
 * The cap is a BYTE budget, not a flat duration — firmware 2.5 explicitly allows mono
 * samples up to 40s (and lower sample rates allow even longer). Guard on slice byte size,
 * never on a flat `frames > 20*sampleRate`, which would wrongly reject valid 20–40s mono.
 */
const val MAX_SAMPLE_BYTES = 3_750_000

// ── UI state model ────────────────────────────────────────────────────────────

/** Which mode the KitScreen is in. */
enum class KitMode { CHOP, KIT }

/** State for a single item (one file / one slice assignment) in the result list. */
data class KitResultItem(
    val label: String,
    val state: KitItemState = KitItemState.Pending,
    val errorMessage: String? = null,
)

enum class KitItemState { Pending, Working, Done, Error }

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

    private val _mode = MutableStateFlow(KitMode.CHOP)
    val mode: StateFlow<KitMode> = _mode.asStateFlow()

    private val _selectedGroup = MutableStateFlow(PadChannel.A)
    val selectedGroup: StateFlow<PadChannel> = _selectedGroup.asStateFlow()

    // Slice count: stored as String for TextField binding; validated on use.
    private val _sliceCountText = MutableStateFlow(DEFAULT_SLICE_COUNT.toString())
    val sliceCountText: StateFlow<String> = _sliceCountText.asStateFlow()

    private val _items = MutableStateFlow<List<KitResultItem>>(emptyList())
    val items: StateFlow<List<KitResultItem>> = _items.asStateFlow()

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

    fun onModeChange(mode: KitMode) { _mode.value = mode }
    fun onGroupChange(group: PadChannel) { _selectedGroup.value = group }
    fun onSliceCountChange(text: String) { _sliceCountText.value = text }
    fun dismissSnackbar() { _snackbarMessage.value = null }

    /** Trigger the appropriate SAF picker based on the current mode. */
    fun triggerPick() {
        when (_mode.value) {
            KitMode.CHOP -> onRequestLoopPick?.invoke()
            KitMode.KIT  -> onRequestKitPick?.invoke()
        }
    }

    /**
     * Called by MainActivity when the user completes the single-document SAF picker (chop mode).
     *
     * Steps:
     * 1. [SampleImportManager.convert] to decode/resample (inside picker-callback grant).
     * 2. [LoopSlicer.slicePcmBytes] to split PCM into N equal byte arrays.
     * 3. Byte-budget guard: if any slice exceeds [MAX_SAMPLE_BYTES], error all rows and return.
     * 4. For i in 0..sliceCount-1: [MIDIRepository.putSampleFile] with slice bytes →
     *    sliceNodeId, then [MIDIRepository.assignSampleToPad] with full trim (0, sliceFrameCount).
     *
     * @param uri     Single audio file URI (valid for the picker-callback grant only).
     * @param context Activity context for contentResolver.
     */
    fun onLoopFilePicked(uri: Uri, context: Context) {
        val sliceCount = resolvedSliceCount()
        val group = _selectedGroup.value
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "loop.wav"
        val safeName = manager.sanitizeName(rawName) ?: rawName

        // Seed N rows — one per slice (no separate upload row; each row covers its own upload+assign).
        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }
        _items.value = sliceLabels.map { KitResultItem(it) }

        viewModelScope.launch {
            // Decode/convert inside the picker-callback grant (Landmine 7).
            val converted = try {
                withContext(Dispatchers.IO) { manager.convert(context, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "KitViewModel chop: convert failed for $safeName", e)
                val msg = e.message ?: "Convert failed"
                _items.value = sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) }
                _snackbarMessage.value = "Convert failed: ${e.message ?: e}"
                return@launch
            }

            // Slice the PCM bytes into N equal pieces on frame boundaries.
            val slices = LoopSlicer.slicePcmBytes(converted.pcm, converted.channels, sliceCount)
            val bytesPerFrame = converted.channels * 2

            // Byte-budget guard: reject before any device write if the largest slice exceeds the
            // device's per-sample size cap (a BYTE budget, not a flat duration — see MAX_SAMPLE_BYTES).
            // The slice ByteArray length IS its byte size (sliceFrames * channels * 2).
            val maxSliceBytes = slices.maxOfOrNull { it.size } ?: 0
            if (maxSliceBytes > MAX_SAMPLE_BYTES) {
                val msg = "slice exceeds the device's per-sample size limit (~20s stereo / 40s mono) — " +
                    "add more slices or shorten the loop"
                _items.value = sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) }
                _snackbarMessage.value = msg
                return@launch
            }

            // Per-slice upload + assign — same path as kit mode.
            for (i in slices.indices) {
                val slicePcm = slices[i]
                val sliceFrames = slicePcm.size / bytesPerFrame
                val sliceName = "${safeName.substringBeforeLast('.')}_${i + 1}.wav"
                updateItem(i) { it.copy(state = KitItemState.Working) }

                val sliceNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(sliceName, slicePcm, converted.channels, converted.sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chop: putSampleFile failed for slice $i ($sliceName)", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Slice ${i + 1} upload failed: ${e.message ?: e}"
                    continue
                }

                if (sliceNodeId == null) {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    continue
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, i, sliceNodeId, 0, sliceFrames)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chop: assignSampleToPad slice $i failed", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    _snackbarMessage.value = "Slice ${i + 1} assign failed: ${e.message ?: e}"
                    continue
                }

                if (ok) {
                    updateItem(i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }

            val doneCount = _items.value.count { it.isDone() }
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
        val names = capped.map { uri ->
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "sample.wav"
        }
        _items.value = names.map { KitResultItem(it) }

        capped.forEachIndexed { i, uri ->
            val rawName = names[i]
            viewModelScope.launch {
                updateItem(i) { it.copy(state = KitItemState.Working) }

                // Decode — can overlap with other files' decodes.
                val converted = try {
                    withContext(Dispatchers.IO) { manager.convert(context, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kit: convert failed for $rawName", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Convert failed") }
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
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Upload failed for $safeName: ${e.message ?: e}"
                    return@launch
                }

                if (sampleNodeId == null) {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Upload rejected for $safeName — reconnect and retry"
                    return@launch
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, i, sampleNodeId, 0, frames)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kit: assignSampleToPad $i failed", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    _snackbarMessage.value = "Assign failed for $safeName: ${e.message ?: e}"
                    return@launch
                }

                if (ok) {
                    updateItem(i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
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
        val sliceCount = resolvedSliceCount()
        val group = _selectedGroup.value
        val safeName = manager.sanitizeName(name) ?: name

        val sliceLabels = (0 until sliceCount).map { i -> "slice ${group.name}${i + 1}" }
        _items.value = sliceLabels.map { KitResultItem(it) }

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
                _items.value = sliceLabels.map { KitResultItem(it, KitItemState.Error, msg) }
                _snackbarMessage.value = msg
                return@launch
            }

            // Per-slice upload + assign.
            for (i in slices.indices) {
                val slicePcm = slices[i]
                val sliceFrames = slicePcm.size / bytesPerFrame
                val sliceName = "${safeName.substringBeforeLast('.')}_${i + 1}.wav"
                updateItem(i) { it.copy(state = KitItemState.Working) }

                val sliceNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(sliceName, slicePcm, channels, sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chopFromPcm: putSampleFile failed for slice $i ($sliceName)", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    _snackbarMessage.value = "Slice ${i + 1} upload failed: ${e.message ?: e}"
                    continue
                }

                if (sliceNodeId == null) {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    _snackbarMessage.value = "Slice ${i + 1} upload rejected — reconnect and retry"
                    continue
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, i, sliceNodeId, 0, sliceFrames)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel chopFromPcm: assignSampleToPad slice $i failed", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    continue
                }
                if (ok) {
                    updateItem(i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
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
        _items.value = capped.map { (name, _, _) -> KitResultItem(name) }

        capped.forEachIndexed { i, triple ->
            val (rawName, pcm, channels) = triple
            val safeName = manager.sanitizeName(rawName) ?: rawName
            val frames = pcm.size / 2 / channels
            viewModelScope.launch {
                updateItem(i) { it.copy(state = KitItemState.Working) }

                val sampleNodeId: Int? = try {
                    deviceMutex.withLock {
                        midi.putSampleFile(safeName, pcm, channels, sampleRate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kitFromPcm: putSampleFile failed for $safeName", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Upload failed") }
                    return@launch
                }

                if (sampleNodeId == null) {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Upload rejected by device") }
                    return@launch
                }

                val ok = try {
                    deviceMutex.withLock {
                        midi.assignSampleToPad(group, i, sampleNodeId, 0, frames)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "KitViewModel kitFromPcm: assignSampleToPad $i failed", e)
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = e.message ?: "Assign failed") }
                    return@launch
                }

                if (ok) {
                    updateItem(i) { it.copy(state = KitItemState.Done) }
                } else {
                    updateItem(i) { it.copy(state = KitItemState.Error, errorMessage = "Assign rejected by device") }
                }
            }
        }
    }

    /** Parse [sliceCountText], clamped to [1, MAX_SLICES]. Returns MAX_SLICES on parse failure. */
    fun resolvedSliceCount(): Int =
        _sliceCountText.value.toIntOrNull()?.coerceIn(1, MAX_SLICES) ?: MAX_SLICES

    /** Update a single item by index (immutable list swap). */
    internal fun updateItem(index: Int, transform: (KitResultItem) -> KitResultItem) {
        val list = _items.value.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _items.value = list
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable
// ─────────────────────────────────────────────────────────────────────────────

private val PanelRadius = RoundedCornerShape(3.dp)
private val Mono = FontFamily.Monospace

/**
 * Kit screen: loop chopper and drum-kit builder, two modes behind a segmented control.
 *
 * The app shell owns the header + bottom nav; this renders the body only.
 */
@Composable
fun KitScreen(viewModel: KitViewModel) {
    val t = LocalEP133Tokens.current
    val mode by viewModel.mode.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val sliceCountText by viewModel.sliceCountText.collectAsState()
    val items by viewModel.items.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Section header + batch status.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ep133SectionLabel(
                    if (mode == KitMode.CHOP) "Loop Chopper" else "Drum Kit",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = headLabel,
                    color = headColor,
                    fontFamily = Mono,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
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

            // Chop-only: slice count input.
            if (mode == KitMode.CHOP) {
                val sliceError = sliceCountText.toIntOrNull().let { n ->
                    when {
                        n == null -> "enter a number"
                        n < 1 -> "min 1"
                        n > MAX_SLICES -> "max $MAX_SLICES (one group has $MAX_SLICES pads)"
                        else -> null
                    }
                }
                OutlinedTextField(
                    value = sliceCountText,
                    onValueChange = { viewModel.onSliceCountChange(it) },
                    label = {
                        Text(
                            "SLICES (1–$MAX_SLICES)",
                            fontFamily = Mono,
                            fontSize = 9.sp,
                            color = t.text3,
                        )
                    },
                    isError = sliceError != null,
                    supportingText = if (sliceError != null) {
                        { Text(sliceError, color = t.accent, fontFamily = Mono, fontSize = 9.sp) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = t.accent,
                        unfocusedBorderColor = t.rule,
                        focusedTextColor = t.text,
                        unfocusedTextColor = t.text,
                        cursorColor = t.accent,
                    ),
                )
            }

            // Drop / pick hint panel.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelRadius)
                    .background(t.inset, PanelRadius)
                    .dashedBorder(t.rule)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (mode == KitMode.CHOP)
                        "PICK ONE LOOP · CHOP INTO ${viewModel.resolvedSliceCount()} SLICES → GROUP ${selectedGroup.name}"
                    else
                        "PICK UP TO $MAX_SLICES ONE-SHOTS → GROUP ${selectedGroup.name}",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (mode == KitMode.CHOP) "pick loop to chop" else "pick one-shots to build kit",
                    color = t.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            // Result list.
            if (items.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    itemsIndexed(items) { _, item ->
                        KitResultRow(item)
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            // Pick button.
            Ep133PrimaryButton(
                label = when {
                    items.isEmpty() && mode == KitMode.CHOP -> "PICK LOOP"
                    items.isEmpty() && mode == KitMode.KIT  -> "PICK FILES"
                    mode == KitMode.CHOP                    -> "PICK NEW LOOP"
                    else                                    -> "PICK MORE FILES"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                onClick = { viewModel.triggerPick() },
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

// ── Row component ──────────────────────────────────────────────────────────────

private const val STRIP_CELLS = 12

@Composable
private fun KitResultRow(item: KitResultItem) {
    val t = LocalEP133Tokens.current

    val stateLabel = when (item.state) {
        KitItemState.Pending -> "PENDING"
        KitItemState.Working -> "WORKING"
        KitItemState.Done    -> "DONE"
        KitItemState.Error   -> "ERROR"
    }
    val stateColor = when (item.state) {
        KitItemState.Done  -> t.live
        KitItemState.Error -> t.accent
        else               -> t.text2
    }
    val fillColor = when (item.state) {
        KitItemState.Done  -> t.live
        KitItemState.Error -> t.accent
        else               -> t.accent
    }
    val filledCells = when (item.state) {
        KitItemState.Pending -> 0
        KitItemState.Working -> 1
        KitItemState.Done    -> STRIP_CELLS
        KitItemState.Error   -> 1
    }

    Column(
        modifier = Modifier
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
                text = item.label,
                modifier = Modifier.weight(1f),
                color = t.text,
                fontFamily = Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateLabel,
                color = stateColor,
                fontFamily = Mono,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(STRIP_CELLS) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i < filledCells) fillColor else t.inset),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "pad assign",
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
            )
            Text(
                text = item.errorMessage ?: when (item.state) {
                    KitItemState.Pending -> "QUEUED"
                    KitItemState.Working -> "…"
                    KitItemState.Done    -> "OK"
                    KitItemState.Error   -> "FAILED"
                },
                modifier = Modifier.weight(1f, fill = false),
                color = if (item.isError()) t.accent else t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
