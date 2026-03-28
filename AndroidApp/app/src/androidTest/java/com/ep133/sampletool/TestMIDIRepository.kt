package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake MIDIRepository for UI tests — no real MIDI hardware needed.
 * All send methods are no-ops.
 *
 * Pass [initialState] to pre-configure device state (e.g., permission states for CONN-04 tests).
 */
open class TestMIDIRepository(
    initialState: DeviceState = DeviceState(),
) : MIDIRepository(NoOpMIDIPort()) {

    private val _testDeviceState = MutableStateFlow(initialState)

    override val deviceState get() = _testDeviceState

    /** Helper: create with a specific PermissionState and disconnected. */
    companion object {
        fun withPermissionState(state: PermissionState) = TestMIDIRepository(
            initialState = DeviceState(
                connected = false,
                permissionState = state,
            )
        )
    }
}

private class NoOpMIDIPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}
