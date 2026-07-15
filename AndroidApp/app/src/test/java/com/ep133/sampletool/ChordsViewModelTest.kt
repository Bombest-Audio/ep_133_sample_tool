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
import com.ep133.sampletool.domain.model.Vibe
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.chords.ChordsViewModel
import kotlinx.coroutines.Dispatchers
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

/**
 * MIDIRepository with controllable device state for testing. Seeds the protected
 * [_deviceState] backing flow directly rather than overriding [deviceState], matching
 * the convention in the androidTest doubles.
 */
private class FakeMIDIRepo(
    val port: SpyMIDIPort,
    initialConnected: Boolean = false,
) : MIDIRepository(port) {

    constructor(initialConnected: Boolean = false) : this(SpyMIDIPort(initialConnected), initialConnected)

    init {
        _deviceState.value = DeviceState(
            connected = initialConnected,
            outputPortId = if (initialConnected) "out" else null,
        )
    }

    fun setConnected(connected: Boolean) {
        _deviceState.value = DeviceState(
            connected = connected,
            outputPortId = if (connected) "out" else null,
        )
    }
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

    // ── chord-map note ownership ──────────────────────────────────────────────

    @Test
    fun chordMap_staleNoteOff_doesNotKillNewerChord() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)
        vm.programToGroup(PadChannel.A)
        testDispatcher.scheduler.advanceUntilIdle()

        val base = PadChannel.A.baseNote
        fun midiIn(vararg bytes: Int) {
            repo.port.onMidiReceived?.invoke("in", bytes.map { it.toByte() }.toByteArray())
            testDispatcher.scheduler.runCurrent()
        }

        midiIn(0x90, base, 100)     // press pad 0 -> chord I sounds
        midiIn(0x90, base + 1, 100) // press pad 1 -> chord IV replaces it
        val sentAfterSecondPress = repo.port.sent.size

        midiIn(0x80, base, 0)       // release pad 0: stale, must be ignored
        assertEquals(
            "Releasing a superseded pad must not stop the newer chord",
            sentAfterSecondPress, repo.port.sent.size,
        )

        midiIn(0x80, base + 1, 0)   // release the owning pad: chord stops
        assertTrue(repo.port.sent.size > sentAfterSecondPress)
    }

    // ── selectProgression cleanup ─────────────────────────────────────────────

    @Test
    fun selectProgression_cancelsActiveChordMap() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)
        vm.programToGroup(PadChannel.A)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(PadChannel.A, vm.chordMapGroup.value)

        // Switching progressions must tear down the chord-map session
        vm.selectProgression(null)
        assertNull(vm.chordMapGroup.value)
    }

    @Test
    fun selectProgression_stopsPreviewChord() = runTest {
        val synth = RecordingSynth()
        val repo = FakeMIDIRepo() // offline, so preview routes to the local synth
        val vm = ChordsViewModel(
            chordPlayer = ChordPlayer(midi = repo, localSynth = synth),
            midiRepo = repo,
            ioDispatcher = testDispatcher,
        )
        vm.previewChord(SIMPLE_PROGRESSION.degrees.first())
        assertTrue(synth.notesOn > 0)

        vm.selectProgression(SIMPLE_PROGRESSION)
        assertTrue("Preview notes must be released on selection change", synth.allOffCalls > 0)
    }

    // ── sound selection over MIDI ─────────────────────────────────────────────

    @Test
    fun selectSound_above128_sendsBankSelectBeforeProgramChange() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)

        // Sound #200: index 199 -> bank MSB 1, program 71
        vm.selectSound(EP133Sound(number = 200, name = "HIGH PAD", category = "melodic"))

        val bytes = repo.port.sent.last()
        // CC0 (Bank Select MSB) = 1, CC32 (LSB) = 0, then PC 71 - in that order
        assertEquals(0xB0.toByte(), bytes[0]); assertEquals(0x00.toByte(), bytes[1]); assertEquals(0x01.toByte(), bytes[2])
        assertEquals(0xB0.toByte(), bytes[3]); assertEquals(0x20.toByte(), bytes[4]); assertEquals(0x00.toByte(), bytes[5])
        assertEquals(0xC0.toByte(), bytes[6]); assertEquals(71.toByte(), bytes[7])
    }

    @Test
    fun selectSound_below128_sendsBankZero() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)

        vm.selectSound(FAKE_SOUND) // #42 -> bank 0, program 41

        val bytes = repo.port.sent.last()
        assertEquals(0x00.toByte(), bytes[2])   // bank MSB 0
        assertEquals(41.toByte(), bytes[7])     // program 41
    }

    // ── playback completion ───────────────────────────────────────────────────

    @Test
    fun playProgression_naturalCompletion_clearsPlayingProgressionId() = runTest {
        val vm = makeVm()

        vm.playProgression(SIMPLE_PROGRESSION)
        assertEquals(SIMPLE_PROGRESSION.id, vm.playingProgressionId.value)

        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.playingProgressionId.value)
        assertFalse(vm.isPlaying.value)
        assertEquals(-1, vm.playingStep.value)
    }

    // ── pad-load sweep cancellation ───────────────────────────────────────────

    @Test
    fun cancelChordMap_stopsPendingPadLoads() = runTest {
        val repo = FakeMIDIRepo(initialConnected = true)
        val vm = makeVm(repo = repo)
        vm.selectSound(FAKE_SOUND)
        vm.selectProgression(SIMPLE_PROGRESSION)
        val sentBeforeProgram = repo.port.sent.size

        vm.programToGroup(PadChannel.A)
        // Advance far enough for a couple of staggered pad loads, then cancel mid-sweep.
        testDispatcher.scheduler.advanceTimeBy(65)
        testDispatcher.scheduler.runCurrent()
        vm.cancelChordMap()
        val sentAtCancel = repo.port.sent.size
        assertTrue("Expected some pad loads before cancel", sentAtCancel > sentBeforeProgram)
        assertTrue("Sweep should not have finished", sentAtCancel < sentBeforeProgram + 12)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("No pad loads after cancel", sentAtCancel, repo.port.sent.size)
    }

    // ── generateProgression ───────────────────────────────────────────────────

    @Test
    fun generateProgression_selectsGeneratedProgression() = runTest {
        val vm = makeVm()
        vm.adjustGeneratorBars(2) // 4 -> 6
        vm.generateProgression(seed = 99L)

        val selected = vm.selectedProgression.value
        assertEquals(6, selected?.degrees?.size)
        assertTrue(selected!!.id.startsWith("gen-"))
    }

    @Test
    fun generateProgression_sameSeed_isDeterministic() = runTest {
        val vm = makeVm()
        vm.generateProgression(seed = 7L)
        val first = vm.selectedProgression.value

        vm.selectProgression(null)
        vm.generateProgression(seed = 7L)
        val second = vm.selectedProgression.value

        assertEquals(first?.degrees, second?.degrees)
    }

    @Test
    fun generateProgression_usesSelectedVibe() = runTest {
        val vm = makeVm()
        vm.toggleVibe(Vibe.BLUES)
        vm.generateProgression(seed = 1L)

        assertEquals(setOf(Vibe.BLUES), vm.selectedProgression.value?.vibes)
    }

    @Test
    fun adjustGeneratorBars_clampsToRange() = runTest {
        val vm = makeVm()
        vm.adjustGeneratorBars(-100)
        assertEquals(2, vm.generatorBars.value)
        vm.adjustGeneratorBars(100)
        assertEquals(16, vm.generatorBars.value)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeVm(repo: FakeMIDIRepo = FakeMIDIRepo()): ChordsViewModel {
        val chordPlayer = ChordPlayer(midi = repo, localSynth = NoOpSynth())
        // Inject the test dispatcher as ioDispatcher so the staggered pad-load sweep
        // runs on virtual time and is controllable from the scheduler.
        return ChordsViewModel(chordPlayer = chordPlayer, midiRepo = repo, ioDispatcher = testDispatcher)
    }
}

/** SynthEngine spy counting note events - avoids AudioTrack in unit tests. */
private class RecordingSynth : SynthEngine {
    var notesOn = 0
    var allOffCalls = 0
    override fun noteOn(note: Int, velocity: Int) { notesOn++ }
    override fun noteOff(note: Int) {}
    override fun allNotesOff() { allOffCalls++ }
    override fun close() {}
}

/** SynthEngine stub that does nothing - avoids AudioTrack in unit tests. */
private class NoOpSynth : SynthEngine {
    override fun noteOn(note: Int, velocity: Int) {}
    override fun noteOff(note: Int) {}
    override fun allNotesOff() {}
    override fun close() {}
}
