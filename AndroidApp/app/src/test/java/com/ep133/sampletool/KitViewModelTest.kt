package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.kit.KitItemState
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kit.DEFAULT_SLICE_COUNT
import com.ep133.sampletool.ui.kit.MAX_SLICES
import com.ep133.sampletool.ui.kit.PAD_FILL_ORDER
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
 * Records all [putSampleFile] calls as [PutCall] and all [assignSampleToPad] calls as [AssignCall]
 * so tests can assert on count, order, and per-call arguments.
 *
 * [putSampleFile] returns a synthetic nodeId (the call index, 1-based) when connected,
 * null when disconnected — matching the MIDIRepository contract.
 */
private class KitFakeMIDIRepo(
    spy: KitSpyMIDIPort,
    connected: Boolean = false,
) : MIDIRepository(spy) {

    data class PutCall(
        val name: String,
        val pcmBytes: ByteArray,
        val channels: Int,
        val sampleRate: Int,
    )

    data class AssignCall(
        val group: PadChannel,
        val gridIndex: Int,
        val sampleNodeId: Int,
        val sampleStart: Int,
        val sampleEnd: Int,
        val muteGroup: Boolean,
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
    val putCalls = mutableListOf<PutCall>()
    val assignCalls = mutableListOf<AssignCall>()

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        putCalls.add(PutCall(name, pcmBytes.copyOf(), channels, sampleRate))
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
        muteGroup: Boolean,
    ): Boolean {
        assignCalls.add(AssignCall(group, gridIndex, sampleNodeId, sampleStart, sampleEnd, muteGroup))
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
     * Total byte count = frames * channels * 2.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Chop mode — per-slice upload contract
    // ─────────────────────────────────────────────────────────────────────────

    // ── putSampleFile called N times (once per slice, not once total) ─────────

    @Test
    fun chop_putSampleFileCalled_nTimesPerSlice() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())

        val frames = 1000
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals(
            "putSampleFile must be called once per slice ($sliceCount times total)",
            sliceCount,
            repo.putCalls.size,
        )
    }

    // ── assignSampleToPad called N times with full trim (0, sliceFrameCount) ──

    @Test
    fun chop_assignCalledN_timesWithFullTrim() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())

        val frames = 1000
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals("assignSampleToPad must be called $sliceCount times", sliceCount, repo.assignCalls.size)

        // Each slice uses full trim: start=0, end=sliceFrameCount.
        // Slice i covers frames [ i*frames/N .. (i+1)*frames/N ).
        for (i in 0 until sliceCount) {
            val call = repo.assignCalls[i]
            val sliceFrameCount = ((i + 1).toLong() * frames / sliceCount).toInt() -
                (i.toLong() * frames / sliceCount).toInt()
            // Pads fill in EP-133 numeric order (. 0 ENT 1-9), so slice i lands on PAD_FILL_ORDER[i].
            assertEquals("slice $i: gridIndex", PAD_FILL_ORDER[i], call.gridIndex)
            // nodeId is the call-index (1-based) — slice 0 is the first put call → nodeId 1.
            assertEquals("slice $i: sampleNodeId", i + 1, call.sampleNodeId)
            assertEquals("slice $i: sampleStart must be 0 (full trim on slice)", 0, call.sampleStart)
            assertEquals("slice $i: sampleEnd must equal sliceFrameCount", sliceFrameCount, call.sampleEnd)
        }
    }

    // ── Per-group state is independent and persists across group switches ──────

    @Test
    fun perGroup_stateIsIndependentAndPersists() = runTest {
        val (_, vm) = makeVm(connected = true)

        // Group A: chop 3 slices.
        vm.onGroupChange(PadChannel.A)
        vm.onSliceCountChange("3")
        vm.chopFromPcm("a.wav", pcm(600))
        advanceUntilIdle()
        assertEquals("A has 3 items", 3, vm.items.value.size)

        // Switch to B — a clean slate with its own defaults.
        vm.onGroupChange(PadChannel.B)
        advanceUntilIdle()
        assertEquals("B starts empty", 0, vm.items.value.size)
        assertEquals("B keeps its own default slice count", DEFAULT_SLICE_COUNT.toString(), vm.sliceCountText.value)

        // B: chop 5 slices.
        vm.onSliceCountChange("5")
        vm.chopFromPcm("b.wav", pcm(1000))
        advanceUntilIdle()
        assertEquals("B has 5 items", 5, vm.items.value.size)

        // Back to A — A's state persisted.
        vm.onGroupChange(PadChannel.A)
        advanceUntilIdle()
        assertEquals("A still has its 3 items", 3, vm.items.value.size)
        assertEquals("A's slice count persisted", "3", vm.sliceCountText.value)
    }

    // ── Choke group (sound.mutegroup) reaches assignSampleToPad ────────────────

    @Test
    fun chop_chokeGroup_defaultsOn_writesMuteGroupTrue() = runTest {
        val (repo, vm) = makeVm(connected = true)
        vm.onSliceCountChange("4")
        vm.chopFromPcm("loop.wav", pcm(1000))
        advanceUntilIdle()

        assertEquals("assignSampleToPad called per slice", 4, repo.assignCalls.size)
        assertTrue("choke group defaults on → every pad muteGroup=true", repo.assignCalls.all { it.muteGroup })
    }

    @Test
    fun chop_chokeGroupOff_writesMuteGroupFalse() = runTest {
        val (repo, vm) = makeVm(connected = true)
        vm.onChokeGroupChange(false)
        vm.onSliceCountChange("4")
        vm.chopFromPcm("loop.wav", pcm(1000))
        advanceUntilIdle()

        assertEquals("assignSampleToPad called per slice", 4, repo.assignCalls.size)
        assertTrue("choke off → no pad has muteGroup=true", repo.assignCalls.none { it.muteGroup })
    }

    // ── Per-slice byte content tiles exactly with no dropped samples ───────────

    @Test
    fun chop_sliceByteContent_tilesExactly() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 3
        vm.onSliceCountChange(sliceCount.toString())

        val frames = 10          // 10 frames mono → 20 bytes
        val pcmData = pcm(frames)
        vm.chopFromPcm("loop.wav", pcmData)

        advanceUntilIdle()

        assertEquals("putSampleFile called $sliceCount times", sliceCount, repo.putCalls.size)

        // Stepped boundaries: i*10/3, (i+1)*10/3 — same formula as LoopSlicer.slicePcmBytes.
        val bytesPerFrame = 2   // mono, 2 bytes/frame
        for (i in 0 until sliceCount) {
            val startFrame = (i.toLong() * frames / sliceCount).toInt()
            val endFrame   = ((i + 1).toLong() * frames / sliceCount).toInt()
            val expectedBytes = pcmData.copyOfRange(startFrame * bytesPerFrame, endFrame * bytesPerFrame)
            assertArrayEquals(
                "slice $i PCM content must match stepped frame range [$startFrame..$endFrame)",
                expectedBytes,
                repo.putCalls[i].pcmBytes,
            )
        }

        // Slices must tile exactly — concatenation must equal the original PCM.
        val reassembled = repo.putCalls.flatMap { it.pcmBytes.toList() }.toByteArray()
        assertArrayEquals("concatenated slices must equal original PCM", pcmData, reassembled)
    }

    // ── All N rows reach Done state ───────────────────────────────────────────

    @Test
    fun chop_allItemsDone_whenConnected() = runTest {
        val (_, vm) = makeVm(connected = true)

        val sliceCount = 4
        vm.onSliceCountChange(sliceCount.toString())
        vm.chopFromPcm("loop.wav", pcm(1000))

        advanceUntilIdle()

        val items = vm.items.value
        // N rows (one per slice) — no separate upload row.
        assertEquals(sliceCount, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' should be Done; got ${item.state}", KitItemState.Done, item.state)
        }
    }

    // ── Byte-budget guard: STEREO slice over 20 s → rejected, no device writes ──

    @Test
    fun chop_byteBudgetGuard_stereoOver20s_rejected() = runTest {
        val (repo, vm) = makeVm(connected = true)

        // MAX_SAMPLE_BYTES = 3_750_000 = 20s stereo @ 46875 (937500 frames * 2ch * 2bytes).
        // One slice, stereo, just over the byte budget: 937501 frames * 2ch * 2bytes = 3_750_004 bytes.
        val sliceCount = 1
        vm.onSliceCountChange(sliceCount.toString())

        val sampleRate = 46875
        val channels = 2
        val frames = 20 * sampleRate + 1   // 937501 frames → 3_750_004 bytes stereo

        vm.chopFromPcm("bigstereo.wav", pcm(frames, channels), channels, sampleRate)

        advanceUntilIdle()

        // No device writes — the byte-budget guard must fire before any upload.
        assertEquals("putSampleFile must NOT be called when byte-budget guard fires", 0, repo.putCalls.size)
        assertEquals("assignSampleToPad must NOT be called when byte-budget guard fires", 0, repo.assignCalls.size)

        // All rows must be in Error state.
        val items = vm.items.value
        assertEquals("items list must have $sliceCount rows", sliceCount, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' must be Error; got ${item.state}", KitItemState.Error, item.state)
        }
    }

    // ── Byte-budget guard: MONO slice 20–40 s → allowed (upload proceeds) ──────

    @Test
    fun chop_byteBudgetGuard_mono20to40s_allowed() = runTest {
        val (repo, vm) = makeVm(connected = true)

        // A flat "frames > 20*sampleRate" guard would WRONGLY reject this mono slice.
        // 20s mono = 937500 frames = 1_875_000 bytes; 40s mono = 1_875_000 frames = 3_750_000 bytes.
        // Pick ~30s mono: 1_400_000 frames * 1ch * 2bytes = 2_800_000 bytes < MAX_SAMPLE_BYTES (3_750_000).
        val sliceCount = 1
        vm.onSliceCountChange(sliceCount.toString())

        val sampleRate = 46875
        val channels = 1
        val frames = 1_400_000   // ~29.9s mono → 2_800_000 bytes, under the byte budget

        vm.chopFromPcm("longmono.wav", pcm(frames, channels), channels, sampleRate)

        advanceUntilIdle()

        // Upload must proceed — the mono slice is under the byte budget despite being >20 s.
        assertEquals("putSampleFile must be called once (mono 20–40s is allowed)", sliceCount, repo.putCalls.size)
        assertEquals("assignSampleToPad must be called once", sliceCount, repo.assignCalls.size)
        assertEquals("slice byte size", frames * channels * 2, repo.putCalls[0].pcmBytes.size)

        // Row must reach Done.
        val items = vm.items.value
        assertEquals(sliceCount, items.size)
        items.forEach { item ->
            assertEquals("Item '${item.label}' should be Done; got ${item.state}", KitItemState.Done, item.state)
        }
    }

    // ── Disconnected device: first item is Error, no device writes ─────────────

    @Test
    fun chop_uploadRowError_whenDisconnected() = runTest {
        val (repo, vm) = makeVm(connected = false)

        vm.onSliceCountChange("4")
        vm.chopFromPcm("loop.wav", pcm(1000))

        advanceUntilIdle()

        val items = vm.items.value
        assertFalse("items must not be empty after chop", items.isEmpty())
        // First slice row must be Error (putSampleFile returns null → disconnected path).
        assertEquals("first slice row must be Error when disconnected", KitItemState.Error, items[0].state)
    }

    // ── Group selection is forwarded to assignSampleToPad ─────────────────────

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

    // ── sliceCount clamped to MAX_SLICES ──────────────────────────────────────

    @Test
    fun chop_sliceCountClampedToMaxSlices() = runTest {
        val (repo, vm) = makeVm(connected = true)

        // Request more than MAX_SLICES — resolvedSliceCount() must clamp to MAX_SLICES.
        vm.onSliceCountChange("99")
        assertEquals("resolvedSliceCount() must clamp to $MAX_SLICES", MAX_SLICES, vm.resolvedSliceCount())

        val frames = 4800
        vm.chopFromPcm("loop.wav", pcm(frames))

        advanceUntilIdle()

        assertEquals("Exactly MAX_SLICES put calls", MAX_SLICES, repo.putCalls.size)
        assertEquals("Exactly MAX_SLICES assign calls", MAX_SLICES, repo.assignCalls.size)
    }

    // ── Stereo PCM: frame count derived correctly, slice bytes are stereo ──────

    @Test
    fun chop_stereoFrameCount_derivedCorrectly() = runTest {
        val (repo, vm) = makeVm(connected = true)

        val sliceCount = 2
        vm.onSliceCountChange(sliceCount.toString())

        // 800 frames stereo → 800 * 2 channels * 2 bytes = 3200 bytes.
        val frames = 800
        val channels = 2
        val pcmData = pcm(frames, channels)
        vm.chopFromPcm("loop.wav", pcmData, channels)

        advanceUntilIdle()

        assertEquals(sliceCount, repo.assignCalls.size)
        val bytesPerFrame = channels * 2

        val call0 = repo.assignCalls[0]
        val call1 = repo.assignCalls[1]
        // Full trim: start=0, end=sliceFrameCount.
        // slice 0: frames [0..400) → 400 frames
        assertEquals("stereo chop slice 0 start", 0, call0.sampleStart)
        assertEquals("stereo chop slice 0 end", frames / sliceCount, call0.sampleEnd)
        // slice 1: frames [400..800) → 400 frames
        assertEquals("stereo chop slice 1 start", 0, call1.sampleStart)
        assertEquals("stereo chop slice 1 end", frames / sliceCount, call1.sampleEnd)

        // Byte lengths: each slice should be sliceFrameCount * channels * 2 bytes.
        val expectedSliceBytes = (frames / sliceCount) * bytesPerFrame
        assertEquals("stereo slice 0 byte count", expectedSliceBytes, repo.putCalls[0].pcmBytes.size)
        assertEquals("stereo slice 1 byte count", expectedSliceBytes, repo.putCalls[1].pcmBytes.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kit mode — unchanged contract
    // ─────────────────────────────────────────────────────────────────────────

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
        // File i lands on pad PAD_FILL_ORDER[i] (. 0 ENT 1-9 order); concurrent launches may
        // arrive out-of-order, so correlate each call by its expected gridIndex, not call order.
        framesList.forEachIndexed { i, frames ->
            val expectedGrid = PAD_FILL_ORDER[i]
            val call = repo.assignCalls.first { it.gridIndex == expectedGrid }
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
}
