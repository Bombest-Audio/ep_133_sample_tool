package com.ep133.sampletool.domain.sequencer

import com.ep133.sampletool.domain.midi.MIDIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One track in the sequencer. */
data class SeqTrack(
    val name: String,
    val note: Int,
    val channel: Int = 0,
    val velocity: Int = 100,
    val steps: IntArray = IntArray(STEP_COUNT) { 0 },
) {
    companion object {
        const val STEP_COUNT = 16
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeqTrack) return false
        return name == other.name && note == other.note && steps.contentEquals(other.steps)
    }

    override fun hashCode(): Int = name.hashCode() * 31 + steps.contentHashCode()
}

/** EDIT = user-programmed sequencer, LIVE = capturing incoming MIDI from EP-133. */
enum class BeatsMode { EDIT, LIVE }

/** Sequencer state exposed to the UI. */
data class SeqState(
    val bpm: Int = 120,
    val playing: Boolean = false,
    val currentStep: Int = -1,
    val tracks: List<SeqTrack> = DEFAULT_TRACKS,
    val selectedTrack: Int = 0,
    val mode: BeatsMode = BeatsMode.EDIT,
    /** LIVE mode: maps MIDI note → set of step indices where that note was seen. */
    val liveGrid: Map<Int, Set<Int>> = emptyMap(),
    /** LIVE mode: current step position of the capture clock. */
    val liveCurrentStep: Int = -1,
) {
    companion object {
        val DEFAULT_TRACKS = listOf(
            SeqTrack("KICK", note = 36, velocity = 100),
            SeqTrack("SNARE", note = 40, velocity = 100),
            SeqTrack("HI-HAT", note = 43, velocity = 80),
            SeqTrack("CLAP", note = 41, velocity = 90),
        )
    }
}

private const val STEP_COUNT = 16

/**
 * Coroutine-based step sequencer engine with drift-compensated timing.
 *
 * Fires MIDI notes via [MIDIRepository] on each step. Timing runs on
 * [Dispatchers.Default] for maximum precision — MIDI sends are thread-safe.
 */
class SequencerEngine(private val midi: MIDIRepository) {

    private val _state = MutableStateFlow(SeqState())
    val state: StateFlow<SeqState> = _state.asStateFlow()

    private var playJob: Job? = null
    private var liveJob: Job? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    // ── EDIT mode ───────────────────────────────────────

    fun play() {
        if (_state.value.playing) return
        _state.update { it.copy(playing = true, currentStep = -1) }
        // MIDI Start (0xFA) — notify connected devices that sequence playback begins (D-22)
        midi.sendRawBytes(byteArrayOf(0xFA.toByte()))
        playJob = scope.launch { playLoop() }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        midi.allNotesOff()
        // MIDI Stop (0xFC) — notify connected devices that playback has stopped (D-22)
        midi.sendRawBytes(byteArrayOf(0xFC.toByte()))
        _state.update { it.copy(playing = false) }
    }

    fun stop() {
        pause()
        _state.update { it.copy(currentStep = -1) }
    }

    fun toggleStep(trackIndex: Int, stepIndex: Int) {
        _state.update { s ->
            val tracks = s.tracks.toMutableList()
            val track = tracks[trackIndex]
            val steps = track.steps.copyOf()
            steps[stepIndex] = if (steps[stepIndex] > 0) 0 else 1
            tracks[trackIndex] = track.copy(steps = steps)
            s.copy(tracks = tracks)
        }
    }

    fun setBpm(bpm: Int) {
        _state.update { it.copy(bpm = bpm.coerceIn(40, 300)) }
    }

    fun adjustBpm(delta: Int) = setBpm(_state.value.bpm + delta)

    fun selectTrack(index: Int) {
        _state.update { it.copy(selectedTrack = index.coerceIn(0, it.tracks.lastIndex)) }
    }

    fun clearTrack(trackIndex: Int) {
        _state.update { s ->
            val tracks = s.tracks.toMutableList()
            tracks[trackIndex] = tracks[trackIndex].copy(steps = IntArray(STEP_COUNT) { 0 })
            s.copy(tracks = tracks)
        }
    }

    fun clearAll() {
        _state.update { s ->
            s.copy(tracks = s.tracks.map { it.copy(steps = IntArray(STEP_COUNT) { 0 }) })
        }
    }

    // ── LIVE mode ───────────────────────────────────────

    fun setMode(mode: BeatsMode) {
        if (mode == BeatsMode.EDIT) stopLiveCapture()
        _state.update { it.copy(mode = mode) }
    }

    fun startLiveCapture() {
        stopLiveCapture()
        _state.update { it.copy(mode = BeatsMode.LIVE, liveGrid = emptyMap(), liveCurrentStep = 0) }
        liveJob = scope.launch { liveCaptureLoop() }
    }

    fun stopLiveCapture() {
        liveJob?.cancel()
        liveJob = null
        _state.update { it.copy(liveCurrentStep = -1) }
    }

    /** Called from ViewModel when incoming MIDI note arrives. */
    fun recordIncomingNote(note: Int) {
        _state.update { s ->
            if (s.mode != BeatsMode.LIVE || s.liveCurrentStep < 0) return@update s
            val grid = s.liveGrid.toMutableMap()
            val existing = grid[note]?.toMutableSet() ?: mutableSetOf()
            existing.add(s.liveCurrentStep)
            grid[note] = existing
            s.copy(liveGrid = grid)
        }
    }

    fun clearLiveGrid() {
        _state.update { it.copy(liveGrid = emptyMap()) }
    }

    /** Cancel all running coroutines. Call from Activity.onDestroy() to prevent leaks. */
    fun close() { job.cancel() }

    // ── Internal loops ──────────────────────────────────

    private suspend fun playLoop() = coroutineScope {
        // Launch note-off and clock-tick coroutines as structured children of this loop
        // (not the engine-lifetime `scope`) so cancelling playJob in pause() also cancels
        // them — otherwise stray MIDI clock (0xF8) keeps firing after the Stop (0xFC).
        val startTime = System.nanoTime()
        var stepCount = 0L

        try {
            while (true) {
                val currentState = _state.value
                val step = (stepCount % STEP_COUNT).toInt()
                _state.update { it.copy(currentStep = step) }

                val stepDurationMs = 60_000.0 / currentState.bpm / 4.0

                // MIDI Clock (0xF8): send first tick immediately with noteOn, then 5 more spaced
                // across the step. 24 PPQN / 4 steps-per-beat = 6 clocks per step (D-22).
                midi.sendRawBytes(byteArrayOf(0xF8.toByte()))

                // Fire notes for active steps
                currentState.tracks.forEach { track ->
                    if (track.steps[step] > 0) {
                        midi.noteOn(track.note, track.velocity, track.channel)
                    }
                }

                // Schedule note-off at 80% of step duration
                val noteOffDelay = (stepDurationMs * 0.8).toLong()
                launch {
                    delay(noteOffDelay)
                    currentState.tracks.forEach { track ->
                        if (track.steps[step] > 0) {
                            midi.noteOff(track.note, track.channel)
                        }
                    }
                }

                // Send remaining 5 clock ticks spaced evenly across the step (D-22)
                val clockIntervalMs = (stepDurationMs / 6.0).toLong().coerceAtLeast(1L)
                launch {
                    repeat(5) {
                        delay(clockIntervalMs * (it + 1))
                        midi.sendRawBytes(byteArrayOf(0xF8.toByte()))
                    }
                }

                stepCount++

                // Drift-compensated delay
                val expectedNanos = startTime + (stepCount * stepDurationMs * 1_000_000).toLong()
                val sleepNanos = expectedNanos - System.nanoTime()
                if (sleepNanos > 0) {
                    delay(sleepNanos / 1_000_000)
                }
            }
        } catch (_: CancellationException) {}
    }

    private suspend fun liveCaptureLoop() {
        val startTime = System.nanoTime()
        var stepCount = 0L

        try {
            while (true) {
                val step = (stepCount % STEP_COUNT).toInt()
                _state.update { it.copy(liveCurrentStep = step) }
                stepCount++

                val stepDurationMs = 60_000.0 / _state.value.bpm / 4.0
                val expectedNanos = startTime + (stepCount * stepDurationMs * 1_000_000).toLong()
                val sleepNanos = expectedNanos - System.nanoTime()
                if (sleepNanos > 0) delay(sleepNanos / 1_000_000)
            }
        } catch (_: CancellationException) {}
    }
}
