package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.robots.PadsRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.TestTags
import com.ep133.sampletool.ui.pads.PadsScreen
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pads screen UI: grid rendering, group switching, and pad press visual state.
 * Note math and group-sync protocol live in the unit suite (EP133PadsTest, ActiveGroupSyncTest).
 */
class PadsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpPads(midi: ScriptedMIDIRepository = ScriptedMIDIRepository()): PadsRobot {
        composeTestRule.setContent {
            EP133Theme {
                PadsScreen(viewModel = PadsViewModel(midi))
            }
        }
        return PadsRobot(composeTestRule)
    }

    @Test
    fun padGrid_displaysChannelALabels() {
        // Arrange
        val pads = setUpPads()
        // Assert
        pads.assertGridVisible()
            .assertPadLabel("A.")
            .assertPadLabel("A2")
            .assertPadLabel("A5")
    }

    @Test
    fun bankSwitch_updatesGridToChannelB() {
        // Arrange
        val pads = setUpPads()
        // Act + Assert
        pads.selectGroup("B")
            .assertPadLabel("B.")
            .assertPadLabel("B5")
    }

    @Test
    fun bankSwitch_updatesGridToChannelD() {
        // Arrange
        val pads = setUpPads()
        // Act + Assert
        pads.selectGroup("D")
            .assertPadLabel("D.")
            .assertPadLabel("DENT")
    }

    @Test
    fun bankSwitch_channelSelectorShowsAllChannels() {
        // Arrange
        val pads = setUpPads()
        // Assert
        pads.assertPadLabel("A.")
        composeTestRule.onNode(androidx.compose.ui.test.hasTestTag(TestTags.groupChip("A"))).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasTestTag(TestTags.groupChip("B"))).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasTestTag(TestTags.groupChip("C"))).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasTestTag(TestTags.groupChip("D"))).assertExists()
    }

    @Test
    fun padPress_showsPressedStateAndClearsOnRelease() {
        // Arrange
        val pads = setUpPads()
        // Act + Assert: touch-down glows, release returns to idle
        pads.assertPadState(4, TestTags.PAD_STATE_IDLE)
            .pressPad(4)
            .assertPadState(4, TestTags.PAD_STATE_PRESSED)
            .releasePad(4)
            .assertPadState(4, TestTags.PAD_STATE_IDLE)
    }

    @Test
    fun padPress_sendsNoteOnAndOffToDevice() {
        // Arrange: connected so the noteOn/noteOff senders have an output port
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState())
        val pads = setUpPads(midi)
        // Act: pad index 9 is "A." = note 36
        pads.tapPad(9)
        composeTestRule.waitForIdle()
        // Assert: a real 0x90/0x80 frame reached the port
        assertEquals(listOf(36), midi.sentNoteOns().map { it.first })
        assertEquals(listOf(36), midi.sentNoteOffs())
    }

    @Test
    fun noScaleSelected_allPadsIdle() {
        // Arrange
        val pads = setUpPads()
        // Assert
        (0 until 12).forEach { pads.assertPadState(it, TestTags.PAD_STATE_IDLE) }
    }
}
