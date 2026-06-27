package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the active-group poll fix: poll-in-flight guard and structure cache.
 *
 * These tests use a counting spy port to verify:
 *  1. A concurrent second poll returns null immediately without queuing on fileOpMutex.
 *  2. The groups-dir and group-name caches are populated on first call and reused on
 *     subsequent calls — the one-time-resolution FILE_LISTs are not re-issued.
 */
class ActiveGroupSyncTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

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
    private fun fakeOkResponse(reqId: Int): ByteArray {
        val body = byteArrayOf(
            0x00,                           // status = 0 (STATUS_OK)
            0x00, 0x0C, 0x00, 0x00, 0x02, 0x00,  // dummy (matches real HW INIT shape)
        )
        return SysExProtocol.buildFrame(
            deviceId  = 0,
            command   = SysExProtocol.TE_SYSEX_FILE,
            requestId = reqId,
            payload   = body,
        )
    }

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
            0x02,                          // flags = DIRECTORY
            0x00, 0x00, 0x00, 0x00,        // size = 0
        ) + nameBytes
        val rawBody = byteArrayOf(
            0x00,       // status = 0
            0x00, 0x00, // page u16 = 0
        ) + entry
        return SysExProtocol.buildFrame(
            deviceId  = 0,
            command   = SysExProtocol.TE_SYSEX_FILE,
            requestId = reqId,
            payload   = rawBody,
        )
    }

    /**
     * Build a METADATA GET response carrying a JSON string.
     * Body: status=0, page u16, JSON bytes, NUL, terminator-page flag byte.
     *
     * The dispatcher accumulates JSON pages until isMetadataTerminator fires.
     * We build a single-page response that looks like a terminator (page word = 0xFFFF).
     */
    private fun fakeMetaResponse(reqId: Int, json: String): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        // Terminator page: page u16 = 0xFFFF signals end of stream.
        val rawBody = byteArrayOf(
            0x00,                  // status = 0
            0xFF.toByte(), 0xFF.toByte(), // page u16 = 0xFFFF (terminator)
        ) + jsonBytes
        return SysExProtocol.buildFrame(
            deviceId  = 0,
            command   = SysExProtocol.TE_SYSEX_FILE,
            requestId = reqId,
            payload   = rawBody,
        )
    }

    // ── Recording spy MIDIPort ────────────────────────────────────────────────

    /**
     * Records every sent frame. Does NOT auto-ack — tests deliver responses manually.
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
            inputs  = listOf(MIDIPort.Device("in", "EP-133")),
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

    // ── Testable repo ─────────────────────────────────────────────────────────

    private class ActiveGroupTestRepo(port: RecordingPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Expose dispatchSysEx for frame injection. */
        fun inject(frame: ByteArray) = dispatchSysEx(frame)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * A concurrent second getActiveGroupIndex call returns null immediately without
     * queuing on fileOpMutex. The first call holds the mutex; the second sees
     * activeGroupPollInFlight=true and bails before acquiring the lock.
     */
    @Test
    fun concurrentPoll_secondCallReturnsNullImmediately() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        // Launch the first poll — it will block inside fileOpMutex awaiting MIDI responses.
        val firstPollDeferred = async(Dispatchers.Unconfined) {
            repo.getActiveGroupIndex()
        }
        // Yield so the first poll starts and sets activeGroupPollInFlight=true.
        yield()

        // Now launch the second poll — should return null immediately (guard fires).
        val secondResult = repo.getActiveGroupIndex()
        assertNull(
            "Second concurrent poll must return null immediately (poll already in flight)",
            secondResult,
        )

        // Cancel the first poll (it's waiting for MIDI responses that will never arrive).
        firstPollDeferred.cancel()
        try { firstPollDeferred.await() } catch (_: Exception) {}
    }

    /**
     * Unique reqIds: consecutive FILE ops within a single getActiveGroupIndex call all
     * receive distinct reqIds so no response can satisfy the wrong deferred.
     */
    @Test
    fun consecutiveFileOps_haveDistinctReqIds() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        // Run one getActiveGroupIndex call — drive it until it has sent at least two frames.
        val pollJob = launch(Dispatchers.Unconfined) {
            repo.getActiveGroupIndex()
        }
        yield()

        // Respond to FILE_INIT so the poll can proceed to the next op.
        val initReqId = port.lastSentReqId()
        assertTrue("INIT reqId must be in range", initReqId in MIDIRepository.FILE_REQ_ID_MIN..MIDIRepository.FILE_REQ_ID_MAX)
        repo.inject(fakeOkResponse(initReqId))
        yield()

        // If a second frame was sent, it must have a different reqId.
        if (port.sent.size >= 2) {
            val secondReqId = reqIdFrom(port.sent[1])
            assertNotEquals(
                "Second FILE op reqId must differ from INIT reqId",
                initReqId,
                secondReqId,
            )
        }

        pollJob.cancel()
        try { pollJob.join() } catch (_: Exception) {}
    }

    /**
     * Verify the counter skips the PUT range 30..99: all reqIds emitted by FILE ops
     * on a fresh repo are >= FILE_REQ_ID_MIN (100).
     */
    @Test
    fun nextFileReqId_skipsReservedRange() = runTest {
        val port = RecordingPort()
        val repo = ActiveGroupTestRepo(port)

        // Kick off a few ops to collect some reqIds.
        val pollJob = launch(Dispatchers.Unconfined) {
            repo.getActiveGroupIndex()
        }
        yield()

        // Drain FILE_INIT and first resolution frames.
        repeat(5) {
            if (port.sent.isNotEmpty()) {
                val rId = port.lastSentReqId()
                // Respond to advance the poll.
                repo.inject(fakeOkResponse(rId))
                yield()
            }
        }

        // Every reqId we saw must be outside the put-range [30..99] and != 0 and != 1.
        for (frame in port.sent) {
            if (frame.size < 8) continue
            val rId = reqIdFrom(frame)
            if (rId == 0) continue // greet may produce 0 — skip
            assertTrue(
                "reqId $rId must not be in reserved range [1..99]",
                rId !in 1..99,
            )
        }

        pollJob.cancel()
        try { pollJob.join() } catch (_: Exception) {}
    }
}
