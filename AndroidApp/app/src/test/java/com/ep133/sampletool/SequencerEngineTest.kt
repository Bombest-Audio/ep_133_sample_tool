package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for SequencerEngine MIDI Start/Stop/Clock transport output.
 * Wave 0 stubs — production code in Task 3-02.
 * Uses FakeMIDIPortRecording from PadsViewModelTest.kt.
 */
class SequencerEngineTest {

    @Ignore("Wave 1 — MIDI Start/Stop not yet implemented in SequencerEngine")
    @Test
    fun play_sendsMIDIStart() {
        // val port = FakeMIDIPortRecording()
        // val repo = RecordingMIDIRepository(port)
        // val engine = SequencerEngine(repo)
        // engine.play()
        // val rawSent = port.sentMessages.flatMap { it.second.toList() }
        // assertTrue("MIDI Start (0xFA) should be sent on play", rawSent.contains(0xFA.toByte()))
    }

    @Ignore("Wave 1 — MIDI Start/Stop not yet implemented in SequencerEngine")
    @Test
    fun stop_sendsMIDIStop() {
        // val port = FakeMIDIPortRecording()
        // val repo = RecordingMIDIRepository(port)
        // val engine = SequencerEngine(repo)
        // engine.play()
        // engine.stop()
        // val rawSent = port.sentMessages.flatMap { it.second.toList() }
        // assertTrue("MIDI Stop (0xFC) should be sent on stop", rawSent.contains(0xFC.toByte()))
    }

    @Ignore("Wave 1 — MIDI Start/Stop not yet implemented in SequencerEngine")
    @Test
    fun playLoop_sends6ClockTicksPerStep() {
        // Using runTest, run one step cycle
        // assert at least 6 0xF8 bytes sent via port.sentMessages
    }
}
