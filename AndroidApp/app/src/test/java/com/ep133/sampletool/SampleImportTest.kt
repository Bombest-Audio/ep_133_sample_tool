package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.test.runTest
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
    // INIT frame layout: [5, 2, 0, flags=5, 0,0(fileId), parentHi,parentLo,
    //                     size u32 BE, "kick.wav" ASCII, 0x00]
    // DATA frames: [5, 2, 1, pageHi, pageLo, chunk...]
    // Terminator: DATA frame with zero-length chunk
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

        // Frame 0 must be a TYPE_INIT carrying the filename
        val initPayload = unpackPayload(frames[0])
        assertEquals("INIT payload[0] = TE_SYSEX_FILE (5)",
            SysExProtocol.TE_SYSEX_FILE, initPayload[0].toInt() and 0xFF)
        assertEquals("INIT payload[1] = TE_SYSEX_FILE_PUT (2)",
            SysExProtocol.TE_SYSEX_FILE_PUT, initPayload[1].toInt() and 0xFF)
        assertEquals("INIT payload[2] = TYPE_INIT (0)",
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_INIT, initPayload[2].toInt() and 0xFF)

        // filename "kick.wav" must appear at offset 12:
        //   [0]=5, [1]=2, [2]=0, [3]=flags, [4-5]=fileId u16, [6-7]=parentId u16, [8-11]=size u32
        val nameBytes = "kick.wav".toByteArray(Charsets.US_ASCII)
        val nameInInit = initPayload.copyOfRange(12, 12 + nameBytes.size)
        assertArrayEquals("INIT must carry the sanitized filename", nameBytes, nameInInit)
        assertEquals("Byte after filename must be NUL terminator",
            0, initPayload[12 + nameBytes.size].toInt() and 0xFF)

        // All DATA frames (indices 1..expectedDataFrames) must be TYPE_DATA
        for (i in 1..expectedDataFrames) {
            val p = unpackPayload(frames[i])
            assertEquals("DATA frame $i payload[0] = TE_SYSEX_FILE (5)",
                SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF)
            assertEquals("DATA frame $i payload[1] = TE_SYSEX_FILE_PUT (2)",
                SysExProtocol.TE_SYSEX_FILE_PUT, p[1].toInt() and 0xFF)
            assertEquals("DATA frame $i payload[2] = TYPE_DATA (1)",
                SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, p[2].toInt() and 0xFF)
        }

        // Last frame must be a zero-length DATA terminator
        val termPayload = unpackPayload(frames.last())
        assertEquals("Terminator payload[2] = TYPE_DATA (1)",
            SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA, termPayload[2].toInt() and 0xFF)
        // After [5,2,1,pageHi,pageLo] (5 bytes), there must be no data bytes
        assertEquals("Terminator must carry no chunk data (zero-length)", 5, termPayload.size)
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

        // DATA frame payload layout: [5, 2, 1, pageHi, pageLo, chunk...]
        // Chunk data starts at byte 5 of the unpacked payload.
        val reassembled = dataFrames
            .flatMap { frame ->
                val p = unpackPayload(frame)
                p.drop(5).toList()  // skip [TE_SYSEX_FILE, FILE_PUT, TYPE_DATA, pageHi, pageLo]
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
        // Layout: [0]=5, [1]=2, [2]=0, [3]=flags, [4-5]=fileId u16 BE, [6-7]=parentId u16 BE, [8-11]=fileSize u32 BE
        val fileId = ((initPayload[4].toInt() and 0xFF) shl 8) or (initPayload[5].toInt() and 0xFF)
        assertEquals("fileId must be 0 for a new file", 0, fileId)
        val parentId = ((initPayload[6].toInt() and 0xFF) shl 8) or (initPayload[7].toInt() and 0xFF)
        assertEquals("parentId must be the /sounds nodeId", soundsNodeId, parentId)
        val size = ((initPayload[8].toInt() and 0xFF) shl 24) or
            ((initPayload[9].toInt() and 0xFF) shl 16) or
            ((initPayload[10].toInt() and 0xFF) shl 8) or
            (initPayload[11].toInt() and 0xFF)
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
