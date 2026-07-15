package com.ep133.sampletool.domain.generate

import com.ep133.sampletool.domain.model.ChordDegree
import com.ep133.sampletool.domain.model.ChordProgression
import com.ep133.sampletool.domain.model.ChordQuality
import com.ep133.sampletool.domain.model.Vibe
import kotlin.random.Random

/**
 * Rules-based chord progression generator.
 *
 * Each [Vibe] gets a grammar: a pool of scale degrees (derived from the preset
 * library in Progressions.ALL plus standard functional harmony), a weighted
 * first-order transition table (tonic -> subdominant -> dominant movement with
 * vibe-specific color moves), and a fixed cadence that closes the progression
 * on a vibe-appropriate resolution.
 *
 * Pure Kotlin, no Android or device dependencies. Deterministic for a given
 * (keyRoot, vibe, barCount, seed) tuple.
 */
object ProgressionGenerator {

    /** A weighted edge in the transition table. */
    data class Transition(val to: String, val weight: Int)

    /**
     * Per-vibe grammar. Degrees are keyed by roman label; transitions reference
     * those labels. [opener] starts the walk, [cadence] replaces the final
     * chords so the progression resolves.
     */
    data class VibeGrammar(
        val degrees: Map<String, ChordDegree>,
        val transitions: Map<String, List<Transition>>,
        val opener: String,
        val cadence: List<String>,
    )

    private fun deg(roman: String, semitones: Int, quality: ChordQuality) =
        roman to ChordDegree(roman, semitones, quality)

    private fun t(to: String, weight: Int) = Transition(to, weight)

    // Semitone offsets, matching the constants used in ChordProgressions.kt
    private const val R = 0
    private const val S2 = 2
    private const val S3 = 4
    private const val S4 = 5
    private const val S5 = 7
    private const val S6m = 8
    private const val S6 = 9
    private const val S7m = 10

    val GRAMMARS: Map<Vibe, VibeGrammar> = mapOf(
        Vibe.HAPPY to VibeGrammar(
            degrees = mapOf(
                deg("I", R, ChordQuality.MAJOR),
                deg("ii", S2, ChordQuality.MINOR),
                deg("iii", S3, ChordQuality.MINOR),
                deg("IV", S4, ChordQuality.MAJOR),
                deg("V", S5, ChordQuality.MAJOR),
                deg("vi", S6, ChordQuality.MINOR),
            ),
            transitions = mapOf(
                "I" to listOf(t("IV", 4), t("vi", 3), t("V", 2), t("iii", 1)),
                "ii" to listOf(t("V", 5), t("IV", 1)),
                "iii" to listOf(t("IV", 3), t("vi", 2)),
                "IV" to listOf(t("V", 4), t("I", 2), t("ii", 1)),
                "V" to listOf(t("I", 4), t("vi", 2)),
                "vi" to listOf(t("IV", 4), t("ii", 2), t("V", 1)),
            ),
            opener = "I",
            cadence = listOf("V", "I"),
        ),

        Vibe.SAD to VibeGrammar(
            degrees = mapOf(
                deg("I", R, ChordQuality.MAJOR),
                deg("ii", S2, ChordQuality.MINOR),
                deg("iii", S3, ChordQuality.MINOR),
                deg("IV", S4, ChordQuality.MAJOR),
                deg("V", S5, ChordQuality.MAJOR),
                deg("vi", S6, ChordQuality.MINOR),
            ),
            transitions = mapOf(
                "vi" to listOf(t("IV", 4), t("ii", 2), t("iii", 1)),
                "IV" to listOf(t("I", 3), t("V", 2), t("vi", 1)),
                "I" to listOf(t("V", 3), t("iii", 2), t("vi", 1)),
                "V" to listOf(t("vi", 4), t("IV", 1)),
                "ii" to listOf(t("V", 3), t("vi", 2)),
                "iii" to listOf(t("vi", 3), t("IV", 2)),
            ),
            opener = "vi",
            cadence = listOf("V", "vi"),
        ),

        Vibe.BLUES to VibeGrammar(
            degrees = mapOf(
                deg("I7", R, ChordQuality.DOM7),
                deg("IV7", S4, ChordQuality.DOM7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("bVI7", S6m, ChordQuality.DOM7),
            ),
            transitions = mapOf(
                "I7" to listOf(t("IV7", 4), t("V7", 2), t("I7", 2)),
                "IV7" to listOf(t("I7", 4), t("V7", 2), t("bVI7", 1)),
                "V7" to listOf(t("IV7", 3), t("I7", 3)),
                "bVI7" to listOf(t("V7", 4)),
            ),
            opener = "I7",
            cadence = listOf("V7", "I7"),
        ),

        Vibe.JAZZ to VibeGrammar(
            degrees = mapOf(
                deg("Imaj7", R, ChordQuality.MAJ7),
                deg("iim7", S2, ChordQuality.MIN7),
                deg("iiim7", S3, ChordQuality.MIN7),
                deg("IVmaj7", S4, ChordQuality.MAJ7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("vim7", S6, ChordQuality.MIN7),
                deg("VI7", S6, ChordQuality.DOM7),
                deg("bVII7", S7m, ChordQuality.DOM7),
            ),
            transitions = mapOf(
                "Imaj7" to listOf(t("vim7", 3), t("iim7", 3), t("VI7", 2), t("IVmaj7", 1)),
                "iim7" to listOf(t("V7", 6), t("bVII7", 1)),
                "iiim7" to listOf(t("vim7", 3), t("iim7", 2)),
                "IVmaj7" to listOf(t("iiim7", 2), t("iim7", 2), t("bVII7", 1)),
                "V7" to listOf(t("Imaj7", 4), t("iiim7", 1), t("vim7", 1)),
                "vim7" to listOf(t("iim7", 4), t("IVmaj7", 1)),
                "VI7" to listOf(t("iim7", 5)),
                "bVII7" to listOf(t("Imaj7", 4)),
            ),
            opener = "Imaj7",
            cadence = listOf("iim7", "V7", "Imaj7"),
        ),

        Vibe.NEO_SOUL to VibeGrammar(
            degrees = mapOf(
                deg("Imaj7", R, ChordQuality.MAJ7),
                deg("iim7", S2, ChordQuality.MIN7),
                deg("iiim7", S3, ChordQuality.MIN7),
                deg("IVmaj7", S4, ChordQuality.MAJ7),
                deg("ivm7", S4, ChordQuality.MIN7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("vim7", S6, ChordQuality.MIN7),
            ),
            transitions = mapOf(
                "Imaj7" to listOf(t("IVmaj7", 4), t("iiim7", 2), t("vim7", 2)),
                "iim7" to listOf(t("V7", 4), t("iiim7", 2)),
                "iiim7" to listOf(t("vim7", 4), t("IVmaj7", 1)),
                "IVmaj7" to listOf(t("ivm7", 3), t("iim7", 2), t("vim7", 2)),
                "ivm7" to listOf(t("Imaj7", 3), t("V7", 2)),
                "V7" to listOf(t("Imaj7", 4), t("vim7", 2)),
                "vim7" to listOf(t("iim7", 3), t("IVmaj7", 2)),
            ),
            opener = "Imaj7",
            cadence = listOf("V7", "Imaj7"),
        ),

        Vibe.CHILL to VibeGrammar(
            degrees = mapOf(
                deg("Imaj7", R, ChordQuality.MAJ7),
                deg("iim7", S2, ChordQuality.MIN7),
                deg("iiim7", S3, ChordQuality.MIN7),
                deg("IVmaj7", S4, ChordQuality.MAJ7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("vim7", S6, ChordQuality.MIN7),
            ),
            transitions = mapOf(
                "Imaj7" to listOf(t("iiim7", 3), t("IVmaj7", 3), t("iim7", 2)),
                "iim7" to listOf(t("iiim7", 2), t("V7", 3)),
                "iiim7" to listOf(t("IVmaj7", 3), t("vim7", 2)),
                "IVmaj7" to listOf(t("V7", 2), t("Imaj7", 2), t("iiim7", 1)),
                "V7" to listOf(t("Imaj7", 4), t("vim7", 1)),
                "vim7" to listOf(t("iim7", 3), t("IVmaj7", 2)),
            ),
            opener = "Imaj7",
            cadence = listOf("V7", "Imaj7"),
        ),

        Vibe.DRIVING to VibeGrammar(
            degrees = mapOf(
                deg("I", R, ChordQuality.MAJOR),
                deg("ii", S2, ChordQuality.MINOR),
                deg("IV", S4, ChordQuality.MAJOR),
                deg("V", S5, ChordQuality.MAJOR),
                deg("vi", S6, ChordQuality.MINOR),
                deg("bVII", S7m, ChordQuality.MAJOR),
            ),
            transitions = mapOf(
                "I" to listOf(t("V", 3), t("bVII", 3), t("IV", 2), t("vi", 1)),
                "ii" to listOf(t("V", 4)),
                "IV" to listOf(t("V", 3), t("I", 3), t("bVII", 1)),
                "V" to listOf(t("I", 3), t("vi", 2), t("IV", 1)),
                "vi" to listOf(t("IV", 3), t("ii", 2)),
                "bVII" to listOf(t("I", 4), t("IV", 2)),
            ),
            opener = "I",
            cadence = listOf("V", "I"),
        ),

        Vibe.SOULFUL to VibeGrammar(
            degrees = mapOf(
                deg("I", R, ChordQuality.MAJOR),
                deg("Imaj7", R, ChordQuality.MAJ7),
                deg("iim7", S2, ChordQuality.MIN7),
                deg("iiim7", S3, ChordQuality.MIN7),
                deg("IVmaj7", S4, ChordQuality.MAJ7),
                deg("ivm7", S4, ChordQuality.MIN7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("vim7", S6, ChordQuality.MIN7),
                deg("V7/ii", S6, ChordQuality.DOM7),
            ),
            transitions = mapOf(
                "I" to listOf(t("V7/ii", 2), t("IVmaj7", 3), t("vim7", 2)),
                "Imaj7" to listOf(t("V7/ii", 3), t("IVmaj7", 3), t("iiim7", 1)),
                "iim7" to listOf(t("V7", 5)),
                "iiim7" to listOf(t("vim7", 3), t("iim7", 2)),
                "IVmaj7" to listOf(t("ivm7", 3), t("iim7", 2), t("Imaj7", 1)),
                "ivm7" to listOf(t("Imaj7", 3), t("V7", 2)),
                "V7" to listOf(t("I", 3), t("Imaj7", 2), t("vim7", 1)),
                "vim7" to listOf(t("iim7", 4), t("IVmaj7", 1)),
                "V7/ii" to listOf(t("iim7", 5)),
            ),
            opener = "Imaj7",
            cadence = listOf("V7", "I"),
        ),

        Vibe.HIP_HOP to VibeGrammar(
            degrees = mapOf(
                deg("Imaj7", R, ChordQuality.MAJ7),
                deg("iim7", S2, ChordQuality.MIN7),
                deg("iiim7", S3, ChordQuality.MIN7),
                deg("IVmaj7", S4, ChordQuality.MAJ7),
                deg("V7", S5, ChordQuality.DOM7),
                deg("vim7", S6, ChordQuality.MIN7),
            ),
            transitions = mapOf(
                "Imaj7" to listOf(t("iim7", 3), t("vim7", 2), t("iiim7", 2)),
                "iim7" to listOf(t("iiim7", 3), t("V7", 2), t("vim7", 2)),
                "iiim7" to listOf(t("vim7", 4), t("iim7", 2)),
                "IVmaj7" to listOf(t("Imaj7", 3), t("iim7", 2)),
                "V7" to listOf(t("IVmaj7", 3), t("vim7", 2)),
                "vim7" to listOf(t("IVmaj7", 3), t("V7", 2), t("iiim7", 2)),
            ),
            opener = "vim7",
            cadence = listOf("IVmaj7", "vim7"),
        ),
    )

    /**
     * Generate a [barCount]-chord progression for [vibe] in [keyRoot]
     * (one chord per bar). Same inputs and [seed] always produce the same
     * progression. [barCount] is coerced to 2..16.
     */
    fun generate(keyRoot: String, vibe: Vibe, barCount: Int, seed: Long): ChordProgression {
        val bars = barCount.coerceIn(2, 16)
        val grammar = GRAMMARS.getValue(vibe)
        val rng = Random(seed)

        val romans = mutableListOf(grammar.opener)
        while (romans.size < bars) {
            romans += pickNext(grammar, romans.last(), rng)
        }

        // Overwrite the tail with the cadence so the progression resolves.
        // With very short bar counts only the tail of the cadence fits.
        val cadence = grammar.cadence.takeLast(bars)
        for ((i, roman) in cadence.withIndex()) {
            romans[bars - cadence.size + i] = roman
        }

        val degrees = romans.map { grammar.degrees.getValue(it) }
        return ChordProgression(
            id = "gen-${vibe.name.lowercase()}-$seed-$bars",
            name = "Generated ${vibe.label} in $keyRoot",
            degrees = degrees,
            vibes = setOf(vibe),
        )
    }

    private fun pickNext(grammar: VibeGrammar, from: String, rng: Random): String {
        val options = grammar.transitions[from] ?: return grammar.opener
        val total = options.sumOf { it.weight }
        var roll = rng.nextInt(total)
        for (option in options) {
            roll -= option.weight
            if (roll < 0) return option.to
        }
        return options.last().to
    }
}
