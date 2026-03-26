package com.ep133.sampletool.domain.model

enum class ChordQuality(val label: String, val suffix: String, val intervals: List<Int>) {
    MAJOR("Major", "", listOf(0, 4, 7)),
    MINOR("Minor", "m", listOf(0, 3, 7)),
    DOM7("Dominant 7", "7", listOf(0, 4, 7, 10)),
    MAJ7("Major 7", "maj7", listOf(0, 4, 7, 11)),
    MIN7("Minor 7", "m7", listOf(0, 3, 7, 10)),
    MIN7B5("Half-Dim", "m7b5", listOf(0, 3, 6, 10)),
    DIM("Diminished", "dim", listOf(0, 3, 6)),
    DIM7("Diminished 7", "dim7", listOf(0, 3, 6, 9)),
    AUG("Augmented", "aug", listOf(0, 4, 8)),
    DOM9("Dominant 9", "9", listOf(0, 4, 7, 10, 14)),
    MAJ9("Major 9", "maj9", listOf(0, 4, 7, 11, 14)),
    MIN9("Minor 9", "m9", listOf(0, 3, 7, 10, 14)),
    DOM13("Dominant 13", "13", listOf(0, 4, 7, 10, 14, 21)),
    SUS4("Suspended 4", "sus4", listOf(0, 5, 7)),
}

data class ChordDegree(
    val roman: String,
    val semitones: Int,
    val quality: ChordQuality,
)

data class ChordProgression(
    val id: String,
    val name: String,
    val degrees: List<ChordDegree>,
    val vibes: Set<Vibe>,
)

enum class Vibe(val label: String) {
    HAPPY("Happy"),
    SAD("Melancholic"),
    BLUES("Blues"),
    JAZZ("Jazz"),
    NEO_SOUL("Neo Soul"),
    CHILL("Chill"),
    DRIVING("Driving"),
    SOULFUL("Soulful"),
    HIP_HOP("Hip Hop"),
}

private fun deg(roman: String, semitones: Int, quality: ChordQuality) =
    ChordDegree(roman, semitones, quality)

private fun prog(
    id: String,
    name: String,
    degrees: List<ChordDegree>,
    vararg vibes: Vibe,
) = ChordProgression(id, name, degrees, vibes.toSet())

// Shorthand constants for semitone offsets
private const val R = 0    // I (root)
private const val S2 = 2   // II
private const val S3m = 3  // bIII
private const val S3 = 4   // III
private const val S4 = 5   // IV
private const val S5d = 6  // #IV / bV
private const val S5 = 7   // V
private const val S6m = 8  // bVI
private const val S6 = 9   // VI
private const val S7m = 10 // bVII
private const val S7 = 11  // VII

object Progressions {

    val ALL: List<ChordProgression> = listOf(
        // ── PDF Progressions (1–16) ──
        prog("pdf01", "Classic I-IV-V", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("IV", S4, ChordQuality.MAJOR),
            deg("V", S5, ChordQuality.MAJOR), deg("I", R, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.DRIVING),

        prog("pdf02", "Nine Pound Hammer", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("IV", S4, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.DRIVING),

        prog("pdf06", "I-vi-IV-V", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("vi", S6, ChordQuality.MINOR),
            deg("IV", S4, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.SOULFUL),

        prog("pdf07", "50s Turnaround", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("vi", S6, ChordQuality.MINOR),
            deg("IV", S4, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.SOULFUL),

        prog("pdf08", "Heart & Soul", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("vi", S6, ChordQuality.MINOR),
            deg("ii", S2, ChordQuality.MINOR), deg("V", S5, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.SOULFUL, Vibe.HAPPY),

        prog("pdf09", "Simple ii-V", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("ii", S2, ChordQuality.MINOR),
            deg("V", S5, ChordQuality.MAJOR), deg("I", R, ChordQuality.MAJOR),
        ), Vibe.JAZZ, Vibe.CHILL),

        prog("pdf10", "Doo-Wop", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("vi", S6, ChordQuality.MINOR),
            deg("iii", S3, ChordQuality.MINOR), deg("V", S5, ChordQuality.MAJOR),
        ), Vibe.SAD, Vibe.SOULFUL),

        prog("pdf11", "Ascending", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("iii", S3, ChordQuality.MINOR),
            deg("IV", S4, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.DRIVING),

        prog("pdf12", "Stepwise", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("ii", S2, ChordQuality.MINOR),
            deg("iii", S3, ChordQuality.MINOR), deg("IV", S4, ChordQuality.MAJOR),
        ), Vibe.CHILL, Vibe.NEO_SOUL),

        prog("pdf13", "Country V7", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("IV", S4, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR), deg("V7", S5, ChordQuality.DOM7),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.SOULFUL),

        prog("pdf14", "Modal Mix", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("bVII", S7m, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.DRIVING),

        prog("pdf15", "12-Bar Blues", listOf(
            deg("I7", R, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
            deg("IV7", S4, ChordQuality.DOM7), deg("IV7", S4, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
            deg("V7", S5, ChordQuality.DOM7), deg("IV7", S4, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
        ), Vibe.BLUES),

        prog("pdf16", "Jazz Turnaround", listOf(
            deg("VI7", S6, ChordQuality.DOM7), deg("II7", S2, ChordQuality.DOM7),
            deg("V7", S5, ChordQuality.DOM7), deg("I", R, ChordQuality.MAJOR),
        ), Vibe.JAZZ),

        // ── Jazz Blues ──
        prog("jazz01", "Jazz Blues", listOf(
            deg("I7", R, ChordQuality.DOM7), deg("IV7", S4, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("vi", S6, ChordQuality.MINOR),
            deg("ii", S2, ChordQuality.MINOR), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.JAZZ, Vibe.BLUES),

        prog("jazz02", "Minor Blues", listOf(
            deg("im7", R, ChordQuality.MIN7), deg("ivm7", S4, ChordQuality.MIN7),
            deg("im7", R, ChordQuality.MIN7), deg("bVI7", S6m, ChordQuality.DOM7),
            deg("V7", S5, ChordQuality.DOM7), deg("im7", R, ChordQuality.MIN7),
        ), Vibe.BLUES, Vibe.SAD),

        prog("jazz03", "All Blues", listOf(
            deg("I7", R, ChordQuality.DOM7), deg("IV7", S4, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("bVI7", S6m, ChordQuality.DOM7),
            deg("V7", S5, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
        ), Vibe.JAZZ, Vibe.CHILL),

        prog("jazz04", "Tritone Sub Blues", listOf(
            deg("I7", R, ChordQuality.DOM7), deg("IV7", S4, ChordQuality.DOM7),
            deg("I7", R, ChordQuality.DOM7), deg("iim7", S2, ChordQuality.MIN7),
            deg("bII7", 1, ChordQuality.DOM7), deg("I7", R, ChordQuality.DOM7),
        ), Vibe.JAZZ),

        prog("jazz05", "ii-V-I Major", listOf(
            deg("iim7", S2, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
            deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.JAZZ),

        prog("jazz06", "ii-V-I Minor", listOf(
            deg("iim7b5", S2, ChordQuality.MIN7B5), deg("V7", S5, ChordQuality.DOM7),
            deg("im7", R, ChordQuality.MIN7),
        ), Vibe.JAZZ, Vibe.SAD),

        prog("jazz07", "Rhythm Changes A", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("vim7", S6, ChordQuality.MIN7),
            deg("iim7", S2, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.JAZZ, Vibe.DRIVING),

        prog("jazz08", "Backdoor ii-V", listOf(
            deg("ivm7", S4, ChordQuality.MIN7), deg("bVII7", S7m, ChordQuality.DOM7),
            deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.JAZZ, Vibe.NEO_SOUL),

        // ── Neo Soul / R&B ──
        prog("soul01", "Neo Soul Classic", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("IVmaj7", S4, ChordQuality.MAJ7),
            deg("vim7", S6, ChordQuality.MIN7), deg("iim7", S2, ChordQuality.MIN7),
            deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.NEO_SOUL),

        prog("soul02", "Erykah Badu Feel", listOf(
            deg("iim7", S2, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
            deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.NEO_SOUL, Vibe.CHILL),

        prog("soul03", "D'Angelo Vibe", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("IVmaj7", S4, ChordQuality.MAJ7),
            deg("ivm7", S4, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.NEO_SOUL, Vibe.SOULFUL),

        prog("soul04", "Smooth iii-vi", listOf(
            deg("iiim7", S3, ChordQuality.MIN7), deg("vim7", S6, ChordQuality.MIN7),
            deg("iim7", S2, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.NEO_SOUL, Vibe.JAZZ),

        prog("soul05", "R&B Minor Start", listOf(
            deg("vim7", S6, ChordQuality.MIN7), deg("IVmaj7", S4, ChordQuality.MAJ7),
            deg("Imaj7", R, ChordQuality.MAJ7), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.NEO_SOUL, Vibe.SAD),

        prog("soul06", "IV to iv Magic", listOf(
            deg("IVmaj7", S4, ChordQuality.MAJ7), deg("ivm7", S4, ChordQuality.MIN7),
            deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.NEO_SOUL, Vibe.SOULFUL),

        prog("soul07", "Tyler Flow", listOf(
            deg("iim7", S2, ChordQuality.MIN7), deg("iiim7", S3, ChordQuality.MIN7),
            deg("vim7", S6, ChordQuality.MIN7), deg("iiim7", S3, ChordQuality.MIN7),
        ), Vibe.HIP_HOP, Vibe.NEO_SOUL),

        prog("soul08", "Gospel Turn", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("V7/ii", S6, ChordQuality.DOM7),
            deg("iim7", S2, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
            deg("I", R, ChordQuality.MAJOR),
        ), Vibe.SOULFUL, Vibe.NEO_SOUL),

        prog("soul09", "Full Soul", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("V7", S5, ChordQuality.DOM7),
            deg("vim7", S6, ChordQuality.MIN7), deg("iiim7", S3, ChordQuality.MIN7),
            deg("IVmaj7", S4, ChordQuality.MAJ7), deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.NEO_SOUL, Vibe.SOULFUL),

        prog("soul10", "Lo-fi Chill", listOf(
            deg("iim9", S2, ChordQuality.MIN9), deg("V13", S5, ChordQuality.DOM13),
            deg("Imaj9", R, ChordQuality.MAJ9),
        ), Vibe.CHILL, Vibe.NEO_SOUL),

        // ── Pop / Hip Hop ──
        prog("pop01", "Pop Anthem", listOf(
            deg("I", R, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
            deg("vi", S6, ChordQuality.MINOR), deg("IV", S4, ChordQuality.MAJOR),
        ), Vibe.HAPPY, Vibe.DRIVING),

        prog("pop02", "Sad Pop", listOf(
            deg("vi", S6, ChordQuality.MINOR), deg("IV", S4, ChordQuality.MAJOR),
            deg("I", R, ChordQuality.MAJOR), deg("V", S5, ChordQuality.MAJOR),
        ), Vibe.SAD, Vibe.CHILL),

        prog("pop03", "Trap Soul", listOf(
            deg("vim7", S6, ChordQuality.MIN7), deg("IVmaj7", S4, ChordQuality.MAJ7),
            deg("Imaj7", R, ChordQuality.MAJ7), deg("iim7", S2, ChordQuality.MIN7),
        ), Vibe.HIP_HOP, Vibe.SAD),

        prog("pop04", "Drake Vibe", listOf(
            deg("vim7", S6, ChordQuality.MIN7), deg("V7", S5, ChordQuality.DOM7),
            deg("IVmaj7", S4, ChordQuality.MAJ7), deg("Imaj7", R, ChordQuality.MAJ7),
        ), Vibe.HIP_HOP, Vibe.CHILL),

        prog("pop05", "Kanye Soul Sample", listOf(
            deg("Imaj9", R, ChordQuality.MAJ9), deg("ivm9", S4, ChordQuality.MIN9),
            deg("V9", S5, ChordQuality.DOM9),
        ), Vibe.HIP_HOP, Vibe.SOULFUL),

        prog("pop06", "Bedroom Pop", listOf(
            deg("Imaj7", R, ChordQuality.MAJ7), deg("iiim7", S3, ChordQuality.MIN7),
            deg("IVmaj7", S4, ChordQuality.MAJ7), deg("V7", S5, ChordQuality.DOM7),
        ), Vibe.CHILL, Vibe.HAPPY),
    )

    fun forVibes(vibes: Set<Vibe>): List<ChordProgression> =
        if (vibes.isEmpty()) ALL
        else ALL.filter { it.vibes.any { v -> v in vibes } }
}

// ── Chord-to-MIDI resolution ──

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteNameToMidi(name: String, octave: Int = 3): Int {
    val index = NOTE_NAMES.indexOf(name)
    if (index < 0) return 60
    return 12 * (octave + 1) + index
}

fun midiToNoteName(midi: Int): String {
    val name = NOTE_NAMES[midi % 12]
    val octave = (midi / 12) - 1
    return "$name$octave"
}

fun resolveChordName(degree: ChordDegree, keyRoot: String): String {
    val rootMidi = noteNameToMidi(keyRoot, 0) + degree.semitones
    val rootName = NOTE_NAMES[rootMidi % 12]
    return "$rootName${degree.quality.suffix}"
}

fun resolveChordMidiNotes(degree: ChordDegree, keyRoot: String, octave: Int = 3): List<Int> {
    val rootMidi = noteNameToMidi(keyRoot, octave) + degree.semitones
    return degree.quality.intervals.map { rootMidi + it }
}
