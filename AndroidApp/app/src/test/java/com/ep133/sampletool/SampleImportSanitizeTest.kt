package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles — disconnected no-op stubs sufficient for sanitizeName, which
// never touches the device. Mirrors the SampleImportSpyPort/SampleImportFakeRepo
// pattern from SampleImportViewModelTest.kt (renamed to avoid top-level clash).
// ─────────────────────────────────────────────────────────────────────────────

private class SanitizeNoOpPort : MIDIPort {
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

private class SanitizeNoOpRepo : MIDIRepository(SanitizeNoOpPort()) {
    private val _state = MutableStateFlow(DeviceState(connected = false, outputPortId = null))
    override val deviceState get() = _state
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unit tests for [SampleImportManager.sanitizeName].
 *
 * sanitizeName is pure (no I/O, no device interaction), so a disconnected no-op
 * repository is sufficient — only the method under test matters.
 */
class SampleImportSanitizeTest {

    private lateinit var manager: SampleImportManager

    @Before
    fun setUp() {
        manager = SampleImportManager(SanitizeNoOpRepo())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Regression lock: well-formed short ASCII names pass through unchanged
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun kick_passesThroughUnchanged() {
        assertEquals("kick.wav", manager.sanitizeName("kick.wav"))
    }

    @Test
    fun snare_passesThroughUnchanged() {
        assertEquals("snare.wav", manager.sanitizeName("snare.wav"))
    }

    @Test
    fun hihat_passesThroughUnchanged() {
        assertEquals("hihat.wav", manager.sanitizeName("hihat.wav"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Non-ASCII names must produce pure ASCII output, no '?' bytes
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun nonAscii_cafe_producesAsciiResult() {
        val result = manager.sanitizeName("café.wav")
        assertNotNull("café.wav should produce a non-null result", result)
        assertNotNull(result)
        assertTrue(
            "Result must end in .wav, got: $result",
            result!!.endsWith(".wav"),
        )
        assertTrue(
            "Result must be pure ASCII (all code points < 128), got: $result",
            result.all { it.code < 128 },
        )
        assertFalse(
            "Result must not contain '?' (US_ASCII encoding loss), got: $result",
            result.contains('?'),
        )
    }

    @Test
    fun emoji_unicodeOnly_producesAsciiOrNull() {
        val result = manager.sanitizeName("🥁.wav")  // 🥁 drum emoji
        // Either null (no safe chars at all after replacement+trim) or a pure-ASCII string
        if (result != null) {
            assertTrue(
                "Non-null result from emoji name must be pure ASCII, got: $result",
                result.all { it.code < 128 },
            )
            assertTrue("Non-null result must end in .wav", result.endsWith(".wav"))
        }
        // null is also acceptable — "Invalid sample name" is the correct UX response
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Path traversal: no '/', '\', or ".." in output
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun traversal_dotDot_noSlashOrDoubleDotInResult() {
        val result = manager.sanitizeName("../../etc/passwd")
        assertNotNull("Path traversal input should yield a sanitized name, not null", result)
        assertNotNull(result)
        assertFalse("Result must not contain '/'", result!!.contains('/'))
        assertFalse("Result must not contain '\\'", result.contains('\\'))
        assertFalse("Result must not contain '..'", result.contains(".."))
    }

    @Test
    fun subdirPath_noSlashInResult() {
        val result = manager.sanitizeName("a/b/c.wav")
        assertNotNull("Subdir path should yield a non-null result (basename extracted)", result)
        assertNotNull(result)
        assertFalse("Result must not contain '/'", result!!.contains('/'))
        assertFalse("Result must not contain '..'", result.contains(".."))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Length cap: basename (excluding .wav) must be <= MAX_BASENAME_LEN
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun overlong_name_basenameCappedAt32() {
        val longName = "a".repeat(200) + ".wav"
        val result = manager.sanitizeName(longName)
        assertNotNull("200-char name should produce a non-null result", result)
        assertNotNull(result)
        val basename = result!!.removeSuffix(".wav")
        assertTrue(
            "Basename must be <= ${SampleImportManager.MAX_BASENAME_LEN} chars, got ${basename.length}",
            basename.length <= SampleImportManager.MAX_BASENAME_LEN,
        )
        assertTrue("Result must still end in .wav", result.endsWith(".wav"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Whitespace and trailing dots are trimmed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun trailingWhitespace_isTrimmed() {
        val result = manager.sanitizeName("  kick  .wav")
        assertNotNull("Name with surrounding spaces should be valid", result)
        assertNotNull(result)
        // Should sanitize to something valid ending in .wav
        assertTrue("Result must end in .wav, got: $result", result!!.endsWith(".wav"))
        // Leading/trailing whitespace in the basename must be gone
        val basename = result.removeSuffix(".wav")
        assertEquals("Basename must not start or end with space", basename.trim(), basename)
    }

    @Test
    fun trailingDots_areTrimmed() {
        val result = manager.sanitizeName("kick...wav")
        // "kick..." → stem is "kick.." → after replacement of '.' → "kick__" → trimmed → "kick"
        assertNotNull("Name with trailing dots should produce a valid result", result)
        assertNotNull(result)
        assertTrue("Result must end in .wav, got: $result", result!!.endsWith(".wav"))
        val basename = result.removeSuffix(".wav")
        assertFalse("Basename must not end with '_'", basename.endsWith("_"))
        assertFalse("Basename must not end with '-'", basename.endsWith("-"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Empty / all-unsafe inputs return null
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun empty_string_returnsNull() {
        assertNull(manager.sanitizeName(""))
    }

    @Test
    fun slashesOnly_returnsNull() {
        assertNull(manager.sanitizeName("///"))
    }

    @Test
    fun dotsOnly_returnsNull() {
        // "..." → strip "path" components → basename is ".." → stem via substringBeforeLast('.')
        // → "." → after char replacement "." → "_" → trim('_', '-', '.') → "" → null
        assertNull(manager.sanitizeName("..."))
    }
}
