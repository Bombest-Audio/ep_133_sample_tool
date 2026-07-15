package com.ep133.sampletool.domain.export

import com.ep133.sampletool.domain.backup.ManifestSample
import com.ep133.sampletool.domain.backup.ProjectManifest
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * DAW export target formats (ROADMAP 999.6 Tier 1 / issue #50).
 *
 * Each format consumes a [ProjectManifest] (the backup sidecar read model), never the device,
 * so exports work fully offline from the backup library.
 */
enum class ExportFormat(val label: String, val makeExporter: () -> ProjectExporter) {
    DAWPROJECT("DAWproject", { DawprojectExporter() }),
    REAPER("REAPER", { ReaperExporter() }),
    MIDI("MIDI", { MidiFileExporter() }),
}

/**
 * Result of one export run.
 *
 * @param exportDir the folder holding everything written (main artifact, samples, README.txt).
 * @param shareFile the single file suited for the Android share sheet (the .dawproject zip,
 *   a .zip of the REAPER folder, or the .mid file).
 */
data class ExportResult(val exportDir: File, val shareFile: File)

/**
 * Exports one EP-133 project (as captured in a backup manifest) to a DAW-consumable format.
 *
 * Contract: pure JVM file work, no Android dependencies, no device I/O. Sample WAVs are
 * copied (or embedded) from the manifest's resolved files; pads whose sample file is missing
 * on disk are skipped and noted in the export README.
 */
interface ProjectExporter {

    /** File-name-safe short id for this format ("dawproject", "reaper", "midi"). */
    val id: String

    /**
     * Write the export for [manifest] into [outDir] (created if needed; existing content may
     * be overwritten). [baseName] is the artifact base name, typically the backup tar name
     * without extension.
     */
    fun export(manifest: ProjectManifest, outDir: File, baseName: String): ExportResult
}

/** One assigned pad, resolved to its sample file and playback params. */
data class PadExport(
    val group: String,
    /** Pad number within the group, 1..12. */
    val padNumber: Int,
    /** MIDI note the device fires for this pad (group base + padNumber - 1). */
    val midiNote: Int,
    val sample: ManifestSample,
    /** Pitch offset in semitones (device `sound.pitch`). */
    val pitchSemitones: Double,
    /** Pan in -1.0 (left) .. 1.0 (right), 0 center (device `sound.pan`, -100..100 assumed). */
    val pan: Double,
    /** Linear gain, 1.0 = unity (device `sound.amplitude`, 100 = unity). */
    val gain: Double,
    /** Trim start in frames, or -1 when unset (device `sample.start`). */
    val startFrame: Long,
    /** Trim end in frames, or -1 when unset (device `sample.end`). */
    val endFrame: Long,
) {
    /** Display label like "A01". */
    val label: String get() = "$group%02d".format(padNumber)
}

/** The manifest reshaped for exporters: groups A-D in device order, assigned pads only. */
data class ExportModel(
    val manifest: ProjectManifest,
    /** Group letter to its assigned, sample-resolved pads (groups with none are absent). */
    val groups: Map<String, List<PadExport>>,
    /** Human-readable notes about pads/samples that could not be exported. */
    val notes: List<String>,
) {
    val allPads: List<PadExport> get() = groups.values.flatten()
}

/** Group letter to MIDI note base per docs/ep133-sysex-protocol.md (pads A1..A12 = 36..47). */
val GROUP_NOTE_BASES: Map<String, Int> = mapOf("A" to 36, "B" to 48, "C" to 60, "D" to 72)

/** Seconds per exported clip slot in linear layouts (REAPER item spacing, MIDI note grid). */
internal const val EXPORT_TEMPO_BPM = 120.0

/**
 * Resolve [manifest] into an [ExportModel]: keep pads with `sym > 0` whose sample WAV exists
 * on disk, decode the per-pad playback params from the pad metadata JSON, and record a note
 * for anything skipped.
 */
fun buildExportModel(manifest: ProjectManifest): ExportModel {
    val samplesBySym = manifest.samples.associateBy { it.sym }
    val groups = linkedMapOf<String, MutableList<PadExport>>()
    val notes = mutableListOf<String>()

    for (pad in manifest.pads) {
        val meta = try {
            JSONObject(pad.metadataJson)
        } catch (_: JSONException) {
            JSONObject()
        }
        val sym = meta.optInt("sym", 0)
        if (sym <= 0) continue // unassigned pad
        val base = GROUP_NOTE_BASES[pad.group]
        val padNumber = pad.pad.toIntOrNull()
        if (base == null || padNumber == null || padNumber !in 1..12) {
            notes += "pad ${pad.group}/${pad.pad}: unrecognized group or pad name, skipped"
            continue
        }
        val sample = samplesBySym[sym]
        if (sample?.file == null) {
            notes += "pad ${pad.group}/${pad.pad}: sample $sym missing from the backup, skipped"
            continue
        }
        groups.getOrPut(pad.group) { mutableListOf() } += PadExport(
            group = pad.group,
            padNumber = padNumber,
            midiNote = base + padNumber - 1,
            sample = sample,
            pitchSemitones = meta.optDouble("sound.pitch", 0.0),
            pan = (meta.optDouble("sound.pan", 0.0) / 100.0).coerceIn(-1.0, 1.0),
            gain = (meta.optDouble("sound.amplitude", 100.0) / 100.0).coerceAtLeast(0.0),
            startFrame = meta.optLong("sample.start", -1L),
            endFrame = meta.optLong("sample.end", -1L),
        )
    }
    groups.values.forEach { it.sortBy(PadExport::padNumber) }
    val ordered = groups.toSortedMap()
    return ExportModel(manifest, ordered, notes + manifest.skipped.map { "backup: $it" })
}

/** Sample duration in seconds, honoring the pad trim when both frames are set and sane. */
internal fun PadExport.clipSeconds(fallbackSeconds: Double): Double {
    if (startFrame >= 0 && endFrame > startFrame && sample.sampleRate > 0) {
        return (endFrame - startFrame).toDouble() / sample.sampleRate
    }
    return fallbackSeconds
}

/** Whole-sample duration in seconds derived from the WAV file size (16-bit PCM assumed). */
internal fun ManifestSample.durationSeconds(): Double {
    val f = file ?: return 1.0
    val dataBytes = (f.length() - 44L).coerceAtLeast(0L)
    val frameBytes = 2L * channels.coerceAtLeast(1)
    if (sampleRate <= 0) return 1.0
    return dataBytes.toDouble() / frameBytes / sampleRate
}

/**
 * Write the honest-limits README into [outDir]. Every export folder carries one: patterns,
 * FX, and automation are not on the EP-133's readable surface, so the export is a sample and
 * track scaffold, not a full project translation.
 */
internal fun writeExportReadme(outDir: File, model: ExportModel, formatLabel: String) {
    val m = model.manifest
    val lines = buildString {
        appendLine("EP-133 Sample Tool - $formatLabel export")
        appendLine()
        appendLine("Project: ${m.projectName} (slot ${m.projectSlot})")
        appendLine("Pads exported: ${model.allPads.size}")
        appendLine()
        appendLine("WHAT THIS EXPORT CONTAINS")
        appendLine("- Every assigned pad's sample as a WAV (device-native 46875 Hz, 16-bit PCM).")
        appendLine("- One track per pad group (A-D) with a clip/entry per assigned pad.")
        appendLine("- Per-pad parameters applied where the format supports them")
        appendLine("  (pitch as transpose, trim start/end, pan, gain).")
        appendLine()
        appendLine("WHAT IT DOES NOT CONTAIN, AND WHY")
        appendLine("- Patterns/sequences: the EP-133's MIDI-SysEx protocol exposes no way to")
        appendLine("  read pattern data back from the device, so sequences cannot be exported.")
        appendLine("- Punch-in FX, master FX, and automation: same limitation - these live in")
        appendLine("  device state that the hardware never surfaces over USB.")
        appendLine("- The MIDI export is a structural skeleton (one marker note per assigned")
        appendLine("  pad at that pad's device MIDI note), not a performance.")
        appendLine()
        appendLine("Details and progress: https://github.com/Bombest-Audio/ep_133_sample_tool/issues/50")
        if (model.notes.isNotEmpty()) {
            appendLine()
            appendLine("SKIPPED DURING THIS EXPORT")
            model.notes.forEach { appendLine("- $it") }
        }
    }
    File(outDir, "README.txt").writeText(lines, Charsets.UTF_8)
}

/**
 * Copy each pad's WAV into `<outDir>/samples/` (named `<sym>.wav`) and return pad to
 * relative path ("samples/<sym>.wav"). Duplicate syms are copied once.
 */
internal fun copySamples(outDir: File, pads: List<PadExport>): Map<Int, String> {
    val samplesDir = File(outDir, "samples").also { it.mkdirs() }
    val relBySym = linkedMapOf<Int, String>()
    for (pad in pads) {
        val sym = pad.sample.sym
        if (sym in relBySym) continue
        val src = pad.sample.file ?: continue
        val rel = "samples/$sym.wav"
        src.copyTo(File(outDir, rel), overwrite = true)
        relBySym[sym] = rel
    }
    return relBySym
}

/** Escape the XML attribute/text special characters. */
internal fun xmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
