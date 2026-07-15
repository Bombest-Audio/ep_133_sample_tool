package com.ep133.sampletool.domain.midi

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "EP133APP"

/**
 * One pack sample queued for a batch import: display [name] + SAF tree-scoped [uri].
 *
 * [uri] is nullable only so local unit tests can build items without an Android runtime
 * (android.net.Uri is unmockable there); production callers always supply it, and the
 * [BatchPackImporter.import] Context overload requires it.
 */
data class BatchImportItem(val name: String, val uri: Uri? = null)

/**
 * Events emitted by [BatchPackImporter.import], in order:
 *
 * 1. [Blocked] (terminal) if the device is missing or the free-space preflight fails.
 * 2. [Converting] once per item while the convert phase runs.
 * 3. [Uploading] then [FileDone] or [FileFailed] once per item during the upload phase.
 * 4. [BatchComplete] (terminal) with the ok/failed tallies.
 *
 * [index] is 0-based within [total] items.
 */
sealed class BatchImportEvent {
    /** Batch never started: no device, or the preflight rejected it. */
    data class Blocked(val message: String) : BatchImportEvent()
    /** Item [index] of [total] is being decoded/resampled to device format. */
    data class Converting(val index: Int, val total: Int, val name: String) : BatchImportEvent()
    /** Item [index] of [total] is uploading to /sounds. */
    data class Uploading(val index: Int, val total: Int, val name: String) : BatchImportEvent()
    /** Item [index] finished uploading and was confirmed by the device. */
    data class FileDone(val index: Int, val total: Int, val name: String) : BatchImportEvent()
    /** Item [index] failed to convert or upload; the batch continues with the next item. */
    data class FileFailed(
        val index: Int,
        val total: Int,
        val name: String,
        val message: String,
    ) : BatchImportEvent()
    /** All items processed. [ok] + [failed] == item count. */
    data class BatchComplete(val ok: Int, val failed: Int) : BatchImportEvent()
}

/**
 * Sequential batch upload of pack one-shots to /sounds with per-file progress.
 *
 * Two phases:
 * - **Convert**: every item is decoded/resampled up front (per-item failures become
 *   [BatchImportEvent.FileFailed] and are excluded from the preflight sum).
 * - **Preflight + upload**: the summed converted byte size is checked against the device's
 *   remaining /sounds storage ([DeviceState.storageUsedBytes] / storageTotalBytes, populated
 *   by queryDeviceStats). If it doesn't fit, the whole batch is blocked BEFORE any device
 *   write. Uploads then run strictly sequentially via [MIDIRepository.putSampleFile].
 *
 * Wedge safety: cancellation mid-upload is handled inside [FileTransferClient.putSampleFile],
 * which best-effort sends the zero-length DATA terminator to close an in-flight PUT. This
 * class only has to rethrow [CancellationException] (never swallow it) so that unwind path
 * actually runs; a cancelled batch therefore never leaves the device wedged.
 *
 * Storage-unknown policy matches [SampleImportManager]: if stats haven't been read yet the
 * preflight allows the batch (best-effort - the device rejects individual writes when full).
 */
class BatchPackImporter(
    private val midi: MIDIRepository,
    private val manager: SampleImportManager,
) {

    /** Convenience entry point: converts each item via [SampleImportManager.convert]. */
    fun import(items: List<BatchImportItem>, context: Context): Flow<BatchImportEvent> =
        import(items) { item ->
            manager.convert(context, requireNotNull(item.uri) { "BatchImportItem.uri required" })
        }

    /**
     * Import [items] to /sounds, converting each via [convert].
     *
     * The [convert] seam keeps the batch state machine unit-testable without SAF or
     * AudioDecoder (tests pass canned [ConvertedSample]s). Production callers use the
     * [Context] overload above.
     *
     * Cold flow: nothing touches the device until collected; cancelling the collector
     * cancels the in-flight upload (see class doc for the terminator contract).
     */
    fun import(
        items: List<BatchImportItem>,
        convert: suspend (BatchImportItem) -> ConvertedSample,
    ): Flow<BatchImportEvent> = flow {
        if (items.isEmpty()) {
            emit(BatchImportEvent.BatchComplete(ok = 0, failed = 0))
            return@flow
        }
        if (midi.deviceState.value.outputPortId == null) {
            emit(BatchImportEvent.Blocked("No EP-133 connected"))
            return@flow
        }

        val total = items.size

        // Phase 1: convert everything up front so the preflight can sum real device-format sizes.
        // convertFailures holds per-item error text; converted holds index -> sample for the rest.
        val converted = LinkedHashMap<Int, ConvertedSample>()
        val convertFailures = LinkedHashMap<Int, String>()
        items.forEachIndexed { i, item ->
            emit(BatchImportEvent.Converting(i, total, item.name))
            try {
                converted[i] = convert(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "BatchPackImporter: convert failed for ${item.name}", e)
                convertFailures[i] = "Convert failed: ${e.message ?: e}"
            }
        }

        // Phase 2: free-space preflight over the items that actually converted.
        val requiredBytes = converted.values.sumOf { it.pcm.size.toLong() }
        val available = availableBytes()
        if (available != null && requiredBytes > available) {
            emit(
                BatchImportEvent.Blocked(
                    "Not enough space on EP-133: batch needs ${requiredBytes / 1024} KB, " +
                        "only ${available / 1024} KB free"
                )
            )
            return@flow
        }

        // Phase 3: sequential uploads. Per-item failures don't stop the batch;
        // CancellationException always rethrows so FTC's terminator unwind runs.
        var ok = 0
        var failed = 0
        items.forEachIndexed { i, item ->
            val convertError = convertFailures[i]
            if (convertError != null) {
                failed++
                emit(BatchImportEvent.FileFailed(i, total, item.name, convertError))
                return@forEachIndexed
            }
            val sample = converted.getValue(i)
            val safeName = manager.sanitizeName(item.name)
            if (safeName == null) {
                failed++
                emit(BatchImportEvent.FileFailed(i, total, item.name, "Invalid sample name"))
                return@forEachIndexed
            }

            emit(BatchImportEvent.Uploading(i, total, item.name))
            val nodeId = try {
                midi.putSampleFile(safeName, sample.pcm, sample.channels, sample.sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "BatchPackImporter: upload failed for $safeName", e)
                failed++
                emit(BatchImportEvent.FileFailed(i, total, item.name, "Upload failed: ${e.message ?: e}"))
                return@forEachIndexed
            }

            if (nodeId != null) {
                ok++
                emit(BatchImportEvent.FileDone(i, total, item.name))
            } else {
                failed++
                emit(
                    BatchImportEvent.FileFailed(
                        i, total, item.name,
                        "Upload not confirmed by EP-133 (no STATUS_OK)",
                    )
                )
            }
        }

        emit(BatchImportEvent.BatchComplete(ok = ok, failed = failed))
    }

    /** Remaining /sounds bytes, or null when device stats haven't been read yet. */
    private fun availableBytes(): Long? {
        val state = midi.deviceState.value
        val used = state.storageUsedBytes ?: return null
        val total = state.storageTotalBytes ?: return null
        return total - used
    }
}
