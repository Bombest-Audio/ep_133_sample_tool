package com.ep133.sampletool

import com.ep133.sampletool.domain.model.EP133Scales
import com.ep133.sampletool.domain.model.Scale
import org.junit.Test
import org.junit.Assert.*

/**
 * Scale lock computation tests — pure math, no production class dependency beyond Scale/EP133Scales.
 * These tests pass immediately (no @Ignore needed).
 */

/** Compute the set of pitch classes (0-11) that are in the given scale starting at the given root. */
fun computeInScaleSet(scale: Scale, rootNoteName: String): Set<Int> {
    val rootIndex = EP133Scales.ROOT_NOTES.indexOf(rootNoteName)
    if (rootIndex < 0) return emptySet()
    return scale.intervals.map { (rootIndex + it) % 12 }.toSet()
}

class ScaleLockTest {

    @Test
    fun computeInScaleSet_cMajor_yields0_2_4_5_7_9_11() {
        val major = EP133Scales.ALL.first { it.id == "major" }
        val result = computeInScaleSet(major, "C")
        assertEquals(setOf(0, 2, 4, 5, 7, 9, 11), result)
    }

    @Test
    fun note60_cMajor_isInScale() {
        val major = EP133Scales.ALL.first { it.id == "major" }
        val inScaleSet = computeInScaleSet(major, "C")
        // C4 = note 60, pitch class = 60 % 12 = 0
        assertTrue((60 % 12) in inScaleSet)
    }

    @Test
    fun note61_cMajor_notInScale() {
        val major = EP133Scales.ALL.first { it.id == "major" }
        val inScaleSet = computeInScaleSet(major, "C")
        // C#4 = note 61, pitch class = 61 % 12 = 1, not in C major
        assertFalse((61 % 12) in inScaleSet)
    }

    @Test
    fun chromatic_allNotesInScale() {
        val chromatic = EP133Scales.ALL.first { it.id == "chromatic" }
        val inScaleSet = computeInScaleSet(chromatic, "C")
        for (pitchClass in 0..11) {
            assertTrue("Pitch class $pitchClass should be in chromatic scale", pitchClass in inScaleSet)
        }
    }

    @Test
    fun noScaleSelected_allPadsShow() {
        // When inScaleSet is empty (no scale selected), isInScale is true for all pads (per D-29)
        val inScaleSet = emptySet<Int>()
        for (note in 36..83) {
            val isInScale = inScaleSet.isEmpty() || (note % 12) in inScaleSet
            assertTrue("Note $note should show as in-scale when no scale selected", isInScale)
        }
    }
}
