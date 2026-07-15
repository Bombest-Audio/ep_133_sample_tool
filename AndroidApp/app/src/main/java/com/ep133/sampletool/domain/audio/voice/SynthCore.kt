package com.ep133.sampletool.domain.audio.voice

import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin replica of the native voice loop in
 * AndroidApp/app/src/main/cpp/synth_core.h.
 *
 * Purpose: JVM-testable form of the ONE synth voice. The native SynthCore is
 * the production engine (Oboe callback + JNI renderOffline both drive its
 * renderBlock). JVM unit tests cannot load the NDK .so, so this class mirrors
 * the same math, structure, and constants so the offline/live parity and
 * quality-gate properties can be proven on the host.
 *
 * KEEP IN SYNC with synth_core.h: any DSP change there must land here in the
 * same commit. Bit-exactness against the native build still requires an
 * instrumented/hardware pass (documented Phase 8 gap) because libm sinf/tanhf
 * are not guaranteed identical to the JVM's Math functions.
 */
class SynthCore(private val sampleRate: Int) {

    private class Voice {
        var active = false
        var releasing = false

        var midiNote = -1
        var amplitude = 0f
        var frequency = 440f

        var phaseC = 0f
        var phaseM = 0f
        var phaseLFO = 0f

        var modIndex = 0f
        var modPeak = 0f
        var modAttackRate = 0f
        var modDecayRate = 0f
        var modInAttack = true

        var envGain = 0f
        var attackRate = 0f
        var decayRate = 0f
        var sustainLevel = 0f
        var releaseRate = 0f
        var inAttack = true
    }

    private val voices = Array(MAX_VOICES) { Voice() }

    fun noteOn(midiNote: Int, velocity: Int) {
        val target = voices.firstOrNull { !it.active }
            ?: voices.firstOrNull { it.releasing }
            ?: return

        val freq = A4_FREQ * 2f.pow((midiNote - A4_MIDI) / 12f)
        val amp = (velocity / 127f) * 0.55f
        val sr = sampleRate.toFloat()

        val atk = sr * 0.035f
        val dec = sr * 0.600f
        val sus = amp * 0.25f
        val rel = sr * 0.150f

        val peakMod = (velocity / 127f) * 0.8f
        val modAttackSamp = sr * 0.015f
        val modDecaySamp = sr * 0.200f

        val lfoOffset = (midiNote * 0.137f).mod(1f)

        target.midiNote = midiNote
        target.frequency = freq
        target.amplitude = amp
        target.phaseC = 0f
        target.phaseM = 0f
        target.phaseLFO = lfoOffset
        target.modIndex = 0f
        target.modPeak = peakMod
        target.modAttackRate = peakMod / modAttackSamp
        target.modDecayRate = peakMod / modDecaySamp
        target.modInAttack = true
        target.envGain = 0f
        target.attackRate = amp / atk
        target.decayRate = (amp - sus) / dec
        target.sustainLevel = sus
        target.releaseRate = amp / rel
        target.inAttack = true
        target.releasing = false
        target.active = true
    }

    fun noteOff(midiNote: Int) {
        voices.firstOrNull { it.active && it.midiNote == midiNote && !it.releasing }
            ?.releasing = true
    }

    fun allNotesOff() {
        voices.forEach { it.releasing = true }
    }

    /**
     * The per-sample voice loop - mirrors SynthCore::renderBlock in synth_core.h.
     * Output is chunk-size invariant: all per-sample state is carried across
     * calls, so one big call equals any sequence of smaller calls.
     */
    fun renderBlock(out: FloatArray, offset: Int, numFrames: Int) {
        for (i in 0 until numFrames) out[offset + i] = 0f

        val sr = sampleRate.toFloat()
        val lfoIncr = LFO_FREQ / sr

        for (v in voices) {
            if (!v.active) continue

            var phaseC = v.phaseC
            var phaseM = v.phaseM
            var phaseLFO = v.phaseLFO
            var modIndex = v.modIndex
            var envGain = v.envGain
            var inAttack = v.inAttack
            var stillActive = true

            val freqIncr = v.frequency / sr

            for (i in 0 until numFrames) {
                if (v.releasing) {
                    envGain -= v.releaseRate
                    if (envGain <= 0f) {
                        envGain = 0f
                        stillActive = false
                        break
                    }
                } else if (inAttack) {
                    envGain += v.attackRate
                    if (envGain >= v.amplitude) {
                        envGain = v.amplitude
                        inAttack = false
                    }
                } else if (envGain > v.sustainLevel) {
                    envGain -= v.decayRate
                    if (envGain < v.sustainLevel) envGain = v.sustainLevel
                }

                val mod = sinf(phaseM * TWO_PI) * modIndex
                val carrier = sinf((phaseC + mod) * TWO_PI)
                val tremolo = 1f + TREM_DEPTH * sinf(phaseLFO * TWO_PI)

                out[offset + i] += carrier * envGain * tremolo

                if (v.modInAttack) {
                    modIndex += v.modAttackRate
                    if (modIndex >= v.modPeak) {
                        modIndex = v.modPeak
                        v.modInAttack = false
                    }
                } else {
                    modIndex -= v.modDecayRate
                    if (modIndex < 0f) modIndex = 0f
                }

                phaseC += freqIncr; if (phaseC >= 1f) phaseC -= 1f
                phaseM += freqIncr; if (phaseM >= 1f) phaseM -= 1f
                phaseLFO += lfoIncr; if (phaseLFO >= 1f) phaseLFO -= 1f
            }

            v.phaseC = phaseC
            v.phaseM = phaseM
            v.phaseLFO = phaseLFO
            v.modIndex = modIndex
            v.envGain = envGain
            v.inAttack = inAttack
            if (!stillActive) v.active = false
        }

        // Post-mix: soft saturation + 12-bit quantization. tanh bounds the mix
        // below 0 dBFS; no post-tanh normalization (see the NOTE in
        // synth_core.h - the old /tanh(DRIVE) pushed dense chords to +3.5 dBFS).
        for (i in 0 until numFrames) {
            var x = tanh(out[offset + i] * DRIVE)
            x = round(x * BIT_SCALE) / BIT_SCALE
            out[offset + i] = x
        }
    }

    /** Mirrors SynthCore::renderOffline in synth_core.h. */
    fun renderOffline(
        chords: List<List<Int>>,
        chordFrames: List<Int>,
        velocity: Int,
        tailFrames: Int,
    ): FloatArray {
        require(chords.size == chordFrames.size)
        val out = FloatArray(chordFrames.sum() + tailFrames)
        var pos = 0
        for (c in chords.indices) {
            chords[c].forEach { noteOn(it, velocity) }
            renderBlock(out, pos, chordFrames[c])
            pos += chordFrames[c]
            chords[c].forEach { noteOff(it) }
        }
        if (tailFrames > 0) renderBlock(out, pos, tailFrames)
        return out
    }

    private companion object {
        const val MAX_VOICES = 8
        const val A4_FREQ = 440f
        const val A4_MIDI = 69f
        const val TWO_PI = 6.28318530718f

        const val LFO_FREQ = 5.0f
        const val TREM_DEPTH = 0.08f
        const val DRIVE = 0.8f
        const val BIT_SCALE = 2048f

        fun sinf(x: Float): Float = sin(x.toDouble()).toFloat()
        fun tanh(x: Float): Float = kotlin.math.tanh(x.toDouble()).toFloat()
        fun round(x: Float): Float = kotlin.math.round(x.toDouble()).toFloat()
    }
}

/**
 * [RenderableVoice] over the pure-Kotlin [SynthCore] replica. Used by JVM unit
 * tests and available as a no-JNI fallback; on device, prefer
 * [NativeSynthVoice] so the exact native binary renders the bake.
 */
class KotlinSynthVoice : RenderableVoice {
    override fun render(
        chords: List<List<Int>>,
        bpm: Int,
        sampleRate: Int,
        velocity: Int,
    ): FloatArray {
        val frames = RenderableVoice.framesPerBar(bpm, sampleRate)
        val tail = (sampleRate * RELEASE_TAIL_SECONDS).toInt()
        return SynthCore(sampleRate).renderOffline(
            chords = chords,
            chordFrames = List(chords.size) { frames },
            velocity = velocity,
            tailFrames = tail,
        )
    }

    companion object {
        /** 150 ms release + margin so the last chord fully rings out. */
        const val RELEASE_TAIL_SECONDS = 0.25f
    }
}
