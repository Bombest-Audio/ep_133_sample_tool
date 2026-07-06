package com.ep133.sampletool.support

import com.ep133.sampletool.NoOpMIDIPort
import com.ep133.sampletool.TestMIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.midi.MIDIPort

/**
 * Scriptable fake MIDIRepository for E2E-ish UI tests.
 *
 * Extends the inert [TestMIDIRepository] with canned answers per seam, following the
 * hand-rolled Fake/Spy convention used across the unit suite (no mocking framework):
 * - [scriptedSlots] backs `listProjects()` for the Projects screen.
 * - [putSampleScript] backs `putSampleFile()` so import tests control the
 *   Loading → Done | Error sequence (suspend inside the script to hold the UI in Loading).
 * - [statsQuerySucceeds] backs `queryDeviceStats()`; the stats themselves come from the
 *   [DeviceState] passed at construction (the real query mutates state, the fake pre-bakes it).
 * - [scriptedActiveGroupIndex] backs `getActiveGroupIndex()` for device→app group sync.
 *
 * Outgoing channel-voice messages go through the real `noteOn`/`noteOff` code path into
 * [port], a recording MIDIPort — so "pad press dispatches MIDI" UX assertions check the
 * frames that would actually reach the device.
 */
class ScriptedMIDIRepository(
    initialState: DeviceState = DeviceState(),
    val port: RecordingMIDIPort = RecordingMIDIPort(),
) : TestMIDIRepository(initialState, port) {

    var scriptedSlots: List<ProjectSlot> = emptyList()
    var statsQuerySucceeds: Boolean = false
    var scriptedActiveGroupIndex: Int? = null

    /** Script for putSampleFile: return a nodeId = Done, null = not-confirmed, throw = Error. */
    var putSampleScript: suspend (name: String) -> Int? = { 1 }

    /** Names passed to putSampleFile, in call order. */
    val putSampleNames = mutableListOf<String>()

    /** Group indices the app tried to sync to the device. */
    val setActiveGroupCalls = mutableListOf<Int>()

    /** Script for assignSampleToPad: true = device ack'd, false = rejected, throw = exception path. */
    var assignSampleScript: suspend (
        group: PadChannel, gridIndex: Int, sampleNodeId: Int, sampleStart: Int, sampleEnd: Int, muteGroup: Boolean,
    ) -> Boolean = { _, _, _, _, _, _ -> true }

    /** Calls recorded in order, for assertions mirroring putSampleNames. */
    data class AssignCall(
        val group: PadChannel, val gridIndex: Int, val sampleNodeId: Int,
        val sampleStart: Int, val sampleEnd: Int, val muteGroup: Boolean,
    )
    val assignCalls = mutableListOf<AssignCall>()

    override suspend fun listProjects(): List<ProjectSlot> = scriptedSlots

    override suspend fun queryDeviceStats(): Boolean = statsQuerySucceeds

    override suspend fun getActiveGroupIndex(): Int? = scriptedActiveGroupIndex

    override suspend fun setActiveGroup(index: Int): Boolean {
        setActiveGroupCalls += index
        return true
    }

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        putSampleNames += name
        return putSampleScript(name)
    }

    override suspend fun assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
        muteGroup: Boolean,
    ): Boolean {
        assignCalls += AssignCall(group, gridIndex, sampleNodeId, sampleStart, sampleEnd, muteGroup)
        return assignSampleScript(group, gridIndex, sampleNodeId, sampleStart, sampleEnd, muteGroup)
    }

    /** (note, velocity) pairs for every 0x9n frame the app sent. */
    fun sentNoteOns(): List<Pair<Int, Int>> = port.sentFrames
        .filter { it.size == 3 && (it[0].toInt() and 0xF0) == 0x90 }
        .map { it[1].toInt() to it[2].toInt() }

    /** Notes for every 0x8n frame the app sent. */
    fun sentNoteOffs(): List<Int> = port.sentFrames
        .filter { it.size == 3 && (it[0].toInt() and 0xF0) == 0x80 }
        .map { it[1].toInt() }
}

/** MIDIPort spy: records every outgoing frame; everything else is a no-op. */
class RecordingMIDIPort : MIDIPort {
    val sentFrames = mutableListOf<ByteArray>()
    private val noOp = NoOpMIDIPort()

    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = noOp.getUSBDevices()
    override fun sendMidi(portId: String, data: ByteArray) {
        sentFrames += data.copyOf()
    }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}
