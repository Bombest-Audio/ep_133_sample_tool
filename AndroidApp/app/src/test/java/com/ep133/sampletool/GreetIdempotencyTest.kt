package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DRAFT (issue #27, hardware-gated): unit tests for the GREET-echo dedup decision.
 *
 * The device double-sends responses and `queryDeviceStats` self-issues a GREET, so a second
 * GREET copy processed mid-session used to fail the in-flight waiter and tear the session down.
 * The guard swallows a greet whose signature matches the previous one within the dedup window
 * (an echo) while still resetting on a genuinely-later reconnect greet. These tests pin the pure
 * decision helpers; the full path still needs a hardware session to confirm the timing model.
 */
class GreetIdempotencyTest {

    private val payload = byteArrayOf(1, 2, 3, 4)

    @Test
    fun firstGreet_isNeverADuplicate() {
        val sig = MIDIRepository.greetSignature(0x33, payload)
        assertFalse(
            "the first greet (no prior signature) must reset",
            MIDIRepository.isDuplicateGreet(sig, nowMs = 1_000, lastSignature = null, lastAtMs = 0),
        )
    }

    @Test
    fun identicalGreetWithinWindow_isADuplicate() {
        val sig = MIDIRepository.greetSignature(0x33, payload)
        assertTrue(
            "an identical greet a few ms later is a device echo",
            MIDIRepository.isDuplicateGreet(sig, nowMs = 1_010, lastSignature = sig, lastAtMs = 1_000),
        )
    }

    @Test
    fun identicalGreetAfterWindow_isNotADuplicate() {
        val sig = MIDIRepository.greetSignature(0x33, payload)
        val after = 1_000L + MIDIRepository.GREET_DEDUP_WINDOW_MS
        assertFalse(
            "same signature but past the window is a reconnect and must reset",
            MIDIRepository.isDuplicateGreet(sig, nowMs = after, lastSignature = sig, lastAtMs = 1_000),
        )
    }

    @Test
    fun differentSignatureWithinWindow_isNotADuplicate() {
        val a = MIDIRepository.greetSignature(0x33, payload)
        val b = MIDIRepository.greetSignature(0x40, payload)
        assertFalse(
            "a different device/payload within the window is not an echo",
            MIDIRepository.isDuplicateGreet(b, nowMs = 1_010, lastSignature = a, lastAtMs = 1_000),
        )
    }

    @Test
    fun greetSignature_isStableAndDiscriminating() {
        assertEquals(
            "same inputs → same signature",
            MIDIRepository.greetSignature(0x33, byteArrayOf(1, 2, 3)),
            MIDIRepository.greetSignature(0x33, byteArrayOf(1, 2, 3)),
        )
        assertNotEquals(
            "different device id → different signature",
            MIDIRepository.greetSignature(0x33, byteArrayOf(1, 2, 3)),
            MIDIRepository.greetSignature(0x34, byteArrayOf(1, 2, 3)),
        )
        assertNotEquals(
            "different payload → different signature",
            MIDIRepository.greetSignature(0x33, byteArrayOf(1, 2, 3)),
            MIDIRepository.greetSignature(0x33, byteArrayOf(1, 2, 4)),
        )
    }
}
