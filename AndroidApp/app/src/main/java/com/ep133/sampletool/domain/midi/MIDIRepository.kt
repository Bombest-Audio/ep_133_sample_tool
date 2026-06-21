package com.ep133.sampletool.domain.midi

import android.util.Log
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.MidiPort
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
import kotlinx.coroutines.withTimeoutOrNull

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

    // ── Paged project transfer state (Phase 4 GATE) ──
    // A paged GET/PUT keeps its request registered across STATUS_SPECIFIC_SUCCESS_START
    // and resolves on STATUS_OK. Unlike a single CompletableDeferred, intermediate DATA
    // responses keep arriving, so pages flow through a Channel (RESEARCH Pitfall 3).
    @Volatile private var transferInFlight = false
    private var pendingGetInitDeferred: CompletableDeferred<SysExProtocol.GetInitResponse>? = null
    private var pendingGetPages: Channel<SysExProtocol.GetDataResponse>? = null
    private var pendingPutAckDeferred: CompletableDeferred<Boolean>? = null

    // ── Project enumeration state (Phase 4 Wave 2) ──
    // FILE_LIST by node ID returns concatenated directory entries; the dispatcher hands the
    // accumulated body to a CompletableDeferred keyed by nodeListInFlight.
    private var pendingNodeListDeferred: CompletableDeferred<ByteArray>? = null
    private val nodeListBuffer = java.io.ByteArrayOutputStream(512)

    // ── File protocol flows (for BackupManager) ──
    data class FileListEntry(val path: String, val nodeId: Int)

    private val _fileListEntries = MutableSharedFlow<FileListEntry>(extraBufferCapacity = 128)
    val fileListEntries: SharedFlow<FileListEntry> = _fileListEntries.asSharedFlow()

    private val _fileChunks = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 32)
    val fileChunks: SharedFlow<Pair<String, ByteArray>> = _fileChunks.asSharedFlow()

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
                val parsed = SysExProtocol.parseGreetResponse(payload)
                Log.d("EP133APP", "GREET response: $parsed")
                pendingGreetDeferred?.complete(parsed)
                pendingGreetDeferred = null
            }
            SysExProtocol.CMD_PRODUCT_SPECIFIC -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == SysExProtocol.TE_SYSEX_FILE) {
                    val fileCmd = payload.getOrNull(1)?.toInt()?.and(0xFF) ?: return
                    val filePayload = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)
                    dispatchFileResponse(fileCmd, filePayload)
                }
            }
        }
    }

    private fun dispatchFileResponse(fileCmd: Int, payload: ByteArray) {
        when (fileCmd) {
            SysExProtocol.TE_SYSEX_FILE_METADATA -> {
                val parsed = SysExProtocol.parseGreetResponse(payload)  // same key:value format
                Log.d("EP133APP", "FILE_METADATA response: $parsed")
                pendingMetadataDeferred?.complete(parsed)
                pendingMetadataDeferred = null
            }
            SysExProtocol.TE_SYSEX_FILE_LIST -> {
                val status = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return

                // Node-ID listing path (Phase 4 enumeration): accumulate the unpacked entry
                // body across SUCCESS_START responses, resolve the deferred on STATUS_OK.
                val nodeDeferred = pendingNodeListDeferred
                if (nodeDeferred != null) {
                    val listBody = if (payload.size > 1) payload.copyOfRange(1, payload.size) else ByteArray(0)
                    if (status == SysExProtocol.STATUS_OK ||
                        status == SysExProtocol.STATUS_SPECIFIC_SUCCESS_START
                    ) {
                        nodeListBuffer.write(SysExProtocol.unpack7bit(listBody))
                    }
                    if (status == SysExProtocol.STATUS_OK) {
                        nodeDeferred.complete(nodeListBuffer.toByteArray())
                        pendingNodeListDeferred = null
                        nodeListBuffer.reset()
                    } else if (status != SysExProtocol.STATUS_SPECIFIC_SUCCESS_START) {
                        nodeDeferred.completeExceptionally(IllegalStateException("FILE_LIST error status $status"))
                        pendingNodeListDeferred = null
                        nodeListBuffer.reset()
                    }
                    return
                }

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
                    // Legacy Phase 2 single-chunk path — emit to fileChunks for BackupManager.
                    val path = ""  // path tracking handled by BackupManager
                    repositoryScope.launch {
                        _fileChunks.emit(path to payload)
                    }
                }
            }
            SysExProtocol.TE_SYSEX_FILE_PUT -> {
                if (transferInFlight) dispatchPagedPutResponse(payload)
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
            try {
                initDeferred.complete(SysExProtocol.parseGetInitResponse(SysExProtocol.unpack7bit(body)))
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
            SysExProtocol.parseGetDataResponse(SysExProtocol.unpack7bit(body))
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

    /** Route a paged FILE_PUT acknowledgement: STATUS_OK completes, an error status fails. */
    private fun dispatchPagedPutResponse(payload: ByteArray) {
        val status = payload.getOrNull(0)?.toInt()?.and(0xFF) ?: return
        val ack = pendingPutAckDeferred ?: return
        when {
            status == SysExProtocol.STATUS_OK -> if (!ack.isCompleted) ack.complete(true)
            status >= SysExProtocol.STATUS_SPECIFIC_SUCCESS_START -> { /* intermediate — keep pending */ }
            else -> if (!ack.isCompleted) ack.completeExceptionally(IllegalStateException("PUT error status $status"))
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
        statsQueryInFlight = true
        return try {
            queryDeviceStatsInner(portId)
        } finally {
            statsQueryInFlight = false
        }
    }

    private suspend fun queryDeviceStatsInner(portId: String): Boolean {
        // Step 1: GREET (firmware + device identity)
        val greetDeferred = CompletableDeferred<Map<String, String>>()
        pendingGreetDeferred = greetDeferred
        val greetFrame = SysExProtocol.buildGreetFrame(currentDeviceId, requestId = 1)
        midiManager.sendMidi(portId, greetFrame)
        val greetResult = withTimeoutOrNull(5_000) { greetDeferred.await() }
            ?: run { pendingGreetDeferred = null; return false }
        val firmware = greetResult["sw_version"] ?: ""
        _deviceState.value = _deviceState.value.copy(firmwareVersion = firmware)

        // Step 2: FILE_METADATA on /sounds (storage bytes)
        val metaDeferred = CompletableDeferred<Map<String, String>>()
        pendingMetadataDeferred = metaDeferred
        val metaFrame = SysExProtocol.buildFileMetadataFrame(currentDeviceId, "/sounds", requestId = 2)
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

        // Step 3: FILE_LIST on /sounds (count samples).
        // Reset the running count first: if a prior run timed out before STATUS_OK, the
        // count was never cleared and would inflate this run's sampleCount.
        fileListEntryCount = 0
        val fileListDeferred = CompletableDeferred<Int>()
        pendingFileListCountDeferred = fileListDeferred
        val listFrame = SysExProtocol.buildFileListFrame(currentDeviceId, "/sounds", requestId = 3)
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
        if (transferInFlight) throw IllegalStateException("transfer already in flight")
        transferInFlight = true
        val initDeferred = CompletableDeferred<SysExProtocol.GetInitResponse>()
        val pages = Channel<SysExProtocol.GetDataResponse>(Channel.UNLIMITED)
        pendingGetInitDeferred = initDeferred
        pendingGetPages = pages
        return try {
            val initFrame = SysExProtocol.buildFileGetInitFrame(currentDeviceId, nodeId, requestId = 10)
            midiManager.sendMidi(portId, initFrame)
            val init = withTimeoutOrNull(GET_INIT_TIMEOUT_MS) { initDeferred.await() }
                ?: throw IllegalStateException("GET INIT timed out")
            Log.d("EP133APP", "Project GET init: ${init.fileName} ${init.fileSize} bytes")

            val out = java.io.ByteArrayOutputStream(init.fileSize.coerceAtLeast(0))
            val cap = init.fileSize + SysExProtocol.MAX_PAGE_BYTES
            var page = 0
            while (out.size() < init.fileSize) {
                val dataFrame = SysExProtocol.buildFileGetDataFrame(currentDeviceId, page, requestId = 11)
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
            transferInFlight = false
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
        if (transferInFlight) throw IllegalStateException("transfer already in flight")
        transferInFlight = true
        val ack = CompletableDeferred<Boolean>()
        pendingPutAckDeferred = ack
        return try {
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

    // ── Sample file upload to /sounds (Phase 5 Wave 2, SAMPLE-03) ──

    /**
     * Upload a WAV sample to /sounds/<name> via path-string framing proved by BackupManager.restore.
     *
     * Each frame is built with [SysExProtocol.buildFilePutFrame], carrying the full
     * "/sounds/<name>" path string on every chunk (Landmine 4 default, Codex fix #1).
     * Large WAVs still page: wavBytes is sliced into [SysExProtocol.MAX_PAGE_BYTES]-sized
     * chunks, one [buildFilePutFrame] call per chunk.
     *
     * The ack flow is unchanged from [putProjectArchive]: [pendingPutAckDeferred] is
     * registered before the first frame, and [withTimeoutOrNull] resolves on STATUS_OK.
     * [transferInFlight] guards against concurrent transfers. The dispatcher routes
     * TE_SYSEX_FILE_PUT responses to [dispatchPagedPutResponse] as before.
     *
     * HARDWARE-VERIFY (UAT-SOUNDS-PUT): does multi-chunk path-string PUT actually create
     * the file and ack on real hardware? All code-level correctness concerns (name never
     * transmitted, no-ack treated as Done) are now resolved.
     *
     * @param name     Sanitized basename + ".wav" (caller must ensure no path separators).
     * @param wavBytes Complete WAV file bytes (must be > 0; multi-KB is expected).
     * @return         true if the device acknowledged STATUS_OK; false on timeout or error.
     * @throws IllegalStateException if no output port is connected or a transfer is in flight.
     * @throws CancellationException if the coroutine is cancelled.
     */
    open suspend fun putSampleFile(name: String, wavBytes: ByteArray): Boolean {
        val portId = _deviceState.value.outputPortId
            ?: throw IllegalStateException("no output port")
        if (transferInFlight) throw IllegalStateException("transfer already in flight")
        transferInFlight = true
        val ack = CompletableDeferred<Boolean>()
        pendingPutAckDeferred = ack
        return try {
            val path = "/sounds/$name"

            // Edge case: empty bytes — send a single zero-length path frame so the ack
            // contract is satisfied, then wait for STATUS_OK (a real WAV is always multi-KB).
            if (wavBytes.isEmpty()) {
                val frame = SysExProtocol.buildFilePutFrame(
                    currentDeviceId, path, ByteArray(0), chunkIndex = 0, requestId = 30,
                )
                midiManager.sendMidi(portId, frame)
            } else {
                var chunkIndex = 0
                var offset = 0
                while (offset < wavBytes.size) {
                    val end = minOf(offset + SysExProtocol.MAX_PAGE_BYTES, wavBytes.size)
                    val chunk = wavBytes.copyOfRange(offset, end)
                    val frame = SysExProtocol.buildFilePutFrame(
                        currentDeviceId, path, chunk,
                        chunkIndex = chunkIndex,
                        requestId = 30 + (chunkIndex and 0xFF),
                    )
                    midiManager.sendMidi(portId, frame)
                    offset = end
                    chunkIndex++
                }
            }

            withTimeoutOrNull(PUT_ACK_TIMEOUT_MS) { ack.await() } ?: false
        } catch (e: CancellationException) {
            throw e
        } finally {
            pendingPutAckDeferred = null
            transferInFlight = false
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
    suspend fun resolveNodeId(path: String): Int? {
        if (statsQueryInFlight) return null
        statsQueryInFlight = true
        return try {
            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
            var nodeId = 0   // root
            var rid = 50
            for (segment in segments) {
                val body = listNodeBody(nodeId, requestId = rid++) ?: return null
                val child = SysExProtocol.parseFileListEntries(body).firstOrNull { it.name == segment }
                    ?: return null
                nodeId = child.nodeId
            }
            nodeId
        } finally {
            statsQueryInFlight = false
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
        val projectsNode = resolveNodeId("/projects") ?: return emptyList()

        // Active-slot pointer from /projects directory metadata.
        val activeNode = queryProjectsActiveNode()

        if (statsQueryInFlight) return emptyList()
        statsQueryInFlight = true
        val body = try {
            listNodeBody(projectsNode, requestId = 60)
        } finally {
            statsQueryInFlight = false
        } ?: return emptyList()

        return SysExProtocol.parseFileListEntries(body).map { entry ->
            ProjectSlot(
                nodeId = entry.nodeId,
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                isActive = activeNode != null && entry.nodeId == activeNode,
            )
        }
    }

    /** Read the /projects directory metadata "active" pointer (the currently-loaded slot). */
    private suspend fun queryProjectsActiveNode(): Int? {
        val portId = _deviceState.value.outputPortId ?: return null
        if (statsQueryInFlight) return null
        statsQueryInFlight = true
        return try {
            val deferred = CompletableDeferred<Map<String, String>>()
            pendingMetadataDeferred = deferred
            val frame = SysExProtocol.buildFileMetadataFrame(currentDeviceId, "/projects", requestId = 55)
            midiManager.sendMidi(portId, frame)
            val meta = withTimeoutOrNull(FILE_LIST_TIMEOUT_MS) { deferred.await() }
            meta?.get("active")?.toIntOrNull()
        } catch (e: CancellationException) {
            throw e
        } finally {
            pendingMetadataDeferred = null
            statsQueryInFlight = false
        }
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
        private const val FILE_LIST_TIMEOUT_MS = 5_000L
    }
}
