package com.ep133.sampletool.domain.export

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports to Bitwig's open DAWproject container (https://github.com/bitwig/dawproject):
 * a zip holding `project.xml`, `metadata.xml`, and the referenced audio embedded at its
 * relative path.
 *
 * Structure written (kept minimal but schema-valid):
 * - one audio `Track` (+ `Channel`) per pad group A-D,
 * - an `Arrangement` with one `Clip` per assigned pad, each wrapping an `Audio` element
 *   whose `File` points at the embedded `samples/<sym>.wav`,
 * - a 120 BPM `Transport` so hosts have a tempo context.
 *
 * Param mapping: clips are laid out sequentially (2 beats apart) on their group's lane and
 * trimmed via the Warps content window when the pad has sample.start/end. DAWproject has no
 * per-clip pitch/pan/gain attributes in the minimal profile, so those stay documented in
 * README.txt and clip names rather than being invented; group channels carry unity defaults.
 */
class DawprojectExporter : ProjectExporter {

    override val id: String = "dawproject"

    override fun export(
        manifest: com.ep133.sampletool.domain.backup.ProjectManifest,
        outDir: File,
        baseName: String,
    ): ExportResult {
        val model = buildExportModel(manifest)
        outDir.mkdirs()
        val relBySym = copySamples(outDir, model.allPads)
        writeExportReadme(outDir, model, "DAWproject")

        val artifact = File(outDir, "$baseName.dawproject")
        ZipOutputStream(artifact.outputStream().buffered()).use { zip ->
            zip.putTextEntry("metadata.xml", metadataXml(manifest.projectName))
            zip.putTextEntry("project.xml", projectXml(model, relBySym))
            for ((_, rel) in relBySym) {
                zip.putNextEntry(ZipEntry(rel))
                FileInputStream(File(outDir, rel)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return ExportResult(outDir, artifact)
    }

    private fun ZipOutputStream.putTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun metadataXml(projectName: String): String = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<MetaData>
        |  <Title>${xmlEscape("EP-133 project $projectName")}</Title>
        |</MetaData>
        |""".trimMargin()

    private fun projectXml(model: ExportModel, relBySym: Map<Int, String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<Project version=\"1.0\">\n")
        sb.append("  <Application name=\"EP-133 Sample Tool\" version=\"1.0\"/>\n")
        sb.append("  <Transport>\n")
        sb.append("    <Tempo unit=\"bpm\" value=\"$EXPORT_TEMPO_BPM\" min=\"20.0\" max=\"999.0\" id=\"tempo\"/>\n")
        sb.append("    <TimeSignature numerator=\"4\" denominator=\"4\" id=\"timesig\"/>\n")
        sb.append("  </Transport>\n")
        sb.append("  <Structure>\n")
        for (group in model.groups.keys) {
            sb.append("    <Track contentType=\"audio\" loaded=\"true\" id=\"track-$group\" name=\"Group $group\">\n")
            sb.append("      <Channel audioChannels=\"2\" role=\"regular\" id=\"channel-$group\">\n")
            sb.append("        <Volume unit=\"linear\" value=\"1.0\" min=\"0.0\" max=\"2.0\" id=\"vol-$group\"/>\n")
            sb.append("        <Pan unit=\"normalized\" value=\"0.5\" min=\"0.0\" max=\"1.0\" id=\"pan-$group\"/>\n")
            sb.append("      </Channel>\n")
            sb.append("    </Track>\n")
        }
        sb.append("  </Structure>\n")
        sb.append("  <Arrangement id=\"arrangement\">\n")
        sb.append("    <Lanes timeUnit=\"beats\" id=\"lanes-root\">\n")
        for ((group, pads) in model.groups) {
            sb.append("      <Lanes track=\"track-$group\" id=\"lanes-$group\">\n")
            sb.append("        <Clips id=\"clips-$group\">\n")
            pads.forEachIndexed { i, pad ->
                val rel = relBySym.getValue(pad.sample.sym)
                val fullSeconds = pad.sample.durationSeconds()
                val seconds = pad.clipSeconds(fullSeconds)
                val beats = seconds * EXPORT_TEMPO_BPM / 60.0
                val name = xmlEscape("${pad.label} ${pad.sample.name ?: pad.sample.sym.toString()}")
                val playStart = if (pad.startFrame >= 0 && pad.sample.sampleRate > 0) {
                    pad.startFrame.toDouble() / pad.sample.sampleRate
                } else {
                    0.0
                }
                sb.append("          <Clip time=\"${i * 2.0}\" duration=\"$beats\" playStart=\"$playStart\" contentTimeUnit=\"seconds\" name=\"$name\" id=\"clip-${pad.label}\">\n")
                sb.append("            <Audio sampleRate=\"${pad.sample.sampleRate}\" channels=\"${pad.sample.channels}\" duration=\"$fullSeconds\" timeUnit=\"seconds\" id=\"audio-${pad.label}\">\n")
                sb.append("              <File path=\"${xmlEscape(rel)}\"/>\n")
                sb.append("            </Audio>\n")
                sb.append("          </Clip>\n")
            }
            sb.append("        </Clips>\n")
            sb.append("      </Lanes>\n")
        }
        sb.append("    </Lanes>\n")
        sb.append("  </Arrangement>\n")
        sb.append("</Project>\n")
        return sb.toString()
    }
}
