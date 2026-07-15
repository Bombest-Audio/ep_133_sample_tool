package com.ep133.sampletool

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordQuality
import com.ep133.sampletool.domain.model.noteNameToMidi
import com.ep133.sampletool.domain.model.resolveChordMidiNotes
import com.ep133.sampletool.domain.model.resolveChordName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chord-to-MIDI resolution, focused on key-root name parsing. The Chords screen offers
 * flat spellings (Eb, Ab, Bb) that must resolve to their enharmonic sharp pitch class,
 * not silently fall back to C.
 */
class ChordResolutionTest {

    @Test
    fun noteNameToMidi_sharpNames_resolveChromatically() {
        // C3 = 48 with octave = 3 (12 * (octave + 1))
        assertEquals(48, noteNameToMidi("C"))
        assertEquals(49, noteNameToMidi("C#"))
        assertEquals(55, noteNameToMidi("G"))
        assertEquals(59, noteNameToMidi("B"))
    }

    @Test
    fun noteNameToMidi_flatNames_resolveToEnharmonicEquivalent() {
        assertEquals(noteNameToMidi("C#"), noteNameToMidi("Db"))
        assertEquals(noteNameToMidi("D#"), noteNameToMidi("Eb"))
        assertEquals(noteNameToMidi("F#"), noteNameToMidi("Gb"))
        assertEquals(noteNameToMidi("G#"), noteNameToMidi("Ab"))
        assertEquals(noteNameToMidi("A#"), noteNameToMidi("Bb"))
    }

    @Test
    fun noteNameToMidi_flatNames_areNotTheCFallback() {
        // Regression: flats used to miss NOTE_NAMES lookup and return the C fallback (60).
        listOf("Eb", "Ab", "Bb").forEach { name ->
            val midi = noteNameToMidi(name)
            assertEquals("$name must not fall back to C", false, midi == 60)
        }
    }

    @Test
    fun noteNameToMidi_unknownName_fallsBackToMiddleC() {
        assertEquals(60, noteNameToMidi("H"))
    }

    @Test
    fun resolveChordMidiNotes_flatKey_buildsChordOnCorrectRoot() {
        val tonic = ChordDegree("I", 0, ChordQuality.MAJOR)
        // Eb major at octave 3: root Eb3 = 51 -> 51, 55, 58
        assertEquals(listOf(51, 55, 58), resolveChordMidiNotes(tonic, "Eb"))
    }

    @Test
    fun resolveChordName_flatKey_usesEnharmonicRootName() {
        val fourth = ChordDegree("IV", 5, ChordQuality.MAJOR)
        // IV of Eb is Ab, spelled with the sharp-based note table as G#
        assertEquals("G#", resolveChordName(fourth, "Eb"))
    }
}
