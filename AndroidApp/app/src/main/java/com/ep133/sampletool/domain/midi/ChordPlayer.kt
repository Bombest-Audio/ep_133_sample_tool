package com.ep133.sampletool.domain.midi

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.resolveChordMidiNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class ChordPlayer(private val midi: MIDIRepository) {

    private var currentNotes: List<Int> = emptyList()

    fun playChord(degree: ChordDegree, keyRoot: String, velocity: Int = 90, octave: Int = 3) {
        stopCurrentChord()
        val notes = resolveChordMidiNotes(degree, keyRoot, octave)
        currentNotes = notes
        notes.forEach { midi.noteOn(it, velocity) }
    }

    fun stopCurrentChord() {
        currentNotes.forEach { midi.noteOff(it) }
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
}
