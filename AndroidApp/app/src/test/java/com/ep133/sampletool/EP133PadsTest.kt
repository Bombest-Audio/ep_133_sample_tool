package com.ep133.sampletool

import com.ep133.sampletool.domain.model.EP133Pads
import com.ep133.sampletool.domain.model.PadChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for EP133Pads after removing the SPECIAL_PAD0 mapping.
 *
 * Hardware ground truth (adb capture, Pixel + EP-133):
 *   A0 pressed → MIDI IN note=37 ch=0  (A.baseNote=36 + offset=1)
 *   The earlier note-60/ch-6 behavior was never present on the device.
 */
class EP133PadsTest {

    // ── padsForChannel — A group ───────────────────────────────────────────────

    @Test
    fun a0_has_note_37() {
        val pads = EP133Pads.padsForChannel(PadChannel.A)
        val a0 = pads.first { it.label == "A0" }
        assertEquals(37, a0.note)
    }

    @Test
    fun aDot_has_note_36() {
        val pads = EP133Pads.padsForChannel(PadChannel.A)
        val aDot = pads.first { it.label == "A." }
        assertEquals(36, aDot.note)
    }

    @Test
    fun a1_has_note_39() {
        val pads = EP133Pads.padsForChannel(PadChannel.A)
        val a1 = pads.first { it.label == "A1" }
        assertEquals(39, a1.note)
    }

    @Test
    fun no_A_pad_has_note_60() {
        val pads = EP133Pads.padsForChannel(PadChannel.A)
        assertTrue("No A pad should have note 60", pads.none { it.note == 60 })
    }

    // ── padsForChannel — D group ───────────────────────────────────────────────

    @Test
    fun d0_has_note_73() {
        val pads = EP133Pads.padsForChannel(PadChannel.D)
        val d0 = pads.first { it.label == "D0" }
        assertEquals(73, d0.note)
    }

    // ── No pad on channel 6 or 7 (across all groups) ──────────────────────────

    @Test
    fun no_pad_on_channel_6_or_7() {
        val allPads = PadChannel.entries.flatMap { EP133Pads.padsForChannel(it) }
        val special = allPads.filter { it.midiChannel == 6 || it.midiChannel == 7 }
        assertTrue("No pad should be on ch 6 or 7, found: $special", special.isEmpty())
    }

    // ── resolveIncoming ────────────────────────────────────────────────────────

    @Test
    fun resolveIncoming_37_ch0_returns_A_pad0() {
        val result = EP133Pads.resolveIncoming(37)
        assertNotNull(result)
        val (group, idx) = result!!
        assertEquals(PadChannel.A, group)
        // The "0" pad has offset=1; verify the returned index is correct by
        // checking the pad at that index in a fresh list.
        val pad = EP133Pads.padsForChannel(PadChannel.A)[idx]
        assertEquals("A0", pad.label)
    }

    @Test
    fun resolveIncoming_48_ch0_returns_B_dotPad() {
        val result = EP133Pads.resolveIncoming(48)
        assertNotNull(result)
        val (group, idx) = result!!
        assertEquals(PadChannel.B, group)
        val pad = EP133Pads.padsForChannel(PadChannel.B)[idx]
        assertEquals("B.", pad.label)
    }

    @Test
    fun resolveIncoming_60_ch0_returns_C_dotPad() {
        // note 60 is now unambiguously C's base note (C.baseNote=60, offset=0 → ".")
        val result = EP133Pads.resolveIncoming(60)
        assertNotNull(result)
        val (group, idx) = result!!
        assertEquals(PadChannel.C, group)
        val pad = EP133Pads.padsForChannel(PadChannel.C)[idx]
        assertEquals("C.", pad.label)
    }

    @Test
    fun resolveIncoming_out_of_range_returns_null() {
        assertNull(EP133Pads.resolveIncoming(0))
        assertNull(EP133Pads.resolveIncoming(35))
        assertNull(EP133Pads.resolveIncoming(84))
        assertNull(EP133Pads.resolveIncoming(127))
    }
}
