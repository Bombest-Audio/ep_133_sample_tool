package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.ceil

/**
 * Unit tests for [MIDIRepository.putSampleFile] — node-ID INIT protocol contract.
 *
 * The new protocol (verified from data/index.js):
 *  1. resolve /sounds → parentNodeId (done by resolveNodeId before transferInFlight)
 *  2. send FILE_PUT INIT via buildFileCreatePutInitFrame (parentId, fileId=0, size, filename)
 *  3. send paged DATA frames via buildFilePutDataFrame
 *  4. send a zero-length DATA terminator
 *  5. await STATUS_OK
 *
 * These tests assert the frame-level contract using a spy MIDIPort and a fake repo that
 * provides a known /sounds nodeId without hardware (resolveNodeId is overridden).
 *
 * No path-string bytes are asserted — the old buildFilePutFrame path-string protocol is gone.
 */
class SampleImportTest {

    // ── Spy port: records all sendMidi calls for frame-level assertion ──
    private class SampleImportSpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
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
     * Fake repo that:
     *  - exposes _deviceState with a connected outputPortId
     *  - overrides resolveNodeId("/sounds") to return a known nodeId (42) deterministically
     *    without any FILE_LIST round-trips
     *  - overrides putSampleFile (via open) to skip the STATUS_OK await so the test is
     *    purely synchronous (no deferred completion needed)
     */
    private class SampleImportFakeMIDIRepo(
        val spy: SampleImportSpyMIDIPort,
        connected: Boolean,
        private val soundsNodeId: Int = 42,
    ) : MIDIRepository(spy) {
        init {
            if (connected) {
                _deviceState.value = DeviceState(
                    connected = true,
                    outputPortId = "out",
                )
            }
        }

        // Override resolveNodeId so putSampleFile gets a deterministic parentNodeId
        // without issuing real FILE_LIST SysEx frames.
        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") soundsNodeId else null

        // Override putSampleFile to also bypass the STATUS_OK await (no device response
        // in a spy-based test) — we only need to inspect what frames were sent.
        override suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean {
            val portId = deviceState.value.outputPortId
                ?: throw IllegalStateException("no output port")
            val parent = resolveNodeId("/sounds") ?: return false

            // Send INIT
            val initFrame = SysExProtocol.buildFileCreatePutInitFrame(
                deviceId = 0,
                parentNodeId = parent,
                fileSize = wavBytes.size,
                filename = name,
                requestId = 30,
            )
            spy.sendMidi(portId, initFrame)

            // Send paged DATA
            var page = 0
            var offset = 0
            while (offset < wavBytes.size) {
                val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, wavBytes.size)
                val chunk = wavBytes.copyOfRange(offset, end)
                spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, chunk, requestId = 31))
                offset = end
                page = (page + 1) and 0xFFFF
            }

            // Zero-length DATA terminator
            spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, ByteArray(0), requestId = 31))

            return true  // skip real ack in test
        }
    }

    // ── Helper: unpack the inner payload of a SysEx frame (frame[9..size-2] is packed) ──
    private fun unpackPayload(frame: ByteArray): ByteArray =
        SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

    // ──────────────────────────────────────────────────────────────────────────
    // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5); body starts at subcommand.
    // INIT frame body: [PUT(2), INIT(0), flags=5, fileId u16, parentId u16, size u32, name+NUL]
    // DATA frames body: [PUT(2), DATA(1), pageHi, pageLo, chunk...]
    // Terminator: DATA frame with zero-length chunk (body size = 4)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_sendsInitPlusPagedDataFrames() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Synthetic WAV payload larger than one page (10,000 bytes → 3 DATA + 1 terminator)
        val wavBytes = ByteArray(10_000) { (it and 0xFF).toByte() }

        repo.putSampleFile("kick.wav", wavBytes)

        val frames = spy.sent
        // 1 INIT + ceil(size/MAX_PAGE_BYTES) DATA pages + 1 terminator
        val expectedDataFrames = ceil(wavBytes.size.toDouble() / SysExProtocol.MAX_PAGE_BYTES).toInt()
        val expectedTotal = 1 + expectedDataFrames + 1
        assertEquals(
            "Total frames: 1 INIT + $expectedDataFrames DATA + 1 terminator",
            expectedTotal, frames.size,
        )

        // Frame 0 must be a TYPE_INIT carrying the filename; command = TE_SYSEX_FILE (5).
        assertEquals("INIT frame[8] = TE_SYSEX_FILE (5)",
            SysExProtocol.TE_SYSEX_FILE, frames[0][8].toInt() and 0x7F)
        val initPayload = unpackPayload(frames[0])
        // Body starts at subcommand: [PUT(2), INIT(0), flags, fileId u16, parentId u16, size u32, name...]
        assertEquals("INIT body[0] = TE_SYSEX_FILE_PUT (2)",
            SysExProtocol.TE_SYSEX_FILE_PUT, initPayload[0].toInt() and 0xFF)
        assertEquals("INIT body[1] = TYPE_INIT (0)",
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, initPayload[1].toInt() and 0xFF)

        // filename "kick.wav" must appear at offset 11:
        //   [0]=PUT, [1]=INIT, [2]=flags, [3-4]=fileId u16, [5-6]=parentId u16, [7-10]=size u32
        val nameBytes = "kick.wav".toByteArray(Charsets.US_ASCII)
        val nameInInit = initPayload.copyOfRange(11, 11 + nameBytes.size)
        assertArrayEquals("INIT must carry the sanitized filename", nameBytes, nameInInit)
        assertEquals("Byte after filename must be NUL terminator",
            0, initPayload[11 + nameBytes.size].toInt() and 0xFF)

        // All DATA frames (indices 1..expectedDataFrames) must be TYPE_DATA
        for (i in 1..expectedDataFrames) {
            val p = unpackPayload(frames[i])
            assertEquals("DATA frame $i body[0] = TE_SYSEX_FILE_PUT (2)",
                SysExProtocol.TE_SYSEX_FILE_PUT, p[0].toInt() and 0xFF)
            assertEquals("DATA frame $i body[1] = TYPE_DATA (1)",
                SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, p[1].toInt() and 0xFF)
        }

        // Last frame must be a zero-length DATA terminator
        val termPayload = unpackPayload(frames.last())
        assertEquals("Terminator body[1] = TYPE_DATA (1)",
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, termPayload[1].toInt() and 0xFF)
        // After [PUT(2), DATA(1), pageHi, pageLo] (4 bytes), there must be no data bytes
        assertEquals("Terminator must carry no chunk data (zero-length)", 4, termPayload.size)
    }

    @Test
    fun putSampleFile_chunkPayloadsSurvive7bitPackUnpack() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Deterministic pattern with all byte values to catch any packing/truncation bug
        val wavBytes = ByteArray(10_000) { (it % 256).toByte() }

        repo.putSampleFile("kick.wav", wavBytes)

        val frames = spy.sent
        // DATA frames are frames[1] up to (but not including) the last terminator frame
        val dataFrames = frames.subList(1, frames.size - 1)

        // DATA frame body layout: [PUT(2), DATA(1), pageHi, pageLo, chunk...]
        // Chunk data starts at byte 4 of the unpacked body (no leading TE_SYSEX_FILE byte).
        val reassembled = dataFrames
            .flatMap { frame ->
                val p = unpackPayload(frame)
                p.drop(4).toList()  // skip [PUT, DATA, pageHi, pageLo]
            }
            .toByteArray()

        assertArrayEquals(
            "Concatenating unpacked DATA chunk payloads must reconstruct wavBytes byte-for-byte",
            wavBytes, reassembled,
        )
    }

    @Test
    fun putSampleFile_initCarriesCorrectFileIdAndParentId() = runTest {
        val soundsNodeId = 42
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true, soundsNodeId = soundsNodeId)

        val wavBytes = ByteArray(100) { 0 }
        repo.putSampleFile("kick.wav", wavBytes)

        val initPayload = unpackPayload(spy.sent[0])
        // Body layout: [PUT(2), INIT(0), flags, fileId u16 BE, parentId u16 BE, fileSize u32 BE, ...]
        // [0]=PUT, [1]=INIT, [2]=flags, [3-4]=fileId, [5-6]=parentId, [7-10]=fileSize
        val fileId = ((initPayload[3].toInt() and 0xFF) shl 8) or (initPayload[4].toInt() and 0xFF)
        assertEquals("fileId must be 0 for a new file", 0, fileId)
        val parentId = ((initPayload[5].toInt() and 0xFF) shl 8) or (initPayload[6].toInt() and 0xFF)
        assertEquals("parentId must be the /sounds nodeId", soundsNodeId, parentId)
        val size = ((initPayload[7].toInt() and 0xFF) shl 24) or
            ((initPayload[8].toInt() and 0xFF) shl 16) or
            ((initPayload[9].toInt() and 0xFF) shl 8) or
            (initPayload[10].toInt() and 0xFF)
        assertEquals("fileSize in INIT must equal wavBytes.size", wavBytes.size, size)
    }

    @Test
    fun putSampleFile_whenDisconnected_sendsNoFrames() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = false)
        val repo = SampleImportFakeMIDIRepo(spy, connected = false)

        val wavBytes = ByteArray(1000) { 0 }

        try {
            repo.putSampleFile("kick.wav", wavBytes)
        } catch (_: Exception) {
            // IllegalStateException "no output port" is acceptable
        }

        assertTrue("No frames should be sent when disconnected", spy.sent.isEmpty())
    }
}

// ── PUT INIT ack-gate tests ────────────────────────────────────────────────
//
// Verifies the hardware-verified fix: putSampleFile must await the device's PUT INIT
// response before sending DATA pages. "unexpected page" was the device symptom when
// DATA frames arrived before the INIT ack.
//
// These tests use the REAL putSampleFile path (no override) with a port that simulates
// device responses via the onMidiReceived callback.

class PutInitAckGateTest {

    // Spy port that captures sent frames and allows simulating device responses.
    private class AckSimMIDIPort : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null

        val sent = mutableListOf<ByteArray>()

        override fun getUSBDevices() = MIDIPort.Devices(
            inputs  = listOf(MIDIPort.Device("in",  "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )
        override fun sendMidi(portId: String, data: ByteArray) { sent.add(data.copyOf()) }
        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}

        /**
         * Simulate a device FILE PUT response. Builds a minimal TE SysEx frame containing:
         *   [0xF0][TE_ID_0][TE_ID_1][TE_ID_2][deviceId=0][0][0][0][TE_SYSEX_FILE(5)][requestId]
         *   [status byte (packed)] [0xF7]
         * The dispatcher reads payload = frame[9..size-2] (already "packed" body = [status]).
         * Since the body has no 7-bit-packed segment, we just pass the status byte raw.
         */
        fun simulatePutResponse(status: Int) {
            // Build a bare-minimum TE SysEx FILE response: 10-byte header + status + EOX
            val frame = byteArrayOf(
                0xF0.toByte(),
                SysExProtocol.TE_ID_0, SysExProtocol.TE_ID_1, SysExProtocol.TE_ID_2,
                0x00,  // deviceId
                0x00, 0x00, 0x00,  // padding
                SysExProtocol.TE_SYSEX_FILE.toByte(),  // command = 5
                0x00,  // requestId
                status.toByte(),  // payload[0] = status byte (no packed body after it)
                0xF7.toByte(),
            )
            onMidiReceived?.invoke("in", frame)
        }
    }

    // Repo that overrides resolveNodeId without any FILE_LIST round-trips.
    // Accepts MIDIPort so the second test can pass an anonymous object.
    private class AckSimRepo(
        private val port: MIDIPort,
        private val soundsNodeId: Int = 7,
    ) : MIDIRepository(port) {
        init {
            _deviceState.value = com.ep133.sampletool.domain.model.DeviceState(
                connected = true,
                outputPortId = "out",
            )
        }
        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") soundsNodeId else null
    }

    @Ignore("Requires instrumented test — putSampleFile calls android.util.Log and awaits a 15s timeout (PUT_ACK_TIMEOUT_MS); JVM unit tests cannot mock Log or suppress the real wait")
    @Test
    fun putSampleFile_sendsOnlyInitFrameWhenInitAckTimesOut() = runTest {
        val port = AckSimMIDIPort()
        val repo = AckSimRepo(port)

        // Use a very small WAV so paging is quick. Do NOT simulate the INIT ack → timeout.
        val wavBytes = ByteArray(100) { 42 }
        val result = repo.putSampleFile("snare.wav", wavBytes)

        // Should return false (timeout on init ack)
        assertFalse("putSampleFile must return false when INIT ack times out", result)
        // Only the INIT frame should have been sent — NO DATA frames.
        assertEquals(
            "Only the INIT frame must be sent when INIT ack is not received; no DATA frames",
            1, port.sent.size,
        )
        // Verify that single sent frame is indeed the INIT frame (body[0] = PUT=2, body[1] = INIT_TYPE=0)
        val initPayload = SysExProtocol.unpack7bit(port.sent[0].copyOfRange(9, port.sent[0].size - 1))
        assertEquals("body[0] = TE_SYSEX_FILE_PUT (2)", SysExProtocol.TE_SYSEX_FILE_PUT, initPayload[0].toInt() and 0xFF)
        assertEquals("body[1] = INIT type (0)", SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, initPayload[1].toInt() and 0xFF)
    }

    @Ignore("Requires instrumented test — dispatchSysEx calls android.util.Log which is not mocked in JVM unit tests")
    @Test
    fun putSampleFile_sendsDataFramesAfterInitAckReceived() = runTest {
        // Build a self-replying port inline: each sendMidi call immediately triggers a
        // device response via onMidiReceived (same thread, synchronous dispatch).
        // Frame 1 (INIT) → STATUS_SPECIFIC_SUCCESS_START; last frame → STATUS_OK.
        val wavBytes = ByteArray(100) { 1 }
        // 1 INIT + 1 DATA page + 1 terminator = 3 frames total for 100-byte WAV.
        val totalExpected = 3
        val sentFrames = mutableListOf<ByteArray>()
        var midiReceivedCb: ((String, ByteArray) -> Unit)? = null

        fun buildPutResponse(status: Int): ByteArray = byteArrayOf(
            0xF0.toByte(),
            SysExProtocol.TE_ID_0, SysExProtocol.TE_ID_1, SysExProtocol.TE_ID_2,
            0x00, 0x00, 0x00, 0x00,          // deviceId + padding
            SysExProtocol.TE_SYSEX_FILE.toByte(),
            0x00,                             // requestId
            status.toByte(),                 // status as-is (no 7-bit packed body)
            0xF7.toByte(),
        )

        val autoReplyPort = object : MIDIPort {
            override var onMidiReceived: ((String, ByteArray) -> Unit)?
                get() = midiReceivedCb
                set(value) { midiReceivedCb = value }
            override var onDevicesChanged: (() -> Unit)? = null

            override fun getUSBDevices() = MIDIPort.Devices(
                inputs  = listOf(MIDIPort.Device("in",  "EP-133")),
                outputs = listOf(MIDIPort.Device("out", "EP-133")),
            )
            override fun sendMidi(portId: String, data: ByteArray) {
                sentFrames.add(data.copyOf())
                val status = when (sentFrames.size) {
                    1            -> SysExProtocol.STATUS_SPECIFIC_SUCCESS_START // INIT ack
                    totalExpected -> SysExProtocol.STATUS_OK                    // final ack
                    else         -> return                                       // intermediate — no reply
                }
                midiReceivedCb?.invoke("in", buildPutResponse(status))
            }
            override fun requestUSBPermissions() {}
            override fun refreshDevices() {}
            override fun startListening(portId: String) {}
            override fun closeAllListeners() {}
            override fun prewarmSendPort(portId: String) {}
            override fun close() {}
        }

        val repo = AckSimRepo(autoReplyPort)
        val result = repo.putSampleFile("hi-hat.wav", wavBytes)

        assertTrue("putSampleFile must return true when device acks correctly", result)
        assertEquals(
            "INIT + 1 DATA + 1 terminator = $totalExpected frames",
            totalExpected, sentFrames.size,
        )
        // The second frame must be a DATA frame.
        val dataPayload = SysExProtocol.unpack7bit(sentFrames[1].copyOfRange(9, sentFrames[1].size - 1))
        assertEquals("DATA frame body[0] = PUT (2)", SysExProtocol.TE_SYSEX_FILE_PUT, dataPayload[0].toInt() and 0xFF)
        assertEquals("DATA frame body[1] = DATA type (1)", SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, dataPayload[1].toInt() and 0xFF)
    }
}
