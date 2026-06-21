package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.import.SampleImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles (renamed from ProjectsViewModelTest's doubles to avoid top-level
// redeclaration clashes across the shared test source set — see STATE.md Phase 4 note).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Spy MIDIPort that records all sendMidi calls and can simulate connected/disconnected state.
 * Named to avoid clashing with ProjectsSpyMIDIPort in ProjectsViewModelTest.kt.
 */
private class SampleImportSpyPort(private val connected: Boolean = false) : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    val sent = mutableListOf<ByteArray>()

    override fun getUSBDevices() = if (connected) {
        MIDIPort.Devices(
            inputs = listOf(MIDIPort.Device("in", "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )
    } else {
        MIDIPort.Devices(emptyList(), emptyList())
    }

    override fun sendMidi(portId: String, data: ByteArray) { sent.add(data.copyOf()) }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake MIDIRepository with controllable device state for ViewModel testing.
 * Sets the protected _deviceState so the import manager can observe connection status.
 * Named to avoid clashing with ProjectsFakeMIDIRepo in ProjectsViewModelTest.kt.
 */
private class SampleImportFakeRepo(
    val spy: SampleImportSpyPort,
    connected: Boolean = false,
) : MIDIRepository(spy) {
    private val _state = MutableStateFlow(
        if (connected) DeviceState(connected = true, outputPortId = "out")
        else DeviceState(connected = false, outputPortId = null),
    )
    override val deviceState get() = _state

    init {
        _deviceState.value = _state.value
    }

    fun setConnected(connected: Boolean) {
        val state = if (connected) DeviceState(connected = true, outputPortId = "out")
                    else DeviceState(connected = false, outputPortId = null)
        _state.value = state
        _deviceState.value = state
    }

    // putSampleFile returns true when connected, false when disconnected — matches
    // SampleImportManager's corrected fail-closed contract (Codex fix #2).
    override suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean =
        _state.value.connected
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RED (Wave 0): Asserts the SampleImportViewModel + SampleImportManager state-machine contract.
 *
 * Compile-fails on:
 *   - `SampleImportManager` (Wave 2 class, domain/midi/SampleImportManager.kt)
 *   - `SampleImportViewModel` (Wave 3 class, ui/import/SampleImportScreen.kt)
 *   - `stagedSamples` StateFlow on the ViewModel
 *   - `importStagedBytes(name, bytes)` testability seam on the ViewModel
 *
 * No changes to these tests should be needed to turn them GREEN — the production
 * implementations must match this contract.
 *
 * Note: Real SAF URI reads (contentResolver.openInputStream / content:// URI) are
 * hardware/instrumentation-only. These tests cover the state-machine via
 * `importStagedBytes(name, bytes)` — the testability seam the VM exposes for
 * pre-read byte arrays (per 05-VALIDATION Manual-Only section).
 */
class SampleImportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: importStagedBytes maps to stagedSamples in Pending state initially
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun importStagedBytes_mapsToStagedList_pendingInitially() = runTest {
        val spy = SampleImportSpyPort(connected = true)
        val repo = SampleImportFakeRepo(spy, connected = true)
        // Compile-fails until Wave 2 creates SampleImportManager and Wave 3 creates ViewModel
        val manager = SampleImportManager(repo)
        val vm = SampleImportViewModel(repo, manager)

        // Each call to importStagedBytes should add an item to stagedSamples in Pending state
        vm.importStagedBytes("kick.wav", ByteArray(100) { 0 })

        advanceUntilIdle()

        val staged = vm.stagedSamples.value
        assertEquals("One staged entry per importStagedBytes call", 1, staged.size)
        assertEquals("Filename must match", "kick.wav", staged[0].name)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Connected import advances from Pending → Done
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun importStagedBytes_whenConnected_advancesToDone() = runTest {
        val spy = SampleImportSpyPort(connected = true)
        val repo = SampleImportFakeRepo(spy, connected = true)
        val manager = SampleImportManager(repo)
        val vm = SampleImportViewModel(repo, manager)

        vm.importStagedBytes("snare.wav", ByteArray(200) { (it % 256).toByte() })

        advanceUntilIdle()

        val staged = vm.stagedSamples.value
        assertEquals("One staged item", 1, staged.size)
        // The final state must be Done (import acknowledged) — not Pending or Error
        // StagedSample state is a sealed class or enum; assert it's the Done variant
        assertTrue(
            "Staged item must advance to Done after connected import; got: ${staged[0]}",
            staged[0].isDone(),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: Disconnected import advances to Error + sets snackbarMessage
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun importStagedBytes_whenDisconnected_advancesToErrorWithMessage() = runTest {
        val spy = SampleImportSpyPort(connected = false)
        val repo = SampleImportFakeRepo(spy, connected = false)
        val manager = SampleImportManager(repo)
        val vm = SampleImportViewModel(repo, manager)

        vm.importStagedBytes("hihat.wav", ByteArray(200) { 0 })

        advanceUntilIdle()

        val staged = vm.stagedSamples.value
        assertEquals("One staged item", 1, staged.size)
        // Must advance to Error state (device not connected)
        assertTrue(
            "Staged item must advance to Error when disconnected; got: ${staged[0]}",
            staged[0].isError(),
        )
        // Must emit a snackbar message mentioning "EP-133" or "connected"
        val msg = vm.snackbarMessage.value
        assertNotNull("snackbarMessage must be set on disconnected import error", msg)
        assertTrue(
            "snackbarMessage must mention connection state; got: $msg",
            msg!!.contains("EP-133", ignoreCase = true) || msg.contains("connect", ignoreCase = true),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: Multiple staged bytes map to multiple list entries
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun importStagedBytes_multipleFiles_producesMultipleEntries() = runTest {
        val spy = SampleImportSpyPort(connected = false)
        val repo = SampleImportFakeRepo(spy, connected = false)
        val manager = SampleImportManager(repo)
        val vm = SampleImportViewModel(repo, manager)

        vm.importStagedBytes("kick.wav", ByteArray(100) { 0 })
        vm.importStagedBytes("snare.wav", ByteArray(100) { 0 })
        vm.importStagedBytes("hihat.wav", ByteArray(100) { 0 })

        advanceUntilIdle()

        val staged = vm.stagedSamples.value
        assertEquals("Three staged entries (one per importStagedBytes call)", 3, staged.size)
        assertEquals("kick.wav", staged[0].name)
        assertEquals("snare.wav", staged[1].name)
        assertEquals("hihat.wav", staged[2].name)
    }
}
