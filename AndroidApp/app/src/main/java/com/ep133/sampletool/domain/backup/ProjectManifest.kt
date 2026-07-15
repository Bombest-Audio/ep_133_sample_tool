package com.ep133.sampletool.domain.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Read model for a project-backup sidecar manifest (ROADMAP 999.10 / issue #54).
 *
 * A project backup's `.tar` stays the opaque, authoritative restore artifact. The manifest is an
 * additive sidecar directory next to it:
 *
 * ```
 * MyBeat-EP133-P03-2026-07-14-1230.tar            (opaque device archive)
 * MyBeat-EP133-P03-2026-07-14-1230.manifest/      (sidecar, additive)
 *   manifest.json                                 (slot, timestamp, per-pad metadata, sample index)
 *   samples/193.wav                               (referenced samples as device-format WAVs)
 * ```
 *
 * This model is the seam the 999.11 offline mode consumes: load the manifest, resolve sample
 * files, and render/audition a project without a device attached.
 */
data class ProjectManifest(
    /** Manifest schema version (currently [ProjectManifestLoader.VERSION]). */
    val version: Int,
    /** 1..9 device project slot number the backup came from. */
    val projectSlot: Int,
    /** Device-reported slot name (e.g. "03"). */
    val projectName: String,
    /** Backup creation time, epoch millis. */
    val createdAtMillis: Long,
    /** Per-pad sound bindings for groups A-D, device order. */
    val pads: List<ManifestPad>,
    /** Referenced samples, indexed by their device `sym` node ID. */
    val samples: List<ManifestSample>,
    /** Human-readable notes for anything the best-effort writer had to skip. */
    val skipped: List<String>,
)

/** One pad's sound binding: group letter, pad name ("01".."12"), and its raw metadata JSON. */
data class ManifestPad(
    val group: String,
    val pad: String,
    /** The pad node's METADATA GET JSON verbatim (sym, sound.*, sample.*, envelope.*, ...). */
    val metadataJson: String,
) {
    /** Bound sample node ID, or 0 for an unassigned pad (`{"sym":0}`). */
    val sym: Int
        get() = try {
            JSONObject(metadataJson).optInt("sym", 0)
        } catch (_: JSONException) {
            0
        }
}

/** One exported sample referenced by at least one pad. */
data class ManifestSample(
    /** Device node ID of the sample in /sounds — the value pads reference via `sym`. */
    val sym: Int,
    /** Path of the exported WAV relative to the manifest directory (e.g. "samples/193.wav"). */
    val relativePath: String,
    /** Device-reported sample name, if its metadata was readable. */
    val name: String?,
    /** Channel count from the sample's device metadata (1 or 2). */
    val channels: Int,
    /** Sample rate from the sample's device metadata (device-native 46875). */
    val sampleRate: Int,
    /** Resolved on-disk WAV, or null if the file is missing (loader resolves this). */
    val file: File?,
)

/**
 * Parses `manifest.json` out of a sidecar manifest directory and resolves its sample files.
 * Pure file + JSON work — no device, no Android Context — so it unit-tests against temp dirs.
 */
object ProjectManifestLoader {

    /** Current manifest schema version written by ProjectManifestWriter. */
    const val VERSION = 1

    /** Sidecar directory suffix: `<tar name without .tar>.manifest`. */
    const val MANIFEST_DIR_SUFFIX = ".manifest"

    /** Manifest filename inside the sidecar directory. */
    const val MANIFEST_FILENAME = "manifest.json"

    /** Subdirectory of the sidecar holding exported WAVs. */
    const val SAMPLES_DIR = "samples"

    /** The sidecar manifest directory that pairs with [tarFile] (may not exist). */
    fun manifestDirFor(tarFile: File): File =
        File(tarFile.parentFile, tarFile.name.removeSuffix(".tar") + MANIFEST_DIR_SUFFIX)

    /** True iff [tarFile] has a sidecar directory containing a manifest.json. */
    fun hasManifest(tarFile: File): Boolean =
        File(manifestDirFor(tarFile), MANIFEST_FILENAME).isFile

    /**
     * Load and parse the manifest inside [manifestDir]. Sample entries whose WAV is missing on
     * disk keep a null [ManifestSample.file] rather than failing the whole load.
     *
     * @return the parsed manifest, or null if manifest.json is absent or unparseable.
     */
    fun load(manifestDir: File): ProjectManifest? {
        val jsonFile = File(manifestDir, MANIFEST_FILENAME)
        if (!jsonFile.isFile) return null
        val root = try {
            JSONObject(jsonFile.readText(Charsets.UTF_8))
        } catch (_: JSONException) {
            return null
        }

        val pads = root.optJSONArray("pads").orEmpty().mapObjects { pad ->
            ManifestPad(
                group = pad.optString("group"),
                pad = pad.optString("pad"),
                metadataJson = pad.optJSONObject("metadata")?.toString() ?: "{}",
            )
        }

        val samples = root.optJSONArray("samples").orEmpty().mapObjects { s ->
            val rel = s.optString("file")
            val wav = File(manifestDir, rel).takeIf { rel.isNotEmpty() && it.isFile }
            ManifestSample(
                sym = s.optInt("sym"),
                relativePath = rel,
                name = s.optString("name").takeIf { it.isNotEmpty() },
                channels = s.optInt("channels", 1),
                sampleRate = s.optInt("samplerate", 46875),
                file = wav,
            )
        }

        val skipped = root.optJSONArray("skipped").orEmpty().let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }

        return ProjectManifest(
            version = root.optInt("version", VERSION),
            projectSlot = root.optInt("project_slot"),
            projectName = root.optString("project_name"),
            createdAtMillis = root.optLong("created_at"),
            pads = pads,
            samples = samples,
            skipped = skipped,
        )
    }

    /** Load the manifest paired with [tarFile], or null if it has none. */
    fun loadFor(tarFile: File): ProjectManifest? = load(manifestDirFor(tarFile))

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
}
