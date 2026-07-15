package com.ep133.sampletool.domain.pack

import com.ep133.sampletool.domain.audio.voice.SampledInstrument
import com.ep133.sampletool.domain.audio.voice.Zone
import com.ep133.sampletool.domain.audio.voice.WavIo
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * All .xpm schema assumptions live HERE and only here. The format is
 * community-reverse-engineered (no official spec, no real files were on hand
 * when this was written) - see docs/mpc-xpm-schema-notes.md for the assumed
 * document shape, the sources of uncertainty, and the validation checklist to
 * run once real MPC expansion files are available.
 *
 * Element/attribute lookup is case-insensitive to absorb casing drift across
 * MPC software versions.
 */
private object MpcXpmSchema {
    const val PROGRAM = "Program"
    const val PROGRAM_TYPE_ATTR = "type"
    const val TYPE_KEYGROUP = "keygroup"
    const val TYPE_DRUM = "drum"
    const val PROGRAM_NAME = "ProgramName"
    const val INSTRUMENT = "Instrument"
    const val LOW_NOTE = "LowNote"
    const val HIGH_NOTE = "HighNote"
    const val LAYER = "Layer"
    const val LAYER_NUMBER_ATTR = "number"
    const val SAMPLE_NAME = "SampleName"   // no extension; ".wav" appended
    const val SAMPLE_FILE = "SampleFile"   // preferred when present
    const val ROOT_NOTE = "RootNote"       // assumed raw MIDI number
    const val VEL_START = "VelStart"
    const val VEL_END = "VelEnd"
    const val VOLUME = "Volume"            // assumed linear, 1.0 = unity

    const val DEFAULT_LOW_NOTE = 0
    const val DEFAULT_HIGH_NOTE = 127
    const val DEFAULT_VOLUME = 1f
}

/** How an .xpm program routes into the app. */
enum class MpcProgramType { KEYGROUP, DRUM }

/**
 * One keygroup zone in the best-effort subset: key range plus the FIRST
 * velocity layer's sample, root note, and linear gain.
 */
data class MpcZone(
    val sampleFile: String,
    val loKey: Int,
    val hiKey: Int,
    val rootNote: Int,
    val gain: Float = 1f,
)

/** One DRUM-program one-shot (pad order = document order). */
data class MpcDrumSample(val sampleFile: String)

/** A parsed .xpm program. Exactly one of [zones] / [drumSamples] is populated. */
data class MpcProgram(
    val name: String,
    val type: MpcProgramType,
    val zones: List<MpcZone> = emptyList(),
    val drumSamples: List<MpcDrumSample> = emptyList(),
)

/**
 * Parse outcome: [program] is null only when nothing usable was found;
 * [warnings] records every field or entry the parser had to skip or default,
 * so partial imports are never silent.
 */
data class MpcParseResult(
    val program: MpcProgram?,
    val warnings: List<String> = emptyList(),
)

/**
 * Best-effort parser for Akai MPC .xpm program files - pure Kotlin/JVM, no
 * Android imports. Tolerant by contract: unknown fields ignored, missing
 * optional fields defaulted, malformed input returns warnings, never throws.
 */
object MpcExpansionParser {

    /**
     * Parse [xml] (the .xpm file text). [fallbackName] names the program when
     * `<ProgramName>` is absent - pass the file name without extension.
     */
    fun parse(xml: String, fallbackName: String = "MPC Program"): MpcParseResult {
        val warnings = mutableListOf<String>()

        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return MpcParseResult(null, listOf("Not a readable XML document: ${e.message ?: e}"))
        }

        val programEl = firstDescendant(doc.documentElement, MpcXpmSchema.PROGRAM)
            ?: return MpcParseResult(null, listOf("No <${MpcXpmSchema.PROGRAM}> element found"))

        val typeText = attr(programEl, MpcXpmSchema.PROGRAM_TYPE_ATTR)
        val type = when (typeText?.lowercase()) {
            MpcXpmSchema.TYPE_KEYGROUP -> MpcProgramType.KEYGROUP
            MpcXpmSchema.TYPE_DRUM -> MpcProgramType.DRUM
            else -> return MpcParseResult(
                null,
                listOf("Unrecognized program type \"${typeText ?: "(none)"}\" - only Keygroup and Drum are supported"),
            )
        }

        val name = firstDescendant(programEl, MpcXpmSchema.PROGRAM_NAME)
            ?.textContent?.trim()?.ifBlank { null } ?: fallbackName

        val instruments = descendants(programEl, MpcXpmSchema.INSTRUMENT)
        if (instruments.isEmpty()) {
            return MpcParseResult(null, warnings + "\"$name\": no <${MpcXpmSchema.INSTRUMENT}> entries")
        }

        val program = when (type) {
            MpcProgramType.KEYGROUP -> {
                val zones = instruments.mapIndexedNotNull { i, inst -> keygroupZone(inst, i, warnings) }
                if (zones.isEmpty()) {
                    warnings += "\"$name\": no usable keygroup zones"
                    null
                } else MpcProgram(name, type, zones = zones)
            }
            MpcProgramType.DRUM -> {
                val samples = instruments.mapIndexedNotNull { i, inst ->
                    firstLayerSample(inst)?.let { MpcDrumSample(it) }
                        ?: run { warnings += "\"$name\": pad entry ${i + 1} has no sample - skipped"; null }
                }
                if (samples.isEmpty()) {
                    warnings += "\"$name\": no usable drum samples"
                    null
                } else MpcProgram(name, type, drumSamples = samples)
            }
        }
        return MpcParseResult(program, warnings)
    }

    /**
     * Convert a parsed KEYGROUP program to a playable [SampledInstrument].
     * [resolveSample] maps each zone's sample file name to WAV bytes (SAF
     * documents at import time, in-memory fixtures in tests). Zones whose
     * sample fails to resolve or decode are skipped with a warning appended
     * to the returned list; throws only if NO zone resolves.
     */
    fun toSampledInstrument(
        program: MpcProgram,
        resolveSample: (String) -> ByteArray,
        warnings: MutableList<String> = mutableListOf(),
    ): SampledInstrument {
        require(program.type == MpcProgramType.KEYGROUP) { "Only KEYGROUP programs convert to instruments" }
        val zones = program.zones.mapNotNull { z ->
            try {
                val wav = WavIo.read(resolveSample(z.sampleFile))
                Zone(
                    samples = wav.samples,
                    sampleRate = wav.sampleRate,
                    loKey = z.loKey,
                    hiKey = z.hiKey,
                    pitchKeyCenter = z.rootNote,
                    gain = z.gain,
                )
            } catch (e: Exception) {
                warnings += "\"${program.name}\": couldn't load ${z.sampleFile} - ${e.message ?: e}"
                null
            }
        }
        require(zones.isNotEmpty()) { "\"${program.name}\": none of the zone samples could be loaded" }
        return SampledInstrument(zones)
    }

    // ── Element extraction ───────────────────────────────────────────────────

    /** Zone from one keygroup <Instrument>: key range + first sample-bearing layer. */
    private fun keygroupZone(inst: Element, index: Int, warnings: MutableList<String>): MpcZone? {
        val loKey = intChild(inst, MpcXpmSchema.LOW_NOTE) ?: MpcXpmSchema.DEFAULT_LOW_NOTE
        val hiKey = intChild(inst, MpcXpmSchema.HIGH_NOTE) ?: MpcXpmSchema.DEFAULT_HIGH_NOTE
        val layer = firstSampleLayer(inst)
        if (layer == null) {
            warnings += "keygroup entry ${index + 1} has no layer with a sample - skipped"
            return null
        }
        val sample = layerSampleFile(layer) ?: return null // firstSampleLayer guarantees non-null
        // Missing RootNote: default to the key-range midpoint so the zone still plays.
        val root = intChild(layer, MpcXpmSchema.ROOT_NOTE) ?: ((loKey + hiKey) / 2).also {
            warnings += "keygroup entry ${index + 1} ($sample) has no RootNote - assuming $it"
        }
        val gain = floatChild(layer, MpcXpmSchema.VOLUME) ?: MpcXpmSchema.DEFAULT_VOLUME
        return MpcZone(sampleFile = sample, loKey = loKey, hiKey = hiKey, rootNote = root, gain = gain)
    }

    /**
     * Best-effort-subset rule: use only the FIRST velocity layer that carries
     * a sample, ordered by the Layer `number` attribute (document order when
     * absent). Further velocity layers are intentionally dropped.
     */
    private fun firstSampleLayer(inst: Element): Element? =
        descendants(inst, MpcXpmSchema.LAYER)
            .sortedBy { attr(it, MpcXpmSchema.LAYER_NUMBER_ATTR)?.toIntOrNull() ?: Int.MAX_VALUE }
            .firstOrNull { layerSampleFile(it) != null }

    private fun firstLayerSample(inst: Element): String? =
        firstSampleLayer(inst)?.let { layerSampleFile(it) }

    /** SampleFile verbatim when present; else SampleName + ".wav". */
    private fun layerSampleFile(layer: Element): String? {
        firstDescendant(layer, MpcXpmSchema.SAMPLE_FILE)?.textContent?.trim()
            ?.ifBlank { null }?.let { return it }
        return firstDescendant(layer, MpcXpmSchema.SAMPLE_NAME)?.textContent?.trim()
            ?.ifBlank { null }?.let { "$it.wav" }
    }

    // ── Case-insensitive DOM helpers ─────────────────────────────────────────

    private fun descendants(root: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = root.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            if (el.tagName.equals(tag, ignoreCase = true)) out += el
        }
        return out
    }

    private fun firstDescendant(root: Element, tag: String): Element? =
        descendants(root, tag).firstOrNull()

    private fun attr(el: Element, name: String): String? {
        val attrs = el.attributes ?: return null
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.equals(name, ignoreCase = true)) {
                return a.nodeValue?.trim()?.ifBlank { null }
            }
        }
        return null
    }

    private fun intChild(el: Element, tag: String): Int? =
        firstDescendant(el, tag)?.textContent?.trim()?.toIntOrNull()

    private fun floatChild(el: Element, tag: String): Float? =
        firstDescendant(el, tag)?.textContent?.trim()?.toFloatOrNull()
}
