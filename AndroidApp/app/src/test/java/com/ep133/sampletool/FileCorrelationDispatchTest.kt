package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileCorrelationDispatchTest {

    // -- Helpers ----------------------------------------------------------------

    /**
     * Decode the reqId from an outbound SysEx frame at positions [6]/[7].
     * Same formula as the dispatcher: ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F).
     */
    private fun reqIdFrom(frame: ByteArray): Int =
        ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)

    /**
     * Build a minimal FILE (cmd=5) status-0 response frame echoing the given reqId.
     * Body: status=0 + 5 dummy bytes so payload is not empty.
     */
    private fun fakeOkResponse(reqId: Int): ByteArray =
        fakeFileResponse(
            reqId,
            byteArrayOf(0x00, 0x0C, 0x00, 0x00, 0x02, 0x00),
        )

    /**
     * Build a FILE_LIST response that contains a single child entry.
     * Body: status=0, page u16, then a minimal directory entry.
     *
     * @param reqId  The reqId to echo.
     * @param childName  Name of the single child entry (e.g. "projects", "groups", "A").
     * @param childNodeId  NodeId to assign to the child.
     */
    private fun fakeListResponse(reqId: Int, childName: String, childNodeId: Int): ByteArray {
        val nameBytes = childName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        val entry = byteArrayOf(
            ((childNodeId shr 8) and 0x7F).toByte(),
            (childNodeId and 0xFF).toByte(),
            0x02,
            0x00, 0x00, 0x00, 0x00,
        ) + nameBytes
        val body = byteArrayOf(0x00, 0x00) + entry
        return fakeFileResponse(reqId, body)
    }

    /**
     * Build a METADATA GET response carrying a JSON string.
     * Body: status=0, page u16, JSON bytes, NUL, terminator-page flag byte.
     */
    private fun fakeMetaResponse(reqId: Int, json: String): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        val body = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + jsonBytes
        return fakeFileResponse(reqId, body)
    }

    private fun fakeInfoResponse(
        reqId: Int,
        nodeId: Int,
        parentId: Int,
        flags: Int,
        sizeBytes: Long,
        name: String,
    ): ByteArray {
        val body = byteArrayOf(
            ((nodeId shr 8) and 0xFF).toByte(),
            (nodeId and 0xFF).toByte(),
            ((parentId shr 8) and 0xFF).toByte(),
            (parentId and 0xFF).toByte(),
            (flags and 0xFF).toByte(),
            ((sizeBytes shr 24) and 0xFF).toByte(),
            ((sizeBytes shr 16) and 0xFF).toByte(),
            ((sizeBytes shr 8) and 0xFF).toByte(),
            (sizeBytes and 0xFF).toByte(),
        ) + name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        return fakeFileResponse(reqId, body)
    }

    private fun fakeFileResponse(reqId: Int, body: ByteArray): ByteArray {
        val packedBody = SysExProtocol.pack7bit(body)
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
        ) + packedBody + byteArrayOf(SysExProtocol.MIDI_SYSEX_END)
    }

    private suspend fun RecordingPort.awaitSentCount(count: Int) {
        repeat(10) {
            if (sent.size >= count) return
            yield()
        }
    }

    // -- Recording spy MIDIPort -------------------------------------------------

    /**
     * Records every sent frame. Does NOT auto-ack - tests deliver responses manually.
     */
    private class RecordingPort : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null

        val sent = mutableListOf<ByteArray>()

        fun lastSentReqId(): Int {
            val frame = sent.lastOrNull() ?: return -1
            if (frame.size < 8) return -1
            return ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)
        }

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

    // -- Testable repo ----------------------------------------------------------

    private class ActiveGroupTestRepo(port: RecordingPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Expose dispatchSysEx for frame injection. */
        fun inject(frame: ByteArray) = dispatchSysEx(frame)
    }

    // -- Tests ------------------------------------------------------------------

    @Test
    fun getNodeInfo_returnsParsedNodeInfoWhenResponseEchoesSentReqId() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        val op = async(Dispatchers.Unconfined) {
            repo.getNodeInfo(0x1234)
        }
        port.awaitSentCount(1)
        val reqId = reqIdFrom(port.sent[0])

        repo.inject(
            fakeInfoResponse(
                reqId = reqId,
                nodeId = 0x1234,
                parentId = 0x0102,
                flags = SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE,
                sizeBytes = 4096,
                name = "kick.wav",
            ),
        )

        val info = op.await()
        assertNotNull(info)
        assertEquals(0x1234, info!!.nodeId)
        assertEquals(0x0102, info.parentId)
        assertEquals(SysExProtocol.TE_SYSEX_FILE_CAPABILITY_WRITE, info.flags)
        assertEquals(4096L, info.sizeBytes)
        assertEquals("kick.wav", info.name)
    }

    @Test
    fun mismatchedReqId_doesNotCompleteMetadataOpAndReturnsEmptyFallback() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        val op = async(Dispatchers.Unconfined) {
            repo.getMetadataJson(1000)
        }
        port.awaitSentCount(1)
        val reqId = reqIdFrom(port.sent[0])

        repo.inject(fakeMetaResponse(reqId + 1, """{"name":"wrong"}"""))
        yield()
        assertFalse(
            "Mismatched reqId response must not complete the waiting op",
            op.isCompleted,
        )

        advanceTimeBy(5_001)
        yield()

        val result = op.await()
        assertEquals(0, result.length())
    }

    @Test
    fun interleavedMetadataResponses_routeByReqIdWithoutSwapping() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        val first = async(Dispatchers.Unconfined) {
            repo.getMetadataJson(1001)
        }
        port.awaitSentCount(1)
        val firstReqId = reqIdFrom(port.sent[0])

        val second = async(Dispatchers.Unconfined) {
            repo.getMetadataJson(1002)
        }
        port.awaitSentCount(2)
        val secondReqId = reqIdFrom(port.sent[1])
        assertNotEquals(firstReqId, secondReqId)

        repo.inject(fakeMetaResponse(secondReqId, """{"node":"second"}"""))
        repo.inject(fakeMetaResponse(firstReqId, """{"node":"first"}"""))

        assertEquals("first", first.await().getString("node"))
        assertEquals("second", second.await().getString("node"))
    }

    @Test
    fun duplicateMatchingResponse_completesOnceWithoutCrash() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        val op = async(Dispatchers.Unconfined) {
            repo.getMetadataJson(1003)
        }
        port.awaitSentCount(1)
        val reqId = port.lastSentReqId()
        val response = fakeMetaResponse(reqId, """{"dup":true}""")

        repo.inject(response)
        repo.inject(response)

        assertEquals(true, op.await().getBoolean("dup"))
    }
}
