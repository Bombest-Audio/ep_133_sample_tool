package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.WavEncoder
import com.ep133.sampletool.domain.backup.ProjectManifestLoader
import com.ep133.sampletool.domain.backup.ProjectManifestWriter
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.SysExProtocol
import com.ep133.sampletool.domain.model.DeviceState
import com.ep133.sampletool.midi.MIDIPort
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sidecar-manifest writer (999.10) against a scripted repository double: walks the device's
 * groups/A-D/pad tree, captures pad metadata, exports referenced samples as WAVs, and records
 * best-effort skips without ever failing the walk.
 */
class ProjectManifestWriterTest {

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

    /**
     * Scripted node tree: project 3000 → groups 3100 → A..D (3200..3500), each with two pad
     * FILE nodes. Pad A/01 binds sample 193; every other pad is `{"sym":0}`.
     */
    private open class ScriptedRepo : MIDIRepository(NoopPort()) {
        val children = mutableMapOf<Int, List<SysExProtocol.FileEntry>>(
            3000 to listOf(SysExProtocol.FileEntry(3100, 2, 0, "groups")),
            3100 to listOf(
                SysExProtocol.FileEntry(3200, 2, 0, "A"),
                SysExProtocol.FileEntry(3300, 2, 0, "B"),
                SysExProtocol.FileEntry(3400, 2, 0, "C"),
                SysExProtocol.FileEntry(3500, 2, 0, "D"),
            ),
            3200 to pads(3201),
            3300 to pads(3301),
            3400 to pads(3401),
            3500 to pads(3501),
        )
        val metadata = mutableMapOf<Int, JSONObject>().apply {
            for (base in listOf(3201, 3301, 3401, 3501)) {
                put(base, JSONObject("""{"sym":0}"""))
                put(base + 1, JSONObject("""{"sym":0}"""))
            }
            put(
                3201,
                JSONObject(
                    """{"sym":193,"sound.playmode":"oneshot","sample.start":0,"sample.end":4,
                       "envelope.attack":0,"envelope.release":255,"sound.pitch":0.0,"sound.pan":0}""",
                ),
            )
            put(193, JSONObject("""{"channels":1,"samplerate":46875,"name":"kick","format":"s16"}"""))
        }
        val sampleBytes = mutableMapOf<Int, ByteArray>(
            // Raw s16 LE PCM: 4 samples.
            193 to byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00),
        )

        init {
            _deviceState.value = DeviceState(connected = true, outputPortId = "out")
        }

        override suspend fun <T> withFileSession(block: suspend () -> T): T = block()
        override suspend fun listAllChildren(nodeId: Int): List<SysExProtocol.FileEntry> =
            children[nodeId] ?: emptyList()
        override suspend fun getMetadataJson(nodeId: Int): JSONObject =
            metadata[nodeId] ?: JSONObject()
        override suspend fun getFileBytes(nodeId: Int): ByteArray? = sampleBytes[nodeId]

        companion object {
            fun pads(base: Int) = listOf(
                SysExProtocol.FileEntry(base, 0x1d, 0, "01"),
                SysExProtocol.FileEntry(base + 1, 0x1d, 0, "02"),
            )
        }
    }

    private val tmpDir: File = File.createTempFile("ep133-manifest", "").let {
        it.delete()
        it.mkdirs()
        it
    }
    private val slot = MIDIRepository.ProjectSlot(nodeId = 3000, name = "03", sizeBytes = 128, isActive = false)
    private val tarFile = File(tmpDir, "EP133-P03-2026-07-14-1200.tar").apply { writeBytes(ByteArray(16)) }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun writesManifestJson_andDeviceFormatWav() = runTest {
        val dir = ProjectManifestWriter(ScriptedRepo()).writeManifest(slot, tarFile, slotNumber = 3)

        assertNotNull("manifest dir written", dir)
        assertEquals(ProjectManifestLoader.manifestDirFor(tarFile), dir)
        assertTrue(ProjectManifestLoader.hasManifest(tarFile))

        val root = JSONObject(File(dir!!, "manifest.json").readText())
        assertEquals(1, root.getInt("version"))
        assertEquals(3, root.getInt("project_slot"))
        assertEquals("03", root.getString("project_name"))
        assertEquals("4 groups x 2 pads scripted", 8, root.getJSONArray("pads").length())
        assertEquals(0, root.getJSONArray("skipped").length())

        val wav = File(dir, "samples/193.wav").readBytes()
        assertTrue("exported sample is a device-format WAV", WavEncoder.isAlreadyDeviceFormat(wav))
    }

    @Test
    fun roundTrip_loaderReadsWriterOutput() = runTest {
        val dir = ProjectManifestWriter(ScriptedRepo()).writeManifest(slot, tarFile, slotNumber = 3)

        val manifest = ProjectManifestLoader.load(dir!!)

        assertNotNull(manifest)
        assertEquals(3, manifest!!.projectSlot)
        assertEquals("03", manifest.projectName)
        val bound = manifest.pads.single { it.sym != 0 }
        assertEquals("A", bound.group)
        assertEquals("01", bound.pad)
        assertEquals(193, bound.sym)
        val sample = manifest.samples.single()
        assertEquals(193, sample.sym)
        assertEquals("kick", sample.name)
        assertEquals(1, sample.channels)
        assertEquals(46875, sample.sampleRate)
        assertNotNull("sample WAV resolved on disk", sample.file)
    }

    @Test
    fun padMetadataFailure_isSkippedNotFatal() = runTest {
        val repo = object : ScriptedRepo() {
            override suspend fun getMetadataJson(nodeId: Int): JSONObject {
                if (nodeId == 3301) throw RuntimeException("device timeout")
                return super.getMetadataJson(nodeId)
            }
        }

        val dir = ProjectManifestWriter(repo).writeManifest(slot, tarFile, slotNumber = 3)

        val root = JSONObject(File(dir!!, "manifest.json").readText())
        assertEquals("failed pad excluded", 7, root.getJSONArray("pads").length())
        val skipped = root.getJSONArray("skipped")
        assertEquals(1, skipped.length())
        assertTrue(skipped.getString(0).contains("B/01"))
    }

    @Test
    fun sampleDownloadFailure_isSkippedNotFatal() = runTest {
        val repo = ScriptedRepo().apply { sampleBytes.clear() }

        val dir = ProjectManifestWriter(repo).writeManifest(slot, tarFile, slotNumber = 3)

        assertNotNull(dir)
        assertFalse(File(dir!!, "samples/193.wav").exists())
        val root = JSONObject(File(dir, "manifest.json").readText())
        assertEquals(0, root.getJSONArray("samples").length())
        assertTrue(
            root.getJSONArray("skipped").join(",").contains("sample 193"),
        )
    }

    @Test
    fun nothingReadable_writesNoSidecarAndReturnsNull() = runTest {
        val repo = ScriptedRepo().apply { children.clear() }

        val dir = ProjectManifestWriter(repo).writeManifest(slot, tarFile, slotNumber = 3)

        assertNull(dir)
        assertFalse(ProjectManifestLoader.manifestDirFor(tarFile).exists())
        assertFalse(ProjectManifestLoader.hasManifest(tarFile))
    }
}
