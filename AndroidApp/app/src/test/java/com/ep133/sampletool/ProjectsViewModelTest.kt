package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.projects.ProjectsViewModel
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

// ── Test doubles (mirror ChordsViewModelTest's VM-test seam; renamed to avoid
//    top-level redeclaration clashes across the shared test source set) ──

private class ProjectsSpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
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

    override fun sendMidi(portId: String, data: ByteArray) { sent.add(data) }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * MIDIRepository with controllable device state + a canned [listProjects] result — no hardware.
 *
 * [listProjects] is `open` so the test can return a fixed 9-slot enumeration (one active)
 * without driving the real SysEx FILE_LIST request-response cycle.
 */
private class ProjectsFakeMIDIRepo(
    initialConnected: Boolean = false,
    private val cannedSlots: List<MIDIRepository.ProjectSlot> = emptyList(),
) : MIDIRepository(ProjectsSpyMIDIPort(initialConnected)) {
    private val _state = MutableStateFlow(DeviceState(connected = initialConnected))
    override val deviceState get() = _state

    fun setConnected(connected: Boolean) { _state.value = DeviceState(connected = connected) }

    override suspend fun listProjects(): List<MIDIRepository.ProjectSlot> = cannedSlots
}

private fun nineSlots(): List<MIDIRepository.ProjectSlot> =
    (0..8).map { i ->
        MIDIRepository.ProjectSlot(
            nodeId = 100 + i,
            name = "P%02d".format(i),
            sizeBytes = (i + 1) * 1024L,
            isActive = i == 3,   // exactly one active slot
        )
    }

/**
 * ProjectsViewModel unit tests (PROJ-01).
 *
 * Asserts the VM maps the device enumeration into its `slots` StateFlow: 9 entries with
 * exactly one marked active. Uses [ProjectsFakeMIDIRepo] (the established VM-test seam) so no
 * hardware or real SysEx round-trip is required.
 */
class ProjectsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun loadProjects_mapsNineSlots_oneActive() = runTest {
        val repo = ProjectsFakeMIDIRepo(initialConnected = true, cannedSlots = nineSlots())
        val vm = ProjectsViewModel(repo, ProjectBackupManager(repo))

        vm.loadProjects()
        advanceUntilIdle()

        val slots = vm.slots.value
        assertEquals(9, slots.size)
        assertEquals(1, slots.count { it.isActive })
        assertEquals("P03", slots.first { it.isActive }.name)
    }

    @Test
    fun loadProjects_whenEmpty_leavesSlotsEmpty() = runTest {
        val repo = ProjectsFakeMIDIRepo(initialConnected = false, cannedSlots = emptyList())
        val vm = ProjectsViewModel(repo, ProjectBackupManager(repo))

        vm.loadProjects()
        advanceUntilIdle()

        assertTrue(vm.slots.value.isEmpty())
    }
}
