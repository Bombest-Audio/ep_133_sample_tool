package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BatchImportItem
import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles (named KbImport* to avoid top-level redeclaration clashes across
// the shared test source set).
// ─────────────────────────────────────────────────────────────────────────────

private class KbImportSpyPort(private val connected: Boolean = true) : MIDIPort {
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

private class KbImportFakeRepo(
    connected: Boolean = true,
    storageUsed: Long? = null,
    storageTotal: Long? = null,
) : MIDIRepository(KbImportSpyPort(connected)) {

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

    val uploaded = mutableListOf<String>()
    val resultsByName = mutableMapOf<String, Result<Int?>>()
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

private fun kbPcm(bytes: Int = 100) = ConvertedSample(ByteArray(bytes), 1, 46875)

// ─────────────────────────────────────────────────────────────────────────────
// Tests - KitBuilderViewModel batch-import state via the importPack(items, convert) seam
// ─────────────────────────────────────────────────────────────────────────────

class KitBuilderImportTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(repo: KbImportFakeRepo) = KitBuilderViewModel(repo, SampleImportManager(repo))

    private val items = listOf(
        BatchImportItem("kick.wav"),
        BatchImportItem("snare.wav"),
    )

    @Test
    fun importPack_success_recordsResultsAndFinishes() = runTest {
        val repo = KbImportFakeRepo()
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()

        val st = vm.state.value.importState
        assertFalse(st.running)
        assertTrue(st.finished)
        assertEquals(2, st.total)
        assertEquals(2, st.processed)
        assertEquals(listOf(true, true), st.results.map { it.ok })
        assertEquals(listOf("kick.wav", "snare.wav"), st.results.map { it.name })
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)
    }

    @Test
    fun importPack_partialFailure_flagsFailedRow() = runTest {
        val repo = KbImportFakeRepo()
        repo.resultsByName["snare.wav"] = Result.success(null)  // device says no
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()

        val st = vm.state.value.importState
        assertEquals(2, st.processed)
        val snare = st.results.first { it.name == "snare.wav" }
        assertFalse(snare.ok)
        assertNotNull(snare.message)
        assertTrue(st.results.first { it.name == "kick.wav" }.ok)
    }

    @Test
    fun importPack_preflightBlocked_setsBlockedAndNoUploads() = runTest {
        // 1 KB free, two 100 KB samples.
        val repo = KbImportFakeRepo(storageUsed = 1023 * 1024L, storageTotal = 1024 * 1024L)
        val vm = vm(repo)

        vm.importPack(items) { kbPcm(100 * 1024) }
        advanceUntilIdle()

        val st = vm.state.value.importState
        assertNotNull(st.blocked)
        assertTrue(st.finished)
        assertTrue(repo.uploaded.isEmpty())
    }

    @Test
    fun importPack_disconnected_snackbarAndNoJob() = runTest {
        val repo = KbImportFakeRepo(connected = false)
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()

        assertFalse(vm.state.value.importState.active)
        assertEquals("Connect the EP-133 before importing", vm.snackbarMessage.value)
        assertTrue(repo.uploaded.isEmpty())
    }

    @Test
    fun importPack_reentrant_ignoredWhileRunning() = runTest {
        val repo = KbImportFakeRepo()
        val gate = CompletableDeferred<Unit>()
        repo.gatesByName["kick.wav"] = gate
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()
        assertTrue(vm.state.value.importState.running)

        // Second trigger while running must be a no-op (guarded trigger).
        vm.importPack(listOf(BatchImportItem("hat.wav"))) { kbPcm() }
        advanceUntilIdle()
        assertEquals(2, vm.state.value.importState.total)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)
    }

    @Test
    fun cancelImport_midBatch_stopsUploadsAndResetsRunning() = runTest {
        val repo = KbImportFakeRepo()
        val gate = CompletableDeferred<Unit>()
        repo.gatesByName["snare.wav"] = gate
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)  // parked in snare upload
        assertTrue(vm.state.value.importState.running)

        vm.onCancelImport()
        advanceUntilIdle()

        val st = vm.state.value.importState
        assertFalse(st.running)            // finally-reset even on cancellation
        assertTrue(st.finished)            // partial results stay visible
        assertEquals(1, st.results.size)   // only kick completed
        assertTrue(st.results[0].ok)
        assertEquals(listOf("kick.wav", "snare.wav"), repo.uploaded)  // nothing new after cancel
    }

    @Test
    fun dismissImportPanel_clearsStateAndSelection() = runTest {
        val repo = KbImportFakeRepo()
        val vm = vm(repo)

        vm.importPack(items) { kbPcm() }
        advanceUntilIdle()
        assertTrue(vm.state.value.importState.active)

        vm.dismissImportPanel()
        advanceUntilIdle()

        assertFalse(vm.state.value.importState.active)
        assertTrue(vm.state.value.selectedForImport.isEmpty())
    }
}
