package com.ep133.sampletool.domain.pack

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ep133.sampletool.domain.audio.voice.SampledInstrument

private const val TAG = "EP133APP"

/**
 * Finds MPC .xpm program files inside a picked pack folder (root plus one
 * level of subfolders, matching SamplePackLoader's audio walk) and routes
 * them: KEYGROUP programs become [KitInstrument]s, DRUM programs become
 * one-shot [KitCategory]s whose samples resolve to sibling WAV documents.
 *
 * Parse warnings and unresolvable samples are logged and the entry skipped -
 * a bad .xpm never sinks the rest of the pack.
 */
object MpcExpansionScanner {

    data class ScanResult(
        val instruments: List<KitInstrument> = emptyList(),
        val drumCategories: List<KitCategory> = emptyList(),
    )

    fun scan(context: Context, root: DocumentFile): ScanResult {
        val instruments = mutableListOf<KitInstrument>()
        val drumCategories = mutableListOf<KitCategory>()

        for ((xpm, dir) in findXpmFiles(root)) {
            val fileName = xpm.name ?: continue
            val xml = readText(context, xpm.uri)
            if (xml == null) {
                Log.e(TAG, "MPC: couldn't read $fileName")
                continue
            }

            val result = MpcExpansionParser.parse(xml, fileName.substringBeforeLast('.'))
            result.warnings.forEach { Log.w(TAG, "MPC: $fileName: $it") }
            val program = result.program ?: continue

            // Sibling audio documents by lowercased name - the .xpm references
            // samples relative to its own folder.
            val siblings = dir.listFiles()
                .filter { it.isFile && it.name != null }
                .associateBy { it.name!!.lowercase() }

            when (program.type) {
                MpcProgramType.KEYGROUP -> {
                    val sampleUris = mutableMapOf<String, Uri>()
                    var missing = 0
                    for (zone in program.zones) {
                        val doc = siblings[zone.sampleFile.lowercase()]
                        if (doc != null) sampleUris[zone.sampleFile.lowercase()] = doc.uri
                        else { missing++; Log.w(TAG, "MPC: $fileName: sample ${zone.sampleFile} not found next to program") }
                    }
                    if (sampleUris.isEmpty()) {
                        Log.w(TAG, "MPC: $fileName: no zone samples found - instrument skipped")
                        continue
                    }
                    instruments += KitInstrument(
                        name = program.name,
                        uri = xpm.uri,
                        zones = program.zones,
                        sampleUris = sampleUris,
                        meta = "KEYGROUP · ${program.zones.size} zones" +
                            if (missing > 0) " · $missing missing" else "",
                    )
                }
                MpcProgramType.DRUM -> {
                    val id = "MPC · ${program.name.uppercase()}"
                    val samples = program.drumSamples.mapNotNull { drum ->
                        val doc = siblings[drum.sampleFile.lowercase()]
                        if (doc == null) {
                            Log.w(TAG, "MPC: $fileName: drum sample ${drum.sampleFile} not found - skipped")
                            null
                        } else {
                            val kb = (doc.length() / 1024).coerceAtLeast(0)
                            KitSample(
                                name = drum.sampleFile.substringBeforeLast('.'),
                                uri = doc.uri,
                                category = id,
                                meta = "$kb KB",
                            )
                        }
                    }
                    if (samples.isNotEmpty()) drumCategories += KitCategory(id = id, samples = samples)
                    else Log.w(TAG, "MPC: $fileName: no drum samples resolved - category skipped")
                }
            }
        }
        return ScanResult(
            instruments = instruments.sortedBy { it.name.lowercase() },
            drumCategories = drumCategories.sortedBy { it.id.lowercase() },
        )
    }

    /** .xpm files in [root] and one level of subfolders, paired with their containing folder. */
    private fun findXpmFiles(root: DocumentFile): List<Pair<DocumentFile, DocumentFile>> {
        val out = mutableListOf<Pair<DocumentFile, DocumentFile>>()
        fun scanDir(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isFile && child.name?.lowercase()?.endsWith(".xpm") == true) {
                    out += child to dir
                }
            }
        }
        scanDir(root)
        root.listFiles().filter { it.isDirectory }.forEach { scanDir(it) }
        return out
    }

    private fun readText(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "MPC: failed reading $uri", e)
        null
    }
}

/**
 * Loads a scanned [KitInstrument] into a playable [SampledInstrument].
 * IO-bound (reads every zone WAV through SAF) - call from Dispatchers.IO.
 */
object MpcInstrumentLoader {

    fun load(context: Context, instrument: KitInstrument): SampledInstrument {
        val program = MpcProgram(
            name = instrument.name,
            type = MpcProgramType.KEYGROUP,
            zones = instrument.zones,
        )
        val warnings = mutableListOf<String>()
        val loaded = MpcExpansionParser.toSampledInstrument(program, { sampleFile ->
            val uri = instrument.sampleUris[sampleFile.lowercase()]
                ?: throw IllegalArgumentException("sample not in pack")
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("unreadable sample")
        }, warnings)
        warnings.forEach { Log.w(TAG, "MPC: $it") }
        return loaded
    }
}
