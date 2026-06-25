package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the reqId-match guard on FILE (cmd=5) responses.
 *
 * Root cause (hardware-confirmed 2026-06-24): the device sends each response twice due
 * to a duplicate MidiReceiver connection. The dispatcher routes FILE responses by in-flight
 * op STATE. Without reqId filtering, a duplicate FILE_INIT response (same reqId) arrives
 * just after the LIST deferred is registered and completes it with the INIT body — causing
 * resolveNodeId to return null and aborting the upload.
 *
 * Fix: awaitedFileReqId is set before every file-op send. The dispatcher drops any FILE
 * response whose reqId doesn't match awaitedFileReqId.
 *
 * These tests verify the dispatcher ignores stale/duplicate responses and passes matching ones.
 */
class FileReqIdDedupTest {

    // ── Test double: spy MIDIPort that records sent frames ─────────────────────

    private class SpyPort : MIDIPort {
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

        /** Extract the reqId from the last sent FILE frame (frame[6] high nibble + frame[7] low 7). */
        fun lastSentReqId(): Int {
            val frame = sent.lastOrNull() ?: return -1
            if (frame.size < 8) return -1
            return ((frame[6].toInt() and 0x0F) shl 7) or (frame[7].toInt() and 0x7F)
        }
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * MIDIRepository subclass that exposes dispatchSysEx for frame injection and
     * overrides resolveNodeId to avoid hardware round-trips for tests 1–3.
     */
    private class TestableRepo(port: SpyPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Inject a raw SysEx frame as if received from the device. */
        fun injectSysEx(frame: ByteArray) = dispatchSysEx(frame)

        /** Override resolveNodeId to return a fixed nodeId without hardware round-trips. */
        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") 99 else null
    }

    /**
     * MIDIRepository subclass that exposes dispatchSysEx WITHOUT overriding resolveNodeId.
     *
     * Used in [duplicateInitResponse_doesNotPoisonListDeferred] which drives the real
     * ensureFileSessionInit + listNodeBody pipeline so the INIT→LIST reqId transition
     * can be observed and stale-response injection can be tested correctly.
     */
    private class RealWalkRepo(port: SpyPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Inject a raw SysEx frame as if received from the device. */
        fun injectSysEx(frame: ByteArray) = dispatchSysEx(frame)
    }

    // ── Frame-building helpers ─────────────────────────────────────────────────

    /**
     * Build a minimal FILE (cmd=5) response frame for injection into dispatchSysEx.
     *
     * The frame layout mirrors the real device response:
     *   [0] F0 | [1-3] TE ID | [4] deviceId | [5] 0x40 | [6] flags|reqIdHigh | [7] reqIdLow
     *   [8] command=5 | [9..n-2] packed body | [last] F7
     *
     * We use SysExProtocol.buildFrame directly — the reqId byte positions are identical
     * between request and response frames. The dispatcher extracts:
     *   responseReqId = ((message[6] & 0x0F) << 7) | (message[7] & 0x7F)
     * which equals the reqId passed to buildFrame (the flag bits are masked out by 0x0F).
     *
     * Body convention for FILE_INIT response (status=0, chunkSize body):
     *   packed body = status byte (0x00) + dummy bytes
     * The dispatcher unpacks payload[1..] as the body (payload[0] = status).
     */
    private fun buildFakeFileResponse(reqId: Int, statusByte: Int = 0): ByteArray {
        // Body (pre-pack): status=statusByte, then 5 dummy bytes so body.size > 1 after unpack
        // and dispatchSysEx doesn't return early on `if (body.isEmpty()) return`.
        val rawBody = byteArrayOf(
            statusByte.toByte(),        // status (payload[0] — before packed section)
            0x00, 0x0C, 0x00, 0x00, 0x02, 0x00,  // dummy chunk-size bytes (matches real HW INIT)
        )
        return SysExProtocol.buildFrame(
            deviceId  = 0,
            command   = SysExProtocol.TE_SYSEX_FILE,
            requestId = reqId,
            payload   = rawBody,
        )
    }

    /**
     * Build a minimal FILE_LIST response frame that the dispatcher will correctly parse.
     *
     * Real device response layout for a FILE frame (verified 2026-06-23):
     *   [F0][TE_ID_0][TE_ID_1][TE_ID_2][deviceId][0x40][flags|reqIdHigh][reqIdLow]
     *   [TE_SYSEX_FILE=5][status][7-bit-packed body...][F7]
     *
     * In dispatchSysEx: payload = frame[9..end-1]; fileStatus = payload[0] = status;
     * packedBody = payload[1..]; body = unpack7bit(packedBody).
     *
     * So we must NOT use SysExProtocol.buildFrame (which packs the ENTIRE payload including
     * the status byte). Instead we manually place the status byte unpackaged at position 9,
     * then pack only the body content and append it.
     *
     * Body content after unpacking = page-u16-BE followed by entry bytes.
     * The FILE_LIST handler strips page u16 (first 2 bytes) and passes remainder to
     * parseFileListEntries. Entry format: nodeId-u16-BE, flags-u8, size-u32-BE, name-NUL.
     */
    private fun buildFakeFileListResponse(reqId: Int): ByteArray {
        // Entry for "sounds" with nodeId=99.
        val entry = byteArrayOf(
            0x00, 0x63,             // nodeId = 99
            0x02,                   // flags = DIRECTORY (0x02) — sounds is a dir
            0x00, 0x00, 0x00, 0x00, // size = 0
        ) + "sounds".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        // Body = page u16 (0x0000) + entry
        val body = byteArrayOf(0x00, 0x00) + entry
        // Pack the body for the packed section of the frame.
        val packed = SysExProtocol.pack7bit(body)
        // Assemble the frame manually: header + status byte (raw) + packed body + F7
        val reqHigh = ((reqId shr 7) and 0x0F).toByte()
        val reqLow  = (reqId and 0x7F).toByte()
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

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * A FILE response whose reqId matches awaitedFileReqId completes the in-flight deferred.
     *
     * Scenario: FILE_INIT send is observed; matching response (same reqId extracted from the
     * sent frame) completes pendingFileInitDeferred.
     *
     * With nextFileReqId() the INIT reqId is no longer a fixed constant — it is whatever the
     * counter returns at test time. We read it from the sent frame so the test stays correct
     * regardless of counter state.
     */
    @Test
    fun matchingFileResponse_completesDeferred() = runTest {
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Drive ensureFileSessionInit asynchronously — it awaits pendingFileInitDeferred.
        val initResult = CompletableDeferred<Boolean>()
        val job = launch(Dispatchers.Unconfined) {
            initResult.complete(repo.ensureFileSessionInit())
        }

        // Wait until the frame is sent (awaitedFileReqId set before sendMidi).
        kotlinx.coroutines.yield()

        // Extract the reqId the repo used from the sent frame.
        val initReqId = port.lastSentReqId()
        assertTrue("port should have sent a FILE_INIT frame (reqId > 0)", initReqId > 0)

        // Inject a matching response.
        repo.injectSysEx(buildFakeFileResponse(reqId = initReqId))

        job.join()
        // ensureFileSessionInit should have returned true (init deferred completed).
        assertTrue("Matching FILE response should complete the INIT deferred", initResult.await())
    }

    /**
     * A FILE response with a stale/duplicate reqId (mismatch) does NOT complete any deferred.
     *
     * Scenario: FILE_INIT is in flight; a response with a different reqId arrives (stale).
     * The dispatcher must drop it — the INIT deferred must NOT be completed.
     */
    @Test
    fun staleFileResponse_doesNotCompleteDeferred() = runTest {
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Start FILE_INIT — pendingFileInitDeferred is live.
        var initCompleted = false
        val initJob = launch(Dispatchers.Unconfined) {
            repo.ensureFileSessionInit()
            initCompleted = true
        }
        kotlinx.coroutines.yield()

        val initReqId = port.lastSentReqId()
        assertTrue(initReqId > 0)

        // Inject a stale response with a different reqId (initReqId + 1 wraps safely).
        val staleReqId = if (initReqId < MIDIRepository.FILE_REQ_ID_MAX) initReqId + 1 else initReqId - 1
        val staleResponse = buildFakeFileResponse(reqId = staleReqId)
        repo.injectSysEx(staleResponse)

        // Give the coroutine a chance to complete if the deferred was wrongly completed.
        kotlinx.coroutines.yield()

        // The INIT deferred must NOT have been completed.
        assertFalse(
            "Stale FILE response (reqId=$staleReqId) must not complete INIT deferred (awaiting reqId=$initReqId)",
            initCompleted,
        )

        // Clean up: cancel the dangling job.
        initJob.cancel()
    }

    /**
     * A duplicate FILE_INIT response (same reqId, arrives again) is dropped after the first
     * match has already cleared awaitedFileReqId.
     *
     * Once the first matching response is consumed, awaitedFileReqId=-1. A second arrival of
     * the same reqId now has awaitedFileReqId==-1, so the guard condition
     * (awaitedFileReqId != -1 && responseReqId != awaitedFileReqId) is FALSE — the frame
     * passes through, BUT pendingFileInitDeferred is null (already completed and cleared).
     * dispatchFileResponse handles null deferreds gracefully — no double-complete.
     */
    @Test
    fun duplicateMatchingResponse_afterFirstConsumed_isHandledGracefully() = runTest {
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Drive FILE_INIT to completion.
        val initJob = launch(Dispatchers.Unconfined) {
            repo.ensureFileSessionInit()
        }
        kotlinx.coroutines.yield()

        val initReqId = port.lastSentReqId()
        assertTrue(initReqId > 0)

        val response = buildFakeFileResponse(reqId = initReqId)
        // First injection: matches, completes deferred, clears awaitedFileReqId.
        repo.injectSysEx(response)
        initJob.join()

        // Second injection of the exact same frame: awaitedFileReqId is now -1, so the guard
        // passes, but pendingFileInitDeferred is null — should not throw.
        try {
            repo.injectSysEx(response)
        } catch (e: Exception) {
            fail("Duplicate response after deferred consumed must not throw: ${e.message}")
        }
        // No assertion needed — just verifies no exception and no double-complete crash.
    }

    /**
     * When a FILE_INIT response arrives while the LIST deferred is in flight, the INIT
     * duplicate is ignored.
     *
     * This is the exact hardware-confirmed failure mode: a duplicate FILE_INIT response
     * arrives just after the LIST request was sent. The reqIds differ → duplicate dropped →
     * LIST deferred unaffected.
     *
     * This test uses [RealWalkRepo] (no resolveNodeId override) so the real
     * ensureFileSessionInit + resolveNodeIdInternal pipeline is exercised, making the
     * INIT→LIST reqId transition observable and testable.
     */
    @Test
    fun duplicateInitResponse_doesNotPoisonListDeferred() = runTest {
        val port = SpyPort()
        val repo = RealWalkRepo(port)

        // Drive resolveNodeId("/sounds") — the real implementation calls:
        //   fileOpMutex.withLock → ensureFileSessionInitNoLock (sends FILE_INIT)
        //                        → resolveNodeIdInternal("/sounds") (sends FILE_LIST of root)
        // We observe both reqIds from the sent frames.
        var nodeId: Int? = -1
        val resolveJob = launch(Dispatchers.Unconfined) {
            nodeId = repo.resolveNodeId("/sounds")
        }
        // Let resolveNodeId start and reach ensureFileSessionInit.
        kotlinx.coroutines.yield()

        // Capture FILE_INIT reqId from the first sent frame.
        val initReqId = port.lastSentReqId()
        assertTrue("port should have sent FILE_INIT", initReqId > 0)

        // Inject the FILE_INIT response → INIT deferred completes, awaitedFileReqId cleared.
        repo.injectSysEx(buildFakeFileResponse(reqId = initReqId))
        kotlinx.coroutines.yield()

        // Now resolveNodeIdInternal sends a FILE_LIST of the root node (segment="sounds").
        val listReqId = port.lastSentReqId()
        assertTrue("port should have sent FILE_LIST after INIT", listReqId > 0)
        assertTrue("LIST reqId must differ from INIT reqId", listReqId != initReqId)

        // Inject a STALE DUPLICATE of the INIT response — simulates HW duplicate arriving late.
        // With the fix: initReqId != listReqId (awaitedFileReqId) → dispatcher drops it.
        repo.injectSysEx(buildFakeFileResponse(reqId = initReqId))  // stale duplicate
        kotlinx.coroutines.yield()

        // The LIST deferred must NOT have been completed by the stale INIT duplicate.
        // Inject the CORRECT LIST response with a "sounds" entry → LIST deferred completes.
        repo.injectSysEx(buildFakeFileListResponse(reqId = listReqId))
        resolveJob.join()

        // If the stale INIT response had poisoned the LIST deferred, resolveNodeId would have
        // returned null (no "sounds" in an INIT body). With the fix the real LIST response
        // arrived and nodeId should be the "sounds" entry's nodeId (99, per buildFakeFileListResponse).
        assertNotNull(
            "resolveNodeId must succeed after stale INIT duplicate is dropped and real LIST arrives",
            nodeId,
        )
    }
}
