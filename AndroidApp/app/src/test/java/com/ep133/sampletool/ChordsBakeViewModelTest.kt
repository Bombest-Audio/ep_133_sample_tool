package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import com.ep133.sampletool.domain.midi.ChordBakeManager
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SynthEngine
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.ChordQuality
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.chords.BakeUiState
import com.ep133.sampletool.ui.chords.ChordsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ── Test doubles ──────────────────────────────────────────────────────────────

private class VmSpyMIDIPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

private class VmFakeMIDIRepo(
    connected: Boolean,
    private val hangUpload: Boolean = false,
) : MIDIRepository(VmSpyMIDIPort()) {

    private val _state = MutableStateFlow(
        DeviceState(connected = connected, outputPortId = if (connected) "out" else null),
    )
    override val deviceState get() = _state

    val putNames = mutableListOf<String>()
    var uploadCancelled = false

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        putNames.add(name)
        if (hangUpload) {
            try {
                awaitCancellation()
            } catch (e: Throwable) {
                uploadCancelled = true
                throw e
            }
        }
        return 1
    }
}

/** Fast deterministic voice so VM tests never spin a real synth render. */
private class VmCannedVoice : RenderableVoice {
    override fun render(chords: List<List<Int>>, bpm: Int, sampleRate: Int, velocity: Int) =
        FloatArray(64) { 0.25f }
}

private class DummySynth : SynthEngine {
    override fun noteOn(midiNote: Int, velocity: Int) {}
    override fun noteOff(midiNote: Int) {}
    override fun allNotesOff() {}
    override fun close() {}
}

private val PROG = ChordProgression(
    id = "bake-test",
    name = "Bake Prog",
    degrees = listOf(ChordDegree("I", 0, ChordQuality.MAJOR)),
    vibes = emptySet(),
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class ChordsBakeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(repo: VmFakeMIDIRepo): ChordsViewModel = ChordsViewModel(
        chordPlayer = ChordPlayer(repo, DummySynth()),
        midiRepo = repo,
        bakeManager = ChordBakeManager(repo),
        bakeVoiceProvider = { VmCannedVoice() },
    )

    /** Alternate advancing the test scheduler with real-time sleeps until [done]. */
    private fun pump(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(1)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun bake_happyPath_reachesDoneAndUploads() = runTest(testDispatcher) {
        val repo = VmFakeMIDIRepo(connected = true)
        val vm = makeVm(repo)
        vm.selectProgression(PROG)

        vm.bakeSelectedProgression()
        assertTrue(vm.bakeState.value is BakeUiState.Running)

        pump { vm.bakeState.value is BakeUiState.Done }

        assertEquals(BakeUiState.Done("Bake Prog.wav"), vm.bakeState.value)
        assertEquals(listOf("Bake Prog.wav"), repo.putNames)

        vm.dismissBakeResult()
        assertEquals(BakeUiState.Idle, vm.bakeState.value)
    }

    @Test
    fun bake_withoutDevice_isNoOp() = runTest(testDispatcher) {
        val repo = VmFakeMIDIRepo(connected = false)
        val vm = makeVm(repo)
        vm.selectProgression(PROG)

        vm.bakeSelectedProgression()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(BakeUiState.Idle, vm.bakeState.value)
        assertTrue(repo.putNames.isEmpty())
    }

    @Test
    fun bake_withoutSelectedProgression_isNoOp() = runTest(testDispatcher) {
        val repo = VmFakeMIDIRepo(connected = true)
        val vm = makeVm(repo)

        vm.bakeSelectedProgression()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(BakeUiState.Idle, vm.bakeState.value)
        assertTrue(repo.putNames.isEmpty())
    }

    @Test
    fun bake_whileRunning_secondCallIsNoOp_andCancelResetsToIdle() = runTest(testDispatcher) {
        val repo = VmFakeMIDIRepo(connected = true, hangUpload = true)
        val vm = makeVm(repo)
        vm.selectProgression(PROG)

        vm.bakeSelectedProgression()
        pump { repo.putNames.isNotEmpty() }
        assertTrue(vm.bakeState.value is BakeUiState.Running)

        // Re-trigger while running: guarded, no second upload starts.
        vm.bakeSelectedProgression()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repo.putNames.size)

        // Cancel: the running flag is reset in finally, upload unwinds cleanly.
        vm.cancelBake()
        pump { vm.bakeState.value is BakeUiState.Idle }
        assertEquals(BakeUiState.Idle, vm.bakeState.value)
        assertTrue(repo.uploadCancelled)
    }
}
