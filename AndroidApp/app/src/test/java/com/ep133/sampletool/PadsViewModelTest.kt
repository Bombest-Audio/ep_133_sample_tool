package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.midi.MIDIPort
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * FakeMIDIPortRecording: MIDIPort that records all sendMidi calls for assertion.
 */
class FakeMIDIPortRecording : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    val sentMessages: MutableList<Pair<String, ByteArray>> = mutableListOf()

    override fun getUSBDevices() = MIDIPort.Devices(
        inputs = listOf(MIDIPort.Device("port-in", "EP-133")),
        outputs = listOf(MIDIPort.Device("port-out", "EP-133")),
    )
    override fun sendMidi(portId: String, data: ByteArray) {
        sentMessages.add(portId to data)
    }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/** Wraps FakeMIDIPortRecording and provides named output port so noteOn/noteOff send. */
class RecordingMIDIRepository(val port: FakeMIDIPortRecording) : MIDIRepository(port) {
    init {
        // Simulate device connected so outputPortId is non-null
        port.onDevicesChanged?.invoke()
    }
}

/**
 * Tests for PadsViewModel multi-touch velocity support and scale lock.
 * Wave 0 stubs — production code in Task 3-01.
 */
class PadsViewModelTest {

    @Ignore("Wave 1 — padDown velocity param not yet added")
    @Test
    fun padDown_withVelocity_sendsNoteOnWithCorrectVelocity() {
        // val port = FakeMIDIPortRecording()
        // val fakeMidi = RecordingMIDIRepository(port)
        // val vm = PadsViewModel(fakeMidi)
        // vm.padDown(0, 64)
        // val noteOn = port.sentMessages.lastOrNull()?.second
        // assertNotNull(noteOn)
        // assertEquals(64, noteOn!![2].toInt() and 0x7F)
    }

    @Ignore("Wave 1 — padDown velocity param not yet added")
    @Test
    fun padDown_defaultVelocity_is100() {
        // val port = FakeMIDIPortRecording()
        // val fakeMidi = RecordingMIDIRepository(port)
        // val vm = PadsViewModel(fakeMidi)
        // vm.padDown(0)
        // val noteOn = port.sentMessages.lastOrNull()?.second
        // assertNotNull(noteOn)
        // assertEquals(100, noteOn!![2].toInt() and 0x7F)
    }

    @Ignore("Wave 1 — padDown velocity param not yet added")
    @Test
    fun multiTouch_twoSimultaneousPadDowns_sendsTwoNoteOns() {
        // val port = FakeMIDIPortRecording()
        // val fakeMidi = RecordingMIDIRepository(port)
        // val vm = PadsViewModel(fakeMidi)
        // vm.padDown(0, 80)
        // vm.padDown(1, 90)  // no padUp between
        // val noteOns = port.sentMessages.filter { (it.second[0].toInt() and 0xF0) == 0x90 }
        // assertEquals(2, noteOns.size)
    }

    @Test
    fun velocity_fromPressure_halfPressure_yields63or64() {
        // Pure math test — no ViewModel dependency
        val pressure = 0.5f
        val velocity = (pressure.coerceIn(0f, 1f) * 127).toInt().coerceAtLeast(1)
        assertTrue("Half pressure should yield 63 or 64", velocity in 63..64)
    }
}
