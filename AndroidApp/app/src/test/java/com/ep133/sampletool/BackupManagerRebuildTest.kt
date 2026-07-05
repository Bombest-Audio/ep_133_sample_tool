package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BackupManager
import com.ep133.sampletool.domain.midi.BackupProgress
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.RestoreProgress
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Covers the BackupManager rebuild on the reqId-correlated file ops: backup must assemble the
 * FULL multi-chunk bytes (the old single-chunk model truncated/dropped them) and restore must PUT
 * every file back exactly.
 */
class BackupManagerRebuildTest {

    private class NoopPort : MIDIPort {
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

    /** Repo double: canned /sounds entries + whole-file bytes, records restore PUTs. */
    private class FakeRepo(
        private val files: LinkedHashMap<Int, Pair<String, ByteArray>>, // nodeId -> (name, bytes)
        connected: Boolean = true,
    ) : MIDIRepository(NoopPort()) {
        val restored = LinkedHashMap<String, ByteArray>()

        init {
            if (connected) _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        override suspend fun listSoundEntries(): List<SysExProtocol.FileEntry> =
            files.map { (nodeId, nb) -> SysExProtocol.FileEntry(nodeId, 1, nb.second.size.toLong(), nb.first) }

        override suspend fun getFileBytes(nodeId: Int): ByteArray? = files[nodeId]?.second

        override suspend fun putSampleFile(
            name: String,
            pcmBytes: ByteArray,
            channels: Int,
            sampleRate: Int,
        ): Int? {
            restored[name] = pcmBytes
            return 42  // fake node ID
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = z.readBytes()
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return out
    }

    @Test
    fun backup_assemblesFullMultiChunkFiles_noTruncation() = runTest {
        // Each file is far larger than one SysEx chunk — the bug was truncation to a single chunk.
        val big1 = ByteArray(5000) { (it % 251).toByte() }
        val big2 = ByteArray(9000) { ((it * 7) % 251).toByte() }
        val repo = FakeRepo(linkedMapOf(1001 to ("001.pcm" to big1), 1002 to ("002.pcm" to big2)))

        val events = BackupManager(repo).createBackup().toList()
        val done = events.filterIsInstance<BackupProgress.Done>().single()
        val zip = unzip(done.pakBytes)

        assertArrayEquals("001.pcm full bytes", big1, zip["001.pcm"])
        assertArrayEquals("002.pcm full bytes", big2, zip["002.pcm"])
        assertTrue("metadata.json present", zip.containsKey("metadata.json"))
    }

    @Test
    fun backup_offline_emitsError() = runTest {
        val repo = FakeRepo(linkedMapOf(), connected = false)
        val events = BackupManager(repo).createBackup().toList()
        assertTrue("offline backup errors", events.any { it is BackupProgress.Error })
    }

    @Test
    fun restore_putsEveryFileBack_withExactBytes() = runTest {
        val a = ByteArray(3000) { it.toByte() }
        val b = ByteArray(4096) { (255 - (it % 256)).toByte() }
        val pak = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("kick.pcm")); z.write(a); z.closeEntry()
                z.putNextEntry(ZipEntry("snare.pcm")); z.write(b); z.closeEntry()
                z.putNextEntry(ZipEntry("metadata.json")); z.write("{}".toByteArray()); z.closeEntry()
            }
        }.toByteArray()

        val repo = FakeRepo(linkedMapOf())
        val events = BackupManager(repo).restore(pak).toList()

        assertTrue("restore completes", events.any { it is RestoreProgress.Done })
        assertEquals(setOf("kick.pcm", "snare.pcm"), repo.restored.keys)
        assertArrayEquals(a, repo.restored["kick.pcm"])
        assertArrayEquals(b, repo.restored["snare.pcm"])
    }
}
