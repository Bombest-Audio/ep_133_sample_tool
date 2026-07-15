package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.WavEncoder
import com.ep133.sampletool.domain.backup.ProjectManifest
import com.ep133.sampletool.domain.backup.ProjectManifestLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared synthetic-manifest builder for the DAW exporter tests (ROADMAP 999.6).
 *
 * Writes a real sidecar manifest directory (manifest.json + samples/<sym>.wav) into a temp
 * dir and loads it back through [ProjectManifestLoader], so the tests exercise the same
 * read model the app uses.
 *
 * Layout: pad A/01 -> sym 101 (pitched +3.5, panned right, half gain, trimmed to frames
 * 100..4788), pad A/02 -> sym 102, pad B/01 -> sym 102 (shared sample), pad C/05 -> sym 0
 * (unassigned), pad D/12 -> sym 999 whose WAV is intentionally absent (skip path).
 */
object DawExportFixtures {

    const val SAMPLE_RATE = 46875

    fun writeManifestDir(root: File): File {
        val dir = File(root, "Fixture-EP133-P03.manifest").also { it.mkdirs() }
        val samplesDir = File(dir, "samples").also { it.mkdirs() }

        // Two real WAVs: 4788 frames (~0.1 s) mono and 2000 frames mono.
        File(samplesDir, "101.wav").writeBytes(WavEncoder.encodeWav(ShortArray(4788), SAMPLE_RATE, 1))
        File(samplesDir, "102.wav").writeBytes(WavEncoder.encodeWav(ShortArray(2000), SAMPLE_RATE, 1))

        fun pad(group: String, pad: String, metadata: JSONObject) = JSONObject()
            .put("group", group)
            .put("pad", pad)
            .put("metadata", metadata)

        val pads = JSONArray()
            .put(
                pad(
                    "A", "01",
                    JSONObject()
                        .put("sym", 101)
                        .put("sound.pitch", 3.5)
                        .put("sound.pan", 50)
                        .put("sound.amplitude", 50)
                        .put("sample.start", 100)
                        .put("sample.end", 4788),
                ),
            )
            .put(pad("A", "02", JSONObject().put("sym", 102)))
            .put(pad("B", "01", JSONObject().put("sym", 102)))
            .put(pad("C", "05", JSONObject().put("sym", 0)))
            .put(pad("D", "12", JSONObject().put("sym", 999)))

        val samples = JSONArray()
            .put(sampleEntry(101, "kick one"))
            .put(sampleEntry(102, "snare two"))
            .put(sampleEntry(999, "ghost")) // file intentionally not written

        val rootJson = JSONObject()
            .put("version", ProjectManifestLoader.VERSION)
            .put("project_slot", 3)
            .put("project_name", "03")
            .put("created_at", 1720000000000L)
            .put("pads", pads)
            .put("samples", samples)
            .put("skipped", JSONArray())

        File(dir, ProjectManifestLoader.MANIFEST_FILENAME)
            .writeText(rootJson.toString(2), Charsets.UTF_8)
        return dir
    }

    private fun sampleEntry(sym: Int, name: String): JSONObject = JSONObject()
        .put("sym", sym)
        .put("file", "samples/$sym.wav")
        .put("name", name)
        .put("channels", 1)
        .put("samplerate", SAMPLE_RATE)

    fun loadManifest(root: File): ProjectManifest =
        requireNotNull(ProjectManifestLoader.load(writeManifestDir(root))) {
            "fixture manifest failed to load"
        }
}
