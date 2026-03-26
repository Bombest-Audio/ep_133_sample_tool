package com.ep133.sampletool.domain.sequencer

import com.ep133.sampletool.domain.midi.MIDIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Sequencer state exposed to the UI. */
data class SeqState(
    val bpm: Int = 120,
    val playing: Boolean = false,
    val currentStep: Int = -1,
    val tracks: List<SeqTrack> = DEFAULT_TRACKS,
    val selectedTrack: Int = 0,
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
    private val scope = CoroutineScope(Dispatchers.Default)

    fun play() {
        if (_state.value.playing) return
        _state.value = _state.value.copy(playing = true, currentStep = -1)
        playJob = scope.launch { playLoop() }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        midi.allNotesOff()
        _state.value = _state.value.copy(playing = false)
    }

    fun stop() {
        pause()
        _state.value = _state.value.copy(currentStep = -1)
    }

    fun toggleStep(trackIndex: Int, stepIndex: Int) {
        val tracks = _state.value.tracks.toMutableList()
        val track = tracks[trackIndex]
        val steps = track.steps.copyOf()
        steps[stepIndex] = if (steps[stepIndex] > 0) 0 else 1
        tracks[trackIndex] = track.copy(steps = steps)
        _state.value = _state.value.copy(tracks = tracks)
    }

    fun setBpm(bpm: Int) {
        _state.value = _state.value.copy(bpm = bpm.coerceIn(40, 300))
    }

    fun adjustBpm(delta: Int) {
        setBpm(_state.value.bpm + delta)
    }

    fun selectTrack(index: Int) {
        _state.value = _state.value.copy(
            selectedTrack = index.coerceIn(0, _state.value.tracks.lastIndex)
        )
    }

    fun clearTrack(trackIndex: Int) {
        val tracks = _state.value.tracks.toMutableList()
        tracks[trackIndex] = tracks[trackIndex].copy(steps = IntArray(STEP_COUNT) { 0 })
        _state.value = _state.value.copy(tracks = tracks)
    }

    fun clearAll() {
        val tracks = _state.value.tracks.map { it.copy(steps = IntArray(STEP_COUNT) { 0 }) }
        _state.value = _state.value.copy(tracks = tracks)
    }

    private suspend fun playLoop() {
        val startTime = System.nanoTime()
        var stepCount = 0L

        try {
            while (true) {
                val currentState = _state.value
                val step = (stepCount % STEP_COUNT).toInt()
                _state.value = currentState.copy(currentStep = step)

                // Fire notes for active steps
                currentState.tracks.forEach { track ->
                    if (track.steps[step] > 0) {
                        midi.noteOn(track.note, track.velocity, track.channel)
                    }
                }

                // Schedule note-off at 80% of step duration
                val stepDurationMs = 60_000.0 / currentState.bpm / 4.0
                val noteOffDelay = (stepDurationMs * 0.8).toLong()
                scope.launch {
                    delay(noteOffDelay)
                    currentState.tracks.forEach { track ->
                        if (track.steps[step] > 0) {
                            midi.noteOff(track.note, track.channel)
                        }
                    }
                }

                stepCount++

                // Drift-compensated delay — compare elapsed vs expected time
                val expectedNanos = startTime + (stepCount * stepDurationMs * 1_000_000).toLong()
                val sleepNanos = expectedNanos - System.nanoTime()
                if (sleepNanos > 0) {
                    delay(sleepNanos / 1_000_000)
                }
            }
        } catch (_: CancellationException) {
            // Normal stop
        }
    }
}
