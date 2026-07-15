package com.ep133.sampletool.domain.export

import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports to a plain-text REAPER project (`.rpp`) plus a `samples/` folder of WAVs.
 *
 * Structure: one `<TRACK` per pad group A-D; one `<ITEM` per assigned pad, laid out
 * sequentially (2 s apart) on its group track, with a relative `<SOURCE WAVE FILE`.
 *
 * Param mapping (all four supported here):
 * - pitch: `PLAYRATE` field 3 (semitone pitch adjust, preserve-pitch on),
 * - pan and gain: item `VOLPAN <linearGain> <pan>`,
 * - trim start: `SOFFS` (source offset seconds), trim end: item `LENGTH`.
 *
 * The share artifact is a zip of the whole export folder so the rpp and its relative
 * samples travel together.
 */
class ReaperExporter : ProjectExporter {

    override val id: String = "reaper"

    override fun export(
        manifest: com.ep133.sampletool.domain.backup.ProjectManifest,
        outDir: File,
        baseName: String,
    ): ExportResult {
        val model = buildExportModel(manifest)
        outDir.mkdirs()
        val relBySym = copySamples(outDir, model.allPads)
        writeExportReadme(outDir, model, "REAPER")

        val rpp = File(outDir, "$baseName.rpp")
        rpp.writeText(projectText(model, relBySym), Charsets.UTF_8)

        val zip = File(outDir, "$baseName-reaper.zip")
        zipFolder(outDir, zip)
        return ExportResult(outDir, zip)
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun projectText(model: ExportModel, relBySym: Map<Int, String>): String {
        val sb = StringBuilder()
        sb.append("<REAPER_PROJECT 0.1 \"6.0/EP-133 Sample Tool\" 0\n")
        sb.append("  TEMPO $EXPORT_TEMPO_BPM 4 4\n")
        for ((group, pads) in model.groups) {
            sb.append("  <TRACK\n")
            sb.append("    NAME \"Group $group\"\n")
            pads.forEachIndexed { i, pad ->
                val rel = relBySym.getValue(pad.sample.sym)
                val length = pad.clipSeconds(pad.sample.durationSeconds())
                val soffs = if (pad.startFrame >= 0 && pad.sample.sampleRate > 0) {
                    pad.startFrame.toDouble() / pad.sample.sampleRate
                } else {
                    0.0
                }
                val name = "${pad.label} ${pad.sample.name ?: pad.sample.sym.toString()}"
                sb.append("    <ITEM\n")
                sb.append("      POSITION ${num(i * 2.0)}\n")
                sb.append("      LENGTH ${num(length)}\n")
                sb.append("      NAME \"${name.replace("\"", "'")}\"\n")
                sb.append("      VOLPAN ${num(pad.gain)} ${num(pad.pan)}\n")
                sb.append("      SOFFS ${num(soffs)}\n")
                // PLAYRATE <rate> <preservePitch> <semitonePitchAdj> <mode> ...
                sb.append("      PLAYRATE 1.000000 1 ${num(pad.pitchSemitones)} -1 0 0.0025\n")
                sb.append("      <SOURCE WAVE\n")
                sb.append("        FILE \"$rel\"\n")
                sb.append("      >\n")
                sb.append("    >\n")
            }
            sb.append("  >\n")
        }
        sb.append(">\n")
        return sb.toString()
    }

    /** Zip [dir]'s contents (recursively, relative paths), excluding [target] itself. */
    private fun zipFolder(dir: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && it != target }
                .forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                    FileInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
