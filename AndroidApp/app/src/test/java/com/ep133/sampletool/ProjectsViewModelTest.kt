package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** MIDIRepository with controllable device state — no hardware needed. */
private class ProjectsFakeMIDIRepo(initialConnected: Boolean = false) : MIDIRepository(ProjectsSpyMIDIPort(initialConnected)) {
    private val _state = MutableStateFlow(DeviceState(connected = initialConnected))
    override val deviceState get() = _state

    fun setConnected(connected: Boolean) { _state.value = DeviceState(connected = connected) }
}

/**
 * Wave 0 scaffold for ProjectsViewModel.
 *
 * Covers (when filled by Wave 2/3):
 *  - listProjects() maps 9 slot entries + marks the active slot
 *
 * ProjectsViewModel does not exist yet, so the placeholder exercises the ProjectsFakeMIDIRepo
 * test double (the seam Wave 2/3 will construct the VM against) and the coroutine
 * harness. Slot-mapping assertions are filled once the VM + listProjects() land.
 *
 * TODO(04-project-management-04): replace the placeholder with ProjectsViewModel
 * slot-mapping assertions (9 entries, active marker) once the VM exists.
 */
class ProjectsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun fakeRepo_reportsConnectionState() = runTest {
        // TODO(04-project-management-04): replace placeholder with listProjects() slot-mapping assertions
        val repo = ProjectsFakeMIDIRepo(initialConnected = true)
        assertTrue(repo.deviceState.value.connected)
        repo.setConnected(false)
        assertFalse(repo.deviceState.value.connected)
    }
}
