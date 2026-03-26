package com.ep133.sampletool

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.ui.sounds.SoundsScreen
import com.ep133.sampletool.ui.sounds.SoundsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Rule
import org.junit.Test

class SoundsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpSounds(midi: MIDIRepository = TestMIDIRepository()) {
        composeTestRule.setContent {
            EP133Theme {
                SoundsScreen(viewModel = SoundsViewModel(midi))
            }
        }
    }

    @Test
    fun soundList_displaysFactorySounds() {
        setUpSounds()
        composeTestRule.onNodeWithText("MICRO KICK").assertIsDisplayed()
    }

    @Test
    fun categoryChip_filtersToKicks() {
        setUpSounds()
        composeTestRule.onNodeWithText("Kicks").performClick()
        composeTestRule.onNodeWithText("MICRO KICK").assertIsDisplayed()
    }

    @Test
    fun categoryChip_allShowsEverything() {
        setUpSounds()
        composeTestRule.onNodeWithText("Kicks").performClick()
        composeTestRule.onNodeWithText("ALL").performClick()
        composeTestRule.onNodeWithText("MICRO KICK").assertIsDisplayed()
    }
}
