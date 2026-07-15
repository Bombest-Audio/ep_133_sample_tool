package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BatchImportEvent
import com.ep133.sampletool.domain.midi.BatchImportItem
import com.ep133.sampletool.domain.midi.BatchPackImporter
import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles (named Batch* to avoid top-level redeclaration clashes across the
// shared test source set - see SampleImportViewModelTest's note).
// ─────────────────────────────────────────────────────────────────────────────

/** Inert MIDIPort - the batch importer never talks to the wire in these tests. */
private class BatchSpyPort(private val connected: Boolean = true) : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    override fun getUSBDevices() = if (connected) {
        MIDIPort.Devices(
            inputs = listOf(MIDIPort.Device("in", "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )
    } else {
        MIDIPort.Devices(emptyList(), emptyList())
    }

    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake repo recording putSampleFile calls. Scriptable per-call results (node ID, null,
 * or exception) and an optional per-call gate so tests can suspend an upload mid-batch
 * and cancel the collector while it's parked there.
 */
private class BatchFakeRepo(
    connected: Boolean = true,
    storageUsed: Long? = null,
    storageTotal: Long? = null,
) : MIDIRepository(BatchSpyPort(connected)) {

    private val _state = MutableStateFlow(
        DeviceState(
            connected = connected,
            outputPortId = if (connected) "out" else null,
            storageUsedBytes = storageUsed,
            storageTotalBytes = storageTotal,
        ),
    )
    override val deviceState get() = _state

    init { _deviceState.value = _state.value }

    /** Names passed to putSampleFile, in call order. */
    val uploaded = mutableListOf<String>()

    /** Per-name scripted result; missing = success with a fake node ID. */
    val resultsByName = mutableMapOf<String, Result<Int?>>()

    /** When set for a name, putSampleFile suspends on it before returning. */
    val gatesByName = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        uploaded += name
        gatesByName[name]?.await()
        val scripted = resultsByName[name] ?: return 42
        return scripted.getOrThrow()
    }
}

private fun pcm(bytes: Int) = ConvertedSample(ByteArray(bytes), channels = 1, sampleRate = 46875)

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

class BatchPackImporterTest {

    private fun importer(repo: BatchFakeRepo) = BatchPackImporter(repo, SampleImportManager(repo))

    private val items = listOf(
        BatchImportItem("kick.wav"),
        BatchImportItem("snare.wav"),
        BatchImportItem("hat.wav"),
    )

    // Happy path: Converting x3, then Uploading/FileDone per file in order, then BatchComplete.
    @Test
    fun importsAllFiles_eventsInOrder() = runTest {
        val repo = BatchFakeRepo()
        val events = importer(repo).import(items) { pcm(100) }.toList()

        val expected = listOf(
            BatchImportEvent.Converting(0, 3, "kick.wav"),
            BatchImportEvent.Converting(1, 3, "snare.wav"),
            BatchImportEvent.Converting(2, 3, "hat.wav"),
            BatchImportEvent.Uploading(0, 3, "kick.wav"),
            BatchImportEvent.FileDone(0, 3, "kick.wav"),
            BatchImportEvent.Uploading(1, 3, "snare.wav"),
            BatchImportEvent.FileDone(1, 3, "snare.wav"),
            BatchImportEvent.Uploading(2, 3, "hat.wav"),
            BatchImportEvent.FileDone(2, 3, "hat.wav"),
            BatchImportEvent.BatchComplete(ok = 3, failed = 0),
        )
        assertEquals(expected, events)
        assertEquals(listOf("kick.wav", "snare.wav", "hat.wav"), repo.uploaded)
    }

    // Preflight: batch is blocked BEFORE any upload when converted sizes exceed free space.
    @Test
    fun preflightBlocksBatch_whenNotEnoughFreeSpace() = runTest {
        // 100 KB free; three 50 KB samples = 150 KB required.
        val repo = BatchFakeRepo(storageUsed = 900 * 1024L, storageTotal = 1000 * 1024L)
        val events = importer(repo).import(items) { pcm(50 * 1024) }.toList()

        assertTrue(events.last() is BatchImportEvent.Blocked)
        assertTrue(repo.uploaded.isEmpty())
        assertFalse(events.any { it is BatchImportEvent.Uploading })
    }

    // Preflight passes when the batch fits exactly.
    @Test
    fun preflightAllowsBatch_whenItFits() = runTest {
        val repo = BatchFakeRepo(storageUsed = 0L, storageTotal = 150 * 1024L)
        val events = importer(repo).import(items) { pcm(50 * 1024) }.toList()

        assertEquals(BatchImportEvent.BatchComplete(ok = 3, failed = 0), events.last())
        assertEquals(3, repo.uploaded.size)
    }

    // Corrupt/over-reported stats (used > total) clamp to 0 free via availableStorageBytes()
    // and block instead of arithmetic-underflowing into a bogus allow.
    @Test
    fun preflightBlocks_whenStorageStatsReportNegativeFree() = runTest {
        val repo = BatchFakeRepo(storageUsed = 2000 * 1024L, storageTotal = 1000 * 1024L)
        val events = importer(repo).import(items) { pcm(1) }.toList()

        assertTrue(events.last() is BatchImportEvent.Blocked)
        assertTrue(repo.uploaded.isEmpty())
    }

    // Unknown storage (stats not yet queried) → best-effort allow, matching SampleImportManager.
    @Test
    fun preflightAllows_whenStorageUnknown() = runTest {
        val repo = BatchFakeRepo(storageUsed = null, storageTotal = null)
        val events = importer(repo).import(items) { pcm(10_000_000) }.toList()
        assertEquals(BatchImportEvent.BatchComplete(ok = 3, failed = 0), events.last())
    }

    // No device → Blocked, nothing converted or uploaded.
    @Test
    fun blocksWhenDisconnected() = runTest {
        val repo = BatchFakeRepo(connected = false)
        var converted = 0
        val events = importer(repo).import(items) { converted++; pcm(100) }.toList()

        assertEquals(listOf<BatchImportEvent>(BatchImportEvent.Blocked("No EP-133 connected")), events)
        assertEquals(0, converted)
        assertTrue(repo.uploaded.isEmpty())
    }

    // A convert failure marks that file failed, is excluded from preflight, and the rest proceed.
    @Test
    fun convertFailure_failsFileButBatchContinues() = runTest {
        val repo = BatchFakeRepo()
        val events = importer(repo).import(items) {
            if (it.name == "snare.wav") throw IllegalArgumentException("too long")
            pcm(100)
        }.toList()

        val failed = events.filterIsInstance<BatchImportEvent.FileFailed>()
        assertEquals(1, failed.size)
        assertEquals("snare.wav", failed[0].name)
        assertTrue(failed[0].message.contains("too long"))
        assertEquals(BatchImportEvent.BatchComplete(ok = 2, failed = 1), events.last())
        assertEquals(listOf("kick.wav", "hat.wav"), repo.uploaded)
    }

    // An upload rejection (null node ID) fails that file; the batch continues.
    @Test
    fun uploadRejected_failsFileButBatchContinues() = runTest {
        val repo = BatchFakeRepo()
        repo.resultsByName["snare.wav"] = Result.success(null)
        val events = importer(repo).import(items) { pcm(100) }.toList()

        val failed = events.filterIsInstance<BatchImportEvent.FileFailed>()
        assertEquals(listOf("snare.wav"), failed.map { it.name })
        assertEquals(BatchImportEvent.BatchComplete(ok = 2, failed = 1), events.last())
        assertEquals(3, repo.uploaded.size)
    }

    // An upload exception fails that file; the batch continues.
    @Test
    fun uploadException_failsFileButBatchContinues() = runTest {
        val repo = BatchFakeRepo()
        repo.resultsByName["kick.wav"] = Result.failure(RuntimeException("timeout"))
        val events = importer(repo).import(items) { pcm(100) }.toList()

        val failed = events.filterIsInstance<BatchImportEvent.FileFailed>()
        assertEquals(listOf("kick.wav"), failed.map { it.name })
        assertTrue(failed[0].message.contains("timeout"))
        assertEquals(BatchImportEvent.BatchComplete(ok = 2, failed = 1), events.last())
    }

    // Cancellation mid-batch: the in-flight putSampleFile is cancelled (FTC's own unwind sends
    // the terminator) and NO further upload starts - the CancellationException propagates.
    @Test
    fun cancellationMidBatch_stopsFurtherUploads() = runTest {
        val repo = BatchFakeRepo()
        val gate = CompletableDeferred<Unit>()
        repo.gatesByName["snare.wav"] = gate

        val seen = mutableListOf<BatchImportEvent>()
        val job = launch {
            importer(repo).import(items) { pcm(100) }.collect { seen += it }
        }
        advanceUntilIdle()

        // Parked inside the second upload - first done, second started, third not.
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)

        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)      // no third upload
        assertFalse(seen.any { it is BatchImportEvent.BatchComplete })    // batch never completed
        assertEquals(1, seen.filterIsInstance<BatchImportEvent.FileDone>().size)
    }

    // Empty batch → immediate BatchComplete(0, 0), no device interaction.
    @Test
    fun emptyBatch_completesImmediately() = runTest {
        val repo = BatchFakeRepo()
        val events = importer(repo).import(emptyList()) { pcm(100) }.toList()
        assertEquals(listOf<BatchImportEvent>(BatchImportEvent.BatchComplete(0, 0)), events)
        assertTrue(repo.uploaded.isEmpty())
    }

    // Names are sanitized before hitting the device (spaces kept, illegal chars replaced).
    @Test
    fun namesAreSanitizedBeforeUpload() = runTest {
        val repo = BatchFakeRepo()
        val dirty = listOf(BatchImportItem("SON WU - BEAR HUGGER KICK.wav"))
        importer(repo).import(dirty) { pcm(100) }.toList()
        assertEquals(listOf("SON WU - BEAR HUGGER KICK.wav"), repo.uploaded)
    }
}
