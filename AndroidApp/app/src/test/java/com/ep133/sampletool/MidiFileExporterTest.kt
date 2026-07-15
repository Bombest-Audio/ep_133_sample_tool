package com.ep133.sampletool

import com.ep133.sampletool.domain.export.MidiFileExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the produced SMF bytes with a tiny in-test reader and asserts the type-1 skeleton:
 * tempo track + one track per group, one marker note per assigned pad at the device note.
 */
class MidiFileExporterTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "midi-test-${System.nanoTime()}")
        .also { it.mkdirs() }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    // ── Tiny SMF reader (test-only) ──

    private class Smf(val format: Int, val division: Int, val tracks: List<ByteArray>)

    private fun readSmf(bytes: ByteArray): Smf {
        fun u16(o: Int) = ((bytes[o].toInt() and 0xFF) shl 8) or (bytes[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[o + i].toInt() and 0xFF) }
        assertEquals("MThd", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(6, u32(4))
        val format = u16(8)
        val ntrks = u16(10)
        val division = u16(12)
        var o = 14
        val tracks = mutableListOf<ByteArray>()
        repeat(ntrks) {
            assertEquals("MTrk", String(bytes, o, 4, Charsets.US_ASCII))
            val len = u32(o + 4)
            tracks += bytes.copyOfRange(o + 8, o + 8 + len)
            o += 8 + len
        }
        assertEquals(bytes.size, o)
        return Smf(format, division, tracks)
    }

    private fun readVlq(data: ByteArray, pos: IntArray): Int {
        var value = 0
        while (true) {
            val b = data[pos[0]++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return value
        }
    }

    /** (deltaTicks, note, velocity) of every note-on in a track chunk. */
    private fun noteOns(track: ByteArray): List<Triple<Int, Int, Int>> {
        val pos = intArrayOf(0)
        val result = mutableListOf<Triple<Int, Int, Int>>()
        while (pos[0] < track.size) {
            val delta = readVlq(track, pos)
            val status = track[pos[0]++].toInt() and 0xFF
            when {
                status == 0xFF -> {
                    pos[0]++ // meta type
                    val len = readVlq(track, pos)
                    pos[0] += len
                }
                status and 0xF0 == 0x90 || status and 0xF0 == 0x80 -> {
                    val note = track[pos[0]++].toInt() and 0x7F
                    val vel = track[pos[0]++].toInt() and 0x7F
                    if (status and 0xF0 == 0x90 && vel > 0) result += Triple(delta, note, vel)
                }
                else -> error("unexpected status 0x${status.toString(16)} in test SMF")
            }
        }
        return result
    }

    @Test
    fun `smf is type 1 with tempo track plus a track per group and device pad notes`() {
        val manifest = DawExportFixtures.loadManifest(tmp)
        val outDir = File(tmp, "out")

        val result = MidiFileExporter().export(manifest, outDir, "Fixture-EP133-P03")
        assertEquals("Fixture-EP133-P03.mid", result.shareFile.name)
        assertTrue(File(outDir, "README.txt").isFile)

        val smf = readSmf(result.shareFile.readBytes())
        assertEquals(1, smf.format)
        assertEquals(480, smf.division)
        // Tempo track + groups A and B (C unassigned, D's sample missing).
        assertEquals(3, smf.tracks.size)

        // Tempo track: no notes, carries the set-tempo meta (FF 51 03 07 A1 20 = 120 BPM).
        assertTrue(noteOns(smf.tracks[0]).isEmpty())
        assertTrue(containsTempo120(smf.tracks[0]))

        // Group A: pads 01 and 02 -> notes 36, 37 at fixed velocity 100, one beat apart.
        val groupA = noteOns(smf.tracks[1])
        assertEquals(listOf(36, 37), groupA.map { it.second })
        assertTrue(groupA.all { it.third == 100 })
        assertEquals(0, groupA[0].first)
        assertEquals(240, groupA[1].first) // delta after the previous half-beat note-off

        // Group B: pad 01 -> note 48.
        assertEquals(listOf(48), noteOns(smf.tracks[2]).map { it.second })
    }

    private fun containsTempo120(track: ByteArray): Boolean {
        val needle = byteArrayOf(0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20)
        outer@ for (i in 0..track.size - needle.size) {
            for (j in needle.indices) {
                if (track[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
