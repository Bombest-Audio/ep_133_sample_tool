package com.ep133.sampletool.domain.midi

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.resolveChordMidiNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Plays chords and progressions via MIDI.
 *
 * Routes through [MIDIRepository] when an EP-133 is connected, or falls back to
 * [LocalSynth] (phone speakers) when offline. Callers do not need to check
 * connection state — routing is handled internally.
 */
class ChordPlayer(
    private val midi: MIDIRepository,
    private val localSynth: SynthEngine = LocalSynth(),
) {

    private var currentNotes: List<Int> = emptyList()

    // Snapshot which backend played the current notes so stopCurrentChord() always
    // sends to the same backend, even if connection state changes mid-chord.
    private var playedViaHardware = false

    private val isConnected: Boolean get() = midi.deviceState.value.connected

    fun playChord(degree: ChordDegree, keyRoot: String, velocity: Int = 90, octave: Int = 4) {
        stopCurrentChord()
        val notes = resolveChordMidiNotes(degree, keyRoot, octave)
        currentNotes = notes
        playedViaHardware = isConnected
        if (playedViaHardware) {
            notes.forEach { midi.noteOn(it, velocity) }
        } else {
            notes.forEach { localSynth.noteOn(it, velocity) }
        }
    }

    fun stopCurrentChord() {
        if (playedViaHardware) {
            currentNotes.forEach { midi.noteOff(it) }
        } else {
            localSynth.allNotesOff()
        }
        currentNotes = emptyList()
    }

    suspend fun playProgression(
        progression: ChordProgression,
        keyRoot: String,
        bpm: Int,
        loop: Boolean = false,
        onStep: (Int) -> Unit,
    ) {
        val msPerBar = (60_000.0 / bpm) * 4
        try {
            do {
                progression.degrees.forEachIndexed { index, degree ->
                    onStep(index)
                    playChord(degree, keyRoot)
                    delay(msPerBar.toLong())
                    stopCurrentChord()
                }
            } while (loop)
            onStep(-1)
        } catch (e: CancellationException) {
            stopCurrentChord()
            onStep(-1)
            throw e
        }
    }

    /** Release resources. Call when ViewModel is cleared. */
    fun close() {
        stopCurrentChord()
        localSynth.close()
    }
}
