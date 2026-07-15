package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.BackupItem
import com.ep133.sampletool.ui.offline.OfflineBrowserViewModel
import com.ep133.sampletool.ui.offline.SamplePlayer
import com.ep133.sampletool.ui.offline.offlinePadParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Recording [SamplePlayer] fake — no MediaPlayer, no Android framework. */
private class FakeSamplePlayer(var startResult: Boolean = true) : SamplePlayer {
    val played = mutableListOf<File>()
    var stopCount = 0
    var lastOnComplete: (() -> Unit)? = null

    override fun play(file: File, onComplete: () -> Unit): Boolean {
        played.add(file)
        lastOnComplete = onComplete
        return startResult
    }

    override fun stop() {
        stopCount++
    }
}

/**
 * Offline backup browser ViewModel (ROADMAP 999.11 / issue #55): open a manifest-backed backup
 * from a temp dir, browse group/pad state, and audition through the [SamplePlayer] seam.
 */
class OfflineBrowserViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val player = FakeSamplePlayer()
    private lateinit var vm: OfflineBrowserViewModel

    private val tmpDir: File = File.createTempFile("ep133-offline", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = OfflineBrowserViewModel(player)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tmpDir.deleteRecursively()
    }

    /** Write a tar + sidecar manifest with pads on groups A and B; sym 42 has a real WAV. */
    private fun backupWithManifest(name: String = "MyBeat-EP133-P02-t"): BackupItem {
        val tar = File(tmpDir, "$name.tar").apply { writeBytes(ByteArray(4)) }
        val dir = File(tmpDir, "$name.manifest").also { File(it, "samples").mkdirs() }
        File(dir, "samples/42.wav").writeBytes(ByteArray(8))
        File(dir, "manifest.json").writeText(
            """
            {
              "version": 1,
              "project_slot": 2,
              "project_name": "02",
              "created_at": 1752500000000,
              "pads": [
                {"group": "A", "pad": "02", "metadata": {"sym": 0}},
                {"group": "A", "pad": "01", "metadata":
                  {"sym": 42, "sound.playmode": "oneshot", "sound.pitch": -1.5,
                   "sample.start": 100, "sample.end": 4000,
                   "envelope.attack": 3, "envelope.release": 200}},
                {"group": "B", "pad": "01", "metadata": {"sym": 77}}
              ],
              "samples": [
                {"sym": 42, "file": "samples/42.wav", "name": "snare", "channels": 1, "samplerate": 46875},
                {"sym": 77, "file": "samples/77.wav", "name": "ghost", "channels": 1, "samplerate": 46875}
              ],
              "skipped": []
            }
            """.trimIndent(),
        )
        return BackupItem(file = tar, name = tar.name, timestamp = 0L, hasManifest = true)
    }

    @Test
    fun open_withoutManifest_messagesAndStaysClosed() = runTest(dispatcher) {
        val tar = File(tmpDir, "legacy.tar").apply { writeBytes(ByteArray(4)) }
        vm.open(BackupItem(file = tar, name = "legacy.tar", timestamp = 0L, hasManifest = false))
        advanceUntilIdle()

        assertNull(vm.manifest.value)
        assertNotNull(vm.message.value)
    }

    @Test
    fun open_loadsManifest_resetsToGroupA_noSelection() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()

        val m = vm.manifest.value
        assertNotNull(m)
        assertEquals(2, m!!.projectSlot)
        assertEquals("A", vm.selectedGroup.value)
        assertNull(vm.selectedPad.value)
        assertNull(vm.auditioningSym.value)
        assertEquals("MyBeat-EP133-P02-t.tar", vm.backupName.value)
    }

    @Test
    fun padsInGroup_filtersAndSortsByPadNumber() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()

        val a = vm.padsInGroup("A")
        assertEquals(listOf("01", "02"), a.map { it.pad })
        assertEquals(listOf("01"), vm.padsInGroup("B").map { it.pad })
        assertTrue(vm.padsInGroup("C").isEmpty())
    }

    @Test
    fun selectGroup_switchesAndClearsSelection() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        vm.tapPad(vm.padsInGroup("A")[0])

        vm.selectGroup("B")

        assertEquals("B", vm.selectedGroup.value)
        assertNull(vm.selectedPad.value)

        vm.selectGroup("Z") // invalid — ignored
        assertEquals("B", vm.selectedGroup.value)
    }

    @Test
    fun tapPad_assigned_playsSampleFileAndTracksSym() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        val pad = vm.padsInGroup("A").first { it.sym == 42 }

        vm.tapPad(pad)

        assertEquals(pad, vm.selectedPad.value)
        assertEquals(42, vm.auditioningSym.value)
        assertEquals("42.wav", player.played.single().name)

        // Playback completion clears the auditioning marker.
        player.lastOnComplete?.invoke()
        assertNull(vm.auditioningSym.value)
    }

    @Test
    fun tapPad_sameSymTwice_togglesOff() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        val pad = vm.padsInGroup("A").first { it.sym == 42 }

        vm.tapPad(pad)
        vm.tapPad(pad)

        assertNull(vm.auditioningSym.value)
        assertEquals(1, player.played.size)
        assertTrue(player.stopCount >= 1)
    }

    @Test
    fun tapPad_emptyPad_selectsWithoutPlaying() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        val empty = vm.padsInGroup("A").first { it.sym == 0 }

        vm.tapPad(empty)

        assertEquals(empty, vm.selectedPad.value)
        assertNull(vm.auditioningSym.value)
        assertTrue(player.played.isEmpty())
        assertNull(offlinePadParams(empty))
    }

    @Test
    fun tapPad_missingSampleFile_messagesInsteadOfPlaying() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        val ghost = vm.padsInGroup("B").single() // sym 77 — WAV not on disk

        vm.tapPad(ghost)

        assertNull(vm.auditioningSym.value)
        assertTrue(player.played.isEmpty())
        assertNotNull(vm.message.value)
    }

    @Test
    fun tapPad_playerRefuses_staysIdleWithMessage() = runTest(dispatcher) {
        player.startResult = false
        vm.open(backupWithManifest())
        advanceUntilIdle()

        vm.tapPad(vm.padsInGroup("A").first { it.sym == 42 })

        assertNull(vm.auditioningSym.value)
        assertNotNull(vm.message.value)
    }

    @Test
    fun offlinePadParams_parsesReadoutValues() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()

        val p = offlinePadParams(vm.padsInGroup("A").first { it.sym == 42 })

        assertNotNull(p)
        assertEquals("oneshot", p!!.playmode)
        assertEquals(-1.5, p.pitch, 1e-9)
        assertEquals(100L, p.sampleStart)
        assertEquals(4000L, p.sampleEnd)
        assertEquals(3, p.attack)
        assertEquals(200, p.release)
    }

    @Test
    fun close_stopsPlaybackAndClearsState() = runTest(dispatcher) {
        vm.open(backupWithManifest())
        advanceUntilIdle()
        vm.tapPad(vm.padsInGroup("A").first { it.sym == 42 })

        vm.close()

        assertNull(vm.manifest.value)
        assertNull(vm.selectedPad.value)
        assertNull(vm.auditioningSym.value)
        assertEquals("", vm.backupName.value)
        assertTrue(player.stopCount >= 1)
        assertFalse(player.played.isEmpty()) // it did play before closing
    }
}
