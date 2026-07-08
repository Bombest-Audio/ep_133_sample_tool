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
import java.util.concurrent.atomic.AtomicBoolean

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
open class MIDIRepository(private var midiManager: MIDIPort) {

    /**
     * Stateful file-transfer core (999.5 Plan 01). Owns the file-op mutex, the reqId
     * correlation registry, node-ID resolution, metadata primitives, and paged GET/PUT
     * transfers. This root stays the single owner of [_deviceState] / [currentDeviceId]; FTC
     * reads them live via the trailing lambda constructor params.
     */
    private var ftc: FileTransferClient = FileTransferClient(
        midiManager,
        outputPortId = { _deviceState.value.outputPortId },
        deviceId = { currentDeviceId },
    )

    /**
     * Guard for the active-group poll: set to true while a [getActiveGroupIndex] call is
     * running.  Checked BEFORE acquiring FTC's file-op mutex so that a queued poll (suspended on
     * the mutex) does not accumulate behind a running poll — the second call returns null
     * immediately rather than stacking up.
     *
     * This is an AtomicBoolean so it can be read from any thread without synchronisation.
     * FTC's mutex still serialises the actual op body; this flag is purely an entry guard.
     */
    private val activeGroupPollInFlight = AtomicBoolean(false)

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

    // Frames raw incoming USB-MIDI bytes into complete SysEx / channel messages. Routing stays here:
    // SysEx → dispatchSysEx (protected/open so tests can inject frames), channel notes → _incomingMidi.
    private val streamParser = MidiByteStreamParser(
        onSysEx = { dispatchSysEx(it) },
        onChannelMessage = { _incomingMidi.tryEmit(it) },
    )

    // ── SysEx response deferreds (D-12) ──
    // FTC owns the reqId→waiter correlation registry for file-transfer responses (moved 999.5
    // Plan 01). The root keeps only the device-state deferred below.
    private var pendingGreetDeferred: CompletableDeferred<Map<String, String>>? = null
    private var pendingFileListCountDeferred: CompletableDeferred<Int>? = null
    private var fileListEntryCount: Int = 0
    private var currentDeviceId: Int = 0
    @Volatile private var statsQueryInFlight = false

    // ── FILE_INIT session state / fileWaiters / groupsNodeCache / groupNodeNameCache ──
    // Moved into FileTransferClient (999.5 Plan 01). The root reads them via `ftc.*` where
    // still needed (e.g. resetting on greet/port-swap through ftc.onDeviceGreet()/onPortSwapped()).

    // ── Paged project transfer state (Phase 4 GATE) ──
    // fileOpMutex serializes whole file ops (the device tolerates one transfer at a time), and
    // each op registers a reqId-keyed waiter in fileWaiters — so there is no shared in-flight
    // state to track. (transferInFlight / pendingPut* / awaited*ReqId removed with backlog 999.4.)

    // ── Project enumeration state (Phase 4 Wave 2) ──
    // FILE_LIST by node ID returns concatenated directory entries; the dispatcher hands the
    // accumulated body to a CompletableDeferred keyed by nodeListInFlight.
    // (pendingNodeListDeferred / nodeListBuffer removed — node FILE_LIST correlates by reqId)

    // ── Metadata JSON round-trip state (Step 1 — active-group sync) ──
    // (metadata GET/SET in-flight flags + deferreds removed — both now correlate by reqId via
    //  fileWaiters; see getMetadataJson / setMetadata.)

    // (pendingNodeInfoDeferred removed — FILE_INFO now correlates by reqId via fileWaiters)

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

        // Auto-trigger stats query on device connect (D-13)
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
                // owned by FTC (999.5 Plan 01).
                ftc.onDeviceGreet()

                val parsed = SysExProtocol.parseGreetResponse(payload)
                Log.d("EP133APP", "GREET response: $parsed")
                pendingGreetDeferred?.complete(parsed)
                pendingGreetDeferred = null
            }
            SysExProtocol.TE_SYSEX_FILE -> {
                // File-correlation frames route through FTC (999.5 Plan 01, D-04). This is the
                // same seam FTC's own tests inject into (D-01) — build once, dispatch from here.
                ftc.routeFileFrame(message)
            }
        }
    }

    // dispatchFileResponse / dispatchPagedPutResponse removed — every file response now routes by
    // reqId through fileWaiters (see the TE_SYSEX_FILE branch in dispatchSysEx). The per-op body
    // parsing lives in each awaiting op, not the dispatcher.

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

        // Steps 2–3: sample count + storage from /sounds, over the reqId-correlated file ops
        // (the same primitives the active-group walk and backup ride), now owned by FTC.
        // Runs inside FTC's own file-op mutex via withFileOpLock — this whole method already
        // holds the SAME mutex instance (see queryDeviceStats), so this is a NoLock-equivalent
        // nested call and must go through FTC's *NoLock primitives, not its locking wrapper.
        ftc.ensureFileSessionInitNoLock()
        val soundsNode = ftc.resolveNodeIdInternal("/sounds")
        if (soundsNode != null) {
            // Sample count = entries in /sounds (named files only).
            val body = ftc.listNodeBody(soundsNode, requestId = ftc.nextRequestId())
            if (body != null) {
                val count = SysExProtocol.parseFileListEntries(body).count { it.name.isNotBlank() }
                _deviceState.value = _deviceState.value.copy(sampleCount = count)
            }
            // Storage from the /sounds node metadata. The device reports max_capacity +
            // free_space_in_bytes (hardware-verified 2026-06-27), so used = max − free.
            val meta = ftc.getMetadataJson(soundsNode)
            val total = meta.optLong("max_capacity", -1L).takeIf { it >= 0 }
            val free = meta.optLong("free_space_in_bytes", -1L).takeIf { it >= 0 }
            if (total != null) {
                val used = if (free != null) (total - free).coerceAtLeast(0) else null
                _deviceState.value = _deviceState.value.copy(storageUsedBytes = used, storageTotalBytes = total)
            }
        }

        return true
    }

    // ── Paged project archive transfer (Phase 4 GATE) ── moved to FileTransferClient (999.5-01) ──

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

    // ── Sample file upload to /sounds (Phase 5 Wave 2, SAMPLE-03) ── moved to FTC (999.5-01) ──

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

    // ── FILE_INIT session handshake (Task 3 — once per connection) ── moved to FTC ─────────────

    /**
     * Ensure the FILE_INIT handshake has been completed for this connection. Thin delegation to
     * [FileTransferClient.ensureFileSessionInit].
     *
     * @return true if the session is initialized (immediately or after the handshake),
     *         false if no port is connected or the INIT timed out.
     */
    suspend fun ensureFileSessionInit(): Boolean = ftc.ensureFileSessionInit()

    // ── Project slot enumeration (Phase 4 Wave 2, PROJ-01) ──

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
     * [FileTransferClient.resolveNodeId] — kept `open` so test subclasses that override this on
     * the ROOT for production-facing polymorphism (production code always calls through this
     * public method) still work; the moved seam test doubles now subclass FTC directly (D-01).
     *
     * HARDWARE-VERIFY (Open Q1): confirm /projects lists by nodeId (this walk) vs the
     * Phase 2 path string.
     */
    open suspend fun resolveNodeId(path: String): Int? = ftc.resolveNodeId(path)

    /**
     * Enumerate the 9 EP-133 project slots (PROJ-01). Thin delegation to
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

    // queryProjectsActiveNodeNoLock removed: it used the legacy path-string METADATA frame and
    // a pendingMetadataDeferred that nothing completed after the reqId-first dispatcher refactor
    // (backlog 999.4) — every listProjects() stalled its full 5 s timeout and the active pointer
    // was always null. Caught by SimulatedDeviceTest against the wire-level device simulator.

    // ── Active-group sync: nodeId-form metadata round-trips (Step 1) ─────────────
    //
    // These implement the reference tool's group-select mechanism:
    //   getActiveGroupIndex: /projects→active→projName→/projects/<p>/groups→active→getNode→name
    //   setActiveGroup:      resolve group nodeId, SET groups-dir {active:<nodeId>}
    //
    // Both functions are serialised via FTC's file-op mutex (withFileOpLock) so they cannot
    // interleave with putSampleFile or the active-group poll.  All internal helpers
    // (ftc.resolveNodeIdInternal, ftc.getMetadataJson, ftc.getNodeInfo, ftc.setMetadata) are
    // called on FTC without acquiring the mutex again — they are the NoLock-safe primitives.

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
    open suspend fun getActiveGroupIndex(): Int? {
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
            ftc.withFileOpLock {
                ftc.ensureFileSessionInitNoLock()
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
     * MUST only be called from within an [FileTransferClient.withFileOpLock] context.
     */
    private suspend fun getActiveGroupIndexNoLock(): Int? {
        // Step 1: resolve the /projects node once (cached implicitly by resolveNodeIdInternal
        // finding it by name from root). We need the node ID so we can METADATA GET it.
        // Use the fast METADATA GET path — no directory list needed here.
        val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return null

        // Step 2: METADATA GET on projects node → get active-project nodeId (fast, 1 round-trip).
        val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active project nodeId=$activeProjNodeId")

        // Step 3: resolve groups dir — cache hit avoids directory walk after first call.
        val groupsNode = ftc.groupsNodeCache.getOrPut(activeProjNodeId) {
            // One-time: need the project name to build the path, so do FILE_INFO on activeProjNodeId.
            val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: run {
                Log.w("EP133APP", "MIDI META: could not get project name for nodeId=$activeProjNodeId")
                return null
            }
            Log.d("EP133APP", "MIDI META: active project name='$projName' (one-time resolution)")
            val gn = ftc.resolveNodeIdInternal("/projects/$projName/groups") ?: run {
                Log.w("EP133APP", "MIDI META: /projects/$projName/groups not found — device may use different structure")
                return null
            }
            Log.d("EP133APP", "MIDI META: resolved groups dir nodeId=$gn for project='$projName' (cached)")
            gn
        }

        // Step 4: METADATA GET on groups dir → get active-group nodeId (fast, 1 round-trip).
        val activeGroupNodeId = ftc.getMetadataJson(groupsNode).optInt("active", -1)
            .takeIf { it >= 0 } ?: return null
        Log.d("EP133APP", "MIDI META: active group nodeId=$activeGroupNodeId")

        // Step 5: resolve group name — cache hit avoids directory walk after first call.
        val nameMap = ftc.groupNodeNameCache.getOrPut(groupsNode) {
            // One-time: list groups dir children to build nodeId→name map.
            val body = ftc.listNodeBody(groupsNode, requestId = ftc.nextRequestId()) ?: run {
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
    open suspend fun setActiveGroup(index: Int): Boolean {
        val channel = PadChannel.entries.getOrNull(index) ?: return false
        if (_deviceState.value.outputPortId == null) return false
        return ftc.withFileOpLock {
            try {
                val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return@withFileOpLock false
                val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
                    .takeIf { it >= 0 } ?: return@withFileOpLock false
                val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: return@withFileOpLock false
                val groupNode = ftc.resolveNodeIdInternal("/projects/$projName/groups/${channel.name}") ?: return@withFileOpLock false
                val groupsNode = ftc.resolveNodeIdInternal("/projects/$projName/groups") ?: return@withFileOpLock false
                ftc.setMetadata(groupsNode, """{"active":$groupNode}""")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "MIDI META: setActiveGroup($index) failed", e)
                false
            }
        }
    }

    // ── Pad-assignment (hardware-verified 2026-06-29) ─────────────────────────────
    //
    // Each pad in the active project is a FILE node at:
    //   active-project → groups/<letter> → "<gridIndex+1 zero-padded>"
    // A pad's metadata JSON IS its sound binding: writing the verified JSON below
    // with sym=<sampleNodeId> makes the device play that sample when the pad is hit.

    /**
     * Bind a sample to a pad on the active project via a single METADATA SET.
     *
     * Pad node resolution (hardware-verified 2026-06-29):
     *   /projects → active-project node → FILE_INFO → project name →
     *   /projects/<name>/groups/<group.name> → list children → pick entry
     *   named "%02d".format(gridIndex + 1)
     *
     * Writes the hardware-verified pad metadata JSON (any field deviation may cause
     * the device to silently ignore the assignment):
     * ```
     * {"sym":<sampleNodeId>,"sample.start":<start>,"sample.end":<end>,
     *  "sound.playmode":"<playmode>","sound.amplitude":100,"sound.pan":0,
     *  "sound.pitch":0.00,"envelope.attack":0,"envelope.release":255,
     *  "sound.mutegroup":false,"time.mode":"off","midi.channel":0}
     * ```
     *
     * @param group         Target pad group (A/B/C/D).
     * @param gridIndex     Display-order pad index within the group (0..11, top-left to bottom-right,
     *                      matching [EP133Pads.GRID_ORDER]).
     * @param sampleNodeId  Device-assigned node ID of the uploaded sample (from [putSampleFile]).
     * @param sampleStart   Start frame (0-based, inclusive).  Use 0 to start from the beginning.
     * @param sampleEnd     End frame (exclusive).  Use total frame count to play the full sample.
     * @param playmode      EP-133 playmode string (default "oneshot").
     * @return              true if the device acknowledged the METADATA SET; false on error or
     *                      if the pad node cannot be resolved (logged with details).
     */
    open suspend fun assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String = "oneshot",
        muteGroup: Boolean = false,
    ): Boolean {
        if (_deviceState.value.outputPortId == null) return false
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val padNodeId = resolvePadNodeIdNoLock(group, gridIndex) ?: return@withFileOpLock false

                // Build and write the hardware-verified pad metadata JSON.
                val json = buildPadAssignmentJson(sampleNodeId, sampleStart, sampleEnd, playmode, muteGroup)
                Log.d("EP133APP", "assignSampleToPad: group=${group.name} gridIndex=$gridIndex padNode=$padNodeId json=$json")
                ftc.setMetadata(padNodeId, json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "assignSampleToPad(${group.name}[$gridIndex]) failed", e)
                false
            }
        }
    }

    /**
     * Build the hardware-verified pad assignment metadata JSON.
     *
     * Shape verified on real device (2026-06-29): device accepted and played the sample
     * after receiving a METADATA SET with exactly these keys and types.
     *
     * @param sampleNodeId  Device-assigned node ID from [putSampleFile] (the `sym` field).
     * @param sampleStart   Start frame (0 = beginning of sample).
     * @param sampleEnd     End frame (total frame count = full sample).
     * @param playmode      EP-133 playmode string (e.g. "oneshot", "loop").
     */
    internal fun buildPadAssignmentJson(
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
        muteGroup: Boolean = false,
    ): String = """{"sym":$sampleNodeId,"sample.start":$sampleStart,"sample.end":$sampleEnd,"sound.playmode":"$playmode","sound.amplitude":100,"sound.pan":0,"sound.pitch":0.00,"envelope.attack":0,"envelope.release":255,"sound.mutegroup":$muteGroup,"time.mode":"off","midi.channel":0}"""

    /**
     * Metadata for an empty (unbound) pad — the device's own representation. Hardware-verified:
     * an empty pad's FILE_METADATA GET returns exactly `{"sym":0}` (see pad dumps), so SET-ting
     * it back unbinds the pad.
     */
    private val padEmptyJson = """{"sym":0}"""

    /**
     * Resolve a pad node id at `/projects/<active>/groups/<X>/<NN>` inside an
     * [FileTransferClient.withFileOpLock]-locked context. Shared by [assignSampleToPad] and
     * [clearPad].
     */
    private suspend fun resolvePadNodeIdNoLock(group: PadChannel, gridIndex: Int): Int? {
        val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot resolve /projects"); return null
        }
        val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1).takeIf { it >= 0 } ?: run {
            Log.w("EP133APP", "resolvePadNode: no active project in /projects metadata"); return null
        }
        val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot get project name for nodeId=$activeProjNodeId"); return null
        }
        val groupDirPath = "/projects/$projName/groups/${group.name}"
        val groupDirNode = ftc.resolveNodeIdInternal(groupDirPath) ?: run {
            Log.w("EP133APP", "resolvePadNode: cannot resolve $groupDirPath"); return null
        }
        val body = ftc.listNodeBody(groupDirNode, requestId = ftc.nextRequestId()) ?: run {
            Log.w("EP133APP", "resolvePadNode: FILE_LIST for $groupDirPath returned null"); return null
        }
        // Pad node name = "%02d".format(gridIndex + 1) per hardware-verified naming.
        val padName = "%02d".format(gridIndex + 1)
        return SysExProtocol.parseFileListEntries(body).firstOrNull { it.name == padName }?.nodeId ?: run {
            Log.w("EP133APP", "resolvePadNode: pad '$padName' not found in $groupDirPath"); return null
        }
    }

    /**
     * Unbind a pad on the device: write the empty-pad metadata (`{"sym":0}`) to the pad node so it
     * plays nothing. Returns false if there's no output port or the pad can't be resolved.
     */
    open suspend fun clearPad(group: PadChannel, gridIndex: Int): Boolean {
        if (_deviceState.value.outputPortId == null) return false
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val padNodeId = resolvePadNodeIdNoLock(group, gridIndex) ?: return@withFileOpLock false
                Log.d("EP133APP", "clearPad: group=${group.name} gridIndex=$gridIndex padNode=$padNodeId")
                ftc.setMetadata(padNodeId, padEmptyJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "clearPad(${group.name}[$gridIndex]) failed", e)
                false
            }
        }
    }

    /**
     * Clear a project slot: reset every pad in all four groups to empty (`{"sym":0}`). The slot
     * itself always survives — project directories carry no DELETE capability (flags `0x0e`), so
     * "clearing" means emptying its pads, not removing the numbered slot.
     *
     * @return the number of pads emptied, or -1 on failure / no output port.
     */
    open suspend fun clearProject(projectName: String): Int {
        if (_deviceState.value.outputPortId == null) return -1
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                var cleared = 0
                for (group in PadChannel.entries) {
                    val groupDir = ftc.resolveNodeIdInternal("/projects/$projectName/groups/${group.name}")
                        ?: continue
                    val body = ftc.listNodeBody(groupDir, requestId = ftc.nextRequestId()) ?: continue
                    val padNodes = SysExProtocol.parseFileListEntries(body)
                        .filter { it.name.length == 2 && it.name.all(Char::isDigit) }  // pad dirs 01..12
                        .map { it.nodeId }
                    for (padNode in padNodes) {
                        if (ftc.setMetadata(padNode, padEmptyJson)) cleared++
                    }
                }
                Log.d("EP133APP", "clearProject($projectName): emptied $cleared pads")
                cleared
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "clearProject($projectName) failed", e)
                -1
            }
        }
    }

    /**
     * Read which sample is bound to each pad of [group] in the active project.
     *
     * Returns gridIndex (0-based, 0..11) → sample display name for every OCCUPIED pad — a pad whose
     * metadata `sym` is non-zero (an empty pad is `{"sym":0}`). Empty pads are omitted. Names have
     * their extension stripped; if the sample node can't be named, falls back to `"sample"`.
     *
     * This lets the Kit Builder canvas mirror what's actually on the device instead of showing a
     * blank staging grid. Resolves the group dir + pad list once, then a METADATA GET per pad (plus
     * a FILE_INFO per occupied pad to name it). Returns an empty map when offline or on error.
     */
    open suspend fun readGroupPadState(group: PadChannel): Map<Int, String> {
        if (_deviceState.value.outputPortId == null) return emptyMap()
        return ftc.withFileOpLock {
            ftc.ensureFileSessionInitNoLock()
            try {
                val projectsNode = ftc.resolveNodeIdInternal("/projects") ?: return@withFileOpLock emptyMap()
                val activeProjNodeId = ftc.getMetadataJson(projectsNode).optInt("active", -1)
                    .takeIf { it >= 0 } ?: return@withFileOpLock emptyMap()
                val projName = ftc.getNodeInfo(activeProjNodeId)?.name ?: return@withFileOpLock emptyMap()
                val groupDir = ftc.resolveNodeIdInternal("/projects/$projName/groups/${group.name}")
                    ?: return@withFileOpLock emptyMap()
                val body = ftc.listNodeBody(groupDir, requestId = ftc.nextRequestId()) ?: return@withFileOpLock emptyMap()
                val padEntries = SysExProtocol.parseFileListEntries(body)
                    .filter { it.name.length == 2 && it.name.all(Char::isDigit) }  // pad dirs 01..12
                val result = LinkedHashMap<Int, String>()
                for (entry in padEntries) {
                    val gridIndex = (entry.name.toIntOrNull() ?: continue) - 1
                    if (gridIndex !in 0 until 12) continue
                    val sym = ftc.getMetadataJson(entry.nodeId).optInt("sym", 0)
                    if (sym == 0) continue
                    val name = ftc.getNodeInfo(sym)?.name?.substringBeforeLast('.') ?: "sample"
                    result[gridIndex] = name
                }
                Log.d("EP133APP", "readGroupPadState(${group.name}): ${result.size} occupied pads")
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EP133APP", "readGroupPadState(${group.name}) failed", e)
                emptyMap()
            }
        }
    }

    /**
     * Resolve the /sounds directory node ID. Thin delegation to
     * [FileTransferClient.resolveSoundsNodeId] — retained on the root (protected open) for
     * source-compatibility with any external subclass, but note that [putSampleFile] now calls
     * FTC's own internal `resolveSoundsNodeId`, not this root method (D-01): a subclass
     * overriding THIS method no longer affects `putSampleFile`'s behavior — override FTC's
     * `resolveSoundsNodeId` instead (test doubles construct/subclass [FileTransferClient]
     * directly per the 999.5 CONTEXT).
     */
    protected open suspend fun resolveSoundsNodeId(): Int? = ftc.resolveSoundsNodeId()

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
        // The timeout constants (GET_INIT/GET_PAGE/PUT_ACK/FILE_LIST/METADATA/FILE_INIT/
        // FORCE_CLOSE) moved into FileTransferClient (999.5 Plan 01) along with the file-op
        // primitives that used them. The reqId-space constants below are kept here (not just
        // on FTC) because production tests reference them as `MIDIRepository.PUT_INIT_REQUEST_ID`
        // / `FILE_REQ_ID_MIN` / `FILE_REQ_ID_MAX` — both classes' counters share the same
        // numeric space by construction (FTC's private counter uses identical bounds).

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
