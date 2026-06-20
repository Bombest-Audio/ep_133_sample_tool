package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.ceil

/**
 * RED (Wave 0): Asserts the MIDIRepository.putSampleFile paged transfer contract.
 *
 * Landmine 5 guard: asserts the /sounds PUT sends >1 DATA frame for a multi-KB payload
 * (not single-chunk truncation). A payload reassembly equality check proves byte integrity
 * end-to-end through the 7-bit pack/unpack.
 *
 * Compile-fails on `putSampleFile` until Wave 2 adds that method to MIDIRepository.
 * No changes to these tests should be needed to turn them GREEN — the production
 * implementation must match this contract.
 *
 * Note: The SAF URI read (contentResolver.openInputStream) is hardware/instrumentation-only.
 * This test covers the paged transfer contract using synthetic in-memory byte arrays only
 * (per 05-VALIDATION Manual-Only section).
 */
class SampleImportTest {

    // ── Spy port: records all sendMidi calls for frame-level assertion ──
    private class SampleImportSpyMIDIPort(private val connected: Boolean = false) : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null
        val sent = mutableListOf<ByteArray>()

        override fun getUSBDevices() = if (connected) {
            MIDIPort.Devices(
                inputs = listOf(MIDIPort.Device("in", "EP-133")),
                outputs = listOf(MIDIPort.Device("out", "EP-133")),
            )
        } else {
            MIDIPort.Devices(emptyList(), emptyList())
        }

        override fun sendMidi(portId: String, data: ByteArray) { sent.add(data.copyOf()) }
        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}
    }

    // ── Fake repo: sets protected _deviceState with outputPortId so putSampleFile proceeds ──
    private class SampleImportFakeMIDIRepo(
        val spy: SampleImportSpyMIDIPort,
        connected: Boolean,
    ) : MIDIRepository(spy) {
        init {
            if (connected) {
                _deviceState.value = DeviceState(
                    connected = true,
                    outputPortId = "out",
                )
            }
        }
    }

    // ── Helper: unpack the inner payload of a SysEx frame (frame[9..size-2] is packed) ──
    private fun unpackPayload(frame: ByteArray): ByteArray =
        SysExProtocol.unpack7bit(frame.copyOfRange(9, frame.size - 1))

    // ──────────────────────────────────────────────────────────────────────────
    // Landmine 5 guard: paged transfer for a >4096-byte payload
    //
    // Asserts:
    //   1 INIT frame + ceil(size/MAX_PAGE_BYTES) DATA frames are sent.
    //   Concatenating the unpacked DATA chunk payloads (stripping the page header)
    //   reconstructs the original wavBytes byte-for-byte.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_sendsInitPlusPagedDataFrames() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Synthetic WAV payload larger than one page (10,000 bytes: 3 DATA frames needed)
        val wavBytes = ByteArray(10_000) { (it and 0xFF).toByte() }

        // Call the production method — compile-fails until Wave 2 implements putSampleFile
        repo.putSampleFile("kick.wav", wavBytes)

        val frames = spy.sent
        assertTrue("Expected at least 2 frames (1 INIT + ≥1 DATA), got ${frames.size}", frames.size >= 2)

        // First frame must be INIT-shaped (contains TE_SYSEX_FILE + TE_SYSEX_FILE_PUT)
        val initPayload = unpackPayload(frames[0])
        assertEquals(
            "INIT frame[0] must be TE_SYSEX_FILE (5)",
            SysExProtocol.TE_SYSEX_FILE, initPayload[0].toInt() and 0xFF,
        )
        assertEquals(
            "INIT frame[1] must be TE_SYSEX_FILE_PUT (2)",
            SysExProtocol.TE_SYSEX_FILE_PUT, initPayload[1].toInt() and 0xFF,
        )

        // Remaining frames are DATA frames
        val dataFrames = frames.drop(1)
        val expectedDataFrameCount = ceil(wavBytes.size.toDouble() / SysExProtocol.MAX_PAGE_BYTES).toInt()
        assertEquals(
            "DATA frame count must be ceil(size/MAX_PAGE_BYTES) = $expectedDataFrameCount (Landmine 5)",
            expectedDataFrameCount, dataFrames.size,
        )
        assertTrue(
            "More than 1 DATA frame required for a >4096-byte payload (proves paged, not single-chunk)",
            dataFrames.size > 1,
        )

        // Verify DATA frames carry TE_SYSEX_FILE_PUT in their payloads
        dataFrames.forEach { frame ->
            val p = unpackPayload(frame)
            assertEquals(
                "DATA frame[0] must be TE_SYSEX_FILE",
                SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF,
            )
            assertEquals(
                "DATA frame[1] must be TE_SYSEX_FILE_PUT",
                SysExProtocol.TE_SYSEX_FILE_PUT, p[1].toInt() and 0xFF,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Payload reassembly: chunk bytes survive 7-bit pack/unpack unchanged (Landmine 5)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_chunkPayloadsSurvive7bitPackUnpack() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Deterministic pattern with all byte values to catch any packing/truncation bug
        val wavBytes = ByteArray(10_000) { (it % 256).toByte() }

        // Compile-fails until Wave 2 implements putSampleFile
        repo.putSampleFile("kick.wav", wavBytes)

        // Drop the INIT frame; collect the chunk bytes from each DATA frame's unpacked payload.
        // DATA payload layout per buildFilePutDataFrame:
        //   p[0] = TE_SYSEX_FILE, p[1] = TE_SYSEX_FILE_PUT, p[2] = type_DATA_byte,
        //   p[3..4] = page uint16 BE, p[5..] = chunk bytes
        val dataFrames = spy.sent.drop(1)
        val HEADER_BYTES_IN_DATA_PAYLOAD = 5  // TE_SYSEX_FILE + TE_SYSEX_FILE_PUT + type + page(2)
        val reassembled = dataFrames
            .flatMap { frame ->
                val p = unpackPayload(frame)
                p.drop(HEADER_BYTES_IN_DATA_PAYLOAD).toList()
            }
            .toByteArray()

        assertArrayEquals(
            "Concatenating unpacked chunk payloads must reconstruct the original wavBytes byte-for-byte",
            wavBytes, reassembled,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Disconnected: putSampleFile returns false / no frames sent
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_whenDisconnected_sendsNoFrames() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = false)
        val repo = SampleImportFakeMIDIRepo(spy, connected = false)

        val wavBytes = ByteArray(1000) { 0 }

        // With no outputPortId, putSampleFile must not send any frames
        // (it returns false / early-exits — compile-fails until Wave 2)
        try {
            repo.putSampleFile("kick.wav", wavBytes)
        } catch (_: Exception) {
            // An exception (IllegalStateException "no output port") is also acceptable
        }

        assertTrue("No frames should be sent when disconnected", spy.sent.isEmpty())
    }
}
