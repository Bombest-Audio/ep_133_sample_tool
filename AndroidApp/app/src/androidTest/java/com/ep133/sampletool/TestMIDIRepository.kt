package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.midi.MIDIPort

/**
 * Fake MIDIRepository for UI tests — no real MIDI hardware needed.
 * All send methods are no-ops. Device state stays disconnected.
 */
class TestMIDIRepository : MIDIRepository(NoOpMIDIPort())

private class NoOpMIDIPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun close() {}
}
