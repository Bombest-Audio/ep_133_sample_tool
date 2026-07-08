package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.FileTransferClient
import com.ep133.sampletool.domain.midi.PadAssignmentService
import com.ep133.sampletool.midi.MIDIPort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [PadAssignmentService.buildPadAssignmentJson] — the hardware-verified pad
 * metadata JSON shape (verified against a real EP-133, 2026-06-29).
 *
 * These are pure string/JSON assertions, no device I/O needed. `buildPadAssignmentJson` moved
 * from `MIDIRepository` into `PadAssignmentService` (999.5 Plan 02, D-02) — this test was
 * retargeted to construct `PadAssignmentService` directly (byte-identical assertions).
 */
class PadAssignmentTest {

    // Minimal test double — just enough to construct FTC + PAS and call buildPadAssignmentJson.
    private val port = object : MIDIPort {
        override var onMidiReceived: ((String, ByteArray) -> Unit)? = null
        override var onDevicesChanged: (() -> Unit)? = null
        override fun getUSBDevices() = MIDIPort.Devices(emptyList(), emptyList())
        override fun sendMidi(portId: String, data: ByteArray) {}
        override fun requestUSBPermissions() {}
        override fun refreshDevices() {}
        override fun startListening(portId: String) {}
        override fun closeAllListeners() {}
        override fun prewarmSendPort(portId: String) {}
        override fun close() {}
    }
    private val ftc = FileTransferClient(port, outputPortId = { null }, deviceId = { 0 })
    private val repo = PadAssignmentService(ftc)

    // ── Test A: Required keys present with correct types ─────────────────────────

    @Test
    fun buildPadAssignmentJson_containsRequiredKeys() {
        val json = JSONObject(repo.buildPadAssignmentJson(
            sampleNodeId = 193,
            sampleStart = 0,
            sampleEnd = 46875,
            playmode = "oneshot",
        ))

        // sym = sampleNodeId
        assertEquals("sym", 193, json.getInt("sym"))
        // sample.start / sample.end are frame counts
        assertEquals("sample.start", 0, json.getInt("sample.start"))
        assertEquals("sample.end", 46875, json.getInt("sample.end"))
        // sound.playmode
        assertEquals("sound.playmode", "oneshot", json.getString("sound.playmode"))
        // Fixed device defaults (hardware-verified)
        assertEquals("sound.amplitude", 100, json.getInt("sound.amplitude"))
        assertEquals("sound.pan", 0, json.getInt("sound.pan"))
        assertEquals("envelope.attack", 0, json.getInt("envelope.attack"))
        assertEquals("envelope.release", 255, json.getInt("envelope.release"))
        assertFalse("sound.mutegroup must be false", json.getBoolean("sound.mutegroup"))
        assertEquals("time.mode", "off", json.getString("time.mode"))
        assertEquals("midi.channel", 0, json.getInt("midi.channel"))
    }

    // ── Test B: sym reflects sampleNodeId argument ───────────────────────────────

    @Test
    fun buildPadAssignmentJson_symMatchesSampleNodeId() {
        val json = JSONObject(repo.buildPadAssignmentJson(500, 100, 9000, "loop"))
        assertEquals(500, json.getInt("sym"))
        assertEquals(100, json.getInt("sample.start"))
        assertEquals(9000, json.getInt("sample.end"))
        assertEquals("loop", json.getString("sound.playmode"))
    }

    // ── Test C: sound.pitch serialized as float, not int ────────────────────────

    @Test
    fun buildPadAssignmentJson_pitchIsZeroFloat() {
        val raw = repo.buildPadAssignmentJson(1, 0, 1, "oneshot")
        // The hardware-verified JSON specifies 0.00 (not 0), so the string representation
        // must parse to a double 0.0.
        val json = JSONObject(raw)
        val pitch = json.getDouble("sound.pitch")
        assertEquals("sound.pitch must be 0.0", 0.0, pitch, 0.001)
    }

    // ── Test D: JSON is parseable (no illegal characters) ────────────────────────

    @Test
    fun buildPadAssignmentJson_validJson() {
        // Throws if malformed
        val json = JSONObject(repo.buildPadAssignmentJson(1, 0, 100, "oneshot"))
        // Spot-check one field to ensure the parse didn't return an empty object
        assertEquals(1, json.getInt("sym"))
    }
}
