package com.ep133.sampletool

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.ui.pads.PadsScreen
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Rule
import org.junit.Test

class PadsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpPads(midi: MIDIRepository = TestMIDIRepository()) {
        composeTestRule.setContent {
            EP133Theme {
                PadsScreen(viewModel = PadsViewModel(midi))
            }
        }
    }

    @Test
    fun padGrid_displaysChannelALabels() {
        setUpPads()
        composeTestRule.onNodeWithText("A.").assertIsDisplayed()
        composeTestRule.onNodeWithText("A2").assertIsDisplayed()
        composeTestRule.onNodeWithText("A5").assertIsDisplayed()
    }

    @Test
    fun padGrid_displaysDefaultSoundNames() {
        setUpPads()
        composeTestRule.onNodeWithText("KICK").assertIsDisplayed()
        composeTestRule.onNodeWithText("SNARE").assertIsDisplayed()
    }

    @Test
    fun bankSwitch_updatesGridToChannelB() {
        setUpPads()
        composeTestRule.onNodeWithText("B").performClick()
        composeTestRule.onNodeWithText("B.").assertIsDisplayed()
        composeTestRule.onNodeWithText("B5").assertIsDisplayed()
    }

    @Test
    fun bankSwitch_channelSelectorShowsAllChannels() {
        setUpPads()
        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
        composeTestRule.onNodeWithText("C").assertIsDisplayed()
        composeTestRule.onNodeWithText("D").assertIsDisplayed()
    }
}
