package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.voice.KotlinSynthVoice
import com.ep133.sampletool.domain.audio.voice.RenderableVoice
import com.ep133.sampletool.domain.midi.ChordBakeManager
import com.ep133.sampletool.domain.midi.ChordBakeProgress
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.domain.model.Progressions
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// ── Test doubles ──────────────────────────────────────────────────────────────

private class BakeSpyMIDIPort : MIDIPort {
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

/**
 * Scripted repository fake for bake tests: records putSampleFile calls, returns a
 * canned nodeId (or null), and can be told to hang the upload forever so tests can
 * cancel mid-upload.
 */
private class BakeFakeMIDIRepo(
    connected: Boolean = true,
    storageUsed: Long? = null,
    storageTotal: Long? = null,
    private val putResult: Int? = 7,
    private val hangUpload: Boolean = false,
) : MIDIRepository(BakeSpyMIDIPort()) {

    data class PutCall(val name: String, val pcmBytes: ByteArray, val channels: Int, val sampleRate: Int)

    private val _state = MutableStateFlow(
        DeviceState(
            connected = connected,
            outputPortId = if (connected) "out" else null,
            storageUsedBytes = storageUsed,
            storageTotalBytes = storageTotal,
        ),
    )
    override val deviceState get() = _state

    val putCalls = mutableListOf<PutCall>()
    var uploadCancelled = false

    override suspend fun putSampleFile(
        name: String,
        pcmBytes: ByteArray,
        channels: Int,
        sampleRate: Int,
    ): Int? {
        putCalls.add(PutCall(name, pcmBytes.copyOf(), channels, sampleRate))
        if (hangUpload) {
            try {
                awaitCancellation()
            } catch (e: Throwable) {
                uploadCancelled = true
                throw e
            }
        }
        return putResult
    }
}

/** Canned voice: returns [buffer] and records calls; no synth involved. */
private class CannedVoice(private val buffer: FloatArray) : RenderableVoice {
    var renderCalls = 0
    override fun render(chords: List<List<Int>>, bpm: Int, sampleRate: Int, velocity: Int): FloatArray {
        renderCalls++
        return buffer
    }
}

private class ThrowingVoice : RenderableVoice {
    override fun render(chords: List<List<Int>>, bpm: Int, sampleRate: Int, velocity: Int): FloatArray =
        throw IllegalStateException("boom")
}

private val ONE_CHORD = listOf(listOf(60, 64, 67))

// ── Tests ─────────────────────────────────────────────────────────────────────

class ChordBakeManagerTest {

    // A. Happy path: Rendering → Uploading → Done, mono 46875 raw PCM upload.
    @Test
    fun `bake success emits staged progress and uploads mono device-rate pcm`() = runTest {
        val repo = BakeFakeMIDIRepo()
        val voice = CannedVoice(FloatArray(1000) { 0.5f })
        val events = ChordBakeManager(repo).bake("My Prog", ONE_CHORD, 120, voice).toList()

        assertEquals(
            listOf(
                ChordBakeProgress.Rendering,
                ChordBakeProgress.Uploading,
                ChordBakeProgress.Done("My Prog.wav"),
            ),
            events,
        )
        assertEquals(1, voice.renderCalls)
        val put = repo.putCalls.single()
        assertEquals("My Prog.wav", put.name)
        assertEquals(1, put.channels)
        assertEquals(46875, put.sampleRate)
        assertEquals(2000, put.pcmBytes.size) // 1000 samples * 2 bytes, no RIFF header
    }

    // B. Device guard: no connection → Error, nothing rendered or uploaded.
    @Test
    fun `bake without device emits error and never renders`() = runTest {
        val repo = BakeFakeMIDIRepo(connected = false)
        val voice = CannedVoice(FloatArray(10))
        val events = ChordBakeManager(repo).bake("x", ONE_CHORD, 120, voice).toList()

        assertEquals(listOf(ChordBakeProgress.Error("No EP-133 connected")), events)
        assertEquals(0, voice.renderCalls)
        assertTrue(repo.putCalls.isEmpty())
    }

    // C. Duration pre-flight: 8 bars at 60 BPM = 32 s > 20 s → blocked before render.
    @Test
    fun `bake over 20s device ceiling is blocked before rendering`() = runTest {
        val repo = BakeFakeMIDIRepo()
        val voice = CannedVoice(FloatArray(10))
        val eightBars = List(8) { listOf(60, 64, 67) }
        val events = ChordBakeManager(repo).bake("long", eightBars, 60, voice).toList()

        assertEquals(1, events.size)
        val error = events.single() as ChordBakeProgress.Error
        assertTrue(error.message, error.message.contains("Too long to bake"))
        assertEquals(0, voice.renderCalls)
        assertTrue(repo.putCalls.isEmpty())
    }

    @Test
    fun `estimateDurationSec includes the release tail`() {
        // 4 bars at 120 BPM = 8.0 s of bars + 0.25 s tail.
        assertEquals(
            8.0 + KotlinSynthVoice.RELEASE_TAIL_SECONDS,
            ChordBakeManager.estimateDurationSec(4, 120),
            1e-6,
        )
    }

    // D. Storage pre-flight: not enough free /sounds space → Error, no upload.
    @Test
    fun `bake blocked when rendered pcm exceeds live free space`() = runTest {
        // 1000 float samples → 2000 PCM bytes; only 1999 bytes free.
        val repo = BakeFakeMIDIRepo(storageUsed = 8_001, storageTotal = 10_000)
        val voice = CannedVoice(FloatArray(1000) { 0.1f })
        val events = ChordBakeManager(repo).bake("full", ONE_CHORD, 120, voice).toList()

        assertTrue(events.last() is ChordBakeProgress.Error)
        assertTrue((events.last() as ChordBakeProgress.Error).message.contains("Not enough space"))
        assertTrue(repo.putCalls.isEmpty())
    }

    // E. Cancellation mid-upload: rethrown, flow ends without Done, upload unwound.
    @Test
    fun `cancel mid-upload propagates cancellation and never emits Done`() = runTest {
        val repo = BakeFakeMIDIRepo(hangUpload = true)
        val voice = CannedVoice(FloatArray(100) { 0.2f })
        val seen = mutableListOf<ChordBakeProgress>()

        val job = launch {
            ChordBakeManager(repo).bake("cxl", ONE_CHORD, 120, voice).collect { seen.add(it) }
        }
        // The render hops to the real Default dispatcher, so alternate advancing the
        // test scheduler with a real-time sleep until the upload is actually in flight.
        val deadline = System.currentTimeMillis() + 5_000
        while (repo.putCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            testScheduler.advanceUntilIdle()
            Thread.sleep(1)
        }
        assertEquals(listOf<ChordBakeProgress>(ChordBakeProgress.Rendering, ChordBakeProgress.Uploading), seen)
        assertEquals(1, repo.putCalls.size)

        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(repo.uploadCancelled)
        assertFalse(seen.any { it is ChordBakeProgress.Done })
    }

    // F. Upload not confirmed (null nodeId) → Error.
    @Test
    fun `null nodeId from putSampleFile emits not-confirmed error`() = runTest {
        val repo = BakeFakeMIDIRepo(putResult = null)
        val events = ChordBakeManager(repo).bake("nc", ONE_CHORD, 120, CannedVoice(FloatArray(10))).toList()
        assertTrue((events.last() as ChordBakeProgress.Error).message.contains("not confirmed"))
    }

    // G. Render failure → Error, no upload.
    @Test
    fun `render exception becomes Error and skips upload`() = runTest {
        val repo = BakeFakeMIDIRepo()
        val events = ChordBakeManager(repo).bake("boom", ONE_CHORD, 120, ThrowingVoice()).toList()
        assertTrue((events.last() as ChordBakeProgress.Error).message.contains("Render failed"))
        assertTrue(repo.putCalls.isEmpty())
    }

    // ── Peak guard ────────────────────────────────────────────────────────────

    @Test
    fun `peak guard normalizes an over-full-scale buffer down to 0 dBFS`() {
        val manager = ChordBakeManager(BakeFakeMIDIRepo())
        val hot = floatArrayOf(0.5f, -1.6f, 1.2f)
        val out = manager.normalizeIfOverFullScale(hot, "hot.wav")
        val peak = out.maxOf { abs(it) }
        assertEquals(1.0f, peak, 1e-6f)
        // Relative shape preserved: everything scaled by 1/1.6.
        assertEquals(0.5f / 1.6f, out[0], 1e-6f)
    }

    @Test
    fun `peak guard leaves an in-range buffer untouched`() {
        val manager = ChordBakeManager(BakeFakeMIDIRepo())
        val ok = floatArrayOf(0.1f, -0.9f, 1.0f)
        assertSame(ok, manager.normalizeIfOverFullScale(ok, "ok.wav"))
    }

    @Test
    fun `floatToPcm16Le maps full scale and silence exactly`() {
        val manager = ChordBakeManager(BakeFakeMIDIRepo())
        val bytes = manager.floatToPcm16Le(floatArrayOf(1f, -1f, 0f))
        fun s16(i: Int) = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
        assertEquals(Short.MAX_VALUE, s16(0))
        assertEquals((-Short.MAX_VALUE).toShort(), s16(2))
        assertEquals(0.toShort(), s16(4))
    }

    /**
     * Golden-buffer gain safety: render the densest chord in the preset library with
     * the Kotlin synth replica (bit-matched to the native voice loop) and assert the
     * post-tanh output never exceeds 0 dBFS.
     */
    @Test
    fun `densest preset chord renders within full scale`() {
        var densest: List<Int> = emptyList()
        for (prog in Progressions.ALL) {
            for (degree in prog.degrees) {
                val notes = RenderableVoice.chordOf(degree, "C")
                if (notes.size > densest.size) densest = notes
            }
        }
        assertTrue("expected a chord in the preset library", densest.isNotEmpty())

        val pcm = KotlinSynthVoice().render(listOf(densest), bpm = 120)
        val peak = pcm.maxOf { abs(it) }
        assertTrue("densest chord peaked at $peak (> 1.0)", peak <= 1.0f)
        assertTrue("render produced silence", peak > 0f)
    }

    // Sanitization shared with import: companion form matches the instance contract.
    @Test
    fun `bake sanitizes the progression name before upload`() = runTest {
        val repo = BakeFakeMIDIRepo()
        val events = ChordBakeManager(repo)
            .bake("Näughty/Prog!", ONE_CHORD, 120, CannedVoice(FloatArray(10) { 0.1f }))
            .toList()
        assertTrue(events.last() is ChordBakeProgress.Done)
        // Path component stripped, '!' replaced then trimmed - same contract as import.
        assertEquals("Prog.wav", repo.putCalls.single().name)
        assertNull(repo.putCalls.single().name.find { it == '/' || it == '!' })
    }
}
