package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SynthEngine
import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.ChordQuality
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.EP133Sound
import com.ep133.sampletool.domain.model.PadChannel
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.chords.ChordsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ── Test doubles ──────────────────────────────────────────────────────────────

private class SpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
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

    override fun sendMidi(portId: String, data: ByteArray) { sent.add(data) }
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/** MIDIRepository with controllable device state for testing. */
private class FakeMIDIRepo(initialConnected: Boolean = false) : MIDIRepository(SpyMIDIPort(initialConnected)) {
    private val _state = MutableStateFlow(DeviceState(connected = initialConnected))
    override val deviceState get() = _state

    fun setConnected(connected: Boolean) { _state.value = DeviceState(connected = connected) }
}

private val FAKE_SOUND = EP133Sound(number = 42, name = "RHODES", category = "melodic")

private val SIMPLE_PROGRESSION = ChordProgression(
    id = "test",
    name = "Test Prog",
    degrees = listOf(
        ChordDegree("I",  0, ChordQuality.MAJOR),
        ChordDegree("IV", 5, ChordQuality.MAJOR),
        ChordDegree("V",  7, ChordQuality.MAJOR),
    ),
    vibes = emptySet(),
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class ChordsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp()  { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── selectSound ───────────────────────────────────────────────────────────

    @Test
    fun selectSound_storesSound() = runTest {
        val vm = makeVm()
        vm.selectSound(FAKE_SOUND)
        assertEquals(FAKE_SOUND, vm.selectedSound.value)
    }

    @Test
    fun selectSound_dismissesPicker() = runTest {
        val vm = makeVm()
        vm.openSoundPicker()
        assertTrue(vm.showSoundPicker.value)
        vm.selectSound(FAKE_SOUND)
        assertFalse(vm.showSoundPicker.value)
    }

    @Test
    fun selectSound_null_clearsSelection() = runTest {
        val vm = makeVm()
        vm.selectSound(FAKE_SOUND)
        vm.selectSound(null)
        assertNull(vm.selectedSound.value)
    }

    // ── cancelChordMap ────────────────────────────────────────────────────────

    @Test
    fun cancelChordMap_clearsChordMapGroup() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)

        vm.programToGroup(PadChannel.B)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PadChannel.B, vm.chordMapGroup.value)

        vm.cancelChordMap()
        assertNull(vm.chordMapGroup.value)
    }

    @Test
    fun cancelChordMap_isIdempotent() = runTest {
        val vm = makeVm()
        // Should not throw when called without an active chord map
        vm.cancelChordMap()
        assertNull(vm.chordMapGroup.value)
    }

    // ── programToGroup ────────────────────────────────────────────────────────

    @Test
    fun programToGroup_setsChordMapGroup() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)

        vm.programToGroup(PadChannel.A)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PadChannel.A, vm.chordMapGroup.value)
    }

    @Test
    fun programToGroup_dismissesGroupPicker() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)
        vm.openGroupPicker()
        assertTrue(vm.showGroupPicker.value)

        vm.programToGroup(PadChannel.C)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.showGroupPicker.value)
    }

    @Test
    fun programToGroup_noOp_whenNoSoundSelected() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectProgression(SIMPLE_PROGRESSION)
        // no sound selected

        vm.programToGroup(PadChannel.B)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.chordMapGroup.value)
    }

    @Test
    fun programToGroup_noOp_whenNoProgressionSelected() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        // no progression selected

        vm.programToGroup(PadChannel.B)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.chordMapGroup.value)
    }

    @Test
    fun programToGroup_replacesExistingChordMap() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)

        vm.programToGroup(PadChannel.A)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PadChannel.A, vm.chordMapGroup.value)

        vm.programToGroup(PadChannel.D)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PadChannel.D, vm.chordMapGroup.value)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeVm(repo: FakeMIDIRepo = FakeMIDIRepo()): ChordsViewModel {
        val chordPlayer = ChordPlayer(midi = repo, localSynth = NoOpSynth())
        return ChordsViewModel(chordPlayer = chordPlayer, midiRepo = repo)
    }
}

/** SynthEngine stub that does nothing — avoids AudioTrack in unit tests. */
private class NoOpSynth : SynthEngine {
    override fun noteOn(note: Int, velocity: Int) {}
    override fun noteOff(note: Int) {}
    override fun allNotesOff() {}
    override fun close() {}
}
