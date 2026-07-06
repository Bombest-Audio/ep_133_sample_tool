package com.ep133.sampletool.midi

/**
 * Release counterpart of the debug-only simulator seam.
 *
 * Keeps main-source references (MainActivity) compiling while guaranteeing the release
 * binary contains zero simulator code: `available = false` means the toggle wiring is
 * never installed, and [create] returns null. EP133DeviceSimulator exists only in
 * src/debug — it is not referenced here.
 */
object SimulatedPortProvider {
    const val available = false

    fun create(): MIDIPort? = null
}
