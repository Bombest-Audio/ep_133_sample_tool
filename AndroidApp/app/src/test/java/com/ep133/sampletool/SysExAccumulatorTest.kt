package com.ep133.sampletool

import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SysEx accumulation logic in MIDIRepository.
 * Wave 0 stubs — production code created in Task 1-02.
 */
class SysExAccumulatorTest {

    @Ignore("Wave 1 — SysEx accumulation not yet implemented")
    @Test
    fun singleCompleteMessage_dispatched() {
        // val dispatched = mutableListOf<ByteArray>()
        // val repo = TestableRepository(FakeMIDIPort()) { dispatched.add(it) }
        // repo.feedBytes(byteArrayOf(0xF0.toByte(), 0x00, 0x20, 0x76.toByte(), 0x00, 0xF7.toByte()))
        // assertEquals(1, dispatched.size)
        // assertEquals(6, dispatched[0].size)
    }

    @Ignore("Wave 1 — SysEx accumulation not yet implemented")
    @Test
    fun fragmentedMessage_accumulatesAndDispatches() {
        // Feed [0xF0, 0x00, 0x20] then [0x76, 0x00, 0xF7]; assert dispatch called once with 6-byte result
    }

    @Ignore("Wave 1 — SysEx accumulation not yet implemented")
    @Test
    fun midMessageChannelMessage_ignored() {
        // Channel message bytes (0x90, 60, 100) interspersed with SysEx fragments
        // Assert channel message still dispatched, SysEx still accumulated correctly
    }

    @Ignore("Wave 1 — SysEx accumulation not yet implemented")
    @Test
    fun multipleMessages_eachDispatchedOnce() {
        // Feed two complete SysEx messages back-to-back
        // Assert dispatch called twice
    }
}
