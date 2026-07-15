package com.ep133.sampletool

import com.ep133.sampletool.domain.generate.ProgressionGenerator
import com.ep133.sampletool.domain.generate.VoiceLeading
import com.ep133.sampletool.domain.model.Vibe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLeadingTest {

    // ── movementCost ──────────────────────────────────────────────────────────

    @Test
    fun movementCost_identicalVoicings_isZero() {
        assertEquals(0, VoiceLeading.movementCost(listOf(60, 64, 67), listOf(60, 64, 67)))
    }

    @Test
    fun movementCost_sumsPairedVoiceDeltas() {
        // 60->62 (2) + 64->65 (1) + 67->69 (2)
        assertEquals(5, VoiceLeading.movementCost(listOf(60, 64, 67), listOf(62, 65, 69)))
    }

    @Test
    fun movementCost_isOrderIndependent() {
        assertEquals(
            VoiceLeading.movementCost(listOf(67, 60, 64), listOf(69, 62, 65)),
            VoiceLeading.movementCost(listOf(60, 64, 67), listOf(62, 65, 69)),
        )
    }

    @Test
    fun movementCost_chargesUnpairedVoices() {
        // Triad to seventh chord: the extra note costs its distance to the
        // nearest note of the smaller chord (70 -> 67 = 3).
        assertEquals(3, VoiceLeading.movementCost(listOf(60, 64, 67), listOf(60, 64, 67, 70)))
    }

    // ── hasCrossing ───────────────────────────────────────────────────────────

    @Test
    fun hasCrossing_detectsUnsortedAndDoubledVoices() {
        assertFalse(VoiceLeading.hasCrossing(listOf(60, 64, 67)))
        assertTrue(VoiceLeading.hasCrossing(listOf(64, 60, 67)))
        assertTrue(VoiceLeading.hasCrossing(listOf(60, 60, 67)))
    }

    // ── voiceLead ─────────────────────────────────────────────────────────────

    @Test
    fun voiceLead_producesOneVoicingPerChord() {
        val prog = ProgressionGenerator.generate("C", Vibe.JAZZ, 8, seed = 5L)
        val voicings = VoiceLeading.voiceLead(prog, "C")
        assertEquals(prog.degrees.size, voicings.size)
    }

    @Test
    fun voiceLead_neverCrossesVoices() {
        for (vibe in Vibe.entries) {
            for (seed in 0L until 10L) {
                val prog = ProgressionGenerator.generate("G", vibe, 8, seed)
                VoiceLeading.voiceLead(prog, "G").forEach { voicing ->
                    assertFalse(
                        "$vibe seed $seed crossed voicing $voicing",
                        VoiceLeading.hasCrossing(voicing),
                    )
                }
            }
        }
    }

    @Test
    fun voiceLead_costAtMostNaiveRootPositionBaseline() {
        for (vibe in Vibe.entries) {
            for (seed in 0L until 10L) {
                val prog = ProgressionGenerator.generate("C", vibe, 8, seed)
                val led = VoiceLeading.totalCost(VoiceLeading.voiceLead(prog, "C"))
                val naive = VoiceLeading.totalCost(
                    prog.degrees.map { VoiceLeading.rootPosition(it, "C") },
                )
                assertTrue(
                    "$vibe seed $seed voice-led cost $led exceeds naive $naive",
                    led <= naive,
                )
            }
        }
    }

    @Test
    fun voiceLead_beatsNaiveBaselineSomewhere() {
        // Across a spread of seeds the voicing pass must actually help, not
        // just tie the baseline.
        var improved = false
        for (seed in 0L until 20L) {
            val prog = ProgressionGenerator.generate("C", Vibe.JAZZ, 8, seed)
            val led = VoiceLeading.totalCost(VoiceLeading.voiceLead(prog, "C"))
            val naive = VoiceLeading.totalCost(
                prog.degrees.map { VoiceLeading.rootPosition(it, "C") },
            )
            if (led < naive) improved = true
        }
        assertTrue(improved)
    }

    @Test
    fun candidateVoicings_stayInPlayableRange() {
        val prog = ProgressionGenerator.generate("B", Vibe.SOULFUL, 8, seed = 9L)
        prog.degrees.forEach { degree ->
            VoiceLeading.candidateVoicings(degree, "B").forEach { voicing ->
                assertTrue(voicing.first() >= 40)
                assertTrue(voicing.last() <= 88)
            }
        }
    }
}
