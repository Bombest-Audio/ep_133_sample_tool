package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BatchImportItem
import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles (named KbPrep* to avoid top-level redeclaration clashes across
// the shared test source set).
// ─────────────────────────────────────────────────────────────────────────────

private class KbPrepSpyPort : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null
    override fun getUSBDevices() = MIDIPort.Devices(
        inputs = listOf(MIDIPort.Device("in", "EP-133")),
        outputs = listOf(MIDIPort.Device("out", "EP-133")),
    )
    override fun sendMidi(portId: String, data: ByteArray) {}
    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/** Fake repo that records the exact PCM bytes + channel count each upload delivered. */
private class KbPrepFakeRepo : MIDIRepository(KbPrepSpyPort()) {

    private val _state = MutableStateFlow(DeviceState(connected = true, outputPortId = "out"))
    override val deviceState get() = _state

    init { _deviceState.value = _state.value }

    data class Upload(val name: String, val pcm: ByteArray, val channels: Int)
    val uploads = mutableListOf<Upload>()

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        uploads += Upload(name, pcmBytes.copyOf(), channels)
        return 42
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests - prep toggles applied through the importPack convert seam
// ─────────────────────────────────────────────────────────────────────────────

class KitBuilderPrepTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(repo: KbPrepFakeRepo) = KitBuilderViewModel(repo, SampleImportManager(repo))

    private val items = listOf(BatchImportItem("kick.wav"))

    /** Stereo source with a quiet peak and silent edges - every prep step has work to do. */
    private fun source() = ConvertedSample(
        pcm = s16le(0, 0, 4000, 2000, 0, 0),   // frames: (0,0) (4000,2000) (0,0)
        channels = 2,
        sampleRate = 46875,
    )

    @Test
    fun prepDefaultsOff_uploadIsByteIdenticalToConversion() = runTest {
        val repo = KbPrepFakeRepo()
        val vm = vm(repo)

        vm.importPack(items) { source() }
        advanceUntilIdle()

        assertEquals(1, repo.uploads.size)
        assertArrayEquals(source().pcm, repo.uploads[0].pcm)
        assertEquals(2, repo.uploads[0].channels)
    }

    @Test
    fun prepTogglesOn_uploadIsTrimmedMonoAndNormalized() = runTest {
        val repo = KbPrepFakeRepo()
        val vm = vm(repo)
        vm.onTogglePrepNormalize()
        vm.onTogglePrepTrimSilence()
        vm.onTogglePrepMono()
        advanceUntilIdle()
        assertTrue(vm.state.value.prep.enabled)

        vm.importPack(items) { source() }
        advanceUntilIdle()

        assertEquals(1, repo.uploads.size)
        val up = repo.uploads[0]
        assertEquals(1, up.channels)          // downmixed
        // Trim (default 5 ms padding covers the whole 3-frame clip at 46875 Hz, so all
        // 3 frames survive) then mono (3 samples) - 6 bytes.
        assertEquals(6, up.pcm.size)
        // Peak frame (4000,2000) downmixes to 3000, then normalizes to ~-0.3 dBFS.
        val peak = (0 until 3).maxOf { abs(sampleAt(up.pcm, it)) }
        assertTrue("peak was $peak", peak in 31600..32767)
    }

    @Test
    fun prepToggle_roundTripsOffAgain() = runTest {
        val vm = vm(KbPrepFakeRepo())
        vm.onTogglePrepNormalize()
        advanceUntilIdle()
        assertTrue(vm.state.value.prep.normalize)
        vm.onTogglePrepNormalize()
        advanceUntilIdle()
        assertTrue(!vm.state.value.prep.enabled)
    }
}
