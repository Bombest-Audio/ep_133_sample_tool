package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for PUT DATA page-ack routing through [MIDIRepository.dispatchSysEx].
 *
 * Bug (fixed 2026-06-25): `if (body.isEmpty()) return` at ~line 313 dropped the
 * STATUS-ONLY empty-body page ack from the device before reqId-matching, leaving
 * the page-0 [pendingPutAckDeferred] pending until a 15-second timeout.
 *
 * Fix:
 *   1. Guard changed to `if (payload.isEmpty()) return` — only bail when there is
 *      no status byte at all, not when the post-status body is empty.
 *   2. The already-extracted [fileStatus] is threaded into [dispatchPagedPutResponse]
 *      so PUT completion is based on the correct status even when body is empty.
 *
 * These tests drive the full ack path via a [MIDIPort] that auto-responds with
 * hardware-realistic frames (status-only, empty body) when [sendMidi] is called.
 */

// ── Test-only port: responds to sendMidi with hardware-realistic acks ──────────

/**
 * A MIDIPort that, on each [sendMidi] call, immediately fires [onMidiReceived]
 * with a response frame whose status is controlled by [statusForDataAcks].
 *
 * Frame routing:
 *   - FILE_INIT frame (reqId=83) → FILE ack with status=0 and empty body (status-only).
 *   - PUT INIT frame  (reqId=30) → FILE ack with status=0 and empty body.
 *   - PUT DATA frame  (reqId≥31) → FILE ack with status=[statusForDataAcks] + empty body.
 *
 * The response is a minimal raw SysEx: F0 00 20 76 00 40 <reqHigh> <reqLow> 05 <status> F7
 * exactly what the hardware emits for a STATUS-ONLY (no packed body after the status byte).
 */
private class AutoAckMIDIPort(
    private val statusForDataAcks: Int = SysExProtocol.STATUS_OK,
) : MIDIPort {
    override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
    override var onDevicesChanged: (() -> Unit)? = null

    override fun getUSBDevices() = MIDIPort.Devices(
        inputs  = listOf(MIDIPort.Device("in",  "EP-133")),
        outputs = listOf(MIDIPort.Device("out", "EP-133")),
    )

    override fun sendMidi(portId: String, data: ByteArray) {
        // Extract reqId from the outbound frame (layout matches dispatchSysEx extraction):
        //   frame[6] low nibble = reqId >> 7; frame[7] = reqId & 0x7F
        if (data.size < 10) return
        val reqId = ((data[6].toInt() and 0x0F) shl 7) or (data[7].toInt() and 0x7F)
        val command = data[8].toInt() and 0x7F

        // Only respond to FILE-command frames (command = TE_SYSEX_FILE = 5)
        if (command != SysExProtocol.TE_SYSEX_FILE) return

        // The outbound payload is 7-bit-packed starting at data[9].
        // Unpack to check the sub-type only when needed; for routing we use reqId.
        val packedPayload = if (data.size > 10) data.copyOfRange(9, data.size - 1) else ByteArray(0)
        val innerPayload = if (packedPayload.isNotEmpty()) SysExProtocol.unpack7bit(packedPayload) else ByteArray(0)

        // Determine which status to send back.
        // PUT INIT frames carry innerPayload[0]=TE_SYSEX_FILE_PUT, [1]=TE_SYSEX_FILE_PUT_TYPE_INIT.
        // PUT DATA frames carry innerPayload[1]=TE_SYSEX_FILE_PUT_TYPE_DATA.
        val isPutData = innerPayload.size >= 2 &&
            innerPayload[0].toInt() and 0xFF == SysExProtocol.TE_SYSEX_FILE_PUT &&
            innerPayload[1].toInt() and 0xFF == SysExProtocol.TE_SYSEX_FILE_PUT_TYPE_DATA
        val responseStatus = if (isPutData) statusForDataAcks else SysExProtocol.STATUS_OK

        // Build the response: STATUS-ONLY, empty body (hardware-confirmed PUT DATA ack format).
        // Frame: F0 00 20 76 00 40 <reqHigh4> <reqLow7> 05 <status> F7
        val reqHigh = (reqId shr 7) and 0x0F
        val reqLow  = reqId and 0x7F
        val response = byteArrayOf(
            0xF0.toByte(),
            SysExProtocol.TE_ID_0,
            SysExProtocol.TE_ID_1,
            SysExProtocol.TE_ID_2,
            0x00,          // deviceId
            0x40,          // TE subsystem
            reqHigh.toByte(),
            reqLow.toByte(),
            SysExProtocol.TE_SYSEX_FILE.toByte(),
            responseStatus.toByte(),
            0xF7.toByte(),
        )
        onMidiReceived?.invoke("in", response)
    }

    override fun requestUSBPermissions() {}
    override fun refreshDevices() {}
    override fun startListening(portId: String) {}
    override fun closeAllListeners() {}
    override fun prewarmSendPort(portId: String) {}
    override fun close() {}
}

/**
 * Fake repo that stubs /sounds node resolution to skip FILE_LIST round-trips.
 * Lets [putSampleFile] proceed to the INIT/DATA/ack cycle without hardware.
 *
 * Overrides [resolveSoundsNodeId] (called from within the fileOpMutex locked block in
 * [putSampleFile]) rather than [resolveNodeId] to avoid acquiring the mutex a second time.
 */
private class PutAckTestRepo(
    port: AutoAckMIDIPort,
) : MIDIRepository(port) {
    init {
        _deviceState.value = DeviceState(connected = true, outputPortId = "out")
    }

    override suspend fun resolveSoundsNodeId(): Int? = 42
}

class PutDataAckTest {

    /**
     * Empty-body STATUS_OK ack on the awaited PUT DATA reqId must complete the page ack (true).
     *
     * Before the fix, `if (body.isEmpty()) return` in dispatchSysEx dropped this ack
     * entirely — the deferred timed out after 15 s and putSampleFile returned false.
     * After the fix, the status-only ack is routed and the deferred completes with true.
     */
    @Test
    fun putSampleFile_emptyBodyStatusOkAck_completesPageAckTrue() = runTest {
        val port = AutoAckMIDIPort(statusForDataAcks = SysExProtocol.STATUS_OK)
        val repo = PutAckTestRepo(port)

        // Ensure file session initialized using auto-ack port (FILE_INIT → empty-body status=0).
        repo.ensureFileSessionInit()

        // Upload 1 byte so we get exactly one DATA page + one terminator.
        val result = repo.putSampleFile("kick.wav", ByteArray(1) { 0x42 })

        assertTrue("putSampleFile must return true when device sends empty-body STATUS_OK acks", result)
    }

    /**
     * Empty-body non-OK status on the awaited PUT DATA reqId must complete the page ack (false).
     *
     * Status = 1 is not STATUS_OK (0) and is not >= STATUS_SPECIFIC_SUCCESS_START (64),
     * so it is an error status — page ack should complete with false and upload should abort.
     */
    @Test
    fun putSampleFile_emptyBodyErrorStatusAck_completesPageAckFalse() = runTest {
        val port = AutoAckMIDIPort(statusForDataAcks = 1 /* error status */)
        val repo = PutAckTestRepo(port)

        repo.ensureFileSessionInit()

        val result = repo.putSampleFile("kick.wav", ByteArray(1) { 0x42 })

        assertFalse("putSampleFile must return false when device sends non-OK status on page ack", result)
    }
}
