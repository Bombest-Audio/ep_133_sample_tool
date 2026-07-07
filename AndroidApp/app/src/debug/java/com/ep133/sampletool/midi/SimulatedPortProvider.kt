package com.ep133.sampletool.midi

import com.ep133.sampletool.support.sim.EP133DeviceSimulator

/**
 * Debug-only seam for the Simulated EP-133 toggle.
 *
 * Main source references this object (never [EP133DeviceSimulator] directly), so the
 * simulator class stays out of release binaries entirely — the release variant of this
 * file returns null and `available = false`. MainActivity gates the toggle wiring on
 * [available], and each activation gets a fresh simulator (clean device state).
 */
object SimulatedPortProvider {
    const val available = true

    fun create(): MIDIPort? = EP133DeviceSimulator()
}
