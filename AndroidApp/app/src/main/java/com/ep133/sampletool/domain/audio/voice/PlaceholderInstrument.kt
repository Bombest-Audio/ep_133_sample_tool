package com.ep133.sampletool.domain.audio.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesized stand-in multisample used until the real Donner B1 electric
 * piano samples land (see the TODO(donner-b1) note on [SampledInstrument]).
 *
 * We deliberately do NOT fabricate "Donner B1" audio content: this generator
 * produces a clearly synthetic EP-ish tone (decaying sine + soft 2nd harmonic)
 * so the SampledInstrumentVoice pipeline is exercised end to end with real
 * multisample mechanics (zones, keycenter shift, gain) but no fake asset in
 * the repo. Generated in memory at runtime/test time - nothing committed.
 */
object PlaceholderInstrument {

    /** One zone per octave, C2 (36) through C6 (84). */
    private val KEY_CENTERS = intArrayOf(36, 48, 60, 72, 84)

    fun generate(sampleRate: Int = RenderableVoice.DEVICE_SAMPLE_RATE): SampledInstrument {
        val zones = KEY_CENTERS.map { center ->
            Zone(
                samples = epTone(center, sampleRate),
                sampleRate = sampleRate,
                loKey = center - 6,
                hiKey = center + 6,
                pitchKeyCenter = center,
            )
        }
        return SampledInstrument(zones)
    }

    /** ~1.5 s decaying EP-ish tone at the zone's keycenter, peak 0.8. */
    private fun epTone(midiNote: Int, sampleRate: Int): FloatArray {
        val freq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
        val frames = (sampleRate * 1.5).toInt()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val env = exp(-3.0 * t)
            val fundamental = sin(2.0 * PI * freq * t)
            val secondHarmonic = 0.2 * sin(4.0 * PI * freq * t) * exp(-6.0 * t)
            out[i] = (0.8 * env * (fundamental + secondHarmonic)).toFloat()
        }
        return out
    }
}
