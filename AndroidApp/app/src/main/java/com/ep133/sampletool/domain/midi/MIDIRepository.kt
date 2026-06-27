package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.MidiPort
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.domain.model.Scale
import com.ep133.sampletool.midi.MIDIManager
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level MIDI interface for the EP-133.
 *
 * Wraps a [MIDIPort] implementation with typed helpers for Note On/Off, CC,
 * and Program Change. Exposes device state as a [StateFlow] for Compose observation.
 *
 * Phase 2 additions:
 * - SysEx accumulation buffer for fragmented SysEx messages (D-09, D-10)
 * - [sendRawBytes] for MIDI system real-time messages (Start, Stop, Clock)
 * - [channelFlow] as [StateFlow] for cross-screen channel sharing (D-16)
 * - [queryDeviceStats] for firmware version, storage, and sample count (D-12)
 * - [selectedScale] and [selectedRootNote] as shared state flows (D-17)
 */
open class MIDIRepository(private val midiManager: MIDIPort) {

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
     * [queryDeviceStatsInner]'s GREET frame; PUT_INIT_REQUEST_ID (30) is NOT in this counter's
     * range because [putSampleFile] manages its own transfer-local counter starting at 30.
     *
     * Using a single shared counter means every FILE frame in flight (regardless of op type)
     * has a globally unique reqId — stale or duplicate responses from a prior op can never
     * satisfy [awaitedFileReqId] of a different op.
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

    /**
     * Guard for the active-group poll: set to true while a [getActiveGroupIndex] call is
     * running.  Checked BEFORE acquiring [fileOpMutex] so that a queued poll (suspended on the
     * mutex) does not accumulate behind a running poll — the second call returns null immediately
     * rather than stacking up.
     *
     * This is an AtomicBoolean so it can be read from any thread without synchronisation.
     * The fileOpMutex still serialises the actual op body; this flag is purely an entry guard.
     */
    private val activeGroupPollInFlight = AtomicBoolean(false)

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
    private val groupsNodeCache     = HashMap<Int, Int>()
    private val groupNodeNameCache  = HashMap<Int, Map<Int, String>>()

    protected val _deviceState = MutableStateFlow(DeviceState())
    open val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    /** Incoming MIDI events: Triple(statusByte, note, velocity). */
    data class MidiEvent(val status: Int, val note: Int, val velocity: Int, val channel: Int)

    private val _incomingMidi = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    val incomingMidi: SharedFlow<MidiEvent> = _incomingMidi.asSharedFlow()

    // ── Channel state (D-16) ──
    private val _channel = MutableStateFlow(0)
    /** Currently selected MIDI channel (0-15) as StateFlow for cross-screen sharing. */
    val channelFlow: StateFlow<Int> = _channel.asStateFlow()

    /** Currently selected MIDI channel (0-15). Backed by [channelFlow]. */
    val channel: Int get() = _channel.value

    // ── Scale state (D-17) ──
    private val _selectedScale = MutableStateFlow<Scale?>(null)
    /** Currently selected scale for scale-lock highlighting. Null = no scale (all pads normal). */
    val selectedScale: StateFlow<Scale?> = _selectedScale.asStateFlow()

    private val _selectedRootNote = MutableStateFlow("C")
    /** Currently selected root note for scale-lock. */
    val selectedRootNote: StateFlow<String> = _selectedRootNote.asStateFlow()

    fun setScale(scale: Scale?) { _selectedScale.value = scale }
    fun setRootNote(note: String) { _selectedRootNote.value = note }

    // ── SysEx accumulation buffer (D-09, D-10) ──
    private val sysExBuffer = java.io.ByteArrayOutputStream(512)
    private var inSysEx = false

    // ── Channel message partial-byte buffer ──
    private val channelBuffer = java.io.ByteArrayOutputStream(3)

    // ── SysEx response deferreds (D-12) ──
    private var pendingGreetDeferred: CompletableDeferred<Map<String, String>>? = null
    private var pendingMetadataDeferred: CompletableDeferred<Map<String, String>>? = null
    private var pendingFileListCountDeferred: CompletableDeferred<Int>? = null
    private var fileListEntryCount: Int = 0
    private var currentDeviceId: Int = 0
    @Volatile private var statsQueryInFlight = false

    // ── FILE_INIT session state (Task 3 — hardware-required handshake) ──
    // The device returns "can't list unless initialized" until a FILE_INIT (subcmd=1) is
    // sent. This is a one-time-per-connection handshake; the negotiated chunkSize is used
    // to bound response sizes. Reset to false on greet (new connection).
    @Volatile private var fileSessionInitialized = false
    private var deviceChunkSize: Int = 512
    private var pendingFileInitDeferred: CompletableDeferred<Int>? = null

    // ── Paged project transfer state (Phase 4 GATE) ──
    // A paged GET/PUT keeps its request registered across STATUS_SPECIFIC_SUCCESS_START
    // and resolves on STATUS_OK. Unlike a single CompletableDeferred, intermediate DATA
    // responses keep arriving, so pages flow through a Channel (RESEARCH Pitfall 3).
    @Volatile private var transferInFlight = false
    private var pendingGetInitDeferred: CompletableDeferred<SysExProtocol.GetInitResponse>? = null
    private var pendingGetPages: Channel<SysExProtocol.GetDataResponse>? = null
    // Hardware-verified (2026-06-24): device returns "unexpected page" if DATA frames are sent
    // before the INIT response arrives. pendingPutInitDeferred is completed by the dispatcher
    // on the first PUT response; putSampleFile awaits it before sending any DATA pages.
    private var pendingPutInitDeferred: CompletableDeferred<Boolean>? = null
    private var pendingPutAckDeferred: CompletableDeferred<Boolean>? = null
    // Hardware-proven (2026-06-24): the device echoes the request reqId in each response.
    // awaitedFileReqId is set immediately before EVERY file-op send (INIT, LIST, METADATA,
    // INFO, PUT INIT, PUT DATA, GET INIT, GET DATA) and cleared once the matching response
    // is consumed. The dispatcher ignores any FILE response whose reqId doesn't match —
    // this is the primary defence against duplicate responses poisoning the wrong deferred
    // (hardware-confirmed root cause 2026-06-24: duplicate FILE_INIT response completing
    // the LIST deferred → resolveNodeId returns null → upload aborts).
    @Volatile private var awaitedFileReqId: Int = -1
    // awaitedPutReqId is kept for PUT-specific per-page ack matching inside
    // dispatchPagedPutResponse (the PUT path checks both fields).
    @Volatile private var awaitedPutReqId: Int = -1

    // ── Project enumeration state (Phase 4 Wave 2) ──
    // FILE_LIST by node ID returns concatenated directory entries; the dispatcher hands the
    // accumulated body to a CompletableDeferred keyed by nodeListInFlight.
    private var pendingNodeListDeferred: CompletableDeferred<ByteArray>? = null
    private val nodeListBuffer = java.io.ByteArrayOutputStream(512)

    // ── Metadata JSON round-trip state (Step 1 — active-group sync) ──
    // The nodeId-form METADATA GET response streams pages of JSON fragments; we accumulate
    // them into a StringBuilder and complete the deferred on the terminator page.
    // METADATA SET posts a single frame and awaits an ack on the matching response.
    // FILE_INFO (getNode) completes a single-shot deferred with the parsed NodeInfo.
    //
    // Branch guard: metadataJsonInFlight is true while getMetadataJson/setMetadata owns the
    // METADATA dispatcher slot. When false, incoming METADATA responses fall through to the
    // legacy greet-style parse used by queryProjectsActiveNode (Phase-4 storage queries).
    @Volatile private var metadataJsonInFlight = false
    private var pendingMetadataJsonDeferred: CompletableDeferred<String>? = null
    private val metadataJsonBuffer = StringBuilder(256)
    private var metadataJsonExpectedPage = 0

    @Volatile private var metadataSetInFlight = false
    private var pendingMetadataSetAckDeferred: CompletableDeferred<Boolean>? = null

    private var pendingNodeInfoDeferred: CompletableDeferred<SysExProtocol.NodeInfo>? = null

    // ── File protocol flows (for BackupManager) ──
    data class FileListEntry(val path: String, val nodeId: Int)

    private val _fileListEntries = MutableSharedFlow<FileListEntry>(extraBufferCapacity = 128)
    val fileListEntries: SharedFlow<FileListEntry> = _fileListEntries.asSharedFlow()

    // Legacy single-chunk FILE_GET responses, keyed by the echoed request id so a consumer
    // can correlate each chunk to the GET it sent (responses may arrive out of order).
    private val _fileChunks = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 32)
    val fileChunks: SharedFlow<Pair<Int, ByteArray>> = _fileChunks.asSharedFlow()

    // ── Repository coroutine scope (for queryDeviceStats background launch) ──
    // Use Dispatchers.Default (not Main) to avoid requiring Android Looper in unit tests.
    // queryDeviceStats() is a suspend function — callers control dispatch context.
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(Dispatchers.Default + repositoryJob)

    private var isRefreshing = false

    init {
        midiManager.onDevicesChanged = { updateDeviceStateOnly() }
        midiManager.onMidiReceived = { _, data -> parseMidiInput(data) }
    }

    /** Updates state and re-establishes listeners on new devices. */
    private fun updateDeviceStateOnly() {
        val devices = midiManager.getUSBDevices()
        val wasConnected = _deviceState.value.connected
        val connected = devices.inputs.isNotEmpty() || devices.outputs.isNotEmpty()
        val outputPort = devices.outputs.firstOrNull()
        val permState = (midiManager as? MIDIManager)?.currentPermissionState
            ?: PermissionState.UNKNOWN
        _deviceState.value = _deviceState.value.copy(
            connected = connected,
            deviceName = outputPort?.name ?: "",
            outputPortId = outputPort?.id,
            inputPorts = devices.inputs.map { MidiPort(it.id, it.name) },
            outputPorts = devices.outputs.map { MidiPort(it.id, it.name) },
            permissionState = permState,
        )
        // Close stale listeners and re-establish on current ports
        midiManager.closeAllListeners()
        for (input in devices.inputs) {
            midiManager.startListening(input.id)
        }
        // Pre-warm send port so sequencer noteOn is immediate
        outputPort?.id?.let { midiManager.prewarmSendPort(it) }

        // Auto-trigger stats query on device connect (D-13)
        if (connected && !wasConnected) {
            repositoryScope.launch { queryDeviceStats() }
        }
    }

    /**
     * Byte-by-byte MIDI input processor with SysEx accumulation.
     *
     * - 0xF0 starts SysEx accumulation
     * - 0xF7 ends SysEx and dispatches complete message
     * - All other bytes during SysEx accumulation are buffered
     * - Non-SysEx bytes are passed to the channel message parser
     */
    private fun parseMidiInput(data: ByteArray) {
        for (b in data) {
            val byte = b.toInt() and 0xFF
            when {
                byte == 0xF0 -> {
                    sysExBuffer.reset()
                    sysExBuffer.write(b.toInt())
                    inSysEx = true
                }
                inSysEx && byte == 0xF7 -> {
                    sysExBuffer.write(b.toInt())
                    inSysEx = false
                    val complete = sysExBuffer.toByteArray()
                    sysExBuffer.reset()
                    dispatchSysEx(complete)
                }
                inSysEx -> sysExBuffer.write(b.toInt())
                else -> parseChannelMessageByte(b)
            }
        }
    }

    /**
     * Accumulate channel message bytes (status + data bytes) and dispatch complete messages.
     *
     * Status bytes (high bit set) reset the buffer. Channel messages are typically 2-3 bytes.
     */
    private fun parseChannelMessageByte(b: Byte) {
        val byte = b.toInt() and 0xFF
        if (byte and 0x80 != 0) {
            // New status byte — flush pending partial message and start fresh
            channelBuffer.reset()
            channelBuffer.write(b.toInt())
        } else {
            channelBuffer.write(b.toInt())
        }

        val bytes = channelBuffer.toByteArray()
        if (bytes.isEmpty()) return
        val status = bytes[0].toInt() and 0xFF
        val type = status and 0xF0

        // Dispatch complete 3-byte messages (Note On/Off, CC, Pitch Bend)
        // Dispatch complete 2-byte messages (PC, Channel Pressure)
        val expectedLen = when (type) {
            0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
            0xC0, 0xD0 -> 2
            else -> return
        }

        if (bytes.size >= expectedLen) {
            val ch = status and 0x0F
            val note = bytes.getOrNull(1)?.toInt()?.and(0x7F) ?: 0
            val velocity = bytes.getOrNull(2)?.toInt()?.and(0x7F) ?: 0
            Log.d("EP133APP", "MIDI IN: type=0x${type.toString(16)} ch=$ch note=$note vel=$velocity")
            if (type == 0x90 || type == 0x80 || type == 0xC0) {
                _incomingMidi.tryEmit(MidiEvent(type, note, velocity, ch))
            }
            channelBuffer.reset()
        }
    }

    /**
     * Dispatch a complete SysEx message. Routes to response deferreds for [queryDeviceStats].
     */
    protected open fun dispatchSysEx(message: ByteArray) {
        if (message.size < 10) return
        val isTEManufacturer = message[1] == SysExProtocol.TE_ID_0 &&
            message[2] == SysExProtocol.TE_ID_1 &&
            message[3] == SysExProtocol.TE_ID_2
        if (!isTEManufacturer) {
            Log.d("EP133APP", "SysEx ignored (non-TE manufacturer): ${message.size} bytes")
            return
        }
        val command = message[8].toInt() and 0xFF
        val payload = if (message.size > 10) message.copyOfRange(9, message.size - 1) else ByteArray(0)
        Log.d("EP133APP", "TE SysEx received: cmd=$command payload=${payload.size} bytes")

        when (command) {
            SysExProtocol.CMD_GREET -> {
                // Task 2: adopt the device's real ID from the greet response (byte[4] of message).
                // The device reports 0x33; use whatever it sends so we echo it back in requests.
                val reportedDeviceId = message[4].toInt() and 0x7F
                if (reportedDeviceId != 0) {
                    currentDeviceId = reportedDeviceId
                    Log.d("EP133MIDI", "GREET: adopted deviceId=0x${reportedDeviceId.toString(16)}")
                }
                // Reset file session and structure caches on new greet (new connection).
                fileSessionInitialized = false
                groupsNodeCache.clear()
                groupNodeNameCache.clear()
                Log.d("EP133MIDI", "GREET: cleared structure caches (new connection)")

                val parsed = SysExProtocol.parseGreetResponse(payload)
                Log.d("EP133APP", "GREET response: $parsed")
                pendingGreetDeferred?.complete(parsed)
                pendingGreetDeferred = null
            }
            SysExProtocol.TE_SYSEX_FILE -> {
                // Task 4: hardware-verified (2026-06-23) — file responses arrive under command=5,
                // NOT command=127. Payload is already unpacked by parseMidiInput accumulation;
                // however the frame body is 7-bit packed so we must unpack it here.
                // We log the raw bytes first for HW capture greppability.
                val hexDump = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133MIDI", "MIDI META: inbound FILE response cmd=5 payload[${payload.size}] $hexDump")
                // Hardware-verified (2026-06-24) — responses carry a STATUS byte BEFORE the packed
                // body (reference data/index.js: `let o=9; if(response) status=s[o++]`). So payload[0]
                // is the status; the 7-bit-packed body starts at payload[1]. Unpacking from payload[0]
                // shifts every group boundary and corrupts the data.
                val fileStatus = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                val packedBody = if (payload.size > 1) payload.copyOfRange(1, payload.size) else ByteArray(0)
                val body = if (packedBody.isNotEmpty()) SysExProtocol.unpack7bit(packedBody) else ByteArray(0)
                Log.d("EP133MIDI", "MIDI META: FILE response status=$fileStatus body[${body.size}] ${body.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
                // NOTE: do NOT early-return when body is empty — a status-only empty-body response
                // is a valid PUT DATA page ack (hardware-confirmed 2026-06-25). Bailing here drops
                // the ack before reqId-matching, leaving the page-0 deferred pending until timeout.
                // Guard only when there is no status byte at all (payload itself is empty).
                if (payload.isEmpty()) return

                // Hardware-verified (2026-06-23): device FILE responses do NOT echo the subcommand.
                // FILE_INIT reply unpacked body starts 0x00 (not 0x01=INIT); FILE_LIST reply starts
                // with the page u16 (not 0x04=LIST). Routing by body[0] as a subcommand would never
                // match and leave pendingFileInitDeferred dangling — session never opens.
                //
                // Fix: requests are serialised (one file op in flight at a time, gated by
                // statsQueryInFlight / transferInFlight / ensureFileSessionInit). Determine the
                // in-flight op from state and pass the WHOLE unpacked body to the handler.
                val inFlightCmd = when {
                    pendingFileInitDeferred != null              -> SysExProtocol.TE_SYSEX_FILE_INIT
                    metadataJsonInFlight || metadataSetInFlight  -> SysExProtocol.TE_SYSEX_FILE_METADATA
                    pendingNodeListDeferred != null              -> SysExProtocol.TE_SYSEX_FILE_LIST
                    transferInFlight                             -> SysExProtocol.TE_SYSEX_FILE_PUT
                    pendingGetInitDeferred != null || pendingGetPages != null -> SysExProtocol.TE_SYSEX_FILE_GET
                    pendingNodeInfoDeferred != null              -> SysExProtocol.TE_SYSEX_FILE_INFO
                    else                                         -> -1
                }
                // Extract the response reqId from the raw frame.
                // Frame layout (no F0 — already stripped by parseMidiInput accumulation):
                //   message[0]=F0, [1..3]=TE ID, [4]=deviceId, [5]=0x40, [6]=flags|reqIdHigh, [7]=reqIdLow
                // reqId high bits = message[6] & 0x0F; low 7 bits = message[7] & 0x7F.
                // This is the SAME extraction used by the existing awaitedPutReqId path (commit ba62b55).
                val responseReqId = ((message[6].toInt() and 0x0F) shl 7) or (message[7].toInt() and 0x7F)
                val bodyHex = body.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133MIDI", "MIDI META: FILE cmd=5 inFlightCmd=$inFlightCmd responseReqId=$responseReqId awaitedFileReqId=$awaitedFileReqId body[${body.size}] $bodyHex")
                if (inFlightCmd == -1) {
                    Log.w("EP133MIDI", "MIDI META: unrouted file response — no op in flight, body[${body.size}] $bodyHex")
                    return
                }
                // Unified reqId guard: ignore any file response whose reqId doesn't match what we
                // are currently awaiting. This prevents a duplicate response (hardware sends each
                // response twice due to duplicate MidiReceiver connections) from completing the
                // NEXT op's deferred — the root cause of resolveNodeId("/sounds") returning null.
                // awaitedFileReqId is set before every send and cleared after the awaited response.
                if (awaitedFileReqId != -1 && responseReqId != awaitedFileReqId) {
                    Log.w(
                        "EP133MIDI",
                        "MIDI META: ignoring stale/dup file response reqId=$responseReqId awaiting=$awaitedFileReqId — dropped",
                    )
                    return
                }
                // Clear awaitedFileReqId now that we matched and are about to consume the response.
                // dispatchFileResponse completes the in-flight deferred; subsequent sends will set
                // awaitedFileReqId again before the next send.
                awaitedFileReqId = -1
                dispatchFileResponse(inFlightCmd, body, responseReqId, fileStatus)
            }
        }
    }

    /**
     * Dispatch a FILE response to the matching in-flight handler. [fileCmd] is the op type
     * determined by the caller from in-flight state (NOT from body[0] — device responses do not
     * echo the subcommand). [body] is the WHOLE unpacked response body (no bytes stripped).
     * [responseReqId] is the reqId extracted from the raw frame (frame[6] high bits + frame[7]).
     *
     * Hardware ground truth (2026-06-23):
     *   FILE_INIT reply unpacked: `00 0C 00 00 02 00` (starts 0x00, not 0x01=INIT)
     *   FILE_LIST reply unpacked: page-u16 then entries (starts with page word, not 0x04=LIST)
     */
    private fun dispatchFileResponse(fileCmd: Int, body: ByteArray, responseReqId: Int = -1, fileStatus: Int = -1) {
        // Rename: parameter was historically called "payload" but is now always the whole body.
        val payload = body
        when (fileCmd) {
            SysExProtocol.TE_SYSEX_FILE_INIT -> {
                // Whole body passed. Session opening is what matters; chunkSize parse is
                // approximate (parseFileInitResponse reads body[1..4], which with real HW
                // capture `00 0C 00 00 02 00` yields a large number — tolerated). Never throw.
                val hexDump = body.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133MIDI", "MIDI META: FILE_INIT response body[${body.size}] $hexDump")
                val chunkSize = try {
                    SysExProtocol.parseFileInitResponse(body)
                } catch (_: Exception) { 512 }
                Log.d("EP133MIDI", "MIDI META: FILE_INIT negotiated chunkSize=$chunkSize (approx)")
                deviceChunkSize = chunkSize
                fileSessionInitialized = true
                pendingFileInitDeferred?.complete(chunkSize)
                pendingFileInitDeferred = null
            }
            SysExProtocol.TE_SYSEX_FILE_METADATA -> {
                // Payload is already unpacked (dispatcher unpacks the full body before splitting).
                val hexDump = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133APP", "MIDI META: inbound METADATA payload[${payload.size}] $hexDump")

                when {
                    metadataJsonInFlight -> {
                        // nodeId-form METADATA GET: accumulate JSON pages until terminator.
                        // payload is already unpacked — use directly (no second unpack7bit call).
                        if (SysExProtocol.isMetadataTerminator(payload)) {
                            // Final page — may still carry a fragment before the NUL.
                            if (payload.size > 2) {
                                try {
                                    val (_, fragment) = SysExProtocol.parseMetadataPage(payload)
                                    metadataJsonBuffer.append(fragment)
                                } catch (_: Exception) { /* ignore malformed fragment on terminator */ }
                            }
                            val accumulated = metadataJsonBuffer.toString()
                            Log.d("EP133APP", "MIDI META: METADATA GET complete, accumulated JSON: $accumulated")
                            pendingMetadataJsonDeferred?.complete(accumulated)
                            pendingMetadataJsonDeferred = null
                        } else {
                            try {
                                val (page, fragment) = SysExProtocol.parseMetadataPage(payload)
                                if (page == metadataJsonExpectedPage) {
                                    metadataJsonBuffer.append(fragment)
                                    metadataJsonExpectedPage++
                                } else {
                                    Log.e("EP133APP", "MIDI META: unexpected metadata page $page, expected $metadataJsonExpectedPage")
                                    pendingMetadataJsonDeferred?.completeExceptionally(
                                        IllegalStateException("unexpected metadata page $page, expected $metadataJsonExpectedPage"),
                                    )
                                    pendingMetadataJsonDeferred = null
                                }
                            } catch (e: Exception) {
                                Log.e("EP133APP", "MIDI META: METADATA page parse failed", e)
                                pendingMetadataJsonDeferred?.completeExceptionally(e)
                                pendingMetadataJsonDeferred = null
                            }
                        }
                    }
                    metadataSetInFlight -> {
                        // nodeId-form METADATA SET ack: any response completes the round-trip.
                        Log.d("EP133APP", "MIDI META: METADATA SET ack received")
                        pendingMetadataSetAckDeferred?.complete(true)
                        pendingMetadataSetAckDeferred = null
                    }
                    else -> {
                        // Legacy path-form METADATA response (Phase-4 storage queries / queryProjectsActiveNode).
                        // payload is already unpacked — parse directly as ASCII key:value text.
                        val text = try {
                            String(payload, Charsets.US_ASCII).trim(' ')
                        } catch (_: Exception) { "" }
                        val parsed = text.split(";")
                            .filter { it.contains(":") }
                            .associate { entry ->
                                val idx = entry.indexOf(':')
                                entry.substring(0, idx) to entry.substring(idx + 1)
                            }
                        Log.d("EP133APP", "FILE_METADATA response: $parsed")
                        pendingMetadataDeferred?.complete(parsed)
                        pendingMetadataDeferred = null
                    }
                }
            }
            SysExProtocol.TE_SYSEX_FILE_LIST -> {
                // Hardware ground truth: FILE_LIST response body = [page u16 BE][entries...].
                // No status byte — the old body[0]-as-status routing was wrong.
                val nodeDeferred = pendingNodeListDeferred
                if (nodeDeferred != null) {
                    // Skip the leading page u16 (2 bytes); pass raw entry data to parseFileListEntries.
                    val entriesBody = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0)
                    nodeListBuffer.write(entriesBody)
                    nodeDeferred.complete(nodeListBuffer.toByteArray())
                    pendingNodeListDeferred = null
                    nodeListBuffer.reset()
                    return
                }

                // Legacy path-form FILE_LIST (Phase-4 queryDeviceStats /sounds listing).
                // Body format is unverified on HW now — keep existing behaviour to avoid regressing
                // stats path. Log the raw body for the next hardware capture session.
                val hexDump = body.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133APP", "FILE_LIST legacy path body[${body.size}] $hexDump")
                val status = body.getOrNull(0)?.toInt()?.and(0xFF) ?: return
                if (status == SysExProtocol.STATUS_OK || status == SysExProtocol.STATUS_SPECIFIC_SUCCESS_START) {
                    fileListEntryCount++
                    // Parse entry path from payload for BackupManager (path after status byte)
                    val entryPath = if (payload.size > 1) {
                        String(payload.copyOfRange(1, payload.size), Charsets.US_ASCII).trimEnd('\u0000')
                    } else ""
                    repositoryScope.launch {
                        _fileListEntries.emit(FileListEntry(entryPath, fileListEntryCount))
                    }
                }
                if (status == SysExProtocol.STATUS_OK) {
                    pendingFileListCountDeferred?.complete(fileListEntryCount)
                    pendingFileListCountDeferred = null
                    fileListEntryCount = 0
                }
            }
            SysExProtocol.TE_SYSEX_FILE_GET -> {
                if (transferInFlight) {
                    dispatchPagedGetResponse(payload)
                } else {
                    // Legacy Phase 2 single-chunk path — emit (echoed reqId, payload) so
                    // BackupManager can correlate the chunk to the FILE_GET it sent.
                    repositoryScope.launch {
                        _fileChunks.emit(responseReqId to payload)
                    }
                }
            }
            SysExProtocol.TE_SYSEX_FILE_PUT -> {
                if (transferInFlight) dispatchPagedPutResponse(payload, responseReqId, fileStatus)
            }
            SysExProtocol.TE_SYSEX_FILE_INFO -> {
                // payload is already unpacked — use directly.
                val hexDump = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                Log.d("EP133APP", "MIDI META: inbound FILE_INFO payload[${payload.size}] $hexDump")

                val deferred = pendingNodeInfoDeferred ?: return
                try {
                    val info = SysExProtocol.parseFileInfo(payload)
                    Log.d("EP133APP", "MIDI META: FILE_INFO nodeId=${info.nodeId} name='${info.name}' flags=${info.flags}")
                    deferred.complete(info)
                } catch (e: Exception) {
                    Log.e("EP133APP", "MIDI META: FILE_INFO parse failed", e)
                    deferred.completeExceptionally(e)
                }
                pendingNodeInfoDeferred = null
            }
        }
    }

    /**
     * Route a paged FILE_GET response. [payload] is the body after [FILE, GET] is stripped:
     * `[status][...INIT-or-DATA body]`. The INIT response resolves [pendingGetInitDeferred];
     * subsequent DATA responses stream through [pendingGetPages]. The request stays registered
     * while status >= STATUS_SPECIFIC_SUCCESS_START and completes (channel closes) on STATUS_OK.
     */
    private fun dispatchPagedGetResponse(payload: ByteArray) {
        val status = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return
        val body = if (payload.size > 1) payload.copyOfRange(1, payload.size) else ByteArray(0)

        val initDeferred = pendingGetInitDeferred
        if (initDeferred != null && !initDeferred.isCompleted) {
            // First response after GET_INIT carries fileSize/fileName.
            // body is already unpacked — use directly.
            try {
                initDeferred.complete(SysExProtocol.parseGetInitResponse(body))
            } catch (e: IllegalArgumentException) {
                Log.e("EP133APP", "GET INIT parse failed", e)
                initDeferred.completeExceptionally(e)
            }
            return
        }

        val pages = pendingGetPages ?: return
        if (status != SysExProtocol.STATUS_OK && status < SysExProtocol.STATUS_SPECIFIC_SUCCESS_START) {
            // Error status (< SUCCESS_START, non-OK) — abort the transfer.
            Log.e("EP133APP", "Paged GET aborted: status=$status")
            pages.close(IllegalStateException("device error status $status"))
            return
        }
        val data = try {
            // body is already unpacked — parse directly.
            SysExProtocol.parseGetDataResponse(body)
        } catch (e: IllegalArgumentException) {
            Log.e("EP133APP", "GET DATA parse failed", e)
            pages.close(e)
            return
        }
        pages.trySend(data)
        if (status == SysExProtocol.STATUS_OK) {
            // Terminal response — no more pages will follow.
            pages.close()
        }
    }

    /**
     * Route a paged FILE_PUT response.
     *
     * Hardware-proven (2026-06-24): the device echoes the request reqId in each response.
     * Per-page acks are matched by reqId — a mismatch means the response is stale or from
     * a different op (e.g. a FILE_INFO status=4 fired by an unrelated event). Mismatched
     * responses are logged and ignored so they cannot complete the wrong deferred.
     *
     * Protocol:
     *   1. INIT sent → device responds with STATUS_OK or STATUS_SPECIFIC_SUCCESS_START.
     *      This response completes [pendingPutInitDeferred] (true on success, false on error).
     *   2. DATA pages sent one at a time, each awaited individually via [pendingPutAckDeferred].
     *      The device responds with STATUS_OK or STATUS_SPECIFIC_SUCCESS_START per page;
     *      both are treated as success so the caller can send the next page.
     *   3. Final DATA (zero-length terminator) → device responds with STATUS_OK.
     *      This is also routed through [pendingPutAckDeferred] (same machinery).
     *
     * Routing: if pendingPutInitDeferred is non-null, it owns the first response. Once it is
     * cleared, each per-page deferred is freshly set by the caller before sending and consumed
     * here (completed true on success, false/exceptionally on error).
     *
     * @param payload      Body of the PUT response (bytes AFTER the status — may be empty for a
     *                     STATUS-ONLY ack such as a PUT DATA page ack from hardware).
     * @param responseReqId reqId decoded from the raw frame by the caller (-1 if not decoded).
     * @param fileStatus   Status byte already extracted by dispatchSysEx before unpacking the
     *                     body. This is the authoritative status value — do NOT re-read from
     *                     payload[0], which would be wrong when the body is empty.
     */
    private fun dispatchPagedPutResponse(payload: ByteArray, responseReqId: Int = -1, fileStatus: Int = -1) {
        // Use the pre-extracted fileStatus. If caller passed -1 (legacy / direct call),
        // fall back to payload[0] for backwards-compatibility only.
        val status = if (fileStatus != -1) fileStatus else payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return

        // First response after PUT INIT: complete the init deferred.
        // The INIT reqId is tracked by putSampleFile; we accept any response here because
        // the INIT is the very first frame and no prior PUT responses can race against it.
        val initAck = pendingPutInitDeferred
        if (initAck != null) {
            val ok = status == SysExProtocol.STATUS_OK ||
                status >= SysExProtocol.STATUS_SPECIFIC_SUCCESS_START
            Log.d("EP133MIDI", "MIDI META: PUT INIT ack responseReqId=$responseReqId status=$status ok=$ok")
            if (!initAck.isCompleted) {
                if (ok) initAck.complete(true) else initAck.completeExceptionally(
                    IllegalStateException("PUT INIT error status $status"),
                )
            }
            pendingPutInitDeferred = null
            return
        }

        // Per-page responses: validate reqId before completing the deferred.
        // awaitedPutReqId is set by putSampleFile immediately before each sendMidi call.
        val ack = pendingPutAckDeferred ?: return
        if (responseReqId != -1 && awaitedPutReqId != -1 && responseReqId != awaitedPutReqId) {
            Log.w(
                "EP133MIDI",
                "MIDI META: mismatched file response reqId=$responseReqId awaiting=$awaitedPutReqId — ignoring",
            )
            return
        }
        when {
            status == SysExProtocol.STATUS_OK ||
            status >= SysExProtocol.STATUS_SPECIFIC_SUCCESS_START ->
                if (!ack.isCompleted) ack.complete(true)
            else ->
                if (!ack.isCompleted) ack.complete(false)
        }
    }

    /** Refresh device state from MIDIManager. */
    fun refreshDeviceState() {
        if (isRefreshing) return
        isRefreshing = true
        try {
            midiManager.refreshDevices()
        } finally {
            isRefreshing = false
        }
        val devices = midiManager.getUSBDevices()
        val connected = devices.inputs.isNotEmpty() || devices.outputs.isNotEmpty()
        val outputPort = devices.outputs.firstOrNull()
        val permState = (midiManager as? MIDIManager)?.currentPermissionState
            ?: PermissionState.UNKNOWN
        _deviceState.value = _deviceState.value.copy(
            connected = connected,
            deviceName = outputPort?.name ?: "",
            outputPortId = outputPort?.id,
            inputPorts = devices.inputs.map { MidiPort(it.id, it.name) },
            outputPorts = devices.outputs.map { MidiPort(it.id, it.name) },
            permissionState = permState,
        )
    }

    /**
     * Query real device stats from the EP-133:
     * - GREET → firmwareVersion
     * - FILE_METADATA on /sounds → storageUsedBytes, storageTotalBytes
     * - FILE_LIST on /sounds → sampleCount
     *
     * Returns true if GREET succeeded; false on timeout or no output port.
     */
    suspend fun queryDeviceStats(): Boolean {
        val portId = _deviceState.value.outputPortId ?: return false
        // Guard against overlapping queries (e.g. rapid disconnect/reconnect). Two concurrent
        // runs would race on the shared pending-deferred fields and could call complete() twice
        // on the same CompletableDeferred — an IllegalStateException on the MIDI dispatch path.
        if (statsQueryInFlight) return false
        return fileOpMutex.withLock {
            // Re-check after acquiring the lock — another call may have started while we waited.
            if (statsQueryInFlight) return@withLock false
            statsQueryInFlight = true
            try {
                queryDeviceStatsInner(portId)
            } finally {
                statsQueryInFlight = false
            }
        }
    }

    private suspend fun queryDeviceStatsInner(portId: String): Boolean {
        // Step 1: GREET (firmware + device identity). reqId=1 is the conventional greet ID;
        // it is not a FILE op so it does not draw from nextFileReqId() and there is no
        // awaitedFileReqId set for it (greet uses CMD_GREET, not TE_SYSEX_FILE).
        val greetDeferred = CompletableDeferred<Map<String, String>>()
        pendingGreetDeferred = greetDeferred
        val greetFrame = SysExProtocol.buildGreetFrame(currentDeviceId, requestId = 1)
        midiManager.sendMidi(portId, greetFrame)
        val greetResult = withTimeoutOrNull(5_000) { greetDeferred.await() }
            ?: run { pendingGreetDeferred = null; return false }
        val firmware = greetResult["sw_version"] ?: ""
        _deviceState.value = _deviceState.value.copy(firmwareVersion = firmware)

        // Step 2: FILE_METADATA on /sounds (storage bytes). Use nextFileReqId() so this
        // path-form METADATA GET never aliases with concurrent nodeId-form ops.
        val metaReqId = nextFileReqId()
        val metaDeferred = CompletableDeferred<Map<String, String>>()
        pendingMetadataDeferred = metaDeferred
        val metaFrame = SysExProtocol.buildFileMetadataFrame(currentDeviceId, "/sounds", requestId = metaReqId)
        midiManager.sendMidi(portId, metaFrame)
        val metaResult = withTimeoutOrNull(3_000) { metaDeferred.await() }
        if (metaResult != null) {
            val used = metaResult["used_space_in_bytes"]?.toLongOrNull()
            val total = metaResult["max_capacity"]?.toLongOrNull()
            _deviceState.value = _deviceState.value.copy(
                storageUsedBytes = used,
                storageTotalBytes = total,
            )
        }

        // Step 3: FILE_LIST on /sounds (count samples). Use nextFileReqId().
        // Reset the running count first: if a prior run timed out before STATUS_OK, the
        // count was never cleared and would inflate this run's sampleCount.
        val listReqId = nextFileReqId()
        fileListEntryCount = 0
        val fileListDeferred = CompletableDeferred<Int>()
        pendingFileListCountDeferred = fileListDeferred
        val listFrame = SysExProtocol.buildFileListFrame(currentDeviceId, "/sounds", requestId = listReqId)
        midiManager.sendMidi(portId, listFrame)
        val sampleCount = withTimeoutOrNull(5_000) { fileListDeferred.await() } ?: 0
        _deviceState.value = _deviceState.value.copy(sampleCount = sampleCount)

        return true
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
        val portId = _deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")
        return fileOpMutex.withLock {
            if (transferInFlight) throw IllegalStateException("transfer already in flight")
            transferInFlight = true
            val initDeferred = CompletableDeferred<SysExProtocol.GetInitResponse>()
            val pages = Channel<SysExProtocol.GetDataResponse>(Channel.UNLIMITED)
            pendingGetInitDeferred = initDeferred
            pendingGetPages = pages
            try {
                val getInitReqId = nextFileReqId()
                val initFrame = SysExProtocol.buildFileGetInitFrame(currentDeviceId, nodeId, requestId = getInitReqId)
                awaitedFileReqId = getInitReqId
                midiManager.sendMidi(portId, initFrame)
                val init = withTimeoutOrNull(GET_INIT_TIMEOUT_MS) { initDeferred.await() }
                    ?: throw IllegalStateException("GET INIT timed out")
                Log.d("EP133APP", "Project GET init: ${init.fileName} ${init.fileSize} bytes")

                val out = java.io.ByteArrayOutputStream(init.fileSize.coerceAtLeast(0))
                val cap = init.fileSize + SysExProtocol.MAX_PAGE_BYTES
                var page = 0
                while (out.size() < init.fileSize) {
                    val getDataReqId = nextFileReqId()
                    val dataFrame = SysExProtocol.buildFileGetDataFrame(currentDeviceId, page, requestId = getDataReqId)
                    awaitedFileReqId = getDataReqId
                    midiManager.sendMidi(portId, dataFrame)
                    val resp = withTimeoutOrNull(GET_PAGE_TIMEOUT_MS) { pages.receive() }
                        ?: throw IllegalStateException("GET DATA page $page timed out")
                    check(resp.page == page) { "unexpected page ${resp.page}, expected $page" }
                    if (resp.data.isEmpty()) break
                    check(out.size() + resp.data.size <= cap) {
                        "GET overflow: ${out.size() + resp.data.size} bytes exceeds cap $cap"
                    }
                    out.write(resp.data)
                    page = resp.nextPage
                }
                out.toByteArray()
            } catch (e: CancellationException) {
                throw e
            } finally {
                pages.close()
                pendingGetInitDeferred = null
                pendingGetPages = null
                awaitedFileReqId = -1
                transferInFlight = false
            }
        }
    }

    /**
     * Upload a project archive via the device's two-phase INIT/DATA protocol (restore).
     *
     * Sends PUT_INIT announcing the byte count, then PUT_DATA(page, chunk) frames carrying
     * the archive in `MAX_PAGE_BYTES`-sized slices, awaiting a STATUS_OK acknowledgement.
     * Archive bytes are 7-bit packed by the frame builder.
     */
    suspend fun putProjectArchive(slotNodeId: Int, tarBytes: ByteArray): Boolean {
        val portId = _deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")
        return fileOpMutex.withLock {
            if (transferInFlight) throw IllegalStateException("transfer already in flight")
            transferInFlight = true
            val ack = CompletableDeferred<Boolean>()
            pendingPutAckDeferred = ack
            try {
                val initFrame = SysExProtocol.buildFilePutInitFrame(
                    currentDeviceId, slotNodeId, tarBytes.size, requestId = 20,
                )
                midiManager.sendMidi(portId, initFrame)

                var page = 0
                var offset = 0
                while (offset < tarBytes.size) {
                    val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, tarBytes.size)
                    val chunk = tarBytes.copyOfRange(offset, end)
                    val dataFrame = SysExProtocol.buildFilePutDataFrame(currentDeviceId, page, chunk, requestId = 21)
                    midiManager.sendMidi(portId, dataFrame)
                    offset = end
                    page = (page + 1) and 0xFFFF
                }
                withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { ack.await() } ?: false
            } catch (e: CancellationException) {
                throw e
            } finally {
                pendingPutAckDeferred = null
                transferInFlight = false
            }
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
     *  6. Await STATUS_OK via [pendingPutAckDeferred] / [dispatchPagedPutResponse] (same
     *     machinery as [putProjectArchive]).
     *
     * @param name      Sanitized basename + ".wav" (caller must ensure no path separators).
     * @param pcmBytes  Raw interleaved s16 LE PCM — NO RIFF/WAV header. Must be > 0 bytes.
     * @param channels  Number of audio channels (1 = mono, 2 = stereo).
     * @param sampleRate Sample rate in Hz (always 46875 for device-format audio).
     * @return          true if the device acknowledged STATUS_OK; false on timeout or error.
     * @throws IllegalStateException if no output port is connected or a transfer is in flight.
     * @throws CancellationException if the coroutine is cancelled.
     */
    open suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int = 1,
        sampleRate: Int = 46875,
    ): Boolean {
        val portId = _deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")

        return fileOpMutex.withLock {
        // Ensure the FILE_INIT handshake (once per connection) before resolving any node IDs.
        // Uses the NoLock core — we already hold fileOpMutex.
        ensureFileSessionInitNoLock()

        // Resolve /sounds inside the lock so its FILE_LIST round-trips don't interleave with
        // concurrent ops.  Delegates to resolveSoundsNodeId() so subclasses can override for tests.
        val parent = resolveSoundsNodeId()
        if (parent == null) {
            Log.e("EP133APP", "putSampleFile: cannot resolve /sounds node — aborting upload of $name")
            return@withLock false
        }

        // Compute the raw chunk size from the negotiated chunkSize. The device rejected 4096-byte
        // chunks (STATUS=1 "unexpected page") because a 4096-byte DATA payload 7-bit-packs to
        // ~4.7 KB — well over the device's per-message budget (512 bytes negotiated at INIT).
        // Hardware-confirmed: ~420-byte raw chunks (within the 512-byte budget) get STATUS_OK.
        val rawChunkSize = computeSampleChunkSize(deviceChunkSize)
        val pageCount = if (pcmBytes.isEmpty()) 0 else (pcmBytes.size + rawChunkSize - 1) / rawChunkSize
        Log.d("EP133APP", "putSampleFile: chunkSize=$rawChunkSize pages=$pageCount for $name (${pcmBytes.size} bytes, ch=$channels sr=$sampleRate, deviceChunkSize=$deviceChunkSize)")

        // Build metadata JSON matching data/index.js prepareTeenageMeta key names.
        val metadataJson = """{"channels":$channels,"samplerate":$sampleRate}"""

        if (transferInFlight) throw IllegalStateException("transfer already in flight")
        transferInFlight = true
        // Hardware-verified (2026-06-24): device sends "unexpected page" if DATA frames arrive
        // before the INIT response. Create the init deferred BEFORE sending the INIT frame so
        // the dispatcher can complete it the moment the response arrives.
        val initAck = CompletableDeferred<Boolean>()
        pendingPutInitDeferred = initAck
        // Transfer-local reqId counter. INIT uses reqId 30; each DATA page and the terminator
        // get the next value. reqId is 14-bit (encoded as (reqId >> 7) in frame[6] low nibble
        // and (reqId & 0x7F) in frame[7]; buildFrame encodes this correctly).
        var nextReqId = PUT_INIT_REQUEST_ID  // 30
        // Flag: set to true once the INIT ack is received. Used by the failure path to decide
        // whether to send a force-close terminator (only needed after INIT is acked, so the
        // device has an open transfer it must close to accept new PUTs).
        var initAcked = false
        return try {
            // INIT: announce parent dir, fileId=0 (new file), size, filename, and metadata.
            val initFrame = SysExProtocol.buildFileCreatePutInitFrame(
                currentDeviceId,
                parentNodeId = parent,
                fileSize = pcmBytes.size,
                filename = name,
                requestId = nextReqId,
                metadataJson = metadataJson,
            )
            Log.d("EP133MIDI", "MIDI META: outbound PUT INIT reqId=$nextReqId name=$name size=${pcmBytes.size}")
            awaitedFileReqId = nextReqId
            midiManager.sendMidi(portId, initFrame)
            nextReqId = (nextReqId + 1) and 0x3FFF

            // Await the device's INIT ack before sending any DATA pages.
            // Reference tool (data/index.js): awaits the PUT INIT response before looping DATA.
            val initOk = withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { initAck.await() } ?: false
            if (!initOk) {
                Log.e("EP133APP", "putSampleFile: PUT INIT ack failed or timed out — aborting $name")
                return false
            }
            initAcked = true
            Log.d("EP133APP", "putSampleFile: PUT INIT ack OK — sending DATA pages for $name")

            // DATA pages: slice pcmBytes into rawChunkSize chunks, awaiting each page's ack
            // before sending the next. The device rejects out-of-order or rapid-fire pages
            // (USB-MIDI has no flow control — the reference tool sends serially with await).
            var page = 0
            var offset = 0
            while (offset < pcmBytes.size) {
                val end = minOf(offset + rawChunkSize, pcmBytes.size)
                val chunk = pcmBytes.copyOfRange(offset, end)
                val pageAck = CompletableDeferred<Boolean>()
                pendingPutAckDeferred = pageAck
                awaitedPutReqId = nextReqId
                awaitedFileReqId = nextReqId
                val dataFrame = SysExProtocol.buildFilePutDataFrame(currentDeviceId, page, chunk, requestId = nextReqId)
                Log.d("EP133MIDI", "MIDI META: outbound PUT DATA page=$page reqId=$nextReqId chunkSize=${chunk.size}")
                midiManager.sendMidi(portId, dataFrame)
                nextReqId = (nextReqId + 1) and 0x3FFF
                val pageOk = withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { pageAck.await() } ?: false
                if (!pageOk) {
                    Log.e("EP133APP", "putSampleFile: DATA page $page ack failed or timed out — aborting $name")
                    forceCloseTransfer(portId, page + 1, nextReqId)
                    nextReqId = (nextReqId + 1) and 0x3FFF
                    return false
                }
                offset = end
                page = (page + 1) and 0xFFFF
            }

            // Zero-length DATA terminator (required by reference tool). Await its final ack.
            val termAck = CompletableDeferred<Boolean>()
            pendingPutAckDeferred = termAck
            awaitedPutReqId = nextReqId
            awaitedFileReqId = nextReqId
            val terminatorFrame = SysExProtocol.buildFilePutDataFrame(currentDeviceId, page, ByteArray(0), requestId = nextReqId)
            Log.d("EP133MIDI", "MIDI META: outbound PUT terminator page=$page reqId=$nextReqId")
            midiManager.sendMidi(portId, terminatorFrame)
            nextReqId = (nextReqId + 1) and 0x3FFF
            withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { termAck.await() } ?: false
        } catch (e: CancellationException) {
            if (initAcked) {
                // Best-effort close so the device doesn't stay wedged.
                forceCloseTransfer(portId, 0, nextReqId)
            }
            throw e
        } finally {
            pendingPutInitDeferred = null
            pendingPutAckDeferred = null
            awaitedPutReqId = -1
            awaitedFileReqId = -1
            transferInFlight = false
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
            val termAck = CompletableDeferred<Boolean>()
            pendingPutAckDeferred = termAck
            awaitedPutReqId = reqId
            awaitedFileReqId = reqId
            val frame = SysExProtocol.buildFilePutDataFrame(currentDeviceId, terminatorPage, ByteArray(0), requestId = reqId)
            Log.w("EP133MIDI", "MIDI META: force-closed incomplete transfer page=$terminatorPage reqId=$reqId")
            midiManager.sendMidi(portId, frame)
            withTimeoutOrNull(FORCE_CLOSE_TIMEOUT_MS) { termAck.await() }
        } catch (_: Exception) {
            // Best-effort — ignore all errors, just clean up state
        } finally {
            pendingPutAckDeferred = null
            awaitedPutReqId = -1
            awaitedFileReqId = -1
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
     * Resets on greet (new connection via [dispatchSysEx] CMD_GREET branch).
     *
     * This public entry-point acquires [fileOpMutex].  Code that already holds the mutex
     * (e.g. [putSampleFile], [getActiveGroupIndex]) must call [ensureFileSessionInitNoLock]
     * directly to avoid a re-entrancy deadlock.
     *
     * @return true if the session is initialized (immediately or after the handshake),
     *         false if no port is connected or the INIT timed out.
     */
    suspend fun ensureFileSessionInit(): Boolean = fileOpMutex.withLock { ensureFileSessionInitNoLock() }

    /**
     * NoLock core for [ensureFileSessionInit].
     *
     * MUST only be called from within a [fileOpMutex] locked context — calling this without
     * holding the mutex can corrupt the shared [pendingFileInitDeferred] / [awaitedFileReqId]
     * state if another file op is concurrently in flight.
     */
    private suspend fun ensureFileSessionInitNoLock(): Boolean {
        if (fileSessionInitialized) return true
        val portId = _deviceState.value.outputPortId ?: return false
        val deferred = CompletableDeferred<Int>()
        pendingFileInitDeferred = deferred
        val initReqId = nextFileReqId()
        val frame = SysExProtocol.buildFileInitFrame(currentDeviceId, requestId = initReqId)
        val hexDump = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        Log.d("EP133MIDI", "MIDI META: outbound FILE_INIT frame[${frame.size}] reqId=$initReqId $hexDump")
        awaitedFileReqId = initReqId
        midiManager.sendMidi(portId, frame)
        val chunkSize = withTimeoutOrNull(FILE_INIT_TIMEOUT_MS) { deferred.await() }
        return if (chunkSize != null) {
            Log.d("EP133MIDI", "FILE_INIT: session initialized, chunkSize=$chunkSize")
            // awaitedFileReqId already cleared by dispatcher on match.
            true
        } else {
            Log.e("EP133MIDI", "FILE_INIT: timed out — proceeding anyway (hardware may not require it)")
            pendingFileInitDeferred = null
            if (awaitedFileReqId == initReqId) awaitedFileReqId = -1
            // Best-effort: if the device didn't respond, mark as initialized so we don't loop.
            fileSessionInitialized = true
            false
        }
    }

    // ── Project slot enumeration (Phase 4 Wave 2, PROJ-01) ──

    /** A single EP-133 project slot (one of /projects/P00 .. /projects/P08). */
    data class ProjectSlot(
        val nodeId: Int,
        val name: String,
        val sizeBytes: Long,
        val isActive: Boolean,
    )

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
    private suspend fun listNodeBody(nodeId: Int, requestId: Int): ByteArray? {
        val portId = _deviceState.value.outputPortId ?: return null
        val deferred = CompletableDeferred<ByteArray>()
        pendingNodeListDeferred = deferred
        nodeListBuffer.reset()
        return try {
            val frame = SysExProtocol.buildFileListByNodeFrame(currentDeviceId, nodeId, requestId = requestId)
            awaitedFileReqId = requestId
            midiManager.sendMidi(portId, frame)
            withTimeoutOrNull(FILE_LIST_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EP133APP", "node FILE_LIST failed for node $nodeId", e)
            null
        } finally {
            if (pendingNodeListDeferred === deferred) {
                pendingNodeListDeferred = null
                nodeListBuffer.reset()
            }
            // Clear the guard regardless: on match the dispatcher already cleared it;
            // on timeout it is still set to the stale reqId and must be unblocked.
            if (awaitedFileReqId == requestId) awaitedFileReqId = -1
        }
    }

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
     * and maps each child entry to a [ProjectSlot] (marking the active slot). Returns an empty
     * list if no device is connected or the device does not respond.
     */
    open suspend fun listProjects(): List<ProjectSlot> {
        if (_deviceState.value.outputPortId == null) return emptyList()
        return fileOpMutex.withLock {
            // Ensure file session then resolve — both NoLock because we hold fileOpMutex.
            ensureFileSessionInitNoLock()
            val projectsNode = resolveNodeIdInternal("/projects") ?: return@withLock emptyList()

            // Active-slot pointer from /projects directory metadata (NoLock).
            val activeNode = queryProjectsActiveNodeNoLock()

            val body = listNodeBody(projectsNode, requestId = nextFileReqId()) ?: return@withLock emptyList()

            SysExProtocol.parseFileListEntries(body).map { entry ->
                ProjectSlot(
                    nodeId = entry.nodeId,
                    name = entry.name,
                    sizeBytes = entry.sizeBytes,
                    isActive = activeNode != null && entry.nodeId == activeNode,
                )
            }
        }
    }

    /**
     * Read the /projects directory metadata "active" pointer (the currently-loaded slot).
     *
     * NoLock: must only be called from within a [fileOpMutex] locked context.
     */
    private suspend fun queryProjectsActiveNodeNoLock(): Int? {
        val portId = _deviceState.value.outputPortId ?: return null
        return try {
            val deferred = CompletableDeferred<Map<String, String>>()
            pendingMetadataDeferred = deferred
            val frame = SysExProtocol.buildFileMetadataFrame(currentDeviceId, "/projects", requestId = nextFileReqId())
            midiManager.sendMidi(portId, frame)
            val meta = withTimeoutOrNull(FILE_LIST_TIMEOUT_MS) { deferred.await() }
            meta?.get("active")?.toIntOrNull()
        } catch (e: CancellationException) {
            throw e
        } finally {
            pendingMetadataDeferred = null
        }
    }

    // ── Active-group sync: nodeId-form metadata round-trips (Step 1) ─────────────
    //
    // These implement the reference tool's group-select mechanism:
    //   getActiveGroupIndex: /projects→active→projName→/projects/<p>/groups→active→getNode→name
    //   setActiveGroup:      resolve group nodeId, SET groups-dir {active:<nodeId>}
    //
    // Both functions are serialised via fileOpMutex so they cannot interleave with
    // putSampleFile or the active-group poll.  All internal helpers (resolveNodeIdInternal,
    // getMetadataJson, getNodeInfo, setMetadata) are called without acquiring the mutex again.
    // The metadata GET/SET also use their own metadataJsonInFlight / metadataSetInFlight flags.

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
        val portId = _deviceState.value.outputPortId ?: return JSONObject()
        metadataJsonInFlight = true
        metadataJsonBuffer.clear()
        metadataJsonExpectedPage = 0
        val deferred = CompletableDeferred<String>()
        pendingMetadataJsonDeferred = deferred
        val metaReqId = nextFileReqId()
        return try {
            val frame = SysExProtocol.buildMetadataGetFrame(currentDeviceId, nodeId, page = 0, requestId = metaReqId)
            val hexDump = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            Log.d("EP133APP", "MIDI META: outbound METADATA GET nodeId=$nodeId reqId=$metaReqId frame[${frame.size}] $hexDump")
            awaitedFileReqId = metaReqId
            midiManager.sendMidi(portId, frame)
            val accumulated = withTimeoutOrNull(METADATA_TIMEOUT_MS) { deferred.await() }
                ?: return JSONObject()
            // Hardware-verified (2026-06-24): METADATA GET response body is
            //   `00 00 7B 22 61 63 74 69 76 65 22 3A 33 30 30 30 7D 00`
            // i.e. a 2-byte page prefix + {"active":3000} + trailing NUL. Strip the prefix
            // and NUL by scanning for the outermost '{' ... '}' span before parsing.
            val jsonSpan = run {
                val s = accumulated.indexOf('{')
                val e = accumulated.lastIndexOf('}')
                if (s >= 0 && e > s) accumulated.substring(s, e + 1) else accumulated
            }
            Log.d("EP133APP", "MIDI META: METADATA GET jsonSpan='$jsonSpan' for nodeId=$nodeId")
            // JSON-first parse on the extracted span; defensive greet fallback if that also fails.
            try {
                JSONObject(jsonSpan)
            } catch (_: Exception) {
                Log.d("EP133APP", "MIDI META: JSON parse failed, trying greet fallback for nodeId=$nodeId")
                val greetMap = SysExProtocol.parseGreetResponse(accumulated.toByteArray(Charsets.US_ASCII))
                val fallback = JSONObject()
                greetMap.forEach { (k, v) -> fallback.put(k, v) }
                fallback
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            metadataJsonInFlight = false
            if (pendingMetadataJsonDeferred === deferred) pendingMetadataJsonDeferred = null
            // Clear on timeout (dispatcher already cleared on match; on timeout it stays set).
            if (awaitedFileReqId == metaReqId) awaitedFileReqId = -1
        }
    }

    /**
     * Write [json] as the metadata for [nodeId] via a single METADATA SET frame (METADATA_SET = 1).
     *
     * Awaits the ack deferred completed by [dispatchFileResponse] on the matching response.
     *
     * @return true if the device responded (any non-error ack), false on timeout.
     */
    suspend fun setMetadata(nodeId: Int, json: String): Boolean {
        val portId = _deviceState.value.outputPortId ?: return false
        metadataSetInFlight = true
        val deferred = CompletableDeferred<Boolean>()
        pendingMetadataSetAckDeferred = deferred
        val setReqId = nextFileReqId()
        return try {
            val frame = SysExProtocol.buildMetadataSetFrame(currentDeviceId, nodeId, json, requestId = setReqId)
            val hexDump = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            Log.d("EP133APP", "MIDI META: outbound METADATA SET nodeId=$nodeId json=$json reqId=$setReqId frame[${frame.size}] $hexDump")
            awaitedFileReqId = setReqId
            midiManager.sendMidi(portId, frame)
            withTimeoutOrNull(METADATA_TIMEOUT_MS) { deferred.await() } ?: false
        } catch (e: CancellationException) {
            throw e
        } finally {
            metadataSetInFlight = false
            if (pendingMetadataSetAckDeferred === deferred) pendingMetadataSetAckDeferred = null
            if (awaitedFileReqId == setReqId) awaitedFileReqId = -1
        }
    }

    /**
     * Fetch the [SysExProtocol.NodeInfo] for a specific [nodeId] via FILE_INFO (op 11).
     *
     * @return Parsed [NodeInfo] or null on timeout / parse error.
     */
    suspend fun getNodeInfo(nodeId: Int): SysExProtocol.NodeInfo? {
        val portId = _deviceState.value.outputPortId ?: return null
        val deferred = CompletableDeferred<SysExProtocol.NodeInfo>()
        pendingNodeInfoDeferred = deferred
        val infoReqId = nextFileReqId()
        return try {
            val frame = SysExProtocol.buildFileInfoFrame(currentDeviceId, nodeId, requestId = infoReqId)
            val hexDump = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            Log.d("EP133APP", "MIDI META: outbound FILE_INFO nodeId=$nodeId reqId=$infoReqId frame[${frame.size}] $hexDump")
            awaitedFileReqId = infoReqId
            midiManager.sendMidi(portId, frame)
            withTimeoutOrNull(METADATA_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EP133APP", "MIDI META: getNodeInfo failed for nodeId=$nodeId", e)
            null
        } finally {
            if (pendingNodeInfoDeferred === deferred) pendingNodeInfoDeferred = null
            if (awaitedFileReqId == infoReqId) awaitedFileReqId = -1
        }
    }

    /**
     * Read the device's current active group and return its index (0=A, 1=B, 2=C, 3=D).
     *
     * Fast path (after first call): the heavy directory-walk is cached so subsequent poll ticks
     * issue only two METADATA GET round-trips (projects-node → active-project, groups-node →
     * active-group). The one-time resolution of each cache entry is logged verbosely.
     *
     * Guard: if a poll is already in flight ([activeGroupPollInFlight]), the new call returns
     * null immediately rather than queuing behind the mutex. This prevents 1.5 s poll ticks from
     * accumulating into a backlog when the device is slow.
     *
     * Cache invalidation: both caches are cleared on device greet (new connection).
     *
     * Full walk:
     *   1. METADATA GET on projectsNode → "active" = activeProjNodeId  (fast, cached node)
     *   2. If groupsNodeCache[activeProjNodeId] miss: resolveNodeIdInternal to find groups dir;
     *      log all children to confirm structure (HW-VERIFY-2).
     *   3. METADATA GET on groupsNode → "active" = activeGroupNodeId  (fast, cached node)
     *   4. If groupNodeNameCache[groupsNode] miss: list groupsNode children; log name→nodeId
     *      map (HW-VERIFY-2); cache it.
     *   5. Look up activeGroupNodeId in name map; find PadChannel index by name.
     *
     * @return Group index 0–3, or null if the device is disconnected / no active project.
     */
    suspend fun getActiveGroupIndex(): Int? {
        Log.d("EP133APP", "MIDI META: getActiveGroupIndex() called, outputPort=${_deviceState.value.outputPortId}")
        if (_deviceState.value.outputPortId == null) return null
        // Poll guard: return immediately if a poll is already running. This prevents a
        // 1.5 s tick from stacking up behind the mutex while a previous tick's FILE_LIST
        // is still in flight — the root cause of the poll-backlog timeout loop.
        if (!activeGroupPollInFlight.compareAndSet(false, true)) {
            Log.d("EP133APP", "MIDI META: getActiveGroupIndex — poll already in flight, skipping")
            return null
        }
        return try {
            fileOpMutex.withLock {
                ensureFileSessionInitNoLock()
                try {
                    getActiveGroupIndexNoLock()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("EP133APP", "MIDI META: getActiveGroupIndex failed", e)
                    null
                }
            }
        } finally {
            activeGroupPollInFlight.set(false)
        }
    }

    /**
     * NoLock body for [getActiveGroupIndex].
     * MUST only be called from within a [fileOpMutex] locked context.
     */
    private suspend fun getActiveGroupIndexNoLock(): Int? {
        // Step 1: resolve the /projects node once (cached implicitly by resolveNodeIdInternal
        // finding it by name from root). We need the node ID so we can METADATA GET it.
        // Use the fast METADATA GET path — no directory list needed here.
        val projectsNode = resolveNodeIdInternal("/projects") ?: return null

        // Step 2: METADATA GET on projects node → get active-project nodeId (fast, 1 round-trip).
        val activeProjNodeId = getMetadataJson(projectsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active project nodeId=$activeProjNodeId")

        // Step 3: resolve groups dir — cache hit avoids directory walk after first call.
        val groupsNode = groupsNodeCache.getOrPut(activeProjNodeId) {
            // One-time: need the project name to build the path, so do FILE_INFO on activeProjNodeId.
            val projName = getNodeInfo(activeProjNodeId)?.name ?: run {
                Log.w("EP133APP", "MIDI META: could not get project name for nodeId=$activeProjNodeId")
                return null
            }
            Log.d("EP133APP", "MIDI META: active project name='$projName' (one-time resolution)")
            val gn = resolveNodeIdInternal("/projects/$projName/groups") ?: run {
                Log.w("EP133APP", "MIDI META: /projects/$projName/groups not found — device may use different structure")
                return null
            }
            Log.d("EP133APP", "MIDI META: resolved groups dir nodeId=$gn for project='$projName' (cached)")
            gn
        }

        // Step 4: METADATA GET on groups dir → get active-group nodeId (fast, 1 round-trip).
        val activeGroupNodeId = getMetadataJson(groupsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active group nodeId=$activeGroupNodeId")

        // Step 5: resolve group name — cache hit avoids directory walk after first call.
        val nameMap = groupNodeNameCache.getOrPut(groupsNode) {
            // One-time: list groups dir children to build nodeId→name map.
            val body = listNodeBody(groupsNode, requestId = nextFileReqId()) ?: run {
                Log.w("EP133APP", "MIDI META: could not list groups dir nodeId=$groupsNode")
                return null
            }
            val entries = SysExProtocol.parseFileListEntries(body)
            // Log the actual structure — HW-VERIFY-2 confirms group names.
            Log.d("EP133APP", "MIDI META: groups dir children (one-time resolution): ${entries.map { "${it.nodeId}='${it.name}'" }}")
            if (entries.isEmpty()) {
                Log.w("EP133APP", "MIDI META: groups dir nodeId=$groupsNode has no children — device structure unexpected")
                return null
            }
            entries.associate { it.nodeId to it.name }
        }

        val groupName = nameMap[activeGroupNodeId] ?: run {
            Log.w("EP133APP", "MIDI META: active group nodeId=$activeGroupNodeId not in groups dir map=$nameMap")
            return null
        }
        Log.d("EP133APP", "MIDI META: active group name='$groupName' nodeId=$activeGroupNodeId")
        val idx = PadChannel.entries.indexOfFirst { it.name == groupName }
        return idx.takeIf { it >= 0 }
    }

    /**
     * Set the device's active group to [index] (0=A, 1=B, 2=C, 3=D).
     *
     * Resolves: /projects → active-project name → /projects/<name>/groups/<letter> (target nodeId)
     * → /projects/<name>/groups (groups-dir nodeId) → SET groups-dir `{active:<targetNodeId>}`.
     *
     * @return true if the SET ack was received, false on error or timeout.
     */
    suspend fun setActiveGroup(index: Int): Boolean {
        val channel = PadChannel.entries.getOrNull(index) ?: return false
        if (_deviceState.value.outputPortId == null) return false
        return fileOpMutex.withLock {
            try {
                val projectsNode = resolveNodeIdInternal("/projects") ?: return@withLock false
                val activeProjNodeId = getMetadataJson(projectsNode).optInt("active", -1)
                    .takeIf { it >= 0 } ?: return@withLock false
                val projName = getNodeInfo(activeProjNodeId)?.name ?: return@withLock false
                val groupNode = resolveNodeIdInternal("/projects/$projName/groups/${channel.name}") ?: return@withLock false
                val groupsNode = resolveNodeIdInternal("/projects/$projName/groups") ?: return@withLock false
                setMetadata(groupsNode, """{"active":$groupNode}""")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "MIDI META: setActiveGroup($index) failed", e)
                false
            }
        }
    }

    /**
     * Resolve the /sounds directory node ID inside a [fileOpMutex] locked context.
     *
     * Exists as a protected open method so test subclasses can stub the resolution without
     * overriding the entire locking machinery in [putSampleFile].  Production path delegates
     * to [resolveNodeIdInternal] (NoLock).
     *
     * MUST only be called from within a [fileOpMutex] locked context.
     */
    protected open suspend fun resolveSoundsNodeId(): Int? = resolveNodeIdInternal("/sounds")

    /**
     * NoLock core for [resolveNodeId].  Walks path segments from root (nodeId 0) using
     * [listNodeBody] (also NoLock) without acquiring [fileOpMutex].
     *
     * MUST only be called from within a [fileOpMutex] locked context.
     */
    private suspend fun resolveNodeIdInternal(path: String): Int? {
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

    fun setChannel(ch: Int) {
        _channel.value = ch.coerceIn(0, 15)
    }

    // ── MIDI message senders ──

    fun noteOn(note: Int, velocity: Int = 100, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: run {
            Log.w("EP133APP", "MIDI OUT: no output port! note=$note ch=$ch")
            return
        }
        Log.d("EP133APP", "MIDI OUT: noteOn note=$note vel=$velocity ch=$ch port=$portId")
        val status = 0x90 or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (note and 0x7F).toByte(),
            (velocity and 0x7F).toByte(),
        ))
    }

    fun noteOff(note: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = 0x80 or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (note and 0x7F).toByte(),
            0,
        ))
    }

    fun controlChange(control: Int, value: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = 0xB0 or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (control and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        ))
    }

    fun programChange(program: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = 0xC0 or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (program and 0x7F).toByte(),
        ))
    }

    fun allNotesOff(ch: Int = channel) {
        controlChange(123, 0, ch)
    }

    /**
     * Send raw MIDI bytes (system real-time messages: Start 0xFA, Stop 0xFC, Clock 0xF8).
     */
    fun sendRawBytes(bytes: ByteArray) {
        val portId = _deviceState.value.outputPortId ?: return
        midiManager.sendMidi(portId, bytes)
    }

    /**
     * Load a factory sound onto a pad via note-on (select pad) → Bank Select → Program Change.
     *
     * The EP-133 assigns sounds to the last-played pad, so we must send a note-on
     * first to select the target. All messages are sent as a single byte array to
     * guarantee ordering through the async port path.
     *
     * Sound numbers are 1-999 (EP-133 factory library).
     */
    fun loadSoundToPad(soundNumber: Int, padNote: Int, padChannel: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val index = (soundNumber - 1).coerceAtLeast(0)
        val bankMsb = index / 128
        val program = index % 128
        Log.d("EP133APP", "MIDI OUT: loadSound #$soundNumber → pad note=$padNote padCh=$padChannel bank=$bankMsb pc=$program ch=$ch")

        val noteOnStatus = (0x90 or (padChannel and 0x0F)).toByte()
        val noteOffStatus = (0x80 or (padChannel and 0x0F)).toByte()
        val ccStatus = (0xB0 or (ch and 0x0F)).toByte()
        val pcStatus = (0xC0 or (ch and 0x0F)).toByte()
        val padNoteByte = (padNote and 0x7F).toByte()

        midiManager.sendMidi(portId, byteArrayOf(
            noteOnStatus, padNoteByte, 100.toByte(),
            noteOffStatus, padNoteByte, 0,
            ccStatus, 0, (bankMsb and 0x7F).toByte(),
            ccStatus, 32, 0,
            pcStatus, (program and 0x7F).toByte(),
        ))
    }

    fun requestUSBPermissions() {
        midiManager.requestUSBPermissions()
    }

    /**
     * Cancel repository scope and close the MIDI manager.
     * Call from Activity.onDestroy() to prevent coroutine leaks.
     */
    fun close() {
        repositoryJob.cancel()
        midiManager.close()
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
        private const val FILE_INIT_TIMEOUT_MS    = 5_000L
        // putSampleFile uses a transfer-local counter starting here; each frame increments it.
        // This is the INIT reqId; DATA pages and terminator get 31, 32, ... (masked to 14-bit).
        // The global nextFileReqId() counter skips the range 30..99 so it never aliases these.
        internal const val PUT_INIT_REQUEST_ID    = 30
        // Short timeout for the best-effort force-close terminator.
        private const val FORCE_CLOSE_TIMEOUT_MS  = 2_000L

        // ── nextFileReqId() counter bounds ──────────────────────────────────────
        // 11-bit space: 1..2046. 0 and 2047 (0x7FF) skipped (reserved/invalid).
        // The range 30..99 is skipped (owned by putSampleFile's transfer-local counter).
        // The value 1 is skipped (conventional greet reqId — CMD_GREET, not TE_SYSEX_FILE,
        // but skip for clarity). Initial value starts at 100 so first call returns 100.
        internal const val FILE_REQ_ID_MIN     = 100
        internal const val FILE_REQ_ID_MAX     = 2046
        internal const val FILE_REQ_ID_INITIAL = 100
    }
}
