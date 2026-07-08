package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stateful file-transfer core extracted from [MIDIRepository] (999.5 Plan 01).
 *
 * Owns everything the device's node-ID FILE protocol needs: the file-op mutex, the
 * reqId→waiter correlation registry, the FILE_INIT session handshake, node-ID resolution,
 * the metadata primitives, and the paged GET/PUT transfer protocols (project archives and
 * `/sounds` sample uploads).
 *
 * [MIDIRepository] is the single owner of `_deviceState` / `currentDeviceId` — FTC never
 * touches that state directly.  It reads the live output port id via [outputPortId] and the
 * current device id via [deviceId], both supplied as constructor callbacks so the root can
 * update them independently (e.g. on device greet) without FTC holding a stale copy.
 *
 * The incoming-SysEx file-frame path (TE_SYSEX_FILE / cmd=5) is routed here via
 * [routeFileFrame] — the SAME seam test doubles subclass/inject into (D-01 == D-04, see the
 * 999.5 CONTEXT). Device-state frames (GREET) stay classified in [MIDIRepository.dispatchSysEx];
 * on GREET the root calls [onDeviceGreet] so FTC can reset its own per-connection state.
 *
 * Self-wires [port]'s `onMidiReceived` to its own byte-stream parser so FTC is independently
 * testable/usable against a raw [MIDIPort] (constructing FTC standalone, as retargeted seam
 * tests do per D-01). In production, [MIDIRepository]'s `init` block runs AFTER this
 * constructor and overwrites `onMidiReceived` with its own parser (owning the device-state
 * fan-out via `dispatchSysEx` → `routeFileFrame`) — so this self-wiring is a no-op there.
 */
open class FileTransferClient(
    private val port: MIDIPort,
    private val outputPortId: () -> String?,
    private val deviceId: () -> Int,
) {
    init {
        val selfParser = MidiByteStreamParser(
            onSysEx = { routeFileFrame(it) },
            onChannelMessage = { /* file transfer never observes channel messages */ },
        )
        port.onMidiReceived = { _, data -> selfParser.parse(data) }
    }

    /**
     * Serialises all device file operations so that only one file op can hold the shared mutable
     * state at a time.  Acquiring threads on [fileOpMutex] will suspend (not block) until the
     * current holder releases, preventing poll-vs-import state corruption.
     *
     * CRITICAL: kotlinx [Mutex] is NOT reentrant.  Every function that acquires [fileOpMutex]
     * MUST call internal *NoLock helpers for any nested file ops — never call another
     * [withLock] from within a [withLock] body.  See [ensureFileSessionInitNoLock] and
     * [resolveNodeIdInternal] for the NoLock pattern.
     */
    private val fileOpMutex = Mutex()

    /**
     * Monotonic request-ID counter for all FILE ops.
     *
     * Wraps in the 11-bit space 1..2046 (skips 0 and 2047=0x7FF which some devices treat as
     * reserved). The fixed greet reqId (1) is skipped on wrap-around to avoid aliasing with
     * the GREET frame; PUT_INIT_REQUEST_ID (30) is NOT in this counter's range because
     * [putSampleFile] manages its own transfer-local counter starting at 30.
     *
     * Using a single shared counter means every FILE frame in flight (regardless of op type)
     * has a globally unique reqId — stale or duplicate responses from a prior op can never
     * satisfy the waiter of a different op.
     */
    private val fileReqIdCounter = AtomicInteger(FILE_REQ_ID_INITIAL)

    /**
     * Return the next globally-unique file request ID, wrapping within [FILE_REQ_ID_MIN]..[FILE_REQ_ID_MAX].
     * Skips 0 (invalid) and greet/put reserved IDs.
     */
    private fun nextFileReqId(): Int {
        while (true) {
            val cur = fileReqIdCounter.get()
            val next = if (cur >= FILE_REQ_ID_MAX) FILE_REQ_ID_MIN else cur + 1
            if (fileReqIdCounter.compareAndSet(cur, next)) {
                // Skip IDs reserved for fixed ops: greet=1, PUT_INIT=30 range starts at 30.
                // Greet is CMD_GREET (not a FILE op), but its reqId=1 appears in raw frames
                // so avoid aliasing. PUT range 30..~60 is owned by putSampleFile's local counter.
                if (next == 1 || next in 30..99) continue
                return next
            }
        }
    }

    // ── Active-group structure caches ─────────────────────────────────────────
    // These eliminate the repeated FILE_LIST of /projects on every 1.5s poll tick.
    //
    // groupsNodeCache:     activeProjNodeId → groupsNodeId  (resolved once per project)
    // groupNodeNameCache:  groupsNodeId     → map of (groupNodeId → name "A".."D")
    //
    // Both are invalidated on device greet (new connection) or when outputPortId goes null.
    // If the cached value leads to a failed METADATA GET (device returns no "active"), the
    // cache entries are left in place (structure is unlikely to change mid-session).
    //
    // HashMap is fine here — access is single-threaded (always inside fileOpMutex.withLock).
    val groupsNodeCache = HashMap<Int, Int>()
    val groupNodeNameCache = HashMap<Int, Map<Int, String>>()

    @Volatile private var statsQueryInFlight = false

    /** True while a stats query holds [fileOpMutex] via [withFileOpLock]. Diagnostic-only. */
    val isStatsQueryInFlight: Boolean get() = statsQueryInFlight

    // ── FILE_INIT session state (Task 3 — hardware-required handshake) ──
    // The device returns "can't list unless initialized" until a FILE_INIT (subcmd=1) is
    // sent. This is a one-time-per-connection handshake; the negotiated chunkSize is used
    // to bound response sizes. Reset to false on greet (new connection).
    @Volatile private var fileSessionInitialized = false
    private var deviceChunkSize: Int = 512

    // ── SysEx response deferreds ──
    // reqId→waiter correlation registry (backlog 999.4). Every file op registers a waiter
    // under its unique reqId and [routeFileFrame] routes responses by reqId via [fileWaiters].
    private val fileWaiters = FileWaiterRegistry()

    /**
     * Run [block] under [fileOpMutex]. Exposed so [MIDIRepository]'s PAS-bound (not-yet-moved)
     * methods can still serialise with FTC's file ops via the single shared mutex.
     */
    suspend fun <T> withFileOpLock(block: suspend () -> T): T = fileOpMutex.withLock { block() }

    /**
     * Reset per-connection session state on device greet (new connection). Called by
     * [MIDIRepository]'s CMD_GREET branch — the root owns `currentDeviceId` adoption and
     * calls this afterward so FTC's caches/session/in-flight waiters are cleared too.
     */
    fun onDeviceGreet() {
        fileSessionInitialized = false
        groupsNodeCache.clear()
        groupNodeNameCache.clear()
        Log.d("EP133MIDI", "GREET: cleared structure caches (new connection)")
        // A greet means a (re)connection — fail any file ops still awaiting on the
        // registry so they don't hang to timeout against a device that just reset.
        fileWaiters.failAll(IllegalStateException("device greet/reconnect"))
    }

    /**
     * Reset per-connection session state on a port swap (debug-only Simulated EP-133 toggle).
     * Mirrors [onDeviceGreet] — the new port needs a fresh FILE_INIT handshake and stale
     * caches must not leak across ports.
     */
    fun onPortSwapped() {
        fileSessionInitialized = false
        groupsNodeCache.clear()
        groupNodeNameCache.clear()
    }

    /**
     * Route an incoming TE_SYSEX_FILE (cmd=5) frame. This is the SAME seam the retargeted
     * tests inject into (D-01 == D-04) — the full TE_SYSEX_FILE branch body formerly in
     * [MIDIRepository.dispatchSysEx]: extract status + reqId, unpack the 7-bit body, and
     * route the [FileResponse] to its waiter via [fileWaiters].
     *
     * @param message the complete raw SysEx message (starting 0xF0, ending 0xF7) exactly as
     *   received by the byte-stream parser — identical to what [MIDIRepository.dispatchSysEx]
     *   receives, so callers/tests can inject the same frames they always have.
     */
    fun routeFileFrame(message: ByteArray) {
        if (message.size < 10) return
        val payload = if (message.size > 10) message.copyOfRange(9, message.size - 1) else ByteArray(0)
        // Hardware-verified (2026-06-24) — responses carry a STATUS byte BEFORE the packed
        // body (reference data/index.js: `let o=9; if(response) status=s[o++]`). So payload[0]
        // is the status; the 7-bit-packed body starts at payload[1]. Unpacking from payload[0]
        // shifts every group boundary and corrupts the data.
        val hexDump = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        Log.d("EP133MIDI", "MIDI META: inbound FILE response cmd=5 payload[${payload.size}] $hexDump")
        val fileStatus = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
        val packedBody = if (payload.size > 1) payload.copyOfRange(1, payload.size) else ByteArray(0)
        val body = if (packedBody.isNotEmpty()) SysExProtocol.unpack7bit(packedBody) else ByteArray(0)
        Log.d("EP133MIDI", "MIDI META: FILE response status=$fileStatus body[${body.size}] ${body.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
        // NOTE: do NOT early-return when body is empty — a status-only empty-body response
        // is a valid PUT DATA page ack (hardware-confirmed 2026-06-25). Bailing here drops
        // the ack before reqId-matching, leaving the page-0 deferred pending until timeout.
        // Guard only when there is no status byte at all (payload itself is empty).
        if (payload.isEmpty()) return

        // reqId-first routing (backlog 999.4): every file op registers a waiter under its
        // unique reqId, and route() delivers each response to the waiter that owns it. An
        // unmatched reqId is a stale or duplicate response (the device sends each response
        // twice) and is dropped.
        val responseReqId = ((message[6].toInt() and 0x0F) shl 7) or (message[7].toInt() and 0x7F)
        val routed = fileWaiters.route(FileResponse(responseReqId, fileStatus, body))
        if (routed == FileWaiterRegistry.RouteResult.Unmatched) {
            Log.w("EP133MIDI", "MIDI META: unrouted FILE response reqId=$responseReqId status=$fileStatus — dropped")
        }
    }

    // ── Paged project archive transfer (Phase 4 GATE) ──

    /**
     * Download a full project archive via the device's two-phase INIT/DATA protocol.
     *
     * Sends GET_INIT, awaits the parsed {fileSize, fileName}, then loops GET_DATA(page)
     * requests until `fileSize` bytes are assembled or an empty-data page terminates early.
     * Each DATA response must match the expected page (page mismatch throws). The pending
     * handler stays registered across STATUS_SPECIFIC_SUCCESS_START and resolves on STATUS_OK.
     *
     * An absolute outer timeout guards a never-terminating stream (threat T-04-04); the
     * per-page receive is also bounded. Returns the assembled `.tar` bytes.
     *
     * @throws IllegalStateException on page mismatch, buffer overflow, or device error.
     */
    suspend fun getProjectArchive(nodeId: Int): ByteArray {
        val portId = outputPortId() ?: throw IllegalStateException("no output port")
        return fileOpMutex.withLock {
            // INIT: one request -> one response (OneShot via the registry).
            val initReqId = nextFileReqId()
            val initFrame = SysExProtocol.buildFileGetInitFrame(deviceId(), nodeId, requestId = initReqId)
            val initResp = awaitFileOp(initReqId, FileOpKind.GET_INIT, portId, initFrame, GET_INIT_TIMEOUT_MS)
                ?: throw IllegalStateException("GET INIT timed out")
            val init = SysExProtocol.parseGetInitResponse(initResp.body)
            Log.d("EP133APP", "Project GET init: ${init.fileName} ${init.fileSize} bytes")

            val out = java.io.ByteArrayOutputStream(init.fileSize.coerceAtLeast(0))
            val cap = init.fileSize + SysExProtocol.MAX_PAGE_BYTES
            var page = 0
            while (out.size() < init.fileSize) {
                // Each GET_DATA page is its own request -> its own response (OneShot by reqId).
                val dataReqId = nextFileReqId()
                val dataFrame = SysExProtocol.buildFileGetDataFrame(deviceId(), page, requestId = dataReqId)
                val resp = awaitFileOp(dataReqId, FileOpKind.GET_DATA, portId, dataFrame, GET_PAGE_TIMEOUT_MS)
                    ?: throw IllegalStateException("GET DATA page $page timed out")
                if (resp.status != SysExProtocol.STATUS_OK &&
                    resp.status < SysExProtocol.STATUS_SPECIFIC_SUCCESS_START
                ) {
                    throw IllegalStateException("GET DATA page $page device error status ${resp.status}")
                }
                val data = SysExProtocol.parseGetDataResponse(resp.body)
                check(data.page == page) { "unexpected page ${data.page}, expected $page" }
                if (data.data.isEmpty()) break
                check(out.size() + data.data.size <= cap) {
                    "GET overflow: ${out.size() + data.data.size} bytes exceeds cap $cap"
                }
                out.write(data.data)
                page = data.nextPage
            }
            out.toByteArray()
        }
    }

    /**
     * Upload a project archive via the device's two-phase INIT/DATA protocol (restore).
     *
     * Sends PUT_INIT announcing the byte count, then PUT_DATA(page, chunk) frames carrying
     * the archive in `MAX_PAGE_BYTES`-sized slices, awaiting a STATUS_OK acknowledgement.
     * Archive bytes are 7-bit packed by the frame builder.
     */
    /** True if a PUT ack FileResponse signals success (STATUS_OK or a continuation status). */
    private fun putAckOk(resp: FileResponse?): Boolean =
        resp != null && (resp.status == SysExProtocol.STATUS_OK ||
            resp.status >= SysExProtocol.STATUS_SPECIFIC_SUCCESS_START)

    /**
     * Printable ASCII text from a device error response body, or null if there is none.
     * The EP-133 returns a human-readable reason on rejection (e.g. "not enough space");
     * surfacing it beats a generic "upload rejected" message.
     */
    private fun deviceErrorText(resp: FileResponse?): String? {
        val body = resp?.body ?: return null
        if (body.isEmpty()) return null
        val text = String(body, Charsets.US_ASCII).filter { it.code in 32..126 }.trim()
        return text.ifBlank { null }
    }

    suspend fun putProjectArchive(slotNodeId: Int, tarBytes: ByteArray): Boolean {
        val portId = outputPortId() ?: throw IllegalStateException("no output port")
        return fileOpMutex.withLock {
            var nextReqId = PUT_INIT_REQUEST_ID
            val initReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
            val initFrame = SysExProtocol.buildFilePutInitFrame(deviceId(), slotNodeId, tarBytes.size, requestId = initReqId)
            if (!putAckOk(awaitFileOp(initReqId, FileOpKind.PUT_INIT, portId, initFrame, PUT_ACK_TIMEOUT_MS))) {
                return@withLock false
            }
            var page = 0
            var offset = 0
            while (offset < tarBytes.size) {
                val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, tarBytes.size)
                val chunk = tarBytes.copyOfRange(offset, end)
                val dataReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
                val dataFrame = SysExProtocol.buildFilePutDataFrame(deviceId(), page, chunk, requestId = dataReqId)
                if (!putAckOk(awaitFileOp(dataReqId, FileOpKind.PUT_DATA, portId, dataFrame, PUT_ACK_TIMEOUT_MS))) {
                    return@withLock false
                }
                offset = end
                page = (page + 1) and 0xFFFF
            }
            val termReqId = nextReqId
            val termFrame = SysExProtocol.buildFilePutDataFrame(deviceId(), page, ByteArray(0), requestId = termReqId)
            putAckOk(awaitFileOp(termReqId, FileOpKind.PUT_DATA, portId, termFrame, PUT_ACK_TIMEOUT_MS))
        }
    }

    // ── Sample file upload to /sounds (Phase 5 Wave 2, SAMPLE-03) ──

    /**
     * Upload raw s16 LE PCM bytes to /sounds by creating a new file via the device's node-ID
     * INIT protocol, matching the reference tool (data/index.js uploadSound / fileHandler.put).
     *
     * Protocol (verified verbatim from data/index.js):
     *  1. Resolve the /sounds directory to a numeric node ID BEFORE starting the transfer,
     *     so its FILE_LIST round-trips don't interleave with the PUT frames.
     *  2. Build metadata JSON `{"channels":<n>,"samplerate":<r>}` (exact key names from
     *     data/index.js `prepareTeenageMeta`) and pass it to [SysExProtocol.buildFileCreatePutInitFrame].
     *  3. Send FILE_PUT INIT via [SysExProtocol.buildFileCreatePutInitFrame]: parentId=/sounds
     *     node, fileId=0 (device assigns), fileSize=pcmBytes.size, filename=name, metadataJson.
     *  4. Page the PCM bytes in [SysExProtocol.MAX_PAGE_BYTES] slices via
     *     [SysExProtocol.buildFilePutDataFrame] (existing, unchanged).
     *  5. Send a zero-length DATA frame as the terminator (reference: "sendSysExFileRequest
     *     serial, new SysExFilePutDataRequest(page, new Uint8Array(0))").
     *  6. Await STATUS_OK; on success return the device-assigned node ID parsed from the INIT
     *     response body[0..1] (u16 BE, hardware-verified 2026-06-29 — test upload returned 193).
     *
     * @param name      Sanitized basename + ".wav" (caller must ensure no path separators).
     * @param pcmBytes  Raw interleaved s16 LE PCM — NO RIFF/WAV header. Must be > 0 bytes.
     * @param channels  Number of audio channels (1 = mono, 2 = stereo).
     * @param sampleRate Sample rate in Hz (always 46875 for device-format audio).
     * @return          Device-assigned node ID (positive Int) on success — parsed from the INIT
     *                  response body, or resolved via `/sounds/<name>` when the body lacks it.
     *                  Null on timeout / device error / unresolvable /sounds node, and also when
     *                  the upload was acked but no node ID could be determined either way: callers
     *                  bind pads to the returned ID, so an unverifiable ID must read as failure.
     * @throws IllegalStateException if no output port is connected or a transfer is in flight.
     * @throws CancellationException if the coroutine is cancelled.
     */
    open suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int = 1,
        sampleRate: Int = 46875,
    ): Int? {
        val portId = outputPortId() ?: throw IllegalStateException("no output port")

        return fileOpMutex.withLock {
            // Ensure the FILE_INIT handshake (once per connection) before resolving node IDs.
            ensureFileSessionInitNoLock()

            val parent = resolveSoundsNodeId()
            if (parent == null) {
                Log.e("EP133APP", "putSampleFile: cannot resolve /sounds node — aborting upload of $name")
                return@withLock null
            }

            // ~420-byte raw chunks (within the device's 512-byte budget) get STATUS_OK; 4096 was
            // rejected ("unexpected page") because it 7-bit-packs past the budget.
            val rawChunkSize = computeSampleChunkSize(deviceChunkSize)
            val pageCount = if (pcmBytes.isEmpty()) 0 else (pcmBytes.size + rawChunkSize - 1) / rawChunkSize
            Log.d("EP133APP", "putSampleFile: chunkSize=$rawChunkSize pages=$pageCount for $name (${pcmBytes.size} bytes, ch=$channels sr=$sampleRate, deviceChunkSize=$deviceChunkSize)")

            // Metadata JSON must match the hardware-verified upload (the Python spike that
            // successfully uploaded + played samples): channels/samplerate alone get the PUT INIT
            // rejected — the device needs the sample `format`, a `name`, and a `sound.playmode`.
            val metaName = name.substringBeforeLast('.', name)
            val metadataJson =
                """{"channels":$channels,"samplerate":$sampleRate,"format":"s16","name":"$metaName","sound.playmode":"oneshot"}"""

            // Transfer-local reqId counter (INIT=30, then each DATA page + terminator). Each frame
            // registers its own OneShot waiter under its reqId via awaitFileOp; only one is ever
            // live at a time (await-then-remove), so a stale/duplicate ack can't mis-route.
            var nextReqId = PUT_INIT_REQUEST_ID  // 30
            var initAcked = false
            // Device-assigned node ID, parsed from the PUT INIT response body[0..1] u16 BE.
            // Hardware-verified (2026-06-29): a 0.3s test upload returned nodeId 193 via this path.
            var assignedNodeId: Int? = null
            try {
                // INIT: announce parent dir, fileId=0 (new file), size, filename, metadata.
                val initReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
                val initFrame = SysExProtocol.buildFileCreatePutInitFrame(
                    deviceId(),
                    parentNodeId = parent,
                    fileSize = pcmBytes.size,
                    filename = name,
                    requestId = initReqId,
                    metadataJson = metadataJson,
                )
                Log.d("EP133MIDI", "MIDI META: outbound PUT INIT reqId=$initReqId name=$name size=${pcmBytes.size}")
                val initResp = awaitFileOp(initReqId, FileOpKind.PUT_INIT, portId, initFrame, PUT_ACK_TIMEOUT_MS)
                if (!putAckOk(initResp)) {
                    val devMsg = deviceErrorText(initResp)
                    Log.e("EP133APP", "putSampleFile: PUT INIT rejected for $name — ${devMsg ?: "no ack (timeout)"}")
                    // Surface the device's own reason (e.g. "not enough space") to the caller;
                    // fall back to null only when the device said nothing (genuine timeout).
                    if (devMsg != null) throw java.io.IOException(devMsg)
                    return@withLock null
                }
                // Parse device-assigned node ID from body[0..1] as unsigned 16-bit big-endian.
                // Hardware-verified (2026-06-29): body carries the new file's node ID so callers
                // can use it directly for pad-metadata assignment without a subsequent FILE_LIST.
                if (initResp != null && initResp.body.size >= 2) {
                    assignedNodeId = ((initResp.body[0].toInt() and 0xFF) shl 8) or
                        (initResp.body[1].toInt() and 0xFF)
                    Log.d("EP133APP", "putSampleFile: device assigned nodeId=$assignedNodeId for $name")
                } else {
                    Log.w("EP133APP", "putSampleFile: PUT INIT response body too short to parse nodeId — body=${initResp?.body?.size ?: 0} bytes")
                }
                initAcked = true
                Log.d("EP133APP", "putSampleFile: PUT INIT ack OK — sending DATA pages for $name")

                // DATA pages: serial, awaiting each page's ack before sending the next (USB-MIDI
                // has no flow control; the reference tool sends serially with await).
                var page = 0
                var offset = 0
                while (offset < pcmBytes.size) {
                    val end = minOf(offset + rawChunkSize, pcmBytes.size)
                    val chunk = pcmBytes.copyOfRange(offset, end)
                    val dataReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
                    val dataFrame = SysExProtocol.buildFilePutDataFrame(deviceId(), page, chunk, requestId = dataReqId)
                    Log.d("EP133MIDI", "MIDI META: outbound PUT DATA page=$page reqId=$dataReqId chunkSize=${chunk.size}")
                    val dataResp = awaitFileOp(dataReqId, FileOpKind.PUT_DATA, portId, dataFrame, PUT_ACK_TIMEOUT_MS)
                    if (!putAckOk(dataResp)) {
                        val devMsg = deviceErrorText(dataResp)
                        Log.e("EP133APP", "putSampleFile: DATA page $page rejected for $name — ${devMsg ?: "no ack (timeout)"}")
                        val closeReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
                        forceCloseTransfer(portId, page + 1, closeReqId)
                        if (devMsg != null) throw java.io.IOException(devMsg)
                        return@withLock null
                    }
                    offset = end
                    page = (page + 1) and 0xFFFF
                }

                // Zero-length DATA terminator (required by the reference tool). Await its final ack.
                val termReqId = nextReqId; nextReqId = (nextReqId + 1) and 0x3FFF
                val terminatorFrame = SysExProtocol.buildFilePutDataFrame(deviceId(), page, ByteArray(0), requestId = termReqId)
                Log.d("EP133MIDI", "MIDI META: outbound PUT terminator page=$page reqId=$termReqId")
                if (putAckOk(awaitFileOp(termReqId, FileOpKind.PUT_DATA, portId, terminatorFrame, PUT_ACK_TIMEOUT_MS))) {
                    // Return the device-assigned node ID from the INIT response body. If the body
                    // didn't carry one, resolve the just-created file by path instead — callers
                    // bind pads to this ID (pad metadata "sym"), so a sentinel like -1 must never
                    // escape here. If neither source yields an ID, report failure (null).
                    assignedNodeId ?: resolveNodeIdInternal("/sounds/$name").also {
                        if (it == null) Log.e("EP133APP", "putSampleFile: upload acked but no node ID for $name")
                    }
                } else {
                    null
                }
            } catch (e: CancellationException) {
                // A dangling incomplete PUT wedges the device — best-effort close if INIT was acked.
                if (initAcked) forceCloseTransfer(portId, 0, nextReqId)
                throw e
            }
        } // end fileOpMutex.withLock
    }

    /**
     * Best-effort: send a zero-length DATA terminator to close an incomplete PUT transfer.
     *
     * A dangling incomplete PUT wedges the device — it ignores subsequent PUTs until power-cycle
     * (hardware-proven, 2026-06-24). Called on any DATA page ack failure after the INIT has been
     * acked, so the device can properly close the transfer and accept the next PUT.
     *
     * Uses a short timeout (FORCE_CLOSE_TIMEOUT_MS) and ignores the result — this is best-effort
     * cleanup, not a blocking requirement.
     */
    private suspend fun forceCloseTransfer(portId: String, terminatorPage: Int, reqId: Int) {
        try {
            val frame = SysExProtocol.buildFilePutDataFrame(deviceId(), terminatorPage, ByteArray(0), requestId = reqId)
            Log.w("EP133MIDI", "MIDI META: force-closing incomplete transfer page=$terminatorPage reqId=$reqId")
            awaitFileOp(reqId, FileOpKind.PUT_DATA, portId, frame, FORCE_CLOSE_TIMEOUT_MS)
        } catch (_: CancellationException) {
            // Intentionally NOT rethrown (the one place in this file that doesn't): this is best-effort
            // cleanup that itself runs during cancellation, so a fresh CancellationException from the
            // suspending awaitFileOp is expected here and must not abort the cleanup.
        } catch (_: Exception) {
            // Best-effort — ignore all other errors.
        }
    }

    /**
     * Compute the raw PCM bytes per DATA page for /sounds uploads, bounded by the device's
     * negotiated chunk size from the FILE_INIT handshake.
     *
     * Mirrors the reference tool's calculateMaxPayloadLength formula (data/index.js):
     *   o = 8 + 2 + 1 = 11  (overhead bytes in the SysEx envelope)
     *   s = chunkSize - 6   (usable bytes in one USB packet after SysEx header)
     *   inner = s - 1 - o   (7-bit-pack input capacity minus framing)
     *   maxPayload = inner - (inner / 8)   (7-bit packing expands by 1/8)
     *   rawChunk = maxPayload - 6          (leave headroom for DATA header bytes)
     *
     * Clamped to [64, 440]. Falls back to 256 when chunkSize is 0 or unknown.
     */
    internal fun computeSampleChunkSize(chunkSize: Int): Int {
        if (chunkSize <= 0) return 256
        val o = 11
        val s = chunkSize - 6
        val inner = s - 1 - o
        if (inner <= 0) return 64
        val maxPayload = inner - (inner / 8)
        val raw = maxPayload - 6
        return raw.coerceIn(64, 440)
    }

    // ── FILE_INIT session handshake (Task 3 — once per connection) ─────────────

    /**
     * Ensure the FILE_INIT handshake has been completed for this connection.
     *
     * Hardware-verified: the device returns "can't list unless initialized" until
     * a FILE_INIT (subcommand 1) is sent. This is a one-shot-per-connection call;
     * subsequent calls return immediately if [fileSessionInitialized] is already set.
     *
     * Resets on greet (new connection via [onDeviceGreet]).
     *
     * This public entry-point acquires [fileOpMutex].  Code that already holds the mutex
     * (e.g. [putSampleFile]) must call [ensureFileSessionInitNoLock] directly to avoid a
     * re-entrancy deadlock.
     *
     * @return true if the session is initialized (immediately or after the handshake),
     *         false if no port is connected or the INIT timed out.
     */
    suspend fun ensureFileSessionInit(): Boolean = fileOpMutex.withLock { ensureFileSessionInitNoLock() }

    /**
     * NoLock core for [ensureFileSessionInit].
     *
     * MUST only be called from within a [fileOpMutex] locked context — calling this without
     * holding the mutex can corrupt shared state if another file op is concurrently in flight.
     */
    suspend fun ensureFileSessionInitNoLock(): Boolean {
        if (fileSessionInitialized) return true
        val portId = outputPortId() ?: return false
        val reqId = nextFileReqId()
        val frame = SysExProtocol.buildFileInitFrame(deviceId(), requestId = reqId)
        Log.d("EP133MIDI", "MIDI META: outbound FILE_INIT reqId=$reqId")
        val resp = awaitFileOp(reqId, FileOpKind.INIT, portId, frame, FILE_INIT_TIMEOUT_MS)
        return if (resp != null) {
            // chunkSize parse is approximate (real HW capture `00 0C 00 00 02 00` yields a large
            // number — tolerated). Session opening is what matters; never throw.
            val chunkSize = try { SysExProtocol.parseFileInitResponse(resp.body) } catch (_: Exception) { 512 }
            deviceChunkSize = chunkSize
            fileSessionInitialized = true
            Log.d("EP133MIDI", "FILE_INIT: session initialized, chunkSize=$chunkSize (approx)")
            true
        } else {
            Log.e("EP133MIDI", "FILE_INIT: timed out — proceeding anyway (hardware may not require it)")
            // Best-effort: if the device didn't respond, mark as initialized so we don't loop.
            fileSessionInitialized = true
            false
        }
    }

    /**
     * Pure decode of a FILE_LIST response body into directory entries. Delegates to the
     * SysExProtocol parser so the byte layout lives in one place and stays unit-testable.
     */
    fun parseFileListEntries(body: ByteArray): List<SysExProtocol.FileEntry> =
        SysExProtocol.parseFileListEntries(body)

    /**
     * Issue a node-ID FILE_LIST and return the assembled (unpacked) entry body. Guards
     * against overlapping listings with [statsQueryInFlight] (shared pending fields).
     *
     * HARDWARE-VERIFY (Open Q1): the device may instead accept a path-string FILE_LIST
     * (Phase 2's /sounds path). To switch, replace [SysExProtocol.buildFileListByNodeFrame]
     * with [SysExProtocol.buildFileListFrame] and pass the path — a one-line change.
     */
    suspend fun listNodeBody(nodeId: Int, requestId: Int): ByteArray? {
        val portId = outputPortId() ?: return null
        val frame = SysExProtocol.buildFileListByNodeFrame(deviceId(), nodeId, requestId = requestId)
        val resp = awaitFileOp(requestId, FileOpKind.LIST, portId, frame, FILE_LIST_TIMEOUT_MS) ?: return null
        // Body = [page u16 BE][entries...]; skip the 2-byte page word to return raw entry data.
        return if (resp.body.size > 2) resp.body.copyOfRange(2, resp.body.size) else ByteArray(0)
    }

    /**
     * Allocate the next globally-unique file request ID. Exposed so [MIDIRepository]'s
     * PAS-bound methods (not yet moved) can keep issuing reqIds from the same counter FTC
     * uses internally, preserving the single-counter guarantee across both classes.
     */
    fun nextRequestId(): Int = nextFileReqId()

    /**
     * Resolve a path like "/projects" to a numeric node ID by walking segments from root
     * (nodeId 0), matching each child name in turn (per RESEARCH "Node-ID resolution").
     *
     * Returns null if any segment is not found or the device does not respond.
     *
     * HARDWARE-VERIFY (Open Q1): confirm /projects lists by nodeId (this walk) vs the
     * Phase 2 path string. The path-string fallback is a one-line switch in [listNodeBody].
     */
    open suspend fun resolveNodeId(path: String): Int? {
        return fileOpMutex.withLock {
            // Task 3: ensure FILE_INIT handshake before any node resolution (NoLock — we hold mutex).
            ensureFileSessionInitNoLock()
            resolveNodeIdInternal(path)
        }
    }

    /**
     * Enumerate the 9 EP-133 project slots (PROJ-01).
     *
     * Resolves /projects → nodeId, reads its metadata "active" pointer, FILE_LISTs that node,
     * and maps each child entry to a [MIDIRepository.ProjectSlot] (marking the active slot).
     * Returns an empty list if no device is connected or the device does not respond.
     */
    open suspend fun listProjects(): List<MIDIRepository.ProjectSlot> {
        if (outputPortId() == null) return emptyList()
        return fileOpMutex.withLock {
            // Ensure file session then resolve — both NoLock because we hold fileOpMutex.
            ensureFileSessionInitNoLock()
            val projectsNode = resolveNodeIdInternal("/projects") ?: return@withLock emptyList()

            // Active-slot pointer from /projects directory metadata, via the reqId-routed
            // nodeId-form GET (the same round-trip getActiveGroupIndex rides).
            val activeNode = getMetadataJson(projectsNode).optInt("active", -1).takeIf { it > 0 }

            val body = listNodeBody(projectsNode, requestId = nextFileReqId()) ?: return@withLock emptyList()

            SysExProtocol.parseFileListEntries(body).map { entry ->
                MIDIRepository.ProjectSlot(
                    nodeId = entry.nodeId,
                    name = entry.name,
                    sizeBytes = entry.sizeBytes,
                    isActive = activeNode != null && entry.nodeId == activeNode,
                )
            }
        }
    }

    /**
     * Enumerate the EP-133 /sounds directory — every sample file as a [SysExProtocol.FileEntry]
     * (nodeId + name + size). Used by [BackupManager]. Empty list if offline or unanswered.
     */
    open suspend fun listSoundEntries(): List<SysExProtocol.FileEntry> {
        if (outputPortId() == null) return emptyList()
        return fileOpMutex.withLock {
            ensureFileSessionInitNoLock()
            val soundsNode = resolveNodeIdInternal("/sounds") ?: return@withLock emptyList()
            val body = listNodeBody(soundsNode, requestId = nextFileReqId()) ?: return@withLock emptyList()
            SysExProtocol.parseFileListEntries(body).filter { it.name.isNotBlank() }
        }
    }

    /**
     * Download a file node's full contents via the multi-chunk GET ([getProjectArchive]). Returns
     * null on timeout / device error instead of throwing, so a backup can skip one bad file and
     * keep going.
     */
    open suspend fun getFileBytes(nodeId: Int): ByteArray? = try {
        getProjectArchive(nodeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("EP133APP", "getFileBytes($nodeId) failed", e)
        null
    }

    /**
     * Fetch metadata for [nodeId] using the nodeId-form GET (METADATA_GET = 2).
     *
     * Pages are accumulated until [SysExProtocol.isMetadataTerminator] fires, then
     * the accumulated JSON string is parsed into a [JSONObject].
     *
     * MUST be called from within a [fileOpMutex] locked context.
     *
     * **Defensive (HW-VERIFY-3):** if JSON parsing fails (device returned greet-style
     * `key:value` text instead), falls back to [SysExProtocol.parseGreetResponse] and
     * wraps the result in a JSONObject. This handles Phase-4 metadata (greet-format) when
     * reached via the nodeId path until hardware confirms the response format.
     *
     * @return Parsed [JSONObject] (may be empty on timeout or parse failure).
     */
    suspend fun getMetadataJson(nodeId: Int): JSONObject {
        val portId = outputPortId() ?: return JSONObject()
        val reqId = nextFileReqId()
        val frame = SysExProtocol.buildMetadataGetFrame(deviceId(), nodeId, page = 0, requestId = reqId)
        Log.d("EP133APP", "MIDI META: outbound METADATA GET nodeId=$nodeId reqId=$reqId")
        val resp = awaitFileOp(reqId, FileOpKind.METADATA_GET, portId, frame, METADATA_TIMEOUT_MS)
            ?: return JSONObject()
        // Hardware-verified (2026-06-24): the body is a 2-byte page prefix + {"active":3000} +
        // trailing NUL. Active-group metadata fits one page; extract the outermost { ... } span
        // and parse. (Multi-page metadata is not used by any caller.)
        val accumulated = String(resp.body, Charsets.US_ASCII)
        val jsonSpan = run {
            val s = accumulated.indexOf('{')
            val e = accumulated.lastIndexOf('}')
            if (s >= 0 && e > s) accumulated.substring(s, e + 1) else accumulated
        }
        Log.d("EP133APP", "MIDI META: METADATA GET jsonSpan='$jsonSpan' for nodeId=$nodeId")
        return try {
            JSONObject(jsonSpan)
        } catch (_: Exception) {
            Log.d("EP133APP", "MIDI META: JSON parse failed, trying greet fallback for nodeId=$nodeId")
            val greetMap = SysExProtocol.parseGreetResponse(accumulated.toByteArray(Charsets.US_ASCII))
            JSONObject().apply { greetMap.forEach { (k, v) -> put(k, v) } }
        }
    }

    /**
     * Write [json] as the metadata for [nodeId] via a single METADATA SET frame (METADATA_SET = 1).
     * Correlates the ack by reqId via [fileWaiters].
     *
     * @return true if the device responded (any ack), false on timeout.
     */
    suspend fun setMetadata(nodeId: Int, json: String): Boolean {
        val portId = outputPortId() ?: return false
        val reqId = nextFileReqId()
        val frame = SysExProtocol.buildMetadataSetFrame(deviceId(), nodeId, json, requestId = reqId)
        Log.d("EP133APP", "MIDI META: outbound METADATA SET nodeId=$nodeId json=$json reqId=$reqId")
        return awaitFileOp(reqId, FileOpKind.METADATA_SET, portId, frame, METADATA_TIMEOUT_MS) != null
    }

    /**
     * Shared spine for single-response file ops: register a [FileWaiter.OneShot] under [reqId],
     * send [frame], and await the response the device echoes back under that reqId (routed via
     * [fileWaiters]) up to [timeoutMs]. Always deregisters in finally. Returns null on timeout;
     * rethrows CancellationException. The caller parses [FileResponse.body] for its own op.
     *
     * Routes by reqId, so it is immune to the interleaving/duplicate-response race that the old
     * mutable-flag model hit (backlog 999.4). Does NOT acquire [fileOpMutex] — callers that need
     * device-access serialization hold it already.
     */
    private suspend fun awaitFileOp(
        reqId: Int,
        kind: FileOpKind,
        portId: String,
        frame: ByteArray,
        timeoutMs: Long,
    ): FileResponse? {
        val waiter = FileWaiter.OneShot(reqId, kind)
        fileWaiters.register(waiter)
        return try {
            port.sendMidi(portId, frame)
            withTimeoutOrNull(timeoutMs) { waiter.deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } finally {
            fileWaiters.remove(reqId)
        }
    }

    suspend fun getNodeInfo(nodeId: Int): SysExProtocol.NodeInfo? {
        val portId = outputPortId() ?: return null
        val reqId = nextFileReqId()
        val frame = SysExProtocol.buildFileInfoFrame(deviceId(), nodeId, requestId = reqId)
        Log.d("EP133APP", "MIDI META: outbound FILE_INFO nodeId=$nodeId reqId=$reqId")
        val resp = awaitFileOp(reqId, FileOpKind.INFO, portId, frame, METADATA_TIMEOUT_MS) ?: return null
        return try {
            SysExProtocol.parseFileInfo(resp.body)
        } catch (e: Exception) {
            Log.e("EP133APP", "MIDI META: FILE_INFO parse failed for nodeId=$nodeId", e)
            null
        }
    }

    /**
     * Resolve the /sounds directory node ID inside a [fileOpMutex] locked context.
     *
     * Exists as an open method so test subclasses can stub the resolution without
     * overriding the entire locking machinery in [putSampleFile].  Production path delegates
     * to [resolveNodeIdInternal] (NoLock).
     *
     * MUST only be called from within a [fileOpMutex] locked context.
     */
    open suspend fun resolveSoundsNodeId(): Int? = resolveNodeIdInternal("/sounds")

    /**
     * NoLock core for [resolveNodeId].  Walks path segments from root (nodeId 0) using
     * [listNodeBody] (also NoLock) without acquiring [fileOpMutex].
     *
     * MUST only be called from within a [fileOpMutex] locked context. Exposed (not private)
     * so [MIDIRepository]'s PAS-bound methods (not yet moved) can keep walking paths through
     * the same primitive while they still live on the root.
     */
    suspend fun resolveNodeIdInternal(path: String): Int? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var nodeId = 0   // root
        for (segment in segments) {
            val body = listNodeBody(nodeId, requestId = nextFileReqId())
            if (body == null) {
                Log.d("EP133APP", "MIDI META: resolveInternal('$path') seg='$segment' parent=$nodeId → listNodeBody NULL")
                return null
            }
            val entries = SysExProtocol.parseFileListEntries(body)
            Log.d("EP133APP", "MIDI META: resolveInternal('$path') seg='$segment' parent=$nodeId body=${body.size}B entries=${entries.map { it.name }}")
            val child = entries.firstOrNull { it.name == segment } ?: return null
            nodeId = child.nodeId
        }
        return nodeId
    }

    companion object {
        // Paged-transfer timeouts (threat T-04-04: bound a never-terminating stream).
        private const val GET_INIT_TIMEOUT_MS = 5_000L
        private const val GET_PAGE_TIMEOUT_MS = 5_000L
        private const val PUT_ACK_TIMEOUT_MS = 15_000L
        // Node-ID FILE_LIST + /projects metadata query bound (enumeration, Wave 2).
        // Reverted from 10 s (the bump didn't help — root cause was reqId aliasing, not latency).
        private const val FILE_LIST_TIMEOUT_MS = 5_000L
        // Metadata GET/SET + FILE_INFO round-trip timeout (Step 1 — active-group sync).
        private const val METADATA_TIMEOUT_MS = 5_000L
        // FILE_INIT handshake (Task 3 — once per connection).
        private const val FILE_INIT_TIMEOUT_MS = 5_000L
        // putSampleFile uses a transfer-local counter starting here; each frame increments it.
        // This is the INIT reqId; DATA pages and terminator get 31, 32, ... (masked to 14-bit).
        // The global nextFileReqId() counter skips the range 30..99 so it never aliases these.
        internal const val PUT_INIT_REQUEST_ID = 30
        // Short timeout for the best-effort force-close terminator.
        private const val FORCE_CLOSE_TIMEOUT_MS = 2_000L

        // ── nextFileReqId() counter bounds ──────────────────────────────────────
        // 11-bit space: 1..2046. 0 and 2047 (0x7FF) skipped (reserved/invalid).
        // The range 30..99 is skipped (owned by putSampleFile's transfer-local counter).
        // The value 1 is skipped (conventional greet reqId — CMD_GREET, not TE_SYSEX_FILE,
        // but skip for clarity). Initial value starts at 100 so first call returns 100.
        internal const val FILE_REQ_ID_MIN = 100
        internal const val FILE_REQ_ID_MAX = 2046
        internal const val FILE_REQ_ID_INITIAL = 100
    }
}
