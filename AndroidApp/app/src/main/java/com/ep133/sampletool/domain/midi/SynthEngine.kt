package com.ep133.sampletool.domain.midi

/** Abstraction over an audio synthesis backend. Implemented by [LocalSynth]; test doubles use this. */
interface SynthEngine {
    fun noteOn(note: Int, velocity: Int = 90)
    fun noteOff(note: Int)
    fun allNotesOff()
    fun close()
}
