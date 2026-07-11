@file:Suppress("PackageName")
package com.ep133.sampletool.ui.`import`

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.midi.SampleImportProgress
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.theme.Ep133PrimaryButton
import com.ep133.sampletool.ui.theme.Ep133SectionLabel
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.theme.Mono
import com.ep133.sampletool.ui.theme.accentRail
import com.ep133.sampletool.ui.theme.dashedBorder
import com.ep133.sampletool.ui.theme.PanelRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "EP133APP"

/**
 * One item in the staged-import list.
 *
 * Tracks the import state per file: Pending → (Converting →) Loading → Done | Error.
 * [isDone] and [isError] extension functions support ViewModel test assertions.
 */
data class StagedSample(
    val name: String,
    val state: StagedSampleState = StagedSampleState.Pending,
    val progress: Float = 0f,
    val errorMessage: String? = null,
) {
    /** Returns true iff this sample's import completed successfully. */
    fun isDone(): Boolean = state == StagedSampleState.Done
    /** Returns true iff this sample's import failed. */
    fun isError(): Boolean = state == StagedSampleState.Error
}

/** Import lifecycle state for a staged sample row. */
enum class StagedSampleState {
    Pending,
    Converting,
    Loading,
    Done,
    Error,
}

/**
 * ViewModel for the sample import screen (SAMPLE-01 / SAMPLE-04).
 *
 * Co-located with [SampleImportScreen] per project conventions (see CLAUDE.md).
 * Holds the staged-import list as a [StateFlow] and drives per-sample progress via
 * [SampleImportManager]. Mirrors [DeviceViewModel]'s coroutine + state-flow + SAF-callback shape.
 *
 * Entry points:
 * - [onFilesPicked]: real SAF path — reads bytes under Dispatchers.IO (Landmine 7), seeds rows,
 *   drives [SampleImportManager.importSample] for the full convert + upload pipeline.
 * - [importStagedBytes]: testability seam — pre-read bytes, no URI/AudioDecoder; used by
 *   [SampleImportViewModelTest] without a real device or SAF picker.
 */
class SampleImportViewModel(
    private val midi: MIDIRepository,
    private val manager: SampleImportManager,
) : ViewModel() {

    private val _stagedSamples = MutableStateFlow<List<StagedSample>>(emptyList())
    /** Per-file import list: one [StagedSample] per picked file. */
    val stagedSamples: StateFlow<List<StagedSample>> = _stagedSamples.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    /** Set when an import batch completes or fails at the batch level. */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /**
     * SAF callback — set by MainActivity.onCreate() before setContent.
     * Invoke to launch the OpenMultipleDocuments picker.
     */
    var onRequestPick: (() -> Unit)? = null

    /** Trigger the SAF multi-file picker (delegates to MainActivity launcher). */
    fun triggerPick() {
        onRequestPick?.invoke()
    }

    /** Dismiss the current snackbar message. */
    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * Called by MainActivity when the user completes the SAF picker.
     *
     * Reads each URI's bytes inside the picker-callback grant under [Dispatchers.IO]
     * (Landmine 7 — content:// URI lifetime). Seeds one [StagedSample] per URI then
     * drives [SampleImportManager.importSample] for the full convert + upload pipeline.
     *
     * Must be called while the picker-callback grant is still active.
     *
     * @param uris    URIs returned by [OpenMultipleDocuments] (may be empty if user cancels).
     * @param context Activity context (for contentResolver access inside the grant).
     */
    fun onFilesPicked(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return

        // Derive display names from the URI's last path segment (best-effort, no ContentResolver call needed).
        val newItems = uris.map { uri ->
            val rawName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: uri.toString().substringAfterLast('/')
            StagedSample(name = rawName.ifEmpty { "sample.wav" })
        }
        val startIndex = _stagedSamples.value.size
        _stagedSamples.value = _stagedSamples.value + newItems

        // Launch one coroutine per file; reads happen inside the picker-callback grant (Landmine 7).
        uris.forEachIndexed { i, uri ->
            val index = startIndex + i
            val rawName = newItems[i].name
            viewModelScope.launch {
                try {
                    // Set CONVERTING before the decode/convert step.
                    updateSample(index) { it.copy(state = StagedSampleState.Converting) }

                    // Drive the full import pipeline (convert + upload) under the grant.
                    manager.importSample(rawName, uri, context)
                        .collect { progress ->
                            when (progress) {
                                is SampleImportProgress.Progress -> {
                                    val pct = if (progress.total > 0) {
                                        progress.current.toFloat() / progress.total
                                    } else 0f
                                    updateSample(index) {
                                        it.copy(state = StagedSampleState.Loading, progress = pct)
                                    }
                                }
                                is SampleImportProgress.Done -> {
                                    updateSample(index) {
                                        it.copy(state = StagedSampleState.Done, progress = 1f)
                                    }
                                }
                                is SampleImportProgress.Error -> {
                                    updateSample(index) {
                                        it.copy(state = StagedSampleState.Error, errorMessage = progress.message)
                                    }
                                    _snackbarMessage.value = progress.message
                                }
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "SampleImportViewModel: import failed for $rawName", e)
                    updateSample(index) {
                        it.copy(state = StagedSampleState.Error, errorMessage = e.message ?: "Import failed")
                    }
                    _snackbarMessage.value = e.message ?: "Import failed"
                }
            }
        }
    }

    /**
     * Testability seam: import a sample from pre-read bytes (no SAF URI, no AudioDecoder).
     *
     * Used by [SampleImportViewModelTest] to exercise the state-machine without a real device
     * or SAF picker (per 05-VALIDATION Manual-Only section). Adds a [StagedSample] in
     * [StagedSampleState.Pending] and launches the upload via [SampleImportManager.importSampleBytes].
     *
     * @param name     Raw sample name (will be sanitized by the manager).
     * @param wavBytes Pre-read bytes to upload (assumed already in WAV format for tests).
     */
    fun importStagedBytes(name: String, wavBytes: ByteArray) {
        // Add pending item at the end of the list.
        val index = _stagedSamples.value.size
        _stagedSamples.value = _stagedSamples.value + StagedSample(name = name)

        // Launch the import coroutine on viewModelScope.
        manager.importSampleBytes(name, wavBytes)
            .onEach { progress ->
                when (progress) {
                    is SampleImportProgress.Progress -> {
                        val pct = if (progress.total > 0) {
                            progress.current.toFloat() / progress.total
                        } else 0f
                        updateSample(index) { it.copy(state = StagedSampleState.Loading, progress = pct) }
                    }
                    is SampleImportProgress.Done -> {
                        updateSample(index) { it.copy(state = StagedSampleState.Done, progress = 1f) }
                    }
                    is SampleImportProgress.Error -> {
                        updateSample(index) { it.copy(state = StagedSampleState.Error, errorMessage = progress.message) }
                        _snackbarMessage.value = progress.message
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /** Update a single staged item by index (immutable list replacement). */
    private fun updateSample(index: Int, transform: (StagedSample) -> StagedSample) {
        val current = _stagedSamples.value.toMutableList()
        if (index in current.indices) {
            current[index] = transform(current[index])
            _stagedSamples.value = current
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composable
// ─────────────────────────────────────────────────────────────────────────────


/** Number of page cells in the per-file SysEx progress strip (mirrors the design's 12-cell grid). */
private const val PAGE_CELLS = 12

/**
 * Import screen: pick audio files from device storage and load them onto the EP-133.
 *
 * EP133App owns the faceplate header + bottom nav; this renders the body only.
 * A faceplate [Box] over [LocalEP133Tokens] backs a drop-zone hint,
 * a [LazyColumn] of [StagedSampleRow] items (each a tactile paged-SysEx strip), a protocol note,
 * and the pick/import primary action.
 */
@Composable
fun SampleImportScreen(viewModel: SampleImportViewModel) {
    val t = LocalEP133Tokens.current
    val stagedSamples by viewModel.stagedSamples.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    // Batch summary for the header readout: count of done / errored files.
    val doneCount = stagedSamples.count { it.isDone() }
    val errorCount = stagedSamples.count { it.isError() }
    val headLabel = when {
        stagedSamples.isEmpty() -> "IDLE"
        errorCount > 0 -> "$errorCount ERR · $doneCount OK"
        doneCount == stagedSamples.size -> "ALL DONE"
        else -> "$doneCount / ${stagedSamples.size} OK"
    }
    val headColor = when {
        errorCount > 0 -> t.accent
        stagedSamples.isNotEmpty() && doneCount == stagedSamples.size -> t.live
        else -> t.text3
    }

    Box(modifier = Modifier.fillMaxSize().background(t.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Batch status readout — mono eyebrow + live/done/err tint.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ep133SectionLabel("Sample Import", modifier = Modifier.weight(1f))
                Text(
                    text = headLabel,
                    color = headColor,
                    fontFamily = Mono,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
            }

            // Drop / pick zone — dashed inset panel, conversion is offline-capable.
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
                    text = "DROP OR PICK · RIFF/PCM · s16 · ≤20s",
                    color = t.text3,
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "pick WAVs to upload",
                    color = t.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            // Staged list — one tactile SysEx strip per file. Weighted so the action stays pinned.
            if (stagedSamples.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    itemsIndexed(stagedSamples) { _, sample ->
                        StagedSampleRow(sample)
                    }
                    item {
                        ProtocolNote()
                    }
                }
            } else {
                // No files yet — keep the protocol note visible and absorb the slack.
                Column(modifier = Modifier.weight(1f)) {
                    ProtocolNote()
                }
            }

            // Primary action — pick files / re-pick to stage more.
            Ep133PrimaryButton(
                label = if (stagedSamples.isEmpty()) "PICK FILES" else "PICK MORE FILES",
                modifier = Modifier
                    .testTag(TestTags.IMPORT_PICK_BUTTON)
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

/**
 * One row in the staged-import list — a tactile paged-SysEx strip: name + state code, a 12-cell
 * page grid that fills with accent (or terminal teal/accent), and a mono meta/percent footer.
 */
@Composable
private fun StagedSampleRow(sample: StagedSample) {
    val t = LocalEP133Tokens.current

    val stateLabel = when (sample.state) {
        StagedSampleState.Pending -> "PENDING"
        StagedSampleState.Converting -> "CONVERTING"
        StagedSampleState.Loading -> "LOADING"
        StagedSampleState.Done -> "DONE"
        StagedSampleState.Error -> "ERROR"
    }
    val stateColor = when (sample.state) {
        StagedSampleState.Done -> t.live
        StagedSampleState.Error -> t.accent
        else -> t.text2
    }
    // Fill color for completed page cells; terminal states recolor the whole strip.
    val fillColor = when (sample.state) {
        StagedSampleState.Done -> t.live
        StagedSampleState.Error -> t.accent
        else -> t.accent
    }
    // How many cells are "filled". Converting shows a small lead-in; Loading tracks progress.
    val filledCells = when (sample.state) {
        StagedSampleState.Pending -> 0
        StagedSampleState.Converting -> 1
        StagedSampleState.Loading -> (sample.progress * PAGE_CELLS).toInt().coerceIn(0, PAGE_CELLS)
        StagedSampleState.Done -> PAGE_CELLS
        StagedSampleState.Error -> (sample.progress * PAGE_CELLS).toInt().coerceIn(0, PAGE_CELLS)
    }
    val pct = (sample.progress * 100).toInt().coerceIn(0, 100)
    val footLabel = when (sample.state) {
        StagedSampleState.Pending -> "QUEUED"
        StagedSampleState.Converting -> "DECODE → s16"
        StagedSampleState.Loading -> "$pct%"
        StagedSampleState.Done -> "100%"
        StagedSampleState.Error -> sample.errorMessage ?: "FAILED"
    }

    Column(
        modifier = Modifier
            .testTag(TestTags.importRow(sample.name))
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.panel, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // File name + state code.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sample.name,
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

        // 12-cell paged-SysEx strip — each cell is a page ack.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(PAGE_CELLS) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i < filledCells) fillColor else t.inset),
                )
            }
        }

        // Meta on the left, percent / error on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "SysEx · paged",
                color = t.text3,
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
            )
            Text(
                text = footLabel,
                modifier = Modifier.weight(1f, fill = false),
                color = if (sample.isError()) t.accent else t.text3,
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

/** The paged-SysEx explainer — inset panel with a live accent rail and a mono "i" marker. */
@Composable
private fun ProtocolNote() {
    val t = LocalEP133Tokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelRadius)
            .background(t.inset, PanelRadius)
            .border(1.dp, t.rule, PanelRadius)
            .accentRail(t.live)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = "i",
            color = t.live,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "paged SysEx — each page waits for its ack. terminator on finish, so it never " +
                "wedges the device.",
            color = t.text2,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
        )
    }
}

// ── Lightweight decoration modifiers ──────────────────────────────────────────

