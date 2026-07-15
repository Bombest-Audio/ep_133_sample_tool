package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Phase 6 pattern-write-spike primitives:
 *  1. The complete capability-flag decoder (adds DELETE/MOVE/PLAYBACK, which the shipped
 *     constants omit) — [SysExProtocol.decodeFlags] / [SysExProtocol.isWriteCandidate] /
 *     the new [SysExProtocol.NodeInfo] accessors.
 *  2. The multi-page FILE_LIST loop ([com.ep133.sampletool.domain.midi.FileTransferClient.listAllChildren],
 *     exposed on [MIDIRepository]) that pages until an empty page instead of stopping at page 0
 *     (closes RESEARCH Pitfall 1 — a false NO-GO from missing a node on page 1+).
 *
 * Neither primitive touches hardware. The paging test drives the walker against a [SpyPort]
 * test double (fakes-not-mocks, per project convention), mirroring the frame-construction
 * helpers in [FileReqIdDedupTest].
 */
class PatternSpikeWalkerTest {

    // ── Decoder / classifier cases ──────────────────────────────────────────────

    @Test
    fun decodeFlags_0x1d_returnsReadWriteDeleteFile() {
        // Arrange
        val flags = 0x1d

        // Act
        val decoded = SysExProtocol.decodeFlags(flags)

        // Assert
        assertEquals("READ|WRITE|DELETE|FILE", decoded)
    }

    @Test
    fun decodeFlags_0x0e_returnsReadWriteDir() {
        // Arrange
        val flags = 0x0e

        // Act
        val decoded = SysExProtocol.decodeFlags(flags)

        // Assert
        assertEquals("READ|WRITE|DIR", decoded)
    }

    @Test
    fun decodeFlags_withPlaybackBit_includesPlayback() {
        // Arrange
        val flags = SysExProtocol.TE_SYSEX_FILE_CAPABILITY_PLAYBACK

        // Act
        val decoded = SysExProtocol.decodeFlags(flags)

        // Assert
        assertTrue("decoded='$decoded' should include PLAYBACK", decoded.contains("PLAYBACK"))
    }

    @Test
    fun nodeInfo_isDeletable_reflectsDeleteBit() {
        // Arrange
        val deletableNode = SysExProtocol.NodeInfo(nodeId = 1, parentId = 0, flags = 0x1d, sizeBytes = 0, name = "01")
        val dirNode = SysExProtocol.NodeInfo(nodeId = 2, parentId = 0, flags = 0x0e, sizeBytes = 0, name = "A")

        // Act
        val deletableResult = deletableNode.isDeletable
        val dirResult = dirNode.isDeletable

        // Assert
        assertTrue(deletableResult)
        assertFalse(dirResult)
    }

    @Test
    fun nodeInfo_isPlayable_reflectsPlaybackBit() {
        // Arrange
        val playableNode = SysExProtocol.NodeInfo(
            nodeId = 1, parentId = 0,
            flags = 0x1d or SysExProtocol.TE_SYSEX_FILE_CAPABILITY_PLAYBACK,
            sizeBytes = 0, name = "01",
        )
        val nonPlayableNode = SysExProtocol.NodeInfo(nodeId = 2, parentId = 0, flags = 0x1d, sizeBytes = 0, name = "02")

        // Act
        val playableResult = playableNode.isPlayable
        val nonPlayableResult = nonPlayableNode.isPlayable

        // Assert
        assertTrue(playableResult)
        assertFalse(nonPlayableResult)
    }

    @Test
    fun isWriteCandidate_trueForWritableFile_falseForDirAndReadOnly() {
        // Arrange
        val writableFileFlags = 0x1d      // READ|WRITE|DELETE|FILE
        val writableDirFlags = 0x0e       // READ|WRITE|DIR
        val readOnlyFlags = 0x04          // READ only

        // Act
        val fileResult = SysExProtocol.isWriteCandidate(writableFileFlags)
        val dirResult = SysExProtocol.isWriteCandidate(writableDirFlags)
        val readOnlyResult = SysExProtocol.isWriteCandidate(readOnlyFlags)

        // Assert
        assertTrue(fileResult)
        assertFalse("a directory must never be a write candidate", dirResult)
        assertFalse("a READ-only node must never be a write candidate", readOnlyResult)
    }

    // ── Multi-page listAllChildren loop ─────────────────────────────────────────

    @Test
    fun listAllChildren_pagesUntilEmpty_returnsOnlyPage0Entries() = runTest {
        // Arrange
        val port = SpyPort()
        val repo = TestableRepo(port)
        val nodeId = 3200

        // Act — drive listAllChildren asynchronously so we can inject scripted responses
        // as it issues each page's FILE_LIST request in turn.
        var result: List<SysExProtocol.FileEntry>? = null
        val job = launch(Dispatchers.Unconfined) {
            result = repo.listAllChildren(nodeId)
        }
        kotlinx.coroutines.yield()

        // First request is page 0 — respond with two entries.
        val page0ReqId = port.lastSentReqId()
        assertTrue("port should have sent a FILE_LIST for page 0 (reqId > 0)", page0ReqId > 0)
        repo.injectSysEx(
            buildFakeFileListResponse(
                reqId = page0ReqId,
                entries = listOf(
                    buildEntryBytes(nodeId = 3201, flags = 0x1d, sizeBytes = 0, name = "01"),
                    buildEntryBytes(nodeId = 3202, flags = 0x1d, sizeBytes = 0, name = "02"),
                ),
            ),
        )
        kotlinx.coroutines.yield()

        // Second request must be page 1 (proves the loop does not stop at page 0) —
        // respond with an empty page to terminate the loop.
        val page1ReqId = port.lastSentReqId()
        assertTrue("port should have sent a second FILE_LIST for page 1", page1ReqId > 0)
        assertTrue("page 1 request must use a fresh reqId", page1ReqId != page0ReqId)
        repo.injectSysEx(buildFakeFileListResponse(reqId = page1ReqId, entries = emptyList()))

        job.join()

        // Assert
        assertEquals(2, result!!.size)
        assertEquals(2, port.sent.size)
    }

    @Test
    fun listAllChildren_stopsOnDeviceErrorStatus_doesNotLoopForever() = runTest {
        // Regression (hardware-surfaced, Phase 6 Plan 04): a device error response
        // (status 1, body "invalid id") is a terminal error, NOT list data. Before the fix,
        // listNodeBody stripped the 2-byte page prefix off the error text, mis-parsed the
        // remaining ASCII into a bogus entry, and listAllChildren paged forever. The simulator
        // only ever returns clean empty pages, so this path was never exercised in the sim.
        // Arrange
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Act — the device rejects the LIST for this node with an error status.
        var result: List<SysExProtocol.FileEntry>? = null
        val job = launch(Dispatchers.Unconfined) {
            result = repo.listAllChildren(9999)
        }
        kotlinx.coroutines.yield()

        val reqId = port.lastSentReqId()
        assertTrue("port should have sent the first FILE_LIST", reqId > 0)
        repo.injectSysEx(buildFakeFileListError(reqId = reqId, status = 1))
        job.join()

        // Assert — terminated on the error, with no bogus entry and no second page.
        assertEquals("a device error status must yield zero entries", 0, result!!.size)
        assertEquals("must not page past an error response", 1, port.sent.size)
    }

    // ── Test double: spy MIDIPort that records sent frames (mirrors FileReqIdDedupTest) ──

    private class SpyPort : MIDIPort {
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

        /** Extract the reqId from the last sent FILE frame (frame[6] high nibble + frame[7] low 7). */
        fun lastSentReqId(): Int {
            val frame = sent.lastOrNull() ?: return -1
            if (frame.size < 8) return -1
            return ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)
        }
    }

    /** MIDIRepository subclass that exposes dispatchSysEx for frame injection. */
    private class TestableRepo(port: SpyPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Inject a raw SysEx frame as if received from the device. */
        fun injectSysEx(frame: ByteArray) = dispatchSysEx(frame)
    }

    // ── Frame-building helpers (mirrors FileReqIdDedupTest's buildFakeFileListResponse) ──

    /** Build one FILE_LIST entry: nodeId-u16-BE, flags-u8, size-u32-BE, name-NUL. */
    private fun buildEntryBytes(nodeId: Int, flags: Int, sizeBytes: Long, name: String): ByteArray {
        return byteArrayOf(
            (nodeId shr 8).toByte(), (nodeId and 0xFF).toByte(),
            flags.toByte(),
            ((sizeBytes shr 24) and 0xFF).toByte(),
            ((sizeBytes shr 16) and 0xFF).toByte(),
            ((sizeBytes shr 8) and 0xFF).toByte(),
            (sizeBytes and 0xFF).toByte(),
        ) + name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
    }

    /**
     * Build a FILE_LIST response frame carrying [entries] (possibly empty, to signal the
     * terminating page). Layout mirrors FileReqIdDedupTest's helper: status byte raw at
     * position 9, page-u16 + entries 7-bit packed after.
     */
    private fun buildFakeFileListResponse(reqId: Int, entries: List<ByteArray>): ByteArray {
        val body = byteArrayOf(0x00, 0x00) + entries.fold(ByteArray(0)) { acc, e -> acc + e }
        val packed = SysExProtocol.pack7bit(body)
        val reqHigh = ((reqId shr 7) and 0x0F).toByte()
        val reqLow = (reqId and 0x7F).toByte()
        return byteArrayOf(
            0xF0.toByte(),
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,               // deviceId
            0x40,
            reqHigh,
            reqLow,
            SysExProtocol.TE_SYSEX_FILE.toByte(),
            0x00,               // status = STATUS_OK (NOT packed — raw byte at position 9)
        ) + packed + byteArrayOf(0xF7.toByte())
    }

    /**
     * Build a FILE_LIST *error* response: a raw non-zero [status] byte at position 9, with the
     * device's "invalid id" text as the (7-bit-packed) body. Mirrors what the hardware sends for
     * a node it refuses to list. The body is deliberately non-empty to prove the fix keys off the
     * status byte, not off an empty body.
     */
    private fun buildFakeFileListError(reqId: Int, status: Int): ByteArray {
        val packed = SysExProtocol.pack7bit("invalid id".toByteArray(Charsets.US_ASCII))
        val reqHigh = ((reqId shr 7) and 0x0F).toByte()
        val reqLow = (reqId and 0x7F).toByte()
        return byteArrayOf(
            0xF0.toByte(),
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,               // deviceId
            0x40,
            reqHigh,
            reqLow,
            SysExProtocol.TE_SYSEX_FILE.toByte(),
            status.toByte(),    // raw status byte at position 9
        ) + packed + byteArrayOf(0xF7.toByte())
    }
}
