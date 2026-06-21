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
 * Landmine 5 guard: asserts the /sounds PUT sends >1 frame for a multi-KB payload
 * (not single-chunk truncation). A payload reassembly equality check proves byte integrity
 * end-to-end through the 7-bit pack/unpack.
 *
 * Updated (Codex fix #1): putSampleFile now uses path-string framing via
 * buildFilePutFrame — every frame carries the full "/sounds/<name>" path. There is no
 * longer a separate INIT frame; all frames are FILE_PUT frames with path + chunkIndex + data.
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
    // Landmine 5 guard: path-string framing for a >4096-byte payload
    //
    // putSampleFile now uses buildFilePutFrame for EVERY chunk — no separate INIT.
    // Asserts:
    //   - Every frame carries TE_SYSEX_FILE (5) and TE_SYSEX_FILE_PUT (2) in its payload.
    //   - The ASCII bytes of "/sounds/kick.wav" appear in each frame's unpacked payload
    //     (proves the destination name is transmitted — Codex fix #1).
    //   - ceil(size/MAX_PAGE_BYTES) = 3 frames, and > 1 (proves paging still occurs).
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_sendsInitPlusPagedDataFrames() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Synthetic WAV payload larger than one page (10,000 bytes: 3 frames needed)
        val wavBytes = ByteArray(10_000) { (it and 0xFF).toByte() }

        // Call the production method
        repo.putSampleFile("kick.wav", wavBytes)

        val frames = spy.sent
        val expectedFrameCount = ceil(wavBytes.size.toDouble() / SysExProtocol.MAX_PAGE_BYTES).toInt()
        assertEquals(
            "Frame count must be ceil(size/MAX_PAGE_BYTES) = $expectedFrameCount (paging still occurs)",
            expectedFrameCount, frames.size,
        )
        assertTrue(
            "More than 1 frame required for a >4096-byte payload (proves paged, not single-chunk)",
            frames.size > 1,
        )

        // Every frame must carry TE_SYSEX_FILE + TE_SYSEX_FILE_PUT
        val pathBytes = "/sounds/kick.wav".toByteArray(Charsets.US_ASCII)
        frames.forEachIndexed { i, frame ->
            val p = unpackPayload(frame)
            assertEquals(
                "Frame $i payload[0] must be TE_SYSEX_FILE (5)",
                SysExProtocol.TE_SYSEX_FILE, p[0].toInt() and 0xFF,
            )
            assertEquals(
                "Frame $i payload[1] must be TE_SYSEX_FILE_PUT (2)",
                SysExProtocol.TE_SYSEX_FILE_PUT, p[1].toInt() and 0xFF,
            )
            // Path bytes must appear immediately after [5, 2]
            val pathInFrame = p.copyOfRange(2, 2 + pathBytes.size)
            assertArrayEquals(
                "Frame $i must carry '/sounds/kick.wav' in its payload (Codex fix #1)",
                pathBytes, pathInFrame,
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Payload reassembly: chunk bytes survive 7-bit pack/unpack unchanged (Landmine 5)
    //
    // Path-string frame layout (unpacked):
    //   p[0]       = TE_SYSEX_FILE (5)
    //   p[1]       = TE_SYSEX_FILE_PUT (2)
    //   p[2..2+L-1] = pathBytes (L = "/sounds/kick.wav".length = 17)
    //   p[2+L]     = chunkIndexHi
    //   p[2+L+1]   = chunkIndexLo
    //   p[2+L+2..] = chunk data
    //
    // Header offset = 2 + pathLength + 2 = 21 for "/sounds/kick.wav"
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun putSampleFile_chunkPayloadsSurvive7bitPackUnpack() = runTest {
        val spy = SampleImportSpyMIDIPort(connected = true)
        val repo = SampleImportFakeMIDIRepo(spy, connected = true)

        // Deterministic pattern with all byte values to catch any packing/truncation bug
        val wavBytes = ByteArray(10_000) { (it % 256).toByte() }

        repo.putSampleFile("kick.wav", wavBytes)

        // Compute the header offset in each frame's unpacked payload:
        //   2 bytes ([TE_SYSEX_FILE, TE_SYSEX_FILE_PUT])
        //   + path length ("/sounds/kick.wav" = 17)
        //   + 2 bytes (chunkIndex uint16 BE)
        val pathLength = "/sounds/kick.wav".length
        val headerOffset = 2 + pathLength + 2  // = 21

        val reassembled = spy.sent
            .flatMap { frame ->
                val p = unpackPayload(frame)
                p.drop(headerOffset).toList()
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
        // (it throws IllegalStateException "no output port" — acceptable per the test)
        try {
            repo.putSampleFile("kick.wav", wavBytes)
        } catch (_: Exception) {
            // An exception (IllegalStateException "no output port") is also acceptable
        }

        assertTrue("No frames should be sent when disconnected", spy.sent.isEmpty())
    }
}
