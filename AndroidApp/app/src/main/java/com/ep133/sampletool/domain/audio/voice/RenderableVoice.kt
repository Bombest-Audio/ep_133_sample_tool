package com.ep133.sampletool.domain.audio.voice

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.resolveChordMidiNotes

/**
 * The voice seam for Phase 8: anything that can turn a chord sequence into PCM.
 *
 * Contract: the audio heard in live preview and the audio baked to a sample
 * must come from the SAME voice code path. Implementations either drive the
 * live engine's own render loop offline ([NativeSynthVoice]) or are themselves
 * the single playback path ([SampledInstrumentVoice]).
 */
interface RenderableVoice {

    /**
     * Render [chords] (each a list of MIDI notes, played back to back, one bar
     * per chord at [bpm]) to mono float PCM in [-1, 1] at [sampleRate].
     * A release tail is appended after the last chord so nothing is cut off.
     */
    fun render(
        chords: List<List<Int>>,
        bpm: Int,
        sampleRate: Int = DEVICE_SAMPLE_RATE,
        velocity: Int = 90,
    ): FloatArray

    companion object {
        /** EP-133 K.O. II native sample rate - the bake target. */
        const val DEVICE_SAMPLE_RATE = 46875

        /** Frames in one 4/4 bar at [bpm] and [sampleRate]. */
        fun framesPerBar(bpm: Int, sampleRate: Int): Int {
            require(bpm > 0) { "bpm must be > 0, was $bpm" }
            return ((60.0 / bpm) * 4 * sampleRate).toInt()
        }

        /** Resolve a progression to per-chord MIDI note lists, mirroring ChordPlayer. */
        fun chordsOf(
            progression: ChordProgression,
            keyRoot: String,
            octave: Int = 4,
        ): List<List<Int>> = progression.degrees.map { resolveChordMidiNotes(it, keyRoot, octave) }

        /** Resolve a single chord degree, mirroring ChordPlayer.playChord. */
        fun chordOf(degree: ChordDegree, keyRoot: String, octave: Int = 4): List<Int> =
            resolveChordMidiNotes(degree, keyRoot, octave)
    }
}
