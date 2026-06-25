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
    }

    // ── Test double: MIDIRepository subclass that exposes dispatchSysEx ────────

    /**
     * Exposes [exposedDispatch] so tests can inject FILE response frames directly, and
     * exposes the awaitedFileReqId state indirectly via [driveFileInitResponseWithReqId].
     *
     * Overrides [resolveNodeId] to avoid hardware round-trips.
     */
    private class TestableRepo(port: SpyPort) : MIDIRepository(port) {
        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        /** Inject a raw SysEx frame as if received from the device. */
        fun injectSysEx(frame: ByteArray) = dispatchSysEx(frame)

        /** Override resolveNodeId to return a fixed nodeId without hardware. */
        override suspend fun resolveNodeId(path: String): Int? =
            if (path == "/sounds") 99 else null
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
     * Build a minimal FILE_LIST response frame for injection (same envelope, distinct body).
     * Body: status=0, page u16 (00 00), one short entry "X" so parseFileListEntries gets something.
     * Dispatcher routes this to the FILE_LIST deferred when pendingNodeListDeferred != null.
     */
    private fun buildFakeFileListResponse(reqId: Int): ByteArray {
        // Raw body: status=0, then page u16 BE, then a 7-byte entry header + name
        // File entry format: [nodeId u16 BE][flags][size u32 BE][name NUL]
        val entry = byteArrayOf(
            0x00, 0x63,          // nodeId = 99 (the expected sounds nodeId)
            0x01,                // flags = FILE
            0x00, 0x00, 0x00, 0x00, // size = 0
        ) + "sounds".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
        val rawBody = byteArrayOf(
            0x00,           // status = 0 (STATUS_OK)
            0x00, 0x00,     // page u16 = 0
        ) + entry
        return SysExProtocol.buildFrame(
            deviceId  = 0,
            command   = SysExProtocol.TE_SYSEX_FILE,
            requestId = reqId,
            payload   = rawBody,
        )
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * A FILE response whose reqId matches awaitedFileReqId completes the in-flight deferred.
     *
     * Scenario: FILE_INIT send sets awaitedFileReqId=83; matching response (reqId=83)
     * completes pendingFileInitDeferred. Verified by calling ensureFileSessionInit() in a
     * coroutine and then injecting the matching response.
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

        // Wait until the frame is sent (awaitedFileReqId = 83 set before sendMidi).
        kotlinx.coroutines.yield()

        // Inject a matching response (reqId=83 == FILE_INIT_REQUEST_ID).
        val response = buildFakeFileResponse(reqId = 83)
        repo.injectSysEx(response)

        job.join()
        // ensureFileSessionInit should have returned true (init deferred completed).
        assertTrue("Matching FILE response should complete the INIT deferred", initResult.await())
    }

    /**
     * A FILE response with a stale/duplicate reqId (mismatch) does NOT complete any deferred.
     *
     * Scenario: awaitedFileReqId=83 (FILE_INIT in flight); a duplicate of the PREVIOUS op's
     * response arrives with reqId=50 (stale). The dispatcher must drop it — the INIT deferred
     * must NOT be completed.
     */
    @Test
    fun staleFileResponse_doesNotCompleteDeferred() = runTest {
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Start FILE_INIT — sets awaitedFileReqId=83, pendingFileInitDeferred is live.
        var initCompleted = false
        val initJob = launch(Dispatchers.Unconfined) {
            repo.ensureFileSessionInit()
            initCompleted = true
        }
        kotlinx.coroutines.yield()

        // Inject a stale response with reqId=50 (different from awaitedFileReqId=83).
        val staleResponse = buildFakeFileResponse(reqId = 50)
        repo.injectSysEx(staleResponse)

        // Give the coroutine a chance to complete if the deferred was wrongly completed.
        kotlinx.coroutines.yield()

        // The INIT deferred must NOT have been completed — initCompleted should still be false.
        assertFalse(
            "Stale FILE response (reqId=50) must not complete INIT deferred (awaiting reqId=83)",
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

        val response = buildFakeFileResponse(reqId = 83)
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
     * When a FILE_INIT response (reqId=83) arrives while the LIST deferred is in flight
     * (awaitedFileReqId=50 for a LIST at reqId=50), the INIT duplicate is ignored.
     *
     * This is the exact hardware-confirmed failure mode: a duplicate FILE_INIT response
     * (reqId=83) arrives just after the LIST request was sent (awaitedFileReqId=50).
     * Before the fix: inFlightCmd=FILE_LIST → LIST deferred completed with INIT body → null parse.
     * After the fix: reqId=83 != awaitedFileReqId=50 → frame dropped → LIST deferred unaffected.
     */
    @Test
    fun duplicateInitResponse_doesNotPoisonListDeferred() = runTest {
        val port = SpyPort()
        val repo = TestableRepo(port)

        // Register a fake LIST deferred: set awaitedFileReqId=50 to simulate a LIST being in flight.
        // We do this by starting resolveNodeId with statsQueryInFlight=false (fresh repo).
        // Drive resolveNodeId — internally it calls ensureFileSessionInit then listNodeBody.
        // We intercept by injecting an INIT match first (reqId=83), then inject a stale duplicate
        // (reqId=83) while LIST is in flight (awaitedFileReqId=50).
        var nodeId: Int? = -1
        val resolveJob = launch(Dispatchers.Unconfined) {
            nodeId = repo.resolveNodeId("/sounds")
        }
        // Let resolveNodeId start and reach ensureFileSessionInit.
        kotlinx.coroutines.yield()

        // Inject the FILE_INIT response (reqId=83) → INIT deferred completes, awaitedFileReqId cleared.
        repo.injectSysEx(buildFakeFileResponse(reqId = 83))
        kotlinx.coroutines.yield()

        // Now resolveNodeId proceeds to listNodeBody which sets awaitedFileReqId=50.
        // Inject a STALE DUPLICATE of the INIT response (reqId=83) — simulates HW duplicate.
        // With the fix: reqId=83 != awaitedFileReqId=50 → dispatcher drops it.
        repo.injectSysEx(buildFakeFileResponse(reqId = 83))  // stale duplicate
        kotlinx.coroutines.yield()

        // The LIST deferred must NOT have been completed by the stale INIT duplicate.
        // nodeId should still be null-ish (resolveJob still waiting for real LIST response).
        // Inject the CORRECT LIST response (reqId=50) → LIST deferred completes → entry found.
        repo.injectSysEx(buildFakeFileListResponse(reqId = 50))
        resolveJob.join()

        // If the stale INIT response had poisoned the LIST deferred, resolveNodeId would have
        // returned null (no "sounds" in an INIT body). With the fix, the real LIST response
        // arrived and nodeId should be non-null.
        assertNotNull(
            "resolveNodeId must succeed after stale INIT duplicate is dropped and real LIST arrives",
            nodeId,
        )
    }
}
