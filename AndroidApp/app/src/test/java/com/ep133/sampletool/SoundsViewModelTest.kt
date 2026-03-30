package com.ep133.sampletool

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SoundsViewModel.previewSound() — sound preview on tap.
 * Wave 0 stubs — production code in Task 3-02.
 * Uses FakeMIDIPortRecording from PadsViewModelTest.kt.
 */
class SoundsViewModelTest {

    @Ignore("Wave 1 — SoundsViewModel.previewSound not yet implemented")
    @Test
    fun previewSound_sendsNoteOnImmediately() {
        // val port = FakeMIDIPortRecording()
        // val repo = RecordingMIDIRepository(port)
        // val vm = SoundsViewModel(repo)
        // vm.previewSound(sound)
        // val noteOns = port.sentMessages.filter { (it.second[0].toInt() and 0xF0) == 0x90 }
        // assertTrue(noteOns.isNotEmpty())
    }

    @Ignore("Wave 1 — SoundsViewModel.previewSound not yet implemented")
    @Test
    fun previewSound_sendsNoteOffAfter500ms() = runTest {
        // val port = FakeMIDIPortRecording()
        // val repo = RecordingMIDIRepository(port)
        // val vm = SoundsViewModel(repo)
        // vm.previewSound(sound)
        // advanceTimeBy(501)
        // val noteOffs = port.sentMessages.filter { (it.second[0].toInt() and 0xF0) == 0x80 }
        // assertTrue(noteOffs.isNotEmpty())
    }

    @Ignore("Wave 1 — SoundsViewModel.previewSound not yet implemented")
    @Test
    fun previewSound_cancelsPreviousPreviewIfNewTapBeforeNoteOff() = runTest {
        // Call preview twice with advanceTimeBy(200) between
        // Assert only one noteOff sent (second tap cancels first noteOff job)
    }

    @Ignore("Wave 1 — SoundsViewModel.previewSound not yet implemented")
    @Test
    fun previewSound_usesChannel9() {
        // val port = FakeMIDIPortRecording()
        // val repo = RecordingMIDIRepository(port)
        // val vm = SoundsViewModel(repo)
        // vm.previewSound(sound)
        // val noteOn = port.sentMessages.lastOrNull { (it.second[0].toInt() and 0xF0) == 0x90 }
        // val channel = noteOn?.second?.get(0)?.toInt()?.and(0x0F)
        // assertEquals(9, channel)  // MIDI channel 10 = index 9
    }
}
