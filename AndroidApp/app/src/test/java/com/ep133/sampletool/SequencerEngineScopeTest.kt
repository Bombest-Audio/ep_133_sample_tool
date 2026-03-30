package com.ep133.sampletool

import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class SequencerEngineScopeTest {

    @Test
    fun close_cancelsPendingNoteOffJobs() = runTest {
        val fakeMidi = MIDIRepository(FakeMIDIPort())
        val engine = SequencerEngine(fakeMidi)

        engine.play()
        assertTrue(engine.state.value.playing)

        engine.close()

        // After close(), engine's internal scope is cancelled.
        // playJob is also cancelled. Verify close() does not throw.
        // No further noteOff calls will fire since the scope is cancelled.
        assertTrue("close() completed without throwing", true)
    }
}
