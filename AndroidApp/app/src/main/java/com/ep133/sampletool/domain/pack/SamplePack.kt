package com.ep133.sampletool.domain.pack

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One audio one-shot in a pack. [uri] is a SAF document URI readable while the tree grant holds. */
data class KitSample(
    val name: String,   // display name (filename without extension)
    val uri: Uri,
    val category: String,
    val meta: String,   // small subtitle, e.g. "142 KB"
)

/** A category tab in a pack (a top-level subfolder such as KICKS, SNARES, …). */
data class KitCategory(val id: String, val samples: List<KitSample>)

/**
 * A KEYGROUP instrument found in an MPC expansion (.xpm) inside the pack.
 * [zones] is the parsed key map; [sampleUris] resolves each zone's sample file
 * name (lowercased) to its SAF URI, captured at scan time so loading needs no
 * second directory walk. Load with [MpcInstrumentLoader.load] and play through
 * SampledInstrumentVoice as a chord voice.
 */
data class KitInstrument(
    val name: String,
    val uri: Uri,                       // the .xpm document
    val zones: List<MpcZone>,
    val sampleUris: Map<String, Uri>,
    val meta: String,                   // small subtitle, e.g. "KEYGROUP · 4 zones"
)

/** A loaded sample pack: the folder name + its categories (+ MPC keygroup instruments). */
data class KitPack(
    val name: String,
    val categories: List<KitCategory>,
    val instruments: List<KitInstrument> = emptyList(),
) {
    val isEmpty: Boolean get() = categories.all { it.samples.isEmpty() } && instruments.isEmpty()
}

/**
 * Loads a sample pack from a SAF tree URI (picked via `OpenDocumentTree`). Each top-level subfolder
 * becomes a category (its numeric "N. " prefix stripped, uppercased); audio files anywhere under it
 * (recursing one extra level, e.g. HATS/OPEN HATS) become its samples.
 */
object SamplePackLoader {

    private val AUDIO_EXTS = setOf("wav", "aif", "aiff", "flac", "mp3", "ogg", "m4a")

    suspend fun load(context: Context, treeUri: Uri): KitPack = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext KitPack("(unreadable)", emptyList())
        val packName = root.name ?: "Sample Pack"

        val categories = root.listFiles()
            .filter { it.isDirectory }
            .sortedBy { it.name?.lowercase() ?: "" }
            .map { dir ->
                val id = categoryId(dir.name ?: "?")
                val samples = collectAudio(dir, id).sortedBy { it.name.lowercase() }
                KitCategory(id = id, samples = samples)
            }
            .filter { it.samples.isNotEmpty() }

        // MPC expansions: .xpm program files route KEYGROUP programs to the
        // instruments list and DRUM programs to extra one-shot categories.
        val mpc = MpcExpansionScanner.scan(context, root)

        KitPack(
            name = packName,
            categories = categories + mpc.drumCategories,
            instruments = mpc.instruments,
        )
    }

    /** Strip a leading "N. " / "N " ordering prefix and uppercase, e.g. "1. KICKS" -> "KICKS". */
    private fun categoryId(folderName: String): String =
        folderName.trim()
            .replace(Regex("^\\d+\\s*[.)-]?\\s*"), "")
            .uppercase()
            .ifBlank { folderName.uppercase() }

    /** All audio files directly in [dir] plus one level of nested subfolders. */
    private fun collectAudio(dir: DocumentFile, category: String): List<KitSample> {
        val out = ArrayList<KitSample>()
        for (child in dir.listFiles()) {
            when {
                child.isFile && isAudio(child.name) -> out += toSample(child, category)
                child.isDirectory -> child.listFiles().forEach { grand ->
                    if (grand.isFile && isAudio(grand.name)) out += toSample(grand, category)
                }
            }
        }
        return out
    }

    private fun isAudio(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in AUDIO_EXTS
    }

    private fun toSample(doc: DocumentFile, category: String): KitSample {
        val raw = doc.name ?: "sample"
        val display = raw.substringBeforeLast('.', raw)
        val kb = (doc.length() / 1024).coerceAtLeast(0)
        return KitSample(name = display, uri = doc.uri, category = category, meta = "$kb KB")
    }
}
