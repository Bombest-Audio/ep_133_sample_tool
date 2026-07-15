package com.ep133.sampletool.domain.audio.voice

import kotlin.math.min
import kotlin.math.pow

/**
 * One playable multisample zone: decoded audio plus its SFZ key/vel mapping.
 * [gain] is linear (SfzRegion.volumeDb already converted).
 */
data class Zone(
    val samples: FloatArray,
    val sampleRate: Int,
    val loKey: Int,
    val hiKey: Int,
    val pitchKeyCenter: Int,
    val loVel: Int = 0,
    val hiVel: Int = 127,
    val gain: Float = 1f,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A multisampled instrument: a set of [Zone]s loaded from an SFZ file.
 *
 * TODO(donner-b1): the real Donner B1 electric piano multisample is NOT in the
 * repo yet (no fabricated audio). Drop the export at
 *   AndroidApp/app/src/main/assets/instruments/donner_b1/donner_b1.sfz
 * with its WAVs alongside, load it with [fromSfz], and it plays through
 * [SampledInstrumentVoice] with no code changes. Until then
 * [PlaceholderInstrument.generate] provides a synthesized stand-in.
 */
class SampledInstrument(val zones: List<Zone>) {

    init {
        require(zones.isNotEmpty()) { "Instrument needs at least one zone" }
    }

    /**
     * Pick the zone for [note] at [velocity]: among zones whose key range
     * contains the note (and velocity range the velocity), the one whose
     * pitch_keycenter is nearest; if none contains it, fall back to the
     * globally nearest keycenter so out-of-range notes still sound.
     */
    fun zoneFor(note: Int, velocity: Int): Zone {
        val inRange = zones.filter {
            note in it.loKey..it.hiKey && velocity in it.loVel..it.hiVel
        }
        val candidates = inRange.ifEmpty { zones }
        return candidates.minBy { kotlin.math.abs(it.pitchKeyCenter - note) }
    }

    companion object {
        /**
         * Build an instrument from SFZ text. [resolveSample] maps each region's
         * sample path to WAV bytes (assets, files, or test fixtures).
         */
        fun fromSfz(sfzText: String, resolveSample: (String) -> ByteArray): SampledInstrument {
            val zones = SfzParser.parse(sfzText).map { region ->
                val wav = WavIo.read(resolveSample(region.sample))
                Zone(
                    samples = wav.samples,
                    sampleRate = wav.sampleRate,
                    loKey = region.loKey,
                    hiKey = region.hiKey,
                    pitchKeyCenter = region.pitchKeyCenter,
                    loVel = region.loVel,
                    hiVel = region.hiVel,
                    gain = 10f.pow(region.volumeDb / 20f),
                )
            }
            return SampledInstrument(zones)
        }
    }
}

/**
 * [RenderableVoice] that plays a [SampledInstrument]: nearest-zone selection,
 * linear-interpolation pitch shift from the zone's keycenter (the same
 * interpolation scheme as domain/audio/Resampler, applied to float data with a
 * pitch ratio instead of a Short rate conversion), zone gain, and a short
 * fade at note edges to avoid clicks.
 *
 * This voice IS the playback path for sampled instruments - live preview and
 * bake both call [render], satisfying the Phase 8 same-code-path contract.
 */
class SampledInstrumentVoice(private val instrument: SampledInstrument) : RenderableVoice {

    override fun render(
        chords: List<List<Int>>,
        bpm: Int,
        sampleRate: Int,
        velocity: Int,
    ): FloatArray {
        val framesPerChord = RenderableVoice.framesPerBar(bpm, sampleRate)
        val out = FloatArray(framesPerChord * chords.size)

        for ((c, chord) in chords.withIndex()) {
            val offset = c * framesPerChord
            // Velocity scaling plus a fixed polyphony headroom factor: the
            // densest chord in ChordProgressions has 6 notes, and 6 x 0.16 x
            // full-scale zones stays under 0 dBFS.
            val noteGain = (velocity / 127f) * 0.16f
            for (note in chord) {
                mixNote(out, offset, framesPerChord, note, velocity, noteGain, sampleRate)
            }
        }
        return out
    }

    private fun mixNote(
        out: FloatArray,
        offset: Int,
        maxFrames: Int,
        note: Int,
        velocity: Int,
        noteGain: Float,
        outRate: Int,
    ) {
        val zone = instrument.zoneFor(note, velocity)
        // Playback step through the zone's samples: pitch shift from keycenter
        // combined with sample-rate conversion zoneRate -> outRate.
        val pitchRatio = 2.0.pow((note - zone.pitchKeyCenter) / 12.0)
        val step = pitchRatio * zone.sampleRate / outRate

        val available = ((zone.samples.size - 1) / step).toInt()
        val frames = min(maxFrames, available)
        if (frames <= 0) return

        val fade = min(FADE_FRAMES, frames / 2)
        val gain = noteGain * zone.gain

        for (i in 0 until frames) {
            val srcPos = i * step
            val lo = srcPos.toInt()
            val frac = (srcPos - lo).toFloat()
            val hi = min(lo + 1, zone.samples.size - 1)
            var s = (zone.samples[lo] * (1f - frac) + zone.samples[hi] * frac) * gain

            // Linear edge fades kill note-on/off clicks
            if (i < fade) s *= i.toFloat() / fade
            if (i >= frames - fade) s *= (frames - 1 - i).toFloat() / fade

            out[offset + i] += s
        }
    }

    private companion object {
        /** ~5 ms at 46875 Hz. */
        const val FADE_FRAMES = 234
    }
}
