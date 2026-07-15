package com.ep133.sampletool.domain.audio.voice

/**
 * One SFZ region resolved to the opcodes this app supports.
 * Velocity bounds are optional in the file and default to the full range.
 */
data class SfzRegion(
    val sample: String,
    val loKey: Int,
    val hiKey: Int,
    val pitchKeyCenter: Int,
    val loVel: Int = 0,
    val hiVel: Int = 127,
    /** Region gain in dB (SFZ `volume` opcode), 0 if absent. */
    val volumeDb: Float = 0f,
)

/**
 * Minimal SFZ subset parser - pure Kotlin, no Android imports.
 *
 * Supported: <region> and <group> headers (group opcodes are inherited by the
 * regions that follow), and the opcodes sample, key, lokey, hikey,
 * pitch_keycenter, lovel, hivel, volume. Key opcodes are numeric MIDI only
 * (the Donner B1 export uses numbers). Loop opcodes and everything else are
 * ignored. `//` comments are stripped.
 */
object SfzParser {

    private val OPCODE = Regex("""(\w+)=""")

    fun parse(text: String): List<SfzRegion> {
        val regions = mutableListOf<SfzRegion>()
        var groupOpcodes = mapOf<String, String>()
        var regionOpcodes: MutableMap<String, String>? = null
        var inGroupHeader = false

        fun flushRegion() {
            val ops = regionOpcodes ?: return
            regionOpcodes = null
            val merged = groupOpcodes + ops
            val sample = merged["sample"] ?: return // a region without a sample is unusable
            val key = merged["key"]?.toIntOrNull()
            val loKey = merged["lokey"]?.toIntOrNull() ?: key ?: 0
            val hiKey = merged["hikey"]?.toIntOrNull() ?: key ?: 127
            val center = merged["pitch_keycenter"]?.toIntOrNull() ?: key ?: 60
            regions += SfzRegion(
                sample = sample,
                loKey = loKey,
                hiKey = hiKey,
                pitchKeyCenter = center,
                loVel = merged["lovel"]?.toIntOrNull() ?: 0,
                hiVel = merged["hivel"]?.toIntOrNull() ?: 127,
                volumeDb = merged["volume"]?.toFloatOrNull() ?: 0f,
            )
        }

        for (rawLine in text.lineSequence()) {
            var line = rawLine.substringBefore("//").trim()
            while (line.isNotEmpty()) {
                when {
                    line.startsWith("<region>") -> {
                        flushRegion()
                        regionOpcodes = mutableMapOf()
                        inGroupHeader = false
                        line = line.removePrefix("<region>").trim()
                    }
                    line.startsWith("<group>") -> {
                        flushRegion()
                        groupOpcodes = mapOf()
                        inGroupHeader = true
                        line = line.removePrefix("<group>").trim()
                    }
                    line.startsWith("<") -> {
                        // Unsupported header (<control>, <global>, ...) - skip it
                        flushRegion()
                        inGroupHeader = false
                        line = line.substringAfter(">", "").trim()
                    }
                    else -> {
                        val parsed = parseOpcodes(line)
                        if (inGroupHeader) {
                            groupOpcodes = groupOpcodes + parsed
                        } else {
                            regionOpcodes?.putAll(parsed)
                        }
                        line = ""
                    }
                }
            }
        }
        flushRegion()
        return regions
    }

    /**
     * Parse `name=value` pairs from a line. Values run to the start of the
     * next opcode, so sample paths containing spaces survive.
     */
    private fun parseOpcodes(line: String): Map<String, String> {
        val matches = OPCODE.findAll(line).toList()
        if (matches.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for ((i, m) in matches.withIndex()) {
            val valueEnd = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            val value = line.substring(m.range.last + 1, valueEnd).trim()
            result[m.groupValues[1].lowercase()] = value
        }
        return result
    }
}
