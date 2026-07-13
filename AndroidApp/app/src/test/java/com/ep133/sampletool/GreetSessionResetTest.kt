package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression test for issue #27, option 1: the session reset lives on the port **connect edge**
 * ([MIDIRepository.updateDeviceStateOnly] → `ftc.onDeviceConnected()`), not on the greet response.
 *
 * A greet response is therefore pure data (firmware/identity) and must NOT fail in-flight file
 * waiters. The old reset-on-greet path could tear down a healthy session when a second greet
 * arrived mid-op — but the device does not double-send on the wire (hardware-verified; the
 * historical "double-send" was an already-fixed Android dual-receiver bug), so resetting from the
 * greet handler was both unnecessary and hazardous.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GreetSessionResetTest {

    private fun reqIdFrom(frame: ByteArray): Int =
        ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)

    /** A CMD_GREET response frame (device identity + firmware), status 0. */
    private fun greetFrame(deviceId: Int = 0x33): ByteArray {
        val ascii = "product:EP-133;sw_version:2.5.0".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00)
        return byteArrayOf(
            SysExProtocol.MIDI_SYSEX_START,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            deviceId.toByte(),
            SysExProtocol.MIDI_SYSEX_TE,
            SysExProtocol.BIT_REQUEST_ID_AVAILABLE.toByte(),
            0x01,
            SysExProtocol.CMD_GREET.toByte(),
            0x00,
        ) + SysExProtocol.pack7bit(ascii) + byteArrayOf(SysExProtocol.MIDI_SYSEX_END)
    }

    /** A FILE_INFO response echoing [reqId] for node [nodeId]. */
    private fun infoResponse(reqId: Int, nodeId: Int): ByteArray {
        val body = byteArrayOf(
            ((nodeId shr 8) and 0xFF).toByte(),
            (nodeId and 0xFF).toByte(),
            0x01, 0x02,               // parentId
            SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE.toByte(),
            0x00, 0x00, 0x10, 0x00,   // size
        ) + "kick.wav".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        return byteArrayOf(
            SysExProtocol.MIDI_SYSEX_START,
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,
            SysExProtocol.MIDI_SYSEX_TE,
            (SysExProtocol.BIT_IS_REQUEST or
                SysExProtocol.BIT_REQUEST_ID_AVAILABLE or
                ((reqId shr 7) and 0x0F)).toByte(),
            (reqId and 0x7F).toByte(),
            SysExProtocol.TE_SYSEX_FILE.toByte(),
            0x00,
        ) + SysExProtocol.pack7bit(body) + byteArrayOf(SysExProtocol.MIDI_SYSEX_END)
    }

    private class RecordingPort : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null
        val sent = mutableListOf<ByteArray>()
        override fun getUSBDevices() = MIDIPort.Devices(
            inputs = listOf(MIDIPort.Device("in", "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )
        override fun sendMidi(portId: String, data: ByteArray) { sent.add(data.copyOf()) }
        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}
    }

    private class TestRepo(port: RecordingPort) : MIDIRepository(port) {
        init { _deviceState.value = DeviceState(connected = true, outputPortId = "out") }
        fun inject(frame: ByteArray) = dispatchSysEx(frame)
    }

    private suspend fun RecordingPort.awaitSentCount(count: Int) {
        repeat(10) { if (sent.size >= count) return; yield() }
    }

    @Test
    fun greetResponse_doesNotTearDownInFlightFileOp() = runTest {
        // Arrange: a file op is in flight, its reqId-correlated waiter registered.
        val port = RecordingPort()
        val repo = TestRepo(port)
        val op = async(Dispatchers.Unconfined) { repo.getNodeInfo(0x1234) }
        port.awaitSentCount(1)
        val reqId = reqIdFrom(port.sent[0])

        // Act: a greet response lands mid-op (e.g. a stats refresh). This used to fail the waiter.
        repo.inject(greetFrame())
        yield()

        // Assert: the in-flight op is untouched by the greet...
        assertFalse("a greet response must not complete/fail an in-flight file op", op.isCompleted)

        // ...and the real file response still routes by reqId and completes it.
        repo.inject(infoResponse(reqId, 0x1234))
        val info = op.await()
        assertNotNull("the file op completes on its own response", info)
        assertEquals(0x1234, info!!.nodeId)
    }
}
