package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.midi.MIDIPort

/**
 * Fake MIDIRepository for UI tests — no real MIDI hardware needed.
 * All send methods are no-ops, and the device-facing suspend seams are inert
 * (no SysEx round-trips, no timeouts) so connected-state tests stay fast.
 *
 * Pass [initialState] to pre-configure device state (e.g., permission states for CONN-04 tests),
 * or drive transitions mid-test with [updateDeviceState].
 */
open class TestMIDIRepository(
    initialState: DeviceState = DeviceState(),
    port: MIDIPort = NoOpMIDIPort(),
) : MIDIRepository(port) {

    init {
        // Seed the BASE state — noteOn/noteOff and the import preflight read the base
        // _deviceState directly, so an overridden deviceState val would leave them blind
        // to the test state (the port-null guard would silently drop every send).
        _deviceState.value = initialState
    }

    /** Drive a device-state transition mid-test (connect, permission change, stats update…). */
    fun updateDeviceState(transform: (DeviceState) -> DeviceState) {
        _deviceState.value = transform(_deviceState.value)
    }

    // Inert overrides: the real implementations send SysEx and await replies with multi-second
    // timeouts; against NoOpMIDIPort they would stall connected-state UI tests.
    override suspend fun queryDeviceStats(): Boolean = false
    override suspend fun getActiveGroupIndex(): Int? = null
    override suspend fun setActiveGroup(index: Int): Boolean = true
    override suspend fun listProjects(): List<ProjectSlot> = emptyList()

    /** Helper: create with a specific PermissionState and disconnected. */
    companion object {
        fun withPermissionState(state: PermissionState) = TestMIDIRepository(
            initialState = DeviceState(
                connected = false,
                permissionState = state,
            )
        )

        /** A plausible connected state — output port present so import/backup paths engage. */
        fun connectedState(
            firmwareVersion: String? = null,
            sampleCount: Int? = null,
            storageUsedBytes: Long? = null,
            storageTotalBytes: Long? = null,
        ) = DeviceState(
            connected = true,
            deviceName = "EP-133",
            outputPortId = "test-port",
            permissionState = PermissionState.GRANTED,
            firmwareVersion = firmwareVersion,
            sampleCount = sampleCount,
            storageUsedBytes = storageUsedBytes,
            storageTotalBytes = storageTotalBytes,
        )
    }
}

class NoOpMIDIPort : MIDIPort {
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
