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
import kotlinx.coroutines.CompletableDeferred
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
                // FILE_GET chunk response — emit to fileChunks for BackupManager
                val path = ""  // path tracking handled by BackupManager
                repositoryScope.launch {
                    _fileChunks.emit(path to payload)
                }
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

        // Step 3: FILE_LIST on /sounds (count samples)
        val fileListDeferred = CompletableDeferred<Int>()
        pendingFileListCountDeferred = fileListDeferred
        val listFrame = SysExProtocol.buildFileListFrame(currentDeviceId, "/sounds", requestId = 3)
        midiManager.sendMidi(portId, listFrame)
        val sampleCount = withTimeoutOrNull(5_000) { fileListDeferred.await() } ?: 0
        _deviceState.value = _deviceState.value.copy(sampleCount = sampleCount)

        return true
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
}
