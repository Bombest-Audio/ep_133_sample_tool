package com.ep133.sampletool.domain.backup

import android.util.Log
import com.ep133.sampletool.domain.audio.WavEncoder
import com.ep133.sampletool.domain.midi.MIDIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "EP133APP"

/** Group directory names under `<project>/groups`, device order. */
private val GROUP_NAMES = listOf("A", "B", "C", "D")

/**
 * Writes the sidecar manifest directory next to a project `.tar` backup (ROADMAP 999.10).
 *
 * Layout (see [ProjectManifestLoader] for the read side):
 * `<tar name>.manifest/manifest.json` + `<tar name>.manifest/samples/<sym>.wav`.
 *
 * The walk uses the device's node-ID FILE tree (docs/ep133-sysex-protocol.md): the project node's
 * `groups` child, its A-D group directories, and each group's 12 pad FILE nodes, whose METADATA
 * GET JSON is the pad's sound binding (`sym`, `sound.playmode`, `sample.start`/`end`,
 * `envelope.*`, `sound.pitch`/`pan`, ...). Every `sym > 0` sample is downloaded from /sounds and
 * exported as a device-format WAV (raw s16 PCM wrapped by [WavEncoder]; already-RIFF bytes are
 * written as-is).
 *
 * **Best-effort contract:** the `.tar` is written before this runs and stays valid no matter what
 * happens here. Any pad, sample-metadata, or sample-download failure is logged, recorded in the
 * manifest's `skipped` list, and the walk continues. If nothing at all could be read (device
 * unplugged mid-backup), no manifest directory is left behind and null is returned.
 */
class ProjectManifestWriter(private val midi: MIDIRepository) {

    private data class PadRead(val group: String, val pad: String, val metadata: JSONObject)

    /**
     * Build and write the manifest for [slot] next to its just-written [tarFile].
     *
     * @param slotNumber the 1..9 project slot number recorded in manifest.json.
     * @return the manifest directory, or null when nothing could be read from the device.
     */
    suspend fun writeManifest(
        slot: MIDIRepository.ProjectSlot,
        tarFile: File,
        slotNumber: Int,
    ): File? {
        val skipped = mutableListOf<String>()
        val pads = mutableListOf<PadRead>()
        val sampleMeta = LinkedHashMap<Int, JSONObject>()

        // Phase 1 — one locked session for the whole metadata walk (unlocked primitives inside).
        midi.withFileSession {
            val groupsDir = midi.listAllChildren(slot.nodeId).firstOrNull { it.name == "groups" }
            if (groupsDir == null) {
                skipped += "project ${slot.name}: no groups directory found"
                return@withFileSession
            }
            val groups = midi.listAllChildren(groupsDir.nodeId).filter { it.name in GROUP_NAMES }
            for (group in groups.sortedBy { it.name }) {
                val padNodes = try {
                    midi.listAllChildren(group.nodeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Manifest: listing group ${group.name} failed", e)
                    skipped += "group ${group.name}: pad listing failed"
                    continue
                }
                for (pad in padNodes.sortedBy { it.name }) {
                    try {
                        pads += PadRead(group.name, pad.name, midi.getMetadataJson(pad.nodeId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Manifest: pad ${group.name}/${pad.name} metadata failed", e)
                        skipped += "pad ${group.name}/${pad.name}: metadata read failed"
                    }
                }
            }
            // Sample metadata (channels / samplerate / name) for every referenced sym.
            val syms = pads.mapNotNull { it.metadata.optInt("sym", 0).takeIf { s -> s > 0 } }.distinct()
            for (sym in syms) {
                try {
                    sampleMeta[sym] = midi.getMetadataJson(sym)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Manifest: sample $sym metadata failed", e)
                    skipped += "sample $sym: metadata read failed"
                    sampleMeta[sym] = JSONObject()
                }
            }
        }

        if (pads.isEmpty()) {
            Log.w(TAG, "Manifest: no pad metadata readable for ${slot.name} — skipping sidecar (tar stays valid). Skipped: $skipped")
            return null
        }

        // Phase 2 — sample downloads OUTSIDE the session (getFileBytes is self-locking).
        val sampleBytes = LinkedHashMap<Int, ByteArray>()
        for (sym in sampleMeta.keys) {
            val bytes = try {
                midi.getFileBytes(sym)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Manifest: sample $sym download failed", e)
                null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                sampleBytes[sym] = bytes
            } else {
                skipped += "sample $sym: download failed"
            }
        }

        // Phase 3 — write the sidecar directory.
        return withContext(Dispatchers.IO) {
            val dir = ProjectManifestLoader.manifestDirFor(tarFile)
            val samplesDir = File(dir, ProjectManifestLoader.SAMPLES_DIR).also { it.mkdirs() }

            val sampleEntries = JSONArray()
            for ((sym, bytes) in sampleBytes) {
                val meta = sampleMeta[sym] ?: JSONObject()
                val channels = meta.optInt("channels", 1)
                val sampleRate = meta.optInt("samplerate", 46875)
                val rel = "${ProjectManifestLoader.SAMPLES_DIR}/$sym.wav"
                File(dir, rel).writeBytes(toWav(bytes, channels, sampleRate))
                sampleEntries.put(
                    JSONObject()
                        .put("sym", sym)
                        .put("file", rel)
                        .put("name", meta.optString("name").takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                        .put("channels", channels)
                        .put("samplerate", sampleRate),
                )
            }

            val padEntries = JSONArray()
            for (p in pads) {
                padEntries.put(
                    JSONObject()
                        .put("group", p.group)
                        .put("pad", p.pad)
                        .put("metadata", p.metadata),
                )
            }

            val root = JSONObject()
                .put("version", ProjectManifestLoader.VERSION)
                .put("project_slot", slotNumber)
                .put("project_name", slot.name)
                .put("created_at", System.currentTimeMillis())
                .put("pads", padEntries)
                .put("samples", sampleEntries)
                .put("skipped", JSONArray(skipped))

            File(dir, ProjectManifestLoader.MANIFEST_FILENAME)
                .writeText(root.toString(2), Charsets.UTF_8)
            if (skipped.isNotEmpty()) {
                Log.w(TAG, "Manifest for ${tarFile.name} written with ${skipped.size} skipped item(s): $skipped")
            }
            dir
        }
    }

    /**
     * Wrap device sample [bytes] as a RIFF WAV. Device /sounds nodes hold raw s16 LE PCM
     * (`NNN.pcm`), so the common path converts to samples and encodes via [WavEncoder]; bytes
     * already carrying a device-format RIFF header pass through unchanged. An odd trailing byte
     * (malformed length) is dropped rather than failing the export.
     */
    private fun toWav(bytes: ByteArray, channels: Int, sampleRate: Int): ByteArray {
        if (WavEncoder.isAlreadyDeviceFormat(bytes)) return bytes
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes, 0, samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return WavEncoder.encodeWav(samples, sampleRate = sampleRate, channels = channels)
    }
}
