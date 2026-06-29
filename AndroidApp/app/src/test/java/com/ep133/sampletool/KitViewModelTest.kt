package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.kit.KitItemState
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kit.MAX_SLICES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles
// ─────────────────────────────────────────────────────────────────────────────

/** Spy MIDIPort for KitViewModel tests. */
private class KitSpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    val sent = mutableListOf<ByteArray>()

    override fun getUSBDevices() = if (connected) {
        MIDIPort.Devices(
            inputs = listOf(MIDIPort.Device("in", "EP-133")),
            outputs = listOf(MIDIPort.Device("out", "EP-133")),
        )
    } else {
        MIDIPort.Devices(emptyList(), emptyList())
    }

    override fun sendMidi(portId: String, data: ByteArray) { sent.add(data.copyOf()) }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake MIDIRepository for KitViewModel tests.
 *
 * Records all [assignSampleToPad] calls as [AssignCall] so tests can assert:
 * - how many times it was called
 * - the exact (group, gridIndex, sampleNodeId, sampleStart, sampleEnd) per call
 *
 * [putSampleFile] returns a synthetic nodeId (the call index, 1-based) when connected,
 * null when disconnected — matching the MIDIRepository contract.
 */
private class KitFakeMIDIRepo(
    spy: KitSpyMIDIPort,
    connected: Boolean = false,
) : MIDIRepository(spy) {

    data class AssignCall(
        val group: PadChannel,
        val gridIndex: Int,
        val sampleNodeId: Int,
        val sampleStart: Int,
        val sampleEnd: Int,
    )

    private val _state = MutableStateFlow(
        if (connected) DeviceState(connected = true, outputPortId = "out")
        else DeviceState(connected = false, outputPortId = null),
    )
    override val deviceState get() = _state

    init {
        _deviceState.value = _state.value
    }

    private var putCallCount = 0
    val putCalls = mutableListOf<String>()       // recorded names
    val assignCalls = mutableListOf<AssignCall>()

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        putCalls.add(name)
        return if (_state.value.connected) {
            ++putCallCount   // returns 1, 2, 3, … (never 0 or null when connected)
        } else {
            null
        }
    }

    override suspend fun assignSampleToPad(
        group: PadChannel,
        gridIndex: Int,
        sampleNodeId: Int,
        sampleStart: Int,
        sampleEnd: Int,
        playmode: String,
    ): Boolean {
        assignCalls.add(AssignCall(group, gridIndex, sampleNodeId, sampleStart, sampleEnd))
        return _state.value.connected
    }
}

// SampleImportManager is not open — we use a real instance.
// chopFromPcm / kitFromPcm only call manager.sanitizeName() (no Android deps), so a real
// SampleImportManager is safe on the JVM test runner.

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unit tests for [KitViewModel].
 *
 * All tests use the SAF-free seams [KitViewModel.chopFromPcm] and
 * [KitViewModel.kitFromPcm] so they run on the JVM without instrumentation.
 */
class KitViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build `frames` PCM frames as a raw s16 LE ByteArray: 2 bytes per frame × 1 channel.
     * Total byte count = frames * 2.
     */
    private fun pcm(frames: Int, channels: Int = 1): ByteArray =
        ByteArray(frames * 2 * channels) { (it % 256).toByte() }

    private fun makeVm(connected: Boolean): Pair<KitFakeMIDIRepo, KitViewModel> {
        val spy = KitSpyMIDIPort(connected)
        val repo = KitFakeMIDIRepo(spy, connected)
        val manager = SampleImportManager(repo)
        val vm = KitViewModel(repo, manager)
        return repo to vm
    }

    // ── Chop mode: putSampleFile called exactly ONCE ───────────────────────────

    @Test
    fun chop_putSampleFileCalled_exactlyOnce() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())

        val frames = 1000
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals("putSampleFile must be called exactly once for chop mode", 1, repo.putCalls.size)
    }

    // ── Chop mode: assignSampleToPad called N times with stepped start/end ────

    @Test
    fun chop_assignCalledN_timesWithSteppedTrim() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())

        val frames = 1000
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals("assignSampleToPad must be called $sliceCount times", sliceCount, repo.assignCalls.size)

        // Verify stepped start/end trim for each slice.
        for (i in 0 until sliceCount) {
            val call = repo.assignCalls[i]
            val expectedStart = (i.toLong() * frames / sliceCount).toInt()
            val expectedEnd   = ((i + 1).toLong() * frames / sliceCount).toInt()
            assertEquals("slice $i: gridIndex", i, call.gridIndex)
            assertEquals("slice $i: sampleNodeId", 1, call.sampleNodeId)  // first putSampleFile returns 1
            assertEquals("slice $i: sampleStart", expectedStart, call.sampleStart)
            assertEquals("slice $i: sampleEnd",   expectedEnd,   call.sampleEnd)
        }
    }

    // ── Chop mode: all items reach Done state ─────────────────────────────────

    @Test
    fun chop_allItemsDone_whenConnected() = runTest {
        val (_, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())
        vm.chopFromPcm("loop.wav", pcm(1000))

        advanceUntilIdle()

        val items = vm.items.value
        // items[0] = upload row; items[1..4] = slice rows
        assertEquals(sliceCount + 1, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' should be Done; got ${item.state}", KitItemState.Done, item.state)
        }
    }

    // ── Chop mode: upload row reaches Error when disconnected ─────────────────

    @Test
    fun chop_uploadRowError_whenDisconnected() = runTest {
        val (_, vm) = makeVm(connected = false)

        vm.onSliceCountChange("4")
        vm.chopFromPcm("loop.wav", pcm(1000))

        advanceUntilIdle()

        val items = vm.items.value
        // First item (upload row) must be Error.
        assertFalse("items must not be empty after chop", items.isEmpty())
        assertEquals("upload row must be Error when disconnected", KitItemState.Error, items[0].state)
    }

    // ── Chop mode: group selection is forwarded to assignSampleToPad ──────────

    @Test
    fun chop_groupSelection_forwardedToAssign() = runTest {
        val (repo, vm) = makeVm(connected = true)

        vm.onGroupChange(PadChannel.C)
        vm.onSliceCountChange("2")
        vm.chopFromPcm("loop.wav", pcm(500))

        advanceUntilIdle()

        assertTrue("At least one assign call expected", repo.assignCalls.isNotEmpty())
        repo.assignCalls.forEach { call ->
            assertEquals("All assign calls must use group C", PadChannel.C, call.group)
        }
    }

    // ── Chop mode: sliceCount clamped to MAX_SLICES ───────────────────────────

    @Test
    fun chop_sliceCountClampedToMaxSlices() = runTest {
        val (repo, vm) = makeVm(connected = true)

        // Request more than MAX_SLICES — resolvedSliceCount() must clamp to MAX_SLICES.
        vm.onSliceCountChange("99")
        assertEquals("resolvedSliceCount() must clamp to $MAX_SLICES", MAX_SLICES, vm.resolvedSliceCount())

        val frames = 4800
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals("Exactly MAX_SLICES assign calls", MAX_SLICES, repo.assignCalls.size)
    }

    // ── Kit mode: putSampleFile called once per file ──────────────────────────

    @Test
    fun kit_putSampleFileCalled_oncePerFile() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val files = listOf(
            Triple("kick.wav",  pcm(300), 1),
            Triple("snare.wav", pcm(300), 1),
            Triple("hihat.wav", pcm(300), 1),
        )
        vm.kitFromPcm(files)

        advanceUntilIdle()

        assertEquals("putSampleFile called once per file", files.size, repo.putCalls.size)
    }

    // ── Kit mode: assignSampleToPad called with full trim (0, frames) ─────────

    @Test
    fun kit_assignCalledWithFullTrim() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val framesList = listOf(200, 400, 600)
        val files = framesList.mapIndexed { i, frames ->
            Triple("s$i.wav", pcm(frames), 1)
        }
        vm.kitFromPcm(files)

        advanceUntilIdle()

        assertEquals("One assign call per file", files.size, repo.assignCalls.size)
        // Sort by gridIndex because concurrent launches may arrive out-of-order.
        val sorted = repo.assignCalls.sortedBy { it.gridIndex }
        sorted.forEachIndexed { i, call ->
            val frames = framesList[i]
            assertEquals("file $i: gridIndex", i, call.gridIndex)
            assertEquals("file $i: sampleStart must be 0 (full trim)", 0, call.sampleStart)
            assertEquals("file $i: sampleEnd must be total frames", frames, call.sampleEnd)
        }
    }

    // ── Kit mode: all items Done when connected ───────────────────────────────

    @Test
    fun kit_allItemsDone_whenConnected() = runTest {
        val (_, vm) = makeVm(connected = true)

        val files = listOf(
            Triple("a.wav", pcm(100), 1),
            Triple("b.wav", pcm(200), 1),
        )
        vm.kitFromPcm(files)

        advanceUntilIdle()

        val items = vm.items.value
        assertEquals(files.size, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' should be Done; got ${item.state}", KitItemState.Done, item.state)
        }
    }

    // ── Kit mode: items reach Error when disconnected ─────────────────────────

    @Test
    fun kit_itemsError_whenDisconnected() = runTest {
        val (_, vm) = makeVm(connected = false)

        val files = listOf(
            Triple("a.wav", pcm(100), 1),
            Triple("b.wav", pcm(200), 1),
        )
        vm.kitFromPcm(files)

        advanceUntilIdle()

        val items = vm.items.value
        assertEquals(files.size, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' should be Error when disconnected; got ${item.state}", KitItemState.Error, item.state)
        }
    }

    // ── Kit mode: files capped to MAX_SLICES ──────────────────────────────────

    @Test
    fun kit_filesCappedToMaxSlices() = runTest {
        val (repo, vm) = makeVm(connected = true)

        // Provide more than MAX_SLICES files.
        val files = (0..MAX_SLICES + 3).map { i -> Triple("s$i.wav", pcm(100), 1) }
        vm.kitFromPcm(files)

        advanceUntilIdle()

        assertEquals("items list capped at MAX_SLICES", MAX_SLICES, vm.items.value.size)
        assertEquals("putSampleFile capped at MAX_SLICES", MAX_SLICES, repo.putCalls.size)
        assertEquals("assignSampleToPad capped at MAX_SLICES", MAX_SLICES, repo.assignCalls.size)
    }

    // ── Chop mode: 2-channel (stereo) PCM frame count ────────────────────────

    @Test
    fun chop_stereoFrameCount_derivedCorrectly() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 2
        vm.onSliceCountChange(sliceCount.toString())

        // 800 frames stereo → 800 * 2 channels * 2 bytes = 3200 bytes.
        // frames = pcm.size / 2 / channels = 3200 / 2 / 2 = 800.
        val frames = 800
        val channels = 2
        vm.chopFromPcm("loop.wav", pcm(frames, channels), channels)

        advanceUntilIdle()

        assertEquals(sliceCount, repo.assignCalls.size)
        val call0 = repo.assignCalls[0]
        val call1 = repo.assignCalls[1]
        // slice 0: start=0, end=400
        assertEquals("stereo chop slice 0 start", 0, call0.sampleStart)
        assertEquals("stereo chop slice 0 end", frames / sliceCount, call0.sampleEnd)
        // slice 1: start=400, end=800
        assertEquals("stereo chop slice 1 start", frames / sliceCount, call1.sampleStart)
        assertEquals("stereo chop slice 1 end", frames, call1.sampleEnd)
    }
}
