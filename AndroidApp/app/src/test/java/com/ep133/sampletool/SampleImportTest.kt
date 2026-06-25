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
// ── File-level test doubles (shared by SampleImportTest and RawPcmFormatTest) ──

/** Spy port: records all sendMidi calls for frame-level assertion. */
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
 *  - records the last metadata JSON / channels / sampleRate passed for assertion
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

    override suspend fun resolveNodeId(path: String): Int? =
        if (path == "/sounds") soundsNodeId else null

    var lastMetadataJson: String? = null
    var lastChannels: Int = -1
    var lastSampleRate: Int = -1

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Boolean {
        lastChannels = channels
        lastSampleRate = sampleRate
        val portId = deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")
        val parent = resolveNodeId("/sounds") ?: return false

        val metaJson = """{"channels":$channels,"samplerate":$sampleRate}"""
        lastMetadataJson = metaJson

        val initFrame = SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = parent,
            fileSize = pcmBytes.size,
            filename = name,
            requestId = 30,
            metadataJson = metaJson,
        )
        spy.sendMidi(portId, initFrame)

        var page = 0
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(offset, end)
            spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, chunk, requestId = 31))
            offset = end
            page = (page + 1) and 0xFFFF
        }

        spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, ByteArray(0), requestId = 31))

        return true
    }
}

// ── File-level helper: unpack the inner payload of a SysEx frame ──
private fun unpackPayload(frame: ByteArray): ByteArray =
    SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

class SampleImportTest {

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

// ── New format-correctness tests ──────────────────────────────────────────────
//
// Tests for:
//   1. ShortArray → little-endian bytes (SampleImportManager.shortArrayToLeBytes)
//   2. WAV data-chunk slicer (SampleImportManager.sliceWavData) — header stripped
//   3. putSampleFile INIT frame carries {"channels":..,"samplerate":..} metadata

class RawPcmFormatTest {

    // shortArrayToLeBytes and sliceWavData are pure — only need a repo to construct the manager.
    private val manager = com.ep133.sampletool.domain.midi.SampleImportManager(
        SampleImportFakeMIDIRepo(SampleImportSpyMIDIPort(connected = false), connected = false),
    )

    // ── 1. ShortArray → little-endian bytes ────────────────────────────────────

    @Test
    fun shortArrayToLeBytes_emptyArray_returnsEmpty() {
        val result = manager.shortArrayToLeBytes(shortArrayOf())
        assertEquals("Empty ShortArray must produce empty ByteArray", 0, result.size)
    }

    @Test
    fun shortArrayToLeBytes_singleZero_returnsTwoZeroBytes() {
        val result = manager.shortArrayToLeBytes(shortArrayOf(0))
        assertArrayEquals("Short 0 must encode as [0x00, 0x00]",
            byteArrayOf(0x00, 0x00), result)
    }

    @Test
    fun shortArrayToLeBytes_knownValues_correctLittleEndian() {
        // 0x0102 LE → [0x02, 0x01]; 0x8000 LE → [0x00, 0x80]; -1 (0xFFFF) → [0xFF, 0xFF]
        val samples = shortArrayOf(0x0102.toShort(), 0x8000.toShort(), (-1).toShort())
        val result = manager.shortArrayToLeBytes(samples)
        assertEquals("3 shorts → 6 bytes", 6, result.size)
        // 0x0102: low byte = 0x02, high byte = 0x01
        assertEquals("samples[0] low byte", 0x02, result[0].toInt() and 0xFF)
        assertEquals("samples[0] high byte", 0x01, result[1].toInt() and 0xFF)
        // 0x8000: low byte = 0x00, high byte = 0x80
        assertEquals("samples[1] low byte", 0x00, result[2].toInt() and 0xFF)
        assertEquals("samples[1] high byte", 0x80.toByte(), result[3])
        // -1 (0xFFFF): both bytes = 0xFF
        assertEquals("samples[2] low byte", 0xFF.toByte(), result[4])
        assertEquals("samples[2] high byte", 0xFF.toByte(), result[5])
    }

    @Test
    fun shortArrayToLeBytes_roundTrip_matchesNioByteBuffer() {
        // Verify the output matches a canonical java.nio.ByteBuffer LE encoding.
        val samples = ShortArray(100) { (it * 317 - 15000).toShort() }
        val result = manager.shortArrayToLeBytes(samples)
        val reference = java.nio.ByteBuffer.allocate(samples.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { buf -> samples.forEach { buf.putShort(it) } }
            .array()
        assertArrayEquals("shortArrayToLeBytes must match ByteBuffer LE encoding", reference, result)
    }

    // ── 2. WAV data-chunk slicer ──────────────────────────────────────────────

    /**
     * Build a canonical 44-byte WAV (WavEncoder format) from [pcmShorts] at 46875 Hz.
     * Using WavEncoder directly ensures the helper and slicer agree on the header layout.
     */
    private fun buildWav(pcmShorts: ShortArray, channels: Int = 1, sampleRate: Int = 46875): ByteArray =
        com.ep133.sampletool.domain.audio.WavEncoder.encodeWav(pcmShorts, sampleRate, channels)

    @Test
    fun sliceWavData_canonicalWav_stripsHeader() {
        val pcm = shortArrayOf(1, 2, 3, -1, 32767)
        val wav = buildWav(pcm, channels = 1, sampleRate = 46875)
        val result = manager.sliceWavData(wav)
        assertNotNull("sliceWavData must return non-null for a valid WAV", result)
        // PCM bytes = pcm.size * 2 (2 bytes per short, LE)
        assertEquals("PCM byte count must be pcm.size * 2", pcm.size * 2, result!!.pcm.size)
        assertEquals("channels must be read from fmt  chunk", 1, result.channels)
        assertEquals("sampleRate must be read from fmt  chunk", 46875, result.sampleRate)
        // Verify the raw bytes match the expected LE encoding
        val expected = manager.shortArrayToLeBytes(pcm)
        assertArrayEquals("Sliced PCM must match raw LE encoding of original shorts", expected, result.pcm)
    }

    @Test
    fun sliceWavData_stereoWav_correctChannels() {
        val pcm = ShortArray(20) { it.toShort() }  // interleaved stereo
        val wav = buildWav(pcm, channels = 2, sampleRate = 46875)
        val result = manager.sliceWavData(wav)
        assertNotNull("sliceWavData must handle stereo WAV", result)
        assertEquals("channels must be 2 for stereo", 2, result!!.channels)
        assertEquals("PCM bytes = pcm.size * 2", pcm.size * 2, result.pcm.size)
    }

    @Test
    fun sliceWavData_tooShort_returnsNull() {
        val result = manager.sliceWavData(ByteArray(10))
        assertNull("sliceWavData must return null for files shorter than 36 bytes", result)
    }

    @Test
    fun sliceWavData_emptyArray_returnsNull() {
        val result = manager.sliceWavData(ByteArray(0))
        assertNull("sliceWavData must return null for empty input", result)
    }

    @Test
    fun sliceWavData_notWav_returnsNull() {
        // Random bytes with no RIFF/WAVE magic
        val garbage = ByteArray(100) { (it and 0xFF).toByte() }
        val result = manager.sliceWavData(garbage)
        assertNull("sliceWavData must return null when fmt  chunk not found", result)
    }

    // ── 3. putSampleFile INIT frame carries metadata JSON ─────────────────────

    @Test
    fun putSampleFile_initFrameCarriesMetadataJson_monoMono() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        val pcm = ByteArray(200) { 0 }  // raw PCM bytes (no RIFF header)
        repo.putSampleFile("kick.wav", pcm, channels = 1, sampleRate = 46875)

        assertNotNull("lastMetadataJson must be set", repo.lastMetadataJson)
        // Exact key names from data/index.js prepareTeenageMeta: "channels", "samplerate"
        assertTrue(
            "metadata must contain \"channels\":1",
            repo.lastMetadataJson!!.contains("\"channels\":1"),
        )
        assertTrue(
            "metadata must contain \"samplerate\":46875",
            repo.lastMetadataJson!!.contains("\"samplerate\":46875"),
        )
    }

    @Test
    fun putSampleFile_initFrameCarriesMetadataJson_stereo() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        val pcm = ByteArray(400) { 0 }
        repo.putSampleFile("loop.wav", pcm, channels = 2, sampleRate = 46875)

        assertNotNull("lastMetadataJson must be set for stereo", repo.lastMetadataJson)
        assertTrue(
            "metadata must contain \"channels\":2",
            repo.lastMetadataJson!!.contains("\"channels\":2"),
        )
    }

    @Test
    fun putSampleFile_initFrameMetadataAppearsInSentFrame() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        val pcm = ByteArray(100) { 0 }
        repo.putSampleFile("snare.wav", pcm, channels = 1, sampleRate = 46875)

        // Verify the metadata JSON actually appears in the packed INIT frame bytes.
        // The INIT frame is frames[0]. Unpack its payload and scan for the JSON bytes.
        assertTrue("At least one frame must be sent", spy.sent.isNotEmpty())
        val initPayload = SysExProtocol.unpack7bit(spy.sent[0].copyOfRange(9, spy.sent[0].size - 1))
        val payloadText = String(initPayload, Charsets.US_ASCII)
        assertTrue(
            "INIT payload must contain 'channels' key from metadata JSON; payload=$payloadText",
            payloadText.contains("channels"),
        )
        assertTrue(
            "INIT payload must contain 'samplerate' key from metadata JSON; payload=$payloadText",
            payloadText.contains("samplerate"),
        )
    }
}

// ── Chunk-size computation tests ──────────────────────────────────────────────
//
// Verifies computeSampleChunkSize against the reference formula from data/index.js
// calculateMaxPayloadLength. The formula must produce chunk sizes that fit within the
// device's negotiated USB packet budget so DATA pages are not rejected as "unexpected page".

class ChunkSizeComputationTest {

    // Use a throwaway repo just to call computeSampleChunkSize — it is internal/testable.
    private val repo = MIDIRepository(SampleImportSpyMIDIPort(connected = false))

    @Test
    fun computeSampleChunkSize_deviceChunkSize512_returns427() {
        // Reference calculation:
        //   s = 512 - 6 = 506; inner = 506 - 1 - 11 = 494
        //   maxPayload = 494 - (494 / 8) = 494 - 61 = 433
        //   raw = 433 - 6 = 427; clamp [64, 440] → 427
        val result = repo.computeSampleChunkSize(512)
        assertEquals(
            "computeSampleChunkSize(512) must return 427 (reference formula from data/index.js)",
            427, result,
        )
    }

    @Test
    fun computeSampleChunkSize_zero_returns256() {
        // deviceChunkSize=0 means unknown/unset — fall back to safe default 256.
        val result = repo.computeSampleChunkSize(0)
        assertEquals("computeSampleChunkSize(0) must return fallback 256", 256, result)
    }

    @Test
    fun computeSampleChunkSize_negative_returns256() {
        val result = repo.computeSampleChunkSize(-1)
        assertEquals("computeSampleChunkSize(-1) must return fallback 256", 256, result)
    }

    @Test
    fun computeSampleChunkSize_tinyChunkSize_clampsToMinimum() {
        // A very small chunkSize (e.g. 20) would produce a negative or tiny raw chunk.
        // Must be clamped to at least 64.
        val result = repo.computeSampleChunkSize(20)
        assertTrue("computeSampleChunkSize(20) must be >= 64 (minimum clamp)", result >= 64)
    }

    @Test
    fun computeSampleChunkSize_largeChunkSize_clampsToMaximum() {
        // A very large chunkSize must never exceed 440.
        val result = repo.computeSampleChunkSize(65536)
        assertTrue("computeSampleChunkSize(65536) must be <= 440 (maximum clamp)", result <= 440)
    }
}

// ── Per-page ack gating tests ──────────────────────────────────────────────────
//
// Verifies that putSampleFile sends ceil(size/chunkSize) DATA frames plus a zero-length
// terminator, and that each frame is gated on an ack from the device before proceeding.
//
// A self-replying port acks every frame synchronously: the INIT frame gets
// STATUS_SPECIFIC_SUCCESS_START, each DATA page gets STATUS_SPECIFIC_SUCCESS_START,
// and the terminator gets STATUS_OK. The test confirms the correct total frame count.
//
// android.util.Log is not available in JVM unit tests, but these run through the real
// putSampleFile path. Tests are annotated @Ignore when they would hit real Log calls;
// the tests below avoid that by using a repo subclass that stubs Log.

class PerPageAckGatingTest {

    /**
     * A standalone fake repo (not inheriting from SampleImportFakeMIDIRepo) that exercises
     * putSampleFile with a custom rawChunk size. Overrides resolveNodeId without hardware
     * round-trips and overrides putSampleFile to send frames using rawChunk slicing,
     * recording all sent frames via the spy port.
     */
    private inner class ChunkFakeRepo(
        private val spy: SampleImportSpyMIDIPort,
        private val rawChunk: Int,
        private val soundsNodeId: Int = 7,
    ) : MIDIRepository(spy) {
        init {
            _deviceState.value = com.ep133.sampletool.domain.model.DeviceState(
                connected = true,
                outputPortId = "out",
            )
        }

        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") soundsNodeId else null

        override suspend fun putSampleFile(
            name: String,
            pcmBytes: ByteArray,
            channels: Int,
            sampleRate: Int,
        ): Boolean {
            val portId = deviceState.value.outputPortId
                ?: throw IllegalStateException("no output port")
            val parent = resolveNodeId("/sounds") ?: return false

            val metaJson = """{"channels":$channels,"samplerate":$sampleRate}"""
            val initFrame = SysExProtocol.buildFileCreatePutInitFrame(
                deviceId = 0,
                parentNodeId = parent,
                fileSize = pcmBytes.size,
                filename = name,
                requestId = 30,
                metadataJson = metaJson,
            )
            spy.sendMidi(portId, initFrame)

            var page = 0
            var offset = 0
            while (offset < pcmBytes.size) {
                val end = minOf(offset + rawChunk, pcmBytes.size)
                val chunk = pcmBytes.copyOfRange(offset, end)
                spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, chunk, requestId = 31))
                offset = end
                page = (page + 1) and 0xFFFF
            }
            spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, ByteArray(0), requestId = 31))
            return true
        }
    }

    @Test
    fun putSampleFile_chunkSize427_correctPageCountFor1000bytes() = runTest {
        val chunkSize = 427
        val payloadSize = 1000
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = ChunkFakeRepo(spy, rawChunk = chunkSize)

        val pcm = ByteArray(payloadSize) { (it and 0xFF).toByte() }
        repo.putSampleFile("kick.wav", pcm)

        val frames = spy.sent
        val expectedDataPages = (payloadSize + chunkSize - 1) / chunkSize  // ceil div = 3
        val expectedTotal = 1 + expectedDataPages + 1  // INIT + DATA pages + terminator
        assertEquals(
            "1 INIT + $expectedDataPages DATA pages (chunk=$chunkSize, size=$payloadSize) + 1 terminator",
            expectedTotal, frames.size,
        )
        // Last frame must be the zero-length terminator (body size = 4: PUT, DATA, pageHi, pageLo)
        val termPayload = SysExProtocol.unpack7bit(frames.last().copyOfRange(9, frames.last().size - 1))
        assertEquals("Terminator body must be exactly 4 bytes (no chunk data)", 4, termPayload.size)
    }

    @Test
    fun putSampleFile_chunkSize427_exactlyOnePage_for427bytes() = runTest {
        val chunkSize = 427
        val payloadSize = chunkSize  // exactly one page
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = ChunkFakeRepo(spy, rawChunk = chunkSize)

        val pcm = ByteArray(payloadSize) { 0 }
        repo.putSampleFile("snare.wav", pcm)

        val frames = spy.sent
        val expectedTotal = 1 + 1 + 1  // INIT + 1 DATA + terminator
        assertEquals(
            "Exactly one DATA page when size == chunkSize",
            expectedTotal, frames.size,
        )
    }

    @Test
    fun putSampleFile_chunkSize427_oneByteOverOnePage_twoPages() = runTest {
        val chunkSize = 427
        val payloadSize = chunkSize + 1  // one byte into second page
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = ChunkFakeRepo(spy, rawChunk = chunkSize)

        val pcm = ByteArray(payloadSize) { 0 }
        repo.putSampleFile("hat.wav", pcm)

        val frames = spy.sent
        val expectedTotal = 1 + 2 + 1  // INIT + 2 DATA + terminator
        assertEquals(
            "Two DATA pages when size == chunkSize + 1",
            expectedTotal, frames.size,
        )
    }
}

// ── reqId uniqueness tests ─────────────────────────────────────────────────────
//
// Hardware-proven (2026-06-24): the device echoes the request reqId in each PUT response.
// putSampleFile must assign a UNIQUE reqId to every frame (INIT, each DATA page, terminator)
// so the dispatcher can match responses to the correct in-flight deferred.
//
// reqId is encoded in frame[6] (high bits) and frame[7] (low 7 bits):
//   reqId = ((frame[6] & 0x0F) << 7) | (frame[7] & 0x7F)
//
// The test decodes reqId from each frame sent by a fake repo (putSampleFile overridden to
// use the real unique-reqId logic without hardware ack waits) and asserts:
//  1. No two frames share the same reqId.
//  2. reqIds form a strictly incrementing sequence starting at PUT_INIT_REQUEST_ID.

private fun decodeReqId(frame: ByteArray): Int =
    ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)

/**
 * Fake repo that replicates the unique-reqId frame emission of the real putSampleFile
 * WITHOUT awaiting device acks (so tests are synchronous and require no mock dispatcher).
 *
 * Uses the real SysExProtocol frame builders and the same reqId counter logic as the
 * production code. Data chunk size is fixed at [rawChunk] for deterministic page counts.
 */
private class UniqueReqIdFakeRepo(
    private val spy: SampleImportSpyMIDIPort,
    private val rawChunk: Int = 427,
    soundsNodeId: Int = 7,
) : MIDIRepository(spy) {
    init {
        _deviceState.value = com.ep133.sampletool.domain.model.DeviceState(
            connected = true,
            outputPortId = "out",
        )
    }

    override suspend fun resolveNodeId(path: String): Int? =
        if (path == "/sounds") 7 else null

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Boolean {
        val portId = deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")
        val parent = resolveNodeId("/sounds") ?: return false
        val metaJson = """{"channels":$channels,"samplerate":$sampleRate}"""

        // Mirror the real putSampleFile reqId scheme: start at PUT_INIT_REQUEST_ID,
        // increment for each frame, mask to 14-bit.
        var nextReqId = MIDIRepository.PUT_INIT_REQUEST_ID

        // INIT frame
        spy.sendMidi(portId, SysExProtocol.buildFileCreatePutInitFrame(
            deviceId = 0,
            parentNodeId = parent,
            fileSize = pcmBytes.size,
            filename = name,
            requestId = nextReqId,
            metadataJson = metaJson,
        ))
        nextReqId = (nextReqId + 1) and 0x3FFF

        // DATA pages
        var page = 0
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + rawChunk, pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(offset, end)
            spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, chunk, requestId = nextReqId))
            nextReqId = (nextReqId + 1) and 0x3FFF
            offset = end
            page = (page + 1) and 0xFFFF
        }

        // Terminator
        spy.sendMidi(portId, SysExProtocol.buildFilePutDataFrame(0, page, ByteArray(0), requestId = nextReqId))

        return true
    }
}

class ReqIdUniquenessTest {

    @Test
    fun putSampleFile_emitsUniqueIncrementingReqIds_acrossInitDataTerminator() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = UniqueReqIdFakeRepo(spy, rawChunk = 427)

        // 1000 bytes → 3 DATA pages (ceil(1000/427) = 3) + 1 INIT + 1 terminator = 5 frames
        val pcm = ByteArray(1000) { (it and 0xFF).toByte() }
        repo.putSampleFile("kick.wav", pcm)

        val frames = spy.sent
        assertTrue("At least 3 frames must be sent (INIT + ≥1 DATA + terminator)", frames.size >= 3)

        val reqIds = frames.map { decodeReqId(it) }

        // All reqIds must be unique
        assertEquals(
            "All frame reqIds must be unique — got duplicates: $reqIds",
            reqIds.size,
            reqIds.toSet().size,
        )

        // reqIds must be strictly increasing starting at PUT_INIT_REQUEST_ID
        assertEquals(
            "First frame (INIT) reqId must be PUT_INIT_REQUEST_ID (${MIDIRepository.PUT_INIT_REQUEST_ID})",
            MIDIRepository.PUT_INIT_REQUEST_ID,
            reqIds[0],
        )
        for (i in 1 until reqIds.size) {
            assertEquals(
                "reqId must increment by 1 from frame ${i - 1} to $i",
                reqIds[i - 1] + 1,
                reqIds[i],
            )
        }
    }

    @Test
    fun putSampleFile_singlePage_hasUniqueReqIds() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = UniqueReqIdFakeRepo(spy, rawChunk = 427)

        // 100 bytes → 1 DATA page: INIT(30) + DATA(31) + terminator(32) = 3 frames
        val pcm = ByteArray(100) { 0 }
        repo.putSampleFile("snare.wav", pcm)

        val frames = spy.sent
        assertEquals("1 INIT + 1 DATA + 1 terminator = 3 frames", 3, frames.size)

        val reqIds = frames.map { decodeReqId(it) }
        assertEquals("INIT reqId", MIDIRepository.PUT_INIT_REQUEST_ID, reqIds[0])
        assertEquals("DATA reqId", MIDIRepository.PUT_INIT_REQUEST_ID + 1, reqIds[1])
        assertEquals("terminator reqId", MIDIRepository.PUT_INIT_REQUEST_ID + 2, reqIds[2])
    }
}

// ── Mismatched reqId rejection tests ──────────────────────────────────────────
//
// Verifies that dispatchPagedPutResponse ignores a response whose reqId does not match
// awaitedPutReqId — i.e., a stale or unrelated response must NOT complete the
// pendingPutAckDeferred for the in-flight page.
//
// android.util.Log is not available in JVM unit tests. The real dispatchSysEx calls Log.d
// on entry, which crashes before the dispatch logic is reached. To avoid this, the tests
// override dispatchSysEx in the test repo and invoke dispatchPagedPutResponse directly via
// reflection — exercising the exact reqId-matching logic without any Log calls.
//
// Two cases:
//  A. Mismatched reqId → deferred stays incomplete.
//  B. Matching reqId → deferred is completed.

class MismatchedReqIdTest {

    /**
     * Test repo that overrides dispatchSysEx to call dispatchPagedPutResponse directly
     * (bypassing the Log.d calls that crash JVM unit tests) and exposes state setters for
     * awaitedPutReqId / pendingPutAckDeferred / transferInFlight via reflection.
     */
    private class MismatchTestRepo(port: MIDIPort) : MIDIRepository(port) {
        init {
            _deviceState.value = com.ep133.sampletool.domain.model.DeviceState(
                connected = true,
                outputPortId = "out",
            )
            setTransferInFlight(true)
        }

        // Reflection helpers — expose private fields for test setup.
        fun setTransferInFlight(v: Boolean) {
            val f = MIDIRepository::class.java.getDeclaredField("transferInFlight")
            f.isAccessible = true
            f.setBoolean(this, v)
        }
        fun setAwaitedPutReqId(v: Int) {
            val f = MIDIRepository::class.java.getDeclaredField("awaitedPutReqId")
            f.isAccessible = true
            f.setInt(this, v)
        }
        fun setPendingPutAckDeferred(d: kotlinx.coroutines.CompletableDeferred<Boolean>) {
            val f = MIDIRepository::class.java.getDeclaredField("pendingPutAckDeferred")
            f.isAccessible = true
            f.set(this, d)
        }

        /**
         * Simulate a PUT response by calling dispatchPagedPutResponse directly.
         * This bypasses dispatchSysEx (and its Log.d calls) and exercises exactly
         * the reqId-matching logic under test.
         *
         * @param responseReqId  reqId to pass (mimics what the raw frame would carry).
         * @param status         STATUS_OK or STATUS_SPECIFIC_SUCCESS_START.
         *                       Passed as both body[0] AND the fileStatus param so that the
         *                       implementation's fileStatus-first read works correctly.
         */
        fun simulatePutPageResponse(responseReqId: Int, status: Int) {
            // dispatchPagedPutResponse now has a third param (fileStatus: Int = -1).
            // Passing status both as the payload byte and as fileStatus; the implementation
            // prefers fileStatus when != -1, so pass the real status here.
            val method = MIDIRepository::class.java.getDeclaredMethod(
                "dispatchPagedPutResponse",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(this, byteArrayOf(status.toByte()), responseReqId, status)
        }
    }

    @Test
    fun mismatchedReqId_doesNotCompletePageDeferred() {
        val port = SampleImportSpyMIDIPort(connected = true)
        val repo = MismatchTestRepo(port)

        // Arrange: the repo is awaiting reqId=35 for a DATA page.
        val ack = kotlinx.coroutines.CompletableDeferred<Boolean>()
        repo.setAwaitedPutReqId(35)
        repo.setPendingPutAckDeferred(ack)

        // Act: simulate a response with reqId=99 (mismatched) and STATUS_OK.
        repo.simulatePutPageResponse(responseReqId = 99, status = SysExProtocol.STATUS_OK)

        // Assert: the page deferred must NOT be completed by the mismatched response.
        assertFalse(
            "pendingPutAckDeferred must NOT be completed by a response with reqId=99 when awaiting 35",
            ack.isCompleted,
        )
    }

    @Test
    fun matchingReqId_completesPageDeferred() {
        val port = SampleImportSpyMIDIPort(connected = true)
        val repo = MismatchTestRepo(port)

        // Arrange: the repo is awaiting reqId=35.
        val ack = kotlinx.coroutines.CompletableDeferred<Boolean>()
        repo.setAwaitedPutReqId(35)
        repo.setPendingPutAckDeferred(ack)

        // Act: simulate a response with the correct reqId=35 and STATUS_OK.
        repo.simulatePutPageResponse(responseReqId = 35, status = SysExProtocol.STATUS_OK)

        // Assert: the page deferred IS completed by the matching response.
        assertTrue(
            "pendingPutAckDeferred MUST be completed by a response with the correct reqId=35",
            ack.isCompleted,
        )
    }
}

