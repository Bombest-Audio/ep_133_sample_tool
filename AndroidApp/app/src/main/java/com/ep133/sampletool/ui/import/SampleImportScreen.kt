@file:Suppress("PackageName")
package com.ep133.sampletool.ui.`import`

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.midi.SampleImportProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * One item in the staged-import list.
 *
 * Tracks the import state per file: Pending → (Converting →) Loading → Done | Error.
 * [isDone] and [isError] extension functions support ViewModel test assertions.
 */
data class StagedSample(
    val name: String,
    val state: StagedSampleState = StagedSampleState.Pending,
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
 * ViewModel for the sample import screen (SAMPLE-01 / SAMPLE-03).
 *
 * Co-located with [SampleImportScreen] per project conventions (see CLAUDE.md).
 * Holds the staged-import list as a [StateFlow] and drives per-sample progress via
 * [SampleImportManager]. Mirrors [ProjectsViewModel]'s coroutine + state-flow shape.
 *
 * Wave 3 entry point: the real screen + SAF launcher live here; Wave 2 exposes only
 * the [importStagedBytes] testability seam needed by [SampleImportViewModelTest].
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
                        updateSample(index) { it.copy(state = StagedSampleState.Loading) }
                    }
                    is SampleImportProgress.Done -> {
                        updateSample(index) { it.copy(state = StagedSampleState.Done) }
                    }
                    is SampleImportProgress.Error -> {
                        updateSample(index) { it.copy(state = StagedSampleState.Error, errorMessage = progress.message) }
                        _snackbarMessage.value = progress.message
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /** Update a single staged item by index. */
    private fun updateSample(index: Int, transform: (StagedSample) -> StagedSample) {
        val current = _stagedSamples.value.toMutableList()
        if (index in current.indices) {
            current[index] = transform(current[index])
            _stagedSamples.value = current
        }
    }
}
