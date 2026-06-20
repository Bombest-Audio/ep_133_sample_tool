package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Test
import org.junit.Assert.*

/**
 * Wave 0 scaffold for the EP-133 project file protocol.
 *
 * Covers (when filled by Wave 1):
 *  - GET/PUT INIT/DATA frame-byte layout (nodeId uint16 BE, offset uint32 BE, page uint16 BE)
 *  - FILE_LIST response parse into {nodeId, flags, size, name}
 *
 * The placeholders below assert against symbols that exist TODAY (SysExProtocol
 * constants + the proven 7-bit codec) so the class compiles, the runner executes
 * it, and the suite is green. Real frame-byte assertions are filled in by the
 * plan that adds the project GET/PUT builders.
 *
 * TODO(04-project-management-02): replace placeholders with real GET/PUT INIT/DATA
 * frame-byte assertions + FILE_LIST entry-parse assertions once the builders land.
 */
class ProjectProtocolTest {

    @Test
    fun fileGetSubcommand_hasKnownConstantValue() {
        // TODO(04-project-management-02): replace placeholder with real behavior assertions
        // Wave 1 asserts unpacked[1] == TE_SYSEX_FILE_GET inside a built GET_INIT frame.
        assertEquals(3, SysExProtocol.TE_SYSEX_FILE_GET)
    }

    @Test
    fun pack7bit_roundTrips256ByteBinaryBlob() {
        // The project .tar archive bytes route through this codec (RESEARCH Pitfall 5).
        // Proven round-trip on arbitrary 8-bit data, asserted here against a 256-byte blob.
        val blob = ByteArray(256) { it.toByte() }
        val roundtrip = SysExProtocol.unpack7bit(SysExProtocol.pack7bit(blob))
        assertArrayEquals(blob, roundtrip)
    }
}
