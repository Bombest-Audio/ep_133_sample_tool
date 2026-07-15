package com.ep133.sampletool.domain.generate

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.noteNameToMidi
import kotlin.math.abs

/**
 * Voice-leading pass over a chord progression.
 *
 * For every chord after the first, candidate voicings (all inversions,
 * shifted across neighboring octaves) are scored against the previous
 * chord's voicing with [movementCost]; the cheapest candidate wins.
 * Voicings are kept sorted ascending with no duplicate pitches, so voices
 * never cross within a chord.
 *
 * Pure Kotlin, no Android dependencies.
 */
object VoiceLeading {

    private const val LOW_LIMIT = 40  // E2
    private const val HIGH_LIMIT = 88 // E6

    /**
     * Total movement between two voicings: voices are paired low-to-high and
     * the absolute semitone deltas summed. When the chords have different
     * sizes, each unpaired note costs its distance to the nearest note of the
     * other chord, so growing or shrinking the chord is not free.
     */
    fun movementCost(from: List<Int>, to: List<Int>): Int {
        val a = from.sorted()
        val b = to.sorted()
        val paired = minOf(a.size, b.size)
        var cost = 0
        for (i in 0 until paired) cost += abs(a[i] - b[i])
        val (longer, shorter) = if (a.size > b.size) a to b else b to a
        for (i in paired until longer.size) {
            cost += shorter.minOf { abs(longer[i] - it) }
        }
        return cost
    }

    /** True when the voicing is not strictly ascending (crossed or doubled voices). */
    fun hasCrossing(voicing: List<Int>): Boolean =
        voicing.zipWithNext().any { (lo, hi) -> lo >= hi }

    /** Root-position voicing of [degree], used as the baseline and the first chord. */
    fun rootPosition(degree: ChordDegree, keyRoot: String, octave: Int = 3): List<Int> {
        val root = noteNameToMidi(keyRoot, octave) + degree.semitones
        return degree.quality.intervals.map { root + it }
    }

    /**
     * All candidate voicings of [degree]: every inversion, transposed to the
     * octaves around [octave], filtered to the playable range and to strictly
     * ascending note sets.
     */
    fun candidateVoicings(degree: ChordDegree, keyRoot: String, octave: Int = 3): List<List<Int>> {
        val base = rootPosition(degree, keyRoot, octave)
        val candidates = mutableListOf<List<Int>>()
        for (inversion in base.indices) {
            // Move the lowest [inversion] notes up an octave.
            val inverted = base.mapIndexed { i, note ->
                if (i < inversion) note + 12 else note
            }.sorted()
            for (shift in -1..1) {
                val voicing = inverted.map { it + 12 * shift }
                if (voicing.first() >= LOW_LIMIT && voicing.last() <= HIGH_LIMIT &&
                    !hasCrossing(voicing)
                ) {
                    candidates += voicing
                }
            }
        }
        return candidates
    }

    /**
     * Voice-lead [progression] in [keyRoot]: returns one MIDI voicing per
     * chord. The first chord is root position; each later chord greedily
     * minimizes [movementCost] from its predecessor.
     */
    fun voiceLead(progression: ChordProgression, keyRoot: String, octave: Int = 3): List<List<Int>> {
        val voicings = mutableListOf<List<Int>>()
        for (degree in progression.degrees) {
            val previous = voicings.lastOrNull()
            if (previous == null) {
                voicings += rootPosition(degree, keyRoot, octave)
                continue
            }
            val best = candidateVoicings(degree, keyRoot, octave)
                .minByOrNull { movementCost(previous, it) }
                ?: rootPosition(degree, keyRoot, octave)
            voicings += best
        }
        return voicings
    }

    /** Total [movementCost] across consecutive voicings. */
    fun totalCost(voicings: List<List<Int>>): Int =
        voicings.zipWithNext().sumOf { (a, b) -> movementCost(a, b) }
}
