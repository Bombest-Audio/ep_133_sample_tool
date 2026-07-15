package com.ep133.sampletool

import com.ep133.sampletool.domain.export.ReaperExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Parses the produced .rpp text and checks tracks, items, and param mapping. */
class ReaperExporterTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "reaper-test-${System.nanoTime()}")
        .also { it.mkdirs() }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `rpp has a track per group and an item per assigned pad with params applied`() {
        val manifest = DawExportFixtures.loadManifest(tmp)
        val outDir = File(tmp, "out")

        val result = ReaperExporter().export(manifest, outDir, "Fixture-EP133-P03")
        val rpp = File(outDir, "Fixture-EP133-P03.rpp").readText()

        assertTrue(rpp.startsWith("<REAPER_PROJECT"))
        assertEquals(2, Regex("<TRACK").findAll(rpp).count())
        assertTrue(rpp.contains("NAME \"Group A\""))
        assertTrue(rpp.contains("NAME \"Group B\""))
        assertEquals(3, Regex("<ITEM").findAll(rpp).count())
        assertEquals(3, Regex("<SOURCE WAVE").findAll(rpp).count())
        assertTrue(rpp.contains("FILE \"samples/101.wav\""))
        assertTrue(rpp.contains("FILE \"samples/102.wav\""))

        // A01 param mapping: gain 0.5 / pan 0.5, pitch +3.5 semitones, trim 100..4788 frames.
        assertTrue(rpp.contains("VOLPAN 0.500000 0.500000"))
        assertTrue(rpp.contains("PLAYRATE 1.000000 1 3.500000"))
        val soffs = 100.0 / DawExportFixtures.SAMPLE_RATE
        val length = (4788.0 - 100.0) / DawExportFixtures.SAMPLE_RATE
        assertTrue(rpp.contains(String.format(java.util.Locale.US, "SOFFS %.6f", soffs)))
        assertTrue(rpp.contains(String.format(java.util.Locale.US, "LENGTH %.6f", length)))

        // Samples travel next to the rpp, and the share artifact zips both together.
        assertTrue(File(outDir, "samples/101.wav").isFile)
        ZipFile(result.shareFile).use { zip ->
            assertNotNull(zip.getEntry("Fixture-EP133-P03.rpp"))
            assertNotNull(zip.getEntry("samples/101.wav"))
            assertNotNull(zip.getEntry("samples/102.wav"))
            assertNotNull(zip.getEntry("README.txt"))
        }
    }
}
