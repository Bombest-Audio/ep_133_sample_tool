package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.midi.SysExProtocol.TransferStatus
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Multi-response paged-transfer lifecycle discrimination.
 *
 * The status-classification rule (the heart of the continuation state machine) is pulled
 * into the pure [SysExProtocol.classifyTransferStatus] so it is unit-testable without a
 * device or coroutine timing: STATUS_SPECIFIC_SUCCESS_START keeps the request pending,
 * STATUS_OK completes it, anything else (non-OK, < SUCCESS_START) is an error.
 *
 * The full coroutine-driven dispatch path (Channel receive + per-page timeout reset) needs
 * a SysEx response simulator and is validated on hardware, mirroring MIDIRepositoryStatsTest.
 */
class SysExDispatchTest {

    @Test
    fun successStart_keepsTransferPending() {
        assertEquals(
            TransferStatus.PENDING,
            SysExProtocol.classifyTransferStatus(SysExProtocol.STATUS_SPECIFIC_SUCCESS_START),
        )
        // Any status at or above the continuation threshold stays pending.
        assertEquals(TransferStatus.PENDING, SysExProtocol.classifyTransferStatus(100))
    }

    @Test
    fun ok_completesTransfer() {
        assertEquals(
            TransferStatus.COMPLETE,
            SysExProtocol.classifyTransferStatus(SysExProtocol.STATUS_OK),
        )
    }

    @Test
    fun nonOkBelowThreshold_isError() {
        assertEquals(TransferStatus.ERROR, SysExProtocol.classifyTransferStatus(1))
        assertEquals(TransferStatus.ERROR, SysExProtocol.classifyTransferStatus(63))
    }

    @Test
    fun statusConstants_haveKnownValues() {
        assertEquals(64, SysExProtocol.STATUS_SPECIFIC_SUCCESS_START)
        assertEquals(0, SysExProtocol.STATUS_OK)
    }

    @Ignore("paged GET dispatch lifecycle requires a physical EP-133 or a full SysEx response simulator — JVM cannot simulate device timing/continuation (matches MIDIRepositoryStatsTest convention)")
    @Test
    fun successStart_keepsRequestPending_okCompletes() {
        // Simulate SUCCESS_START intermediate responses (request stays registered) then
        // a STATUS_OK terminator (request resolves). Validated via physical EP-133 UAT.
    }
}
