package com.ep133.sampletool.domain.staging

import com.ep133.sampletool.domain.audio.WavEncoder
import com.ep133.sampletool.domain.midi.ConvertedSample
import java.io.File
import java.io.IOException

/**
 * One staged (locally processed) sample: a playable WAV file in app storage.
 *
 * [name] is the file name including the ".wav" extension; [file] is the on-disk WAV
 * (44-byte RIFF header + s16 LE PCM) so MediaPlayer can audition it directly.
 */
data class StagedSample(val name: String, val file: File, val sizeBytes: Long)

/**
 * Local staging area for prepped samples, backed by a directory in app storage.
 *
 * Prep never mutates the user's source files: processed copies are written HERE (as
 * complete WAV files, so they are locally auditionable) and only these copies are
 * renamed, duplicated, or deleted. Device-side file operations are out of scope -
 * this class only touches the local filesystem.
 *
 * Names must already be device-safe (`SampleImportManager.sanitizeName` output:
 * `[A-Za-z0-9 _-]` basename + ".wav"); anything else is rejected with
 * [IllegalArgumentException] so a hostile name can never traverse out of [dir].
 *
 * Pure java.io - fully unit-testable on the JVM with temp directories. Not internally
 * synchronized; callers serialize access (the ViewModel runs all staging ops on one scope).
 */
class SampleStagingStore(private val dir: File) {

    /** Write [sample] to the staging area as [name], overwriting any existing copy. */
    fun stage(name: String, sample: ConvertedSample): StagedSample {
        val file = fileFor(name)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create staging dir: $dir")
        file.writeBytes(
            WavEncoder.encodeWavBytes(sample.pcm, sample.sampleRate, sample.channels)
        )
        return StagedSample(name, file, file.length())
    }

    /** List staged samples, sorted by name. Missing staging dir reads as empty. */
    fun list(): List<StagedSample> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            .orEmpty()
            .sortedBy { it.name }
            .map { StagedSample(it.name, it, it.length()) }

    /**
     * Rename staged sample [from] to [to].
     *
     * @return the renamed entry, or null if [from] doesn't exist, [to] already exists
     *         (never silently clobber another staged item), or the filesystem rename fails.
     */
    fun rename(from: String, to: String): StagedSample? {
        val src = fileFor(from)
        val dst = fileFor(to)
        if (!src.isFile || dst.exists()) return null
        if (!src.renameTo(dst)) return null
        return StagedSample(to, dst, dst.length())
    }

    /** Delete staged sample [name]. Returns true if the file existed and was removed. */
    fun delete(name: String): Boolean = fileFor(name).delete()

    /**
     * Duplicate staged sample [name] as "<base> copy.wav" ("<base> copy 2.wav", ... on
     * collision). Returns the new entry, or null if the source doesn't exist or no free
     * name is found within [MAX_COPIES] attempts.
     */
    fun duplicate(name: String): StagedSample? {
        val src = fileFor(name)
        if (!src.isFile) return null
        val base = name.removeSuffix(".wav")
        for (n in 1..MAX_COPIES) {
            val candidate = if (n == 1) "$base copy.wav" else "$base copy $n.wav"
            val dst = fileFor(candidate)
            if (dst.exists()) continue
            src.copyTo(dst)
            return StagedSample(candidate, dst, dst.length())
        }
        return null
    }

    /** Resolve [name] inside [dir], rejecting anything that isn't a safe staged WAV name. */
    private fun fileFor(name: String): File {
        require(SAFE_NAME.matches(name)) { "Unsafe staged sample name: $name" }
        return File(dir, name)
    }

    companion object {
        /** Sanitized basename (matches SampleImportManager's charset) + ".wav". */
        private val SAFE_NAME = Regex("[A-Za-z0-9 _-]+\\.wav")

        /** Cap on numbered "copy N" probing in [duplicate]. */
        private const val MAX_COPIES = 100
    }
}
