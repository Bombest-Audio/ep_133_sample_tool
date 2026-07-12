package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.ProjectBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for issue #26: restore filename validation.
 *
 * Bug 1 — the old `\w*P(\d{2})\.tar` regex couldn't span the hyphens and date in the names the
 * app itself writes (`[{customName}-]EP133-P{NN}-{yyyy-MM-dd-HHmm}.tar`), so a file the app
 * created was rejected as "Invalid backup filename" and restore could never find its own backups.
 *
 * Bug 2 — the slot-range guard was `0..8`, but device slots are named 01..09 (indices 1..9), so a
 * legitimate P09 restore was rejected and a nonexistent P00 admitted.
 *
 * [ProjectBackupManager.tarSlotIndex] decodes the name (both bugs' regex half); the 1..9 range
 * check lives in restoreProject and is asserted here against the decoded slot.
 */
class RestoreFilenameValidationTest {

    private fun tarSlot(name: String) = ProjectBackupManager.tarSlotIndex(name)

    @Test
    fun acceptsLegacyPlainForm() {
        assertEquals(3, tarSlot("P03.tar"))
    }

    @Test
    fun acceptsGeneratedFormTheAppWrites() {
        // suggestedProjectFilename output — the exact name that used to be rejected.
        assertEquals(3, tarSlot("EP133-P03-2026-07-11-1230.tar"))
    }

    @Test
    fun acceptsCustomNamePrefixedForm() {
        assertEquals(7, tarSlot("MyBeat_v2-EP133-P07-2026-07-11-1230.tar"))
    }

    @Test
    fun acceptsSlotNine_previouslyRejectedByOffByOne() {
        val slot = tarSlot("EP133-P09-2026-07-11-1230.tar")
        assertEquals(9, slot)
        // P09 is a real device slot — must pass the restore range check.
        assert(slot!! in 1..9) { "P09 must be a valid restore slot" }
    }

    @Test
    fun slotZero_decodesButFailsRangeCheck() {
        // P00 is a valid *name* but not a real slot; the old 0..8 bound wrongly admitted it.
        val slot = tarSlot("P00.tar")
        assertEquals(0, slot)
        assert(slot!! !in 1..9) { "P00 must be rejected by the restore range check" }
    }

    @Test
    fun rejectsTraversalName() {
        assertNull("a path-traversal name must never match", tarSlot("../P03.tar"))
        assertNull(tarSlot("foo/P03.tar"))
        assertNull(tarSlot("""foo\P03.tar"""))
    }

    @Test
    fun rejectsNonBackupNames() {
        assertNull(tarSlot("notabackup.tar"))
        assertNull(tarSlot("P3.tar"))          // single digit — needs two
        assertNull(tarSlot("P03.tar.evil"))    // anchored: nothing after .tar
        assertNull(tarSlot("P03.zip"))
        assertNull(tarSlot("EP133-P03.tar.bak"))
    }
}
