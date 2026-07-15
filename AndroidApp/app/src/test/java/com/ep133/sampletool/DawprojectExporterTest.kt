package com.ep133.sampletool

import com.ep133.sampletool.domain.export.DawprojectExporter
import com.ep133.sampletool.domain.export.buildExportModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Unzips the produced .dawproject and asserts the XML structure (ROADMAP 999.6). */
class DawprojectExporterTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "dawproject-test-${System.nanoTime()}")
        .also { it.mkdirs() }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `export produces a zip with valid project xml, tracks per group and embedded samples`() {
        val manifest = DawExportFixtures.loadManifest(tmp)
        val outDir = File(tmp, "out")

        val result = DawprojectExporter().export(manifest, outDir, "Fixture-EP133-P03")

        assertEquals("Fixture-EP133-P03.dawproject", result.shareFile.name)
        assertTrue(File(outDir, "README.txt").readText().contains("Patterns/sequences"))

        ZipFile(result.shareFile).use { zip ->
            assertNotNull(zip.getEntry("metadata.xml"))
            assertNotNull(zip.getEntry("samples/101.wav"))
            assertNotNull(zip.getEntry("samples/102.wav"))

            val entry = zip.getEntry("project.xml")
            assertNotNull(entry)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(zip.getInputStream(entry))
            val root = doc.documentElement
            assertEquals("Project", root.tagName)

            // Groups A and B have assigned pads; C is unassigned, D's sample is missing.
            val tracks = elements(root, "Track")
            assertEquals(listOf("Group A", "Group B"), tracks.map { it.getAttribute("name") })

            // 3 clips total: A01, A02, B01. Each wraps an Audio/File pair.
            val clips = elements(root, "Clip")
            assertEquals(3, clips.size)
            val files = elements(root, "File").map { it.getAttribute("path") }
            assertEquals(setOf("samples/101.wav", "samples/102.wav"), files.toSet())

            // Tempo context present.
            assertEquals(1, elements(root, "Tempo").size)
        }
    }

    @Test
    fun `export model maps pads to notes and params`() {
        val model = buildExportModel(DawExportFixtures.loadManifest(tmp))

        val a01 = model.groups.getValue("A").first()
        assertEquals(36, a01.midiNote)
        assertEquals(3.5, a01.pitchSemitones, 1e-9)
        assertEquals(0.5, a01.pan, 1e-9)
        assertEquals(0.5, a01.gain, 1e-9)
        assertEquals(100L, a01.startFrame)
        assertEquals(4788L, a01.endFrame)

        // Missing sample file (sym 999) is skipped with a note; unassigned pad is silent.
        assertTrue(model.groups.keys.none { it == "D" })
        assertTrue(model.notes.any { it.contains("sample 999") })
    }
}
