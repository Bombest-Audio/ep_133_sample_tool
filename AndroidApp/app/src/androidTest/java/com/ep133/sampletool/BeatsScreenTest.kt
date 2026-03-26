package com.ep133.sampletool

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.ui.beats.BeatsScreen
import com.ep133.sampletool.ui.beats.BeatsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Rule
import org.junit.Test

class BeatsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpBeats(midi: MIDIRepository = TestMIDIRepository()) {
        val sequencer = SequencerEngine(midi)
        composeTestRule.setContent {
            EP133Theme {
                BeatsScreen(viewModel = BeatsViewModel(sequencer, midi))
            }
        }
    }

    @Test
    fun sequencer_displaysDefaultTracks() {
        setUpBeats()
        composeTestRule.onNodeWithText("KICK").assertIsDisplayed()
        composeTestRule.onNodeWithText("SNARE").assertIsDisplayed()
        composeTestRule.onNodeWithText("HI-HAT").assertIsDisplayed()
        composeTestRule.onNodeWithText("CLAP").assertIsDisplayed()
    }

    @Test
    fun sequencer_displaysBpmValue() {
        setUpBeats()
        composeTestRule.onNodeWithText("120").assertIsDisplayed()
    }

    @Test
    fun sequencer_displaysBpmLabel() {
        setUpBeats()
        composeTestRule.onNodeWithText("BPM").assertIsDisplayed()
    }
}
