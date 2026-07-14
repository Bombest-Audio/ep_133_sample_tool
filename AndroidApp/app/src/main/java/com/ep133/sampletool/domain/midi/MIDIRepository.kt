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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * High-level MIDI interface for the EP-133.
 *
 * Wraps a [MIDIPort] implementation with typed helpers for Note On/Off, CC,
 * and Program Change. Exposes device state as a [StateFlow] for Compose observation.
 *
 * Also provides:
 * - a SysEx accumulation buffer for fragmented SysEx messages
 * - [sendRawBytes] for MIDI system real-time messages (Start, Stop, Clock)
 * - [channelFlow] as [StateFlow] for cross-screen channel sharing
 * - [queryDeviceStats] for firmware version, storage, and sample count
 * - [selectedScale] and [selectedRootNote] as shared state flows
 */
open class MIDIRepository internal constructor(
    private var midiManager: MIDIPort,
    injectedCollaborators: Pair<FileTransferClient, PadAssignmentService>?,
) {

    /**
     * Public one-arg constructor (production path — [MainActivity] et al.). Constructs
     * [FileTransferClient] then [PadAssignmentService] and wires them via the primary
     * constructor above.
     */
    constructor(midiManager: MIDIPort) : this(midiManager, injectedCollaborators = null)

    /**
     * Internal constructor for test injection: accepts pre-built [ftc] / [pas] collaborators so
     * tests can construct a repo around fakes without going through the production wiring path.
     * The public one-arg constructor above remains the only production construction site
     * (see MainActivity.kt).
     */
    internal constructor(
        midiManager: MIDIPort,
        ftc: FileTransferClient,
        pas: PadAssignmentService,
    ) : this(midiManager, injectedCollaborators = ftc to pas)

    /**
     * Stateful file-transfer core. Owns the file-op mutex, the reqId correlation registry,
     * node-ID resolution, metadata primitives, and paged GET/PUT transfers. This root stays
     * the single owner of [_deviceState] / [currentDeviceId]; FTC reads them live via the
     * trailing lambda constructor params.
     */
    private var ftc: FileTransferClient = injectedCollaborators?.first ?: FileTransferClient(
        midiManager,
        outputPortId = { _deviceState.value.outputPortId },
        deviceId = { currentDeviceId },
    )

    /**
     * Pad-assignment layer. Layered on [ftc] by constructor dependency; owns
     * getActiveGroupIndex/setActiveGroup/assignSampleToPad/clearPad/clearProject/
     * readGroupPadState.
     */
    private var pas: PadAssignmentService = injectedCollaborators?.second ?: PadAssignmentService(ftc)

    protected val _deviceState = MutableStateFlow(DeviceState())
    open val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    /** Incoming MIDI event: status byte, note, velocity, and channel. */
    data class MidiEvent(val status: Int, val note: Int, val velocity: Int, val channel: Int)

    private val _incomingMidi = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    val incomingMidi: SharedFlow<MidiEvent> = _incomingMidi.asSharedFlow()

    // ── Channel state ──
    private val _channel = MutableStateFlow(0)
    /** Currently selected MIDI channel (0-15) as StateFlow for cross-screen sharing. */
    val channelFlow: StateFlow<Int> = _channel.asStateFlow()

    /** Currently selected MIDI channel (0-15). Backed by [channelFlow]. */
    val channel: Int get() = _channel.value

    // ── Scale state ──
    private val _selectedScale = MutableStateFlow<Scale?>(null)
    /** Currently selected scale for scale-lock highlighting. Null = no scale (all pads normal). */
    val selectedScale: StateFlow<Scale?> = _selectedScale.asStateFlow()

    private val _selectedRootNote = MutableStateFlow("C")
    /** Currently selected root note for scale-lock. */
    val selectedRootNote: StateFlow<String> = _selectedRootNote.asStateFlow()

    fun setScale(scale: Scale?) { _selectedScale.value = scale }
    fun setRootNote(note: String) { _selectedRootNote.value = note }

    // Frames raw incoming USB-MIDI bytes into complete SysEx / channel messages. Routing stays here:
    // SysEx → dispatchSysEx (protected/open so tests can inject frames), channel notes → _incomingMidi.
    private val streamParser = MidiByteStreamParser(
        onSysEx = { dispatchSysEx(it) },
        onChannelMessage = { _incomingMidi.tryEmit(it) },
    )

    // ── SysEx response deferreds ──
    // FTC owns the reqId→waiter correlation registry for file-transfer responses; the root keeps
    // only the device-state deferreds below.
    private var pendingGreetDeferred: CompletableDeferred<Map<String, String>>? = null
    private var pendingFileListCountDeferred: CompletableDeferred<Int>? = null
    private var fileListEntryCount: Int = 0
    private var currentDeviceId: Int = 0
    @Volatile private var statsQueryInFlight = false

    // FTC's fileOpMutex serializes whole file ops (the device tolerates one transfer at a time),
    // and each op registers a reqId-keyed waiter — so there is no shared in-flight transfer state
    // to track here. Node FILE_LIST, FILE_INFO, and metadata GET/SET all correlate by reqId via
    // those waiters (see getMetadataJson / setMetadata). Session state, fileWaiters, and the group
    // node caches live in FileTransferClient; the root reaches them via `ftc.*` (e.g. resetting on
    // greet/port-swap through ftc.onDeviceGreet() / ftc.onPortSwapped()).

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

    // @Volatile for cross-thread visibility, matching the other in-flight guards (statsQueryInFlight,
    // activeGroupPollInFlight). Not a full lock — it only debounces re-entrant refreshDeviceState calls.
    @Volatile
    private var isRefreshing = false

    init {
        midiManager.onDevicesChanged = { updateDeviceStateOnly() }
        midiManager.onMidiReceived = { _, data -> streamParser.parse(data) }
    }

    /**
     * Recompute [_deviceState] from the current USB device list. Returns the enumerated devices so a
     * caller that also needs to (re)establish listeners can reuse the same list. The single copy of
     * the snapshot logic shared by [updateDeviceStateOnly] and [refreshDeviceState].
     */
    private fun applyDeviceSnapshot(): MIDIPort.Devices {
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
        return devices
    }

    /** Updates state and re-establishes listeners on new devices. */
    private fun updateDeviceStateOnly() {
        val wasConnected = _deviceState.value.connected
        val devices = applyDeviceSnapshot()
        val connected = _deviceState.value.connected
        // Close stale listeners and re-establish on current ports
        midiManager.closeAllListeners()
        for (input in devices.inputs) {
            midiManager.startListening(input.id)
        }
        // Pre-warm send port so sequencer noteOn is immediate
        devices.outputs.firstOrNull()?.id?.let { midiManager.prewarmSendPort(it) }

        // Auto-trigger stats query on device connect
        if (connected && !wasConnected) {
            repositoryScope.launch { queryDeviceStats() }
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
                // Reset file session, structure caches, and fail in-flight file waiters —
                // all owned by FTC.
                ftc.onDeviceGreet()

                val parsed = SysExProtocol.parseGreetResponse(payload)
                Log.d("EP133APP", "GREET response: $parsed")
                pendingGreetDeferred?.complete(parsed)
                pendingGreetDeferred = null
            }
            SysExProtocol.TE_SYSEX_FILE -> {
                // File-correlation frames route through FTC — the same seam FTC's own tests inject
                // into. Every file response correlates by reqId through fileWaiters, and each
                // awaiting op parses its own response body (the dispatcher just routes).
                ftc.routeFileFrame(message)
            }
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
        applyDeviceSnapshot()
    }

    /**
     * Swap the underlying MIDI port at runtime (debug-only Simulated EP-133 toggle seam).
     *
     * Detaches the old port's callbacks, resets per-connection session state so the new
     * port gets a fresh handshake, rewires callbacks, and re-enumerates the new port.
     * Does NOT close either port — the caller (MainActivity) owns port lifecycles, so
     * the real MIDIManager survives a round trip to the simulator and back.
     */
    fun swapPort(newPort: MIDIPort) {
        Log.i(
            "EP133APP",
            "swapPort: ${midiManager.javaClass.simpleName} -> ${newPort.javaClass.simpleName}",
        )
        // Detach the old port so stale callbacks can't mutate state after the swap.
        midiManager.onMidiReceived = null
        midiManager.onDevicesChanged = null
        midiManager.closeAllListeners()
        midiManager = newPort
        // Reset per-connection session state: the new port needs a fresh FILE_INIT
        // handshake, and stale firmware/stats/port ids must not leak across ports.
        ftc.onPortSwapped()
        // FTC's `port` reference is fixed at construction, so re-construct FTC bound to the
        // new port. The state that would leak (caches, session-init) was already cleared above.
        ftc = FileTransferClient(
            newPort,
            outputPortId = { _deviceState.value.outputPortId },
            deviceId = { currentDeviceId },
        )
        // PAS holds `ftc` by constructor reference, so it must be rebuilt on the new `ftc`
        // instance too — otherwise PAS would keep calling the old (detached) FTC.
        pas = PadAssignmentService(ftc)
        _deviceState.value = DeviceState()
        // Rewire exactly as the init block does, then enumerate the new port.
        newPort.onDevicesChanged = { updateDeviceStateOnly() }
        newPort.onMidiReceived = { _, data -> streamParser.parse(data) }
        refreshDeviceState()
    }

    /**
     * Query real device stats from the EP-133:
     * - GREET → firmwareVersion
     * - FILE_METADATA on /sounds → storageUsedBytes, storageTotalBytes
     * - FILE_LIST on /sounds → sampleCount
     *
     * Returns true if GREET succeeded; false on timeout or no output port.
     */
    open suspend fun queryDeviceStats(): Boolean {
        val portId = _deviceState.value.outputPortId ?: return false
        // Guard against overlapping queries (e.g. rapid disconnect/reconnect). Two concurrent
        // runs would race on the shared pending-deferred fields and could call complete() twice
        // on the same CompletableDeferred — an IllegalStateException on the MIDI dispatch path.
        if (statsQueryInFlight) return false
        return ftc.withFileOpLock {
            // Re-check after acquiring the lock — another call may have started while we waited.
            if (statsQueryInFlight) return@withFileOpLock false
            statsQueryInFlight = true
            try {
                queryDeviceStatsInner(portId)
            } finally {
                statsQueryInFlight = false
            }
        }
    }

    private suspend fun queryDeviceStatsInner(portId: String): Boolean {
        if (!queryFirmwareVersion(portId)) return false

        // Sample count + storage from /sounds, over the reqId-correlated file ops (the same
        // primitives the active-group walk and backup ride), now owned by FTC. This whole method
        // already holds FTC's file-op mutex (see queryDeviceStats), so these are NoLock-equivalent
        // nested calls and must go through FTC's *NoLock primitives, not its locking wrapper.
        ftc.ensureFileSessionInitNoLock()
        val soundsNode = ftc.resolveNodeIdInternal("/sounds") ?: return true
        querySampleCount(soundsNode)
        queryStorage(soundsNode)
        return true
    }

    /**
     * GREET the device for firmware + identity and record the firmware version. Returns false on
     * timeout. reqId=1 is the conventional greet ID; it is not a FILE op, so it does not draw from
     * nextFileReqId() (greet uses CMD_GREET, not TE_SYSEX_FILE).
     */
    private suspend fun queryFirmwareVersion(portId: String): Boolean {
        val greetDeferred = CompletableDeferred<Map<String, String>>()
        pendingGreetDeferred = greetDeferred
        val greetFrame = SysExProtocol.buildGreetFrame(currentDeviceId, requestId = 1)
        midiManager.sendMidi(portId, greetFrame)
        val greetResult = withTimeoutOrNull(5_000) { greetDeferred.await() }
            ?: run { pendingGreetDeferred = null; return false }
        _deviceState.value = _deviceState.value.copy(firmwareVersion = greetResult["sw_version"] ?: "")
        return true
    }

    /** Sample count = named entries in /sounds. Assumes an open file session. */
    private suspend fun querySampleCount(soundsNode: Int) {
        val body = ftc.listNodeBody(soundsNode, requestId = ftc.nextRequestId()) ?: return
        val count = SysExProtocol.parseFileListEntries(body).count { it.name.isNotBlank() }
        _deviceState.value = _deviceState.value.copy(sampleCount = count)
    }

    /**
     * Storage used/total from the /sounds node metadata. The device reports max_capacity +
     * free_space_in_bytes (hardware-verified 2026-06-27), so used = max − free.
     */
    private suspend fun queryStorage(soundsNode: Int) {
        val meta = ftc.getMetadataJson(soundsNode)
        val total = meta.optLong("max_capacity", -1L).takeIf { it >= 0 } ?: return
        val free = meta.optLong("free_space_in_bytes", -1L).takeIf { it >= 0 }
        val used = if (free != null) (total - free).coerceAtLeast(0) else null
        _deviceState.value = _deviceState.value.copy(storageUsedBytes = used, storageTotalBytes = total)
    }

    // ── Paged project archive transfer (delegates to FileTransferClient) ──────────

    /**
     * Download a full project archive via the device's two-phase INIT/DATA protocol.
     * Thin delegation to [FileTransferClient.getProjectArchive] — see FTC for the protocol.
     *
     * @throws IllegalStateException on page mismatch, buffer overflow, or device error.
     */
    suspend fun getProjectArchive(nodeId: Int): ByteArray = ftc.getProjectArchive(nodeId)

    /**
     * Free device sample storage in bytes from the latest device stats, or null if unknown.
     * Callers use this to preflight an upload before writing a partial batch the device would
     * reject once /sounds fills.
     */
    fun availableStorageBytes(): Long? {
        val s = _deviceState.value
        val used = s.storageUsedBytes ?: return null
        val total = s.storageTotalBytes ?: return null
        return (total - used).coerceAtLeast(0L)
    }

    /** Upload a project archive via the device's two-phase INIT/DATA protocol (restore). */
    suspend fun putProjectArchive(slotNodeId: Int, tarBytes: ByteArray): Boolean =
        ftc.putProjectArchive(slotNodeId, tarBytes)

    // ── Sample file upload to /sounds (delegates to FileTransferClient) ───────────

    /**
     * Upload raw s16 LE PCM bytes to /sounds by creating a new file via the device's node-ID
     * INIT protocol. Thin delegation to [FileTransferClient.putSampleFile] — see FTC for the
     * full protocol description.
     *
     * @throws IllegalStateException if no output port is connected or a transfer is in flight.
     * @throws CancellationException if the coroutine is cancelled.
     */
    open suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int = 1,
        sampleRate: Int = 46875,
    ): Int? = ftc.putSampleFile(name, pcmBytes, channels, sampleRate)

    /**
     * Compute the raw PCM bytes per DATA page for /sounds uploads. Thin delegation to
     * [FileTransferClient.computeSampleChunkSize] (kept `internal` here for test compatibility).
     */
    internal fun computeSampleChunkSize(chunkSize: Int): Int = ftc.computeSampleChunkSize(chunkSize)

    // ── FILE_INIT session handshake — once per connection (delegates to FileTransferClient) ──

    /**
     * Ensure the FILE_INIT handshake has been completed for this connection. Thin delegation to
     * [FileTransferClient.ensureFileSessionInit].
     *
     * @return true if the session is initialized (immediately or after the handshake),
     *         false if no port is connected or the INIT timed out.
     */
    suspend fun ensureFileSessionInit(): Boolean = ftc.ensureFileSessionInit()

    // ── Project slot enumeration ──

    /** A single EP-133 project slot (one of /projects/P00 .. /projects/P08). */
    data class ProjectSlot(
        val nodeId: Int,
        val name: String,
        val sizeBytes: Long,
        val isActive: Boolean,
    )

    /**
     * Pure decode of a FILE_LIST response body into directory entries. Thin delegation to
     * [FileTransferClient.parseFileListEntries].
     */
    fun parseFileListEntries(body: ByteArray): List<SysExProtocol.FileEntry> =
        ftc.parseFileListEntries(body)

    /**
     * Resolve a path like "/projects" to a numeric node ID. Thin delegation to
     * [FileTransferClient.resolveNodeId] — kept `open` so test subclasses can override it on the
     * root (production code always calls through this public method).
     */
    open suspend fun resolveNodeId(path: String): Int? = ftc.resolveNodeId(path)

    /**
     * Enumerate the 9 EP-133 project slots. Thin delegation to
     * [FileTransferClient.listProjects].
     */
    open suspend fun listProjects(): List<ProjectSlot> = ftc.listProjects()

    /**
     * Enumerate the EP-133 /sounds directory — every sample file as a [SysExProtocol.FileEntry]
     * (nodeId + name + size). Thin delegation to [FileTransferClient.listSoundEntries].
     */
    open suspend fun listSoundEntries(): List<SysExProtocol.FileEntry> = ftc.listSoundEntries()

    /**
     * Download a file node's full contents via the multi-chunk GET. Thin delegation to
     * [FileTransferClient.getFileBytes].
     */
    open suspend fun getFileBytes(nodeId: Int): ByteArray? = ftc.getFileBytes(nodeId)

    // NOTE: don't resolve the active project via a legacy path-string METADATA frame — that form
    // has no reqId to correlate against the reqId-first dispatcher, so its waiter never completes,
    // every listProjects() stalls its full 5 s timeout, and the active pointer reads null. Active
    // resolution goes through the nodeId-form path in PadAssignmentService instead.

    /**
     * Fetch metadata for [nodeId] using the nodeId-form GET (METADATA_GET = 2). Thin delegation
     * to [FileTransferClient.getMetadataJson].
     *
     * MUST be called from within a FTC [FileTransferClient.withFileOpLock] context.
     */
    suspend fun getMetadataJson(nodeId: Int): JSONObject = ftc.getMetadataJson(nodeId)

    /**
     * Write [json] as the metadata for [nodeId] via a single METADATA SET frame. Thin delegation
     * to [FileTransferClient.setMetadata].
     *
     * @return true if the device responded (any ack), false on timeout.
     */
    suspend fun setMetadata(nodeId: Int, json: String): Boolean = ftc.setMetadata(nodeId, json)

    /** Thin delegation to [FileTransferClient.getNodeInfo]. */
    suspend fun getNodeInfo(nodeId: Int): SysExProtocol.NodeInfo? = ftc.getNodeInfo(nodeId)

    /**
     * Enumerate ALL children of [nodeId], paging FILE_LIST until an empty page. Thin
     * delegation to [FileTransferClient.listAllChildren] (Phase 6 pattern-write spike —
     * closes the page-0-only enumeration gap).
     */
    open suspend fun listAllChildren(nodeId: Int): List<SysExProtocol.FileEntry> = ftc.listAllChildren(nodeId)

    // ── Pad-assignment layer (thin delegations to PadAssignmentService) ───────────
    // getActiveGroupIndex/setActiveGroup/assignSampleToPad/clearPad/clearProject/
    // readGroupPadState (and their NoLock helpers) live in `pas`, layered on FTC.

    /** Thin delegation to [PadAssignmentService.getActiveGroupIndex]. */
    open suspend fun getActiveGroupIndex(): Int? = pas.getActiveGroupIndex()

    /** Thin delegation to [PadAssignmentService.setActiveGroup]. */
    open suspend fun setActiveGroup(index: Int): Boolean = pas.setActiveGroup(index)

    /** Thin delegation to [PadAssignmentService.assignSampleToPad]. */
    open suspend fun assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String = "oneshot",
        muteGroup: Boolean = false,
    ): Boolean = pas.assignSampleToPad(group, gridIndex, sampleNodeId, sampleStart, sampleEnd, playmode, muteGroup)

    /** Thin delegation to [PadAssignmentService.clearPad]. */
    open suspend fun clearPad(group: PadChannel, gridIndex: Int): Boolean = pas.clearPad(group, gridIndex)

    /** Thin delegation to [PadAssignmentService.clearProject]. */
    open suspend fun clearProject(projectName: String): Int = pas.clearProject(projectName)

    /** Thin delegation to [PadAssignmentService.readGroupPadState]. */
    open suspend fun readGroupPadState(group: PadChannel): Map<Int, String> = pas.readGroupPadState(group)

    /**
     * Resolve the /sounds directory node ID. Thin delegation to
     * [FileTransferClient.resolveSoundsNodeId] — retained on the root (protected open) for
     * source-compatibility with any external subclass, but note that [putSampleFile] calls FTC's
     * own internal `resolveSoundsNodeId`, not this root method: a subclass overriding THIS method
     * no longer affects `putSampleFile`'s behavior — override FTC's `resolveSoundsNodeId` instead
     * (test doubles construct/subclass [FileTransferClient] directly).
     */
    protected open suspend fun resolveSoundsNodeId(): Int? = ftc.resolveSoundsNodeId()

    fun setChannel(ch: Int) {
        _channel.value = ch.coerceIn(0, 15)
    }

    // ── MIDI message senders ──

    fun noteOn(note: Int, velocity: Int = DEFAULT_VELOCITY, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: run {
            Log.w("EP133APP", "MIDI OUT: no output port! note=$note ch=$ch")
            return
        }
        Log.d("EP133APP", "MIDI OUT: noteOn note=$note vel=$velocity ch=$ch port=$portId")
        val status = STATUS_NOTE_ON or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (note and 0x7F).toByte(),
            (velocity and 0x7F).toByte(),
        ))
    }

    fun noteOff(note: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = STATUS_NOTE_OFF or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (note and 0x7F).toByte(),
            0,
        ))
    }

    fun controlChange(control: Int, value: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = STATUS_CONTROL_CHANGE or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (control and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        ))
    }

    fun programChange(program: Int, ch: Int = channel) {
        val portId = _deviceState.value.outputPortId ?: return
        val status = STATUS_PROGRAM_CHANGE or (ch and 0x0F)
        midiManager.sendMidi(portId, byteArrayOf(
            status.toByte(),
            (program and 0x7F).toByte(),
        ))
    }

    fun allNotesOff(ch: Int = channel) {
        controlChange(CC_ALL_NOTES_OFF, 0, ch)
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
        val bankMsb = index / PROGRAMS_PER_BANK
        val program = index % PROGRAMS_PER_BANK
        Log.d("EP133APP", "MIDI OUT: loadSound #$soundNumber → pad note=$padNote padCh=$padChannel bank=$bankMsb pc=$program ch=$ch")

        val noteOnStatus = (STATUS_NOTE_ON or (padChannel and 0x0F)).toByte()
        val noteOffStatus = (STATUS_NOTE_OFF or (padChannel and 0x0F)).toByte()
        val ccStatus = (STATUS_CONTROL_CHANGE or (ch and 0x0F)).toByte()
        val pcStatus = (STATUS_PROGRAM_CHANGE or (ch and 0x0F)).toByte()
        val padNoteByte = (padNote and 0x7F).toByte()

        midiManager.sendMidi(portId, byteArrayOf(
            noteOnStatus, padNoteByte, DEFAULT_VELOCITY.toByte(),
            noteOffStatus, padNoteByte, 0,
            ccStatus, CC_BANK_SELECT_MSB.toByte(), (bankMsb and 0x7F).toByte(),
            ccStatus, CC_BANK_SELECT_LSB.toByte(), 0,
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
        // ── MIDI channel-voice message constants ──────────────────────────────────
        // Status nibbles (OR'd with the 4-bit channel); CC numbers; and EP-133 sound-load values.
        private const val STATUS_NOTE_ON = 0x90
        private const val STATUS_NOTE_OFF = 0x80
        private const val STATUS_CONTROL_CHANGE = 0xB0
        private const val STATUS_PROGRAM_CHANGE = 0xC0
        private const val CC_ALL_NOTES_OFF = 123
        private const val CC_BANK_SELECT_MSB = 0
        private const val CC_BANK_SELECT_LSB = 32
        private const val PROGRAMS_PER_BANK = 128
        private const val DEFAULT_VELOCITY = 100

        // The file-op timeout constants live in FileTransferClient with the primitives that use
        // them. The reqId-space constants below are kept here (not just on FTC) because tests
        // reference them as `MIDIRepository.PUT_INIT_REQUEST_ID` / `FILE_REQ_ID_MIN` /
        // `FILE_REQ_ID_MAX` — both classes' counters share the same numeric space by construction
        // (FTC's private counter uses identical bounds).

        // putSampleFile uses a transfer-local counter starting here; each frame increments it.
        // This is the INIT reqId; DATA pages and terminator get 31, 32, ... (masked to 14-bit).
        // The global nextFileReqId() counter skips the range 30..99 so it never aliases these.
        internal const val PUT_INIT_REQUEST_ID    = 30

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
