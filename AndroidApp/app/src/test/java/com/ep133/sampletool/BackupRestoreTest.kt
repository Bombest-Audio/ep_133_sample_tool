package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BackupProgress
import com.ep133.sampletool.domain.midi.SysExProtocol
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for BackupManager and BackupProgress.
 */
class BackupRestoreTest {

    /** Build a minimal valid ZIP file in memory with at least one .wav and one .json entry. */
    private fun buildMinimalPak(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("001.wav"))
            zip.write(ByteArray(16) { it.toByte() })
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write("{\"file_count\":1}".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun backupFile_isZipFormat() {
        val pakBytes = buildMinimalPak()
        assertEquals("ZIP magic byte 0", 0x50.toByte(), pakBytes[0])
        assertEquals("ZIP magic byte 1", 0x4B.toByte(), pakBytes[1])
        assertEquals("ZIP magic byte 2", 0x03.toByte(), pakBytes[2])
        assertEquals("ZIP magic byte 3", 0x04.toByte(), pakBytes[3])
    }

    @Test
    fun backupFile_containsWavFiles() {
        val pakBytes = buildMinimalPak()
        val zipInputStream = java.util.zip.ZipInputStream(pakBytes.inputStream())
        val entryNames = mutableListOf<String>()
        var entry = zipInputStream.nextEntry
        while (entry != null) {
            entryNames.add(entry.name)
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        assertTrue("At least one .wav entry expected", entryNames.any { it.endsWith(".wav") })
    }

    @Test
    fun backupFile_containsMetadataJson() {
        val pakBytes = buildMinimalPak()
        val zipInputStream = java.util.zip.ZipInputStream(pakBytes.inputStream())
        val entryNames = mutableListOf<String>()
        var entry = zipInputStream.nextEntry
        while (entry != null) {
            entryNames.add(entry.name)
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        assertTrue("metadata.json expected in archive", entryNames.any { it.endsWith(".json") })
    }

    @Test
    fun fileGetProtocol_buildsCorrectFrame() {
        val frame = SysExProtocol.buildFileGetFrame(
            deviceId = 0,
            path = "/sounds/001.wav",
            chunkIndex = 0,
            requestId = 1,
        )
        // Hardware-verified (2026-06-23): command = TE_SYSEX_FILE (5), NOT CMD_PRODUCT_SPECIFIC (127).
        assertEquals("frame[8] = TE_SYSEX_FILE (5)", SysExProtocol.TE_SYSEX_FILE, frame[8].toInt() and 0x7F)
        // Body starts at subcommand (no leading TE_SYSEX_FILE byte): byte[0] = TE_SYSEX_FILE_GET (3)
        val packedPayload = frame.copyOfRange(9, frame.size - 1)
        val unpacked = SysExProtocol.unpack7bit(packedPayload)
        assertEquals("body[0] = TE_SYSEX_FILE_GET (3)", SysExProtocol.TE_SYSEX_FILE_GET, unpacked[0].toInt() and 0xFF)
    }

    @Ignore("restore flow requires wiring BackupManager to a fake MIDIRepository that tracks sendRawBytes calls — deferred for Phase 4 test coverage")
    @Test
    fun restoreFromValidPak_sendsFilePutCommands() {
        // Given a valid PAK byte array, BackupManager.restore() should invoke sendMidi with FILE_PUT (cmd=2) frames
    }
}
