package com.ep133.sampletool.domain.midi

import com.ep133.sampletool.domain.audio.LoopSlicer
import com.ep133.sampletool.domain.audio.PeakNormalizer
import com.ep133.sampletool.domain.audio.SilenceTrimmer

/**
 * Per-batch sample prep toggles applied between decode and upload/staging.
 *
 * All default OFF - a batch with default options is byte-identical to today's pipeline.
 * The tuning parameters (target level, silence threshold/padding) ship with the DSP
 * objects' defaults and are exposed here so a future UI can surface them.
 */
data class SamplePrepOptions(
    val normalize: Boolean = false,
    val trimSilence: Boolean = false,
    val toMono: Boolean = false,
    val targetDbfs: Double = PeakNormalizer.DEFAULT_TARGET_DBFS,
    val silenceThresholdDbfs: Double = SilenceTrimmer.DEFAULT_THRESHOLD_DBFS,
    val silencePaddingMs: Int = SilenceTrimmer.DEFAULT_PADDING_MS,
) {
    /** True when any prep step is switched on. */
    val enabled: Boolean get() = normalize || trimSilence || toMono
}

/**
 * Applies [SamplePrepOptions] to a [ConvertedSample] (post-decode, pre-upload).
 *
 * Step order is fixed and deliberate:
 * 1. Trim silence - work on the source dynamics before anything rescales them.
 * 2. Downmix to mono - averaging channels can lower the peak, so it must run
 *    before normalization or the target level would be missed.
 * 3. Normalize - last, so the delivered peak is exactly the target.
 *
 * Pure delegation to the domain/audio DSP objects; no Android dependencies.
 */
object SamplePrep {

    /** Return a new [ConvertedSample] with the enabled prep steps applied, in order. */
    fun apply(sample: ConvertedSample, options: SamplePrepOptions): ConvertedSample {
        if (!options.enabled) return sample

        var pcm = sample.pcm
        var channels = sample.channels

        if (options.trimSilence) {
            pcm = SilenceTrimmer.trim(
                pcm = pcm,
                channels = channels,
                sampleRate = sample.sampleRate,
                thresholdDbfs = options.silenceThresholdDbfs,
                paddingMs = options.silencePaddingMs,
            )
        }
        if (options.toMono && channels == 2) {
            pcm = LoopSlicer.downmixStereoToMono(pcm)
            channels = 1
        }
        if (options.normalize) {
            pcm = PeakNormalizer.normalize(pcm, options.targetDbfs)
        }

        return ConvertedSample(pcm = pcm, channels = channels, sampleRate = sample.sampleRate)
    }
}
