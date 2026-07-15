package com.ep133.sampletool

import com.ep133.sampletool.domain.audio.WavEncoder
import com.ep133.sampletool.domain.midi.ConvertedSample
import com.ep133.sampletool.domain.staging.SampleStagingStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleStagingStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SampleStagingStore(File(tmp.root, "staged"))

    private val sample = ConvertedSample(s16le(1000, -1000, 500), 1, 46875)

    @Test
    fun `stage writes a playable device-format WAV`() {
        val staged = store().stage("kick.wav", sample)
        assertTrue(staged.file.isFile)
        assertEquals(44L + 6L, staged.sizeBytes)
        val bytes = staged.file.readBytes()
        assertTrue(WavEncoder.isAlreadyDeviceFormat(bytes))
        assertArrayEquals(sample.pcm, bytes.copyOfRange(44, bytes.size))
    }

    @Test
    fun `stage overwrites an existing copy under the same name`() {
        val s = store()
        s.stage("kick.wav", sample)
        val bigger = ConvertedSample(s16le(1, 2, 3, 4, 5, 6), 1, 46875)
        val restaged = s.stage("kick.wav", bigger)
        assertEquals(1, s.list().size)
        assertEquals(44L + 12L, restaged.sizeBytes)
    }

    @Test
    fun `list is empty for a missing staging dir and sorted by name`() {
        val s = store()
        assertEquals(emptyList<Any>(), s.list())
        s.stage("snare.wav", sample)
        s.stage("kick.wav", sample)
        assertEquals(listOf("kick.wav", "snare.wav"), s.list().map { it.name })
    }

    @Test
    fun `rename moves the file and refuses to clobber an existing target`() {
        val s = store()
        s.stage("kick.wav", sample)
        s.stage("snare.wav", sample)

        val renamed = s.rename("kick.wav", "kick 2.wav")
        assertNotNull(renamed)
        assertEquals(listOf("kick 2.wav", "snare.wav"), s.list().map { it.name })

        assertNull(s.rename("kick 2.wav", "snare.wav"))   // target exists
        assertNull(s.rename("ghost.wav", "x.wav"))        // source missing
    }

    @Test
    fun `delete removes the staged copy only`() {
        val s = store()
        s.stage("kick.wav", sample)
        assertTrue(s.delete("kick.wav"))
        assertFalse(s.delete("kick.wav"))
        assertEquals(0, s.list().size)
    }

    @Test
    fun `duplicate creates numbered copies without touching the source entry`() {
        val s = store()
        s.stage("kick.wav", sample)
        assertEquals("kick copy.wav", s.duplicate("kick.wav")?.name)
        assertEquals("kick copy 2.wav", s.duplicate("kick.wav")?.name)
        assertEquals(3, s.list().size)
        assertArrayEquals(
            File(tmp.root, "staged/kick.wav").readBytes(),
            File(tmp.root, "staged/kick copy.wav").readBytes(),
        )
        assertNull(s.duplicate("ghost.wav"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal names are rejected`() {
        store().delete("../../etc/passwd.wav")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-wav names are rejected`() {
        store().stage("kick.mp3", sample)
    }
}
