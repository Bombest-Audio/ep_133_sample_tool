package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Wave 0 scaffold for the multi-response request lifecycle in MIDIRepository.
 *
 * Covers (when filled by Wave 1):
 *  - STATUS_SPECIFIC_SUCCESS_START (64) keeps the paged request pending
 *  - STATUS_OK (0) completes the request
 *
 * The lifecycle test needs virtual-time/timeout simulation and a SysEx response
 * simulator, which this repo validates on hardware (see MIDIRepositoryStatsTest).
 * That path is @Ignore'd with the established justification string. The executing
 * placeholder asserts the two status-code constants the dispatcher discriminates on.
 *
 * TODO(04-project-management-02): replace the constant placeholder + un-Ignore once
 * a TestableRepository SysEx response simulator exists for the paged dispatch path.
 */
class SysExDispatchTest {

    @Test
    fun statusConstants_haveKnownValues() {
        // TODO(04-project-management-02): replace placeholder with real dispatch lifecycle assertions
        // Wave 1 keeps the pending request alive while status == SUCCESS_START, completes on OK.
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
