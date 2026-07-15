package com.ep133.sampletool.domain.audio.voice

/**
 * JNI gateway to the native offline renderer in native_synth.cpp.
 *
 * A Kotlin object (not a companion) so the JNI symbol is unmangled:
 * Java_com_ep133_sampletool_domain_audio_voice_NativeOfflineRenderer_nativeRenderOffline.
 *
 * The native side constructs a fresh ep133::SynthCore and drives the SAME
 * renderBlock() voice loop the live Oboe callback uses - there is no second
 * synth. Precondition: libnativesynth.so must be loadable (device/emulator);
 * JVM unit tests must use [KotlinSynthVoice] instead.
 */
object NativeOfflineRenderer {

    init {
        System.loadLibrary("nativesynth")
    }

    /**
     * @param chordNotes  flattened MIDI notes of every chord
     * @param chordStarts numChords+1 offsets into [chordNotes]
     * @param chordFrames frames to render per chord
     * @param tailFrames  frames appended after the last noteOff for the release
     * @return mono float PCM in [-1, 1]
     */
    external fun nativeRenderOffline(
        chordNotes: IntArray,
        chordStarts: IntArray,
        chordFrames: IntArray,
        velocity: Int,
        sampleRate: Int,
        tailFrames: Int,
    ): FloatArray
}

/**
 * [RenderableVoice] backed by the native synth's own offline render entry.
 * This is the production bake path on Android: the PCM comes from the exact
 * per-sample loop heard in live preview.
 */
class NativeSynthVoice : RenderableVoice {

    override fun render(
        chords: List<List<Int>>,
        bpm: Int,
        sampleRate: Int,
        velocity: Int,
    ): FloatArray {
        val frames = RenderableVoice.framesPerBar(bpm, sampleRate)
        val notes = chords.flatten().toIntArray()
        val starts = IntArray(chords.size + 1)
        for (c in chords.indices) starts[c + 1] = starts[c] + chords[c].size
        val tail = (sampleRate * KotlinSynthVoice.RELEASE_TAIL_SECONDS).toInt()

        return NativeOfflineRenderer.nativeRenderOffline(
            chordNotes = notes,
            chordStarts = starts,
            chordFrames = IntArray(chords.size) { frames },
            velocity = velocity,
            sampleRate = sampleRate,
            tailFrames = tail,
        )
    }
}
