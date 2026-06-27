@file:Suppress("PackageName")
package com.ep133.sampletool.ui.`import`

import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.midi.SampleImportProgress
import com.ep133.sampletool.ui.theme.TEColors
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

/**
 * Import screen: pick audio files from device storage and load them onto the EP-133.
 *
 * Mirrors [DeviceScreen] for SnackbarHostState + LaunchedEffect + Scaffold structure.
 * Uses a [LazyColumn] of [StagedSampleRow] items, each showing per-file state +
 * [LinearProgressIndicator].
 */
@Composable
fun SampleImportScreen(viewModel: SampleImportViewModel) {
    val stagedSamples by viewModel.stagedSamples.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = "IMPORT SAMPLES",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Offline-pick hint — conversion works offline, only upload needs the device.
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Pick audio files to import onto your EP-133.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Conversion works offline. Connect your EP-133 via USB to upload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { viewModel.triggerPick() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Files")
                    }
                }
            }

            // Staged list
            if (stagedSamples.isNotEmpty()) {
                Text(
                    text = "FILES (${stagedSamples.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(stagedSamples) { _, sample ->
                        StagedSampleRow(sample)
                    }
                }
            }
        }
    }
}

/**
 * One row in the staged-import list: name, state label, LinearProgressIndicator,
 * and a check/error glyph + message on terminal states.
 */
@Composable
private fun StagedSampleRow(sample: StagedSample) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // State glyph
                when (sample.state) {
                    StagedSampleState.Done -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        modifier = Modifier.size(18.dp),
                        tint = TEColors.Teal,
                    )
                    StagedSampleState.Error -> Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> Box(modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))

                // File name
                Text(
                    text = sample.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))

                // State label
                Text(
                    text = when (sample.state) {
                        StagedSampleState.Pending -> "PENDING"
                        StagedSampleState.Converting -> "CONVERTING"
                        StagedSampleState.Loading -> "LOADING"
                        StagedSampleState.Done -> "DONE"
                        StagedSampleState.Error -> "ERROR"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (sample.state) {
                        StagedSampleState.Done -> TEColors.Teal
                        StagedSampleState.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Progress bar (indeterminate for active states, determinate otherwise)
            if (sample.state != StagedSampleState.Pending) {
                Spacer(modifier = Modifier.height(6.dp))
                when (sample.state) {
                    StagedSampleState.Converting, StagedSampleState.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    StagedSampleState.Done -> {
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth(),
                            color = TEColors.Teal,
                        )
                    }
                    StagedSampleState.Error -> {
                        LinearProgressIndicator(
                            progress = { sample.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {}
                }
            }

            // Error message
            if (sample.state == StagedSampleState.Error && sample.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sample.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
