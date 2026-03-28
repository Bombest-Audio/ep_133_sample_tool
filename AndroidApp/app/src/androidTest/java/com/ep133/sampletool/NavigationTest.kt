package com.ep133.sampletool

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.domain.midi.ChordPlayer
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.sequencer.SequencerEngine
import com.ep133.sampletool.ui.EP133App
import com.ep133.sampletool.ui.beats.BeatsViewModel
import com.ep133.sampletool.ui.chords.ChordsViewModel
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.sounds.SoundsViewModel
import org.junit.Rule
import org.junit.Test

class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpApp(midi: MIDIRepository) {
        val sequencer = SequencerEngine(midi)
        val chordPlayer = ChordPlayer(midi)
        composeTestRule.setContent {
            EP133App(
                padsViewModel = PadsViewModel(midi),
                beatsViewModel = BeatsViewModel(sequencer, midi),
                soundsViewModel = SoundsViewModel(midi),
                chordsViewModel = ChordsViewModel(chordPlayer),
                deviceViewModel = DeviceViewModel(midi),
            )
        }
    }

    @Test
    fun bottomNav_padsTabIsSelectedByDefault() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("PADS").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchToBeatsTab() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("BEATS").performClick()
        composeTestRule.onNodeWithText("BPM").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchToSoundsTab() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("SOUNDS").performClick()
        composeTestRule.onNodeWithText("MICRO KICK").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchToDeviceTab() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("DEVICE").performClick()
        composeTestRule.onNodeWithText("EP-133").assertIsDisplayed()
    }

    @Test
    fun bottomNav_roundTripBackToPads() {
        setUpApp(TestMIDIRepository())
        composeTestRule.onNodeWithText("BEATS").performClick()
        composeTestRule.onNodeWithText("PADS").performClick()
        composeTestRule.onNodeWithText("A").assertIsDisplayed()
    }
}
