package com.ep133.sampletool

import com.ep133.sampletool.domain.generate.ProgressionGenerator
import com.ep133.sampletool.domain.model.ChordQuality
import com.ep133.sampletool.domain.model.Vibe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionGeneratorTest {

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    fun generate_honorsBarCount() {
        for (bars in 2..16) {
            val prog = ProgressionGenerator.generate("C", Vibe.JAZZ, bars, seed = 7L)
            assertEquals(bars, prog.degrees.size)
        }
    }

    @Test
    fun generate_coercesBarCountIntoRange() {
        assertEquals(2, ProgressionGenerator.generate("C", Vibe.HAPPY, 0, 1L).degrees.size)
        assertEquals(16, ProgressionGenerator.generate("C", Vibe.HAPPY, 99, 1L).degrees.size)
    }

    @Test
    fun generate_tagsProgressionWithVibe() {
        val prog = ProgressionGenerator.generate("G", Vibe.NEO_SOUL, 4, 3L)
        assertEquals(setOf(Vibe.NEO_SOUL), prog.vibes)
    }

    @Test
    fun everyVibe_hasAGrammar() {
        for (vibe in Vibe.entries) {
            assertTrue("missing grammar for $vibe", vibe in ProgressionGenerator.GRAMMARS)
        }
    }

    @Test
    fun grammars_areInternallyConsistent() {
        for ((vibe, grammar) in ProgressionGenerator.GRAMMARS) {
            assertTrue("$vibe opener not in pool", grammar.opener in grammar.degrees)
            grammar.cadence.forEach {
                assertTrue("$vibe cadence chord $it not in pool", it in grammar.degrees)
            }
            grammar.transitions.forEach { (from, options) ->
                assertTrue("$vibe transition source $from not in pool", from in grammar.degrees)
                options.forEach { option ->
                    assertTrue("$vibe transition target ${option.to} not in pool", option.to in grammar.degrees)
                    assertTrue("$vibe zero weight $from->${option.to}", option.weight > 0)
                }
            }
        }
    }

    // ── Degrees legal for the vibe ────────────────────────────────────────────

    @Test
    fun generate_usesOnlyDegreesFromTheVibePool() {
        for (vibe in Vibe.entries) {
            val pool = ProgressionGenerator.GRAMMARS.getValue(vibe).degrees.values.toSet()
            for (seed in 0L until 20L) {
                val prog = ProgressionGenerator.generate("C", vibe, 8, seed)
                prog.degrees.forEach { degree ->
                    assertTrue("$vibe seed $seed produced illegal degree ${degree.roman}", degree in pool)
                }
            }
        }
    }

    // ── Cadence ───────────────────────────────────────────────────────────────

    @Test
    fun generate_endsOnVibeCadence() {
        for (vibe in Vibe.entries) {
            val cadence = ProgressionGenerator.GRAMMARS.getValue(vibe).cadence
            for (seed in 0L until 10L) {
                val prog = ProgressionGenerator.generate("C", vibe, 8, seed)
                val tail = prog.degrees.takeLast(cadence.size).map { it.roman }
                assertEquals("$vibe seed $seed bad cadence", cadence, tail)
            }
        }
    }

    @Test
    fun bluesCadence_resolvesToTonicDominantSeven() {
        val prog = ProgressionGenerator.generate("A", Vibe.BLUES, 12, 42L)
        val last = prog.degrees.last()
        assertEquals(0, last.semitones)
        assertEquals(ChordQuality.DOM7, last.quality)
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun sameSeed_producesSameProgression() {
        val a = ProgressionGenerator.generate("Eb", Vibe.HIP_HOP, 8, seed = 1234L)
        val b = ProgressionGenerator.generate("Eb", Vibe.HIP_HOP, 8, seed = 1234L)
        assertEquals(a.degrees, b.degrees)
        assertEquals(a.id, b.id)
    }

    @Test
    fun differentSeeds_produceSomeVariation() {
        val progressions = (0L until 10L).map {
            ProgressionGenerator.generate("C", Vibe.JAZZ, 8, seed = it).degrees
        }
        assertTrue(progressions.toSet().size > 1)
    }
}
