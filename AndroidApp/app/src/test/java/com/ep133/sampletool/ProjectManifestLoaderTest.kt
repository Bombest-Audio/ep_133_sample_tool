package com.ep133.sampletool

import com.ep133.sampletool.domain.backup.ProjectManifestLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Manifest read model (999.10): parse manifest.json from a sidecar dir, resolve sample files,
 * and stay defensive about missing/garbage input. This loader is the seam 999.11 offline mode
 * consumes.
 */
class ProjectManifestLoaderTest {

    private val tmpDir: File = File.createTempFile("ep133-loader", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun manifestDir(json: String, sampleFiles: List<String> = emptyList()): File {
        val dir = File(tmpDir, "EP133-P02-test.manifest").also { it.mkdirs() }
        File(dir, "manifest.json").writeText(json)
        for (rel in sampleFiles) {
            File(dir, rel).also { it.parentFile.mkdirs() }.writeBytes(ByteArray(8))
        }
        return dir
    }

    @Test
    fun load_parsesFieldsAndResolvesSampleFiles() {
        val dir = manifestDir(
            """
            {
              "version": 1,
              "project_slot": 2,
              "project_name": "02",
              "created_at": 1752500000000,
              "pads": [
                {"group": "A", "pad": "01", "metadata": {"sym": 42, "sound.playmode": "oneshot"}},
                {"group": "A", "pad": "02", "metadata": {"sym": 0}}
              ],
              "samples": [
                {"sym": 42, "file": "samples/42.wav", "name": "snare", "channels": 2, "samplerate": 46875},
                {"sym": 77, "file": "samples/77.wav", "name": null, "channels": 1, "samplerate": 46875}
              ],
              "skipped": ["sample 77: download failed"]
            }
            """.trimIndent(),
            sampleFiles = listOf("samples/42.wav"),
        )

        val m = ProjectManifestLoader.load(dir)

        assertNotNull(m)
        assertEquals(1, m!!.version)
        assertEquals(2, m.projectSlot)
        assertEquals("02", m.projectName)
        assertEquals(1752500000000L, m.createdAtMillis)
        assertEquals(2, m.pads.size)
        assertEquals(42, m.pads[0].sym)
        assertEquals(0, m.pads[1].sym)
        assertEquals(listOf("sample 77: download failed"), m.skipped)

        val present = m.samples.single { it.sym == 42 }
        assertEquals("snare", present.name)
        assertEquals(2, present.channels)
        assertNotNull("existing WAV resolves to a File", present.file)
        // Missing WAV keeps its entry but resolves to null instead of failing the load.
        assertNull(m.samples.single { it.sym == 77 }.file)
    }

    @Test
    fun load_missingManifestJson_returnsNull() {
        val dir = File(tmpDir, "empty.manifest").also { it.mkdirs() }
        assertNull(ProjectManifestLoader.load(dir))
    }

    @Test
    fun load_malformedJson_returnsNull() {
        assertNull(ProjectManifestLoader.load(manifestDir("not json {{{")))
    }

    @Test
    fun manifestDirFor_and_hasManifest_pairWithTheTar() {
        val tar = File(tmpDir, "MyBeat-EP133-P02-test.tar").apply { writeBytes(ByteArray(4)) }
        val dir = ProjectManifestLoader.manifestDirFor(tar)

        assertEquals("MyBeat-EP133-P02-test.manifest", dir.name)
        assertEquals(tar.parentFile, dir.parentFile)
        assertTrue("no sidecar yet", !ProjectManifestLoader.hasManifest(tar))

        dir.mkdirs()
        File(dir, ProjectManifestLoader.MANIFEST_FILENAME).writeText("{}")
        assertTrue(ProjectManifestLoader.hasManifest(tar))
        assertNotNull(ProjectManifestLoader.loadFor(tar))
    }
}
