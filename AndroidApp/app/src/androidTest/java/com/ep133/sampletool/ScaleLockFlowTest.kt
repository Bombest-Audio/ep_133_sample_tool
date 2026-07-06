package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.robots.AppRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.support.launchEP133App
import com.ep133.sampletool.ui.TestTags
import org.junit.Rule
import org.junit.Test

/**
 * E2E scale-lock flow: scale + root chosen on the Device screen flow through the shared
 * MIDIRepository StateFlows and re-skin the Pads grid. Pitch-class math is covered by
 * the computeInScaleSet unit tests; this asserts the cross-screen UX.
 *
 * Pad index → pitch class (group A): index 9 = "A." = note 36 (pc 0), index 10 = "A0" =
 * note 37 (pc 1), index 0 = "A7" = note 45 (pc 9).
 */
class ScaleLockFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun connectedRepo() = ScriptedMIDIRepository(TestMIDIRepository.connectedState())

    @Test
    fun scaleSelectedOnDevice_highlightsInScalePadsOnPads() {
        // Arrange
        composeTestRule.launchEP133App(connectedRepo())
        val app = AppRobot(composeTestRule)
        // Act: C Major on the Device screen
        app.goToDevice().selectScale("Major")
        // Assert: pc 0 ("A.") in scale, pc 1 ("A0") out
        app.goToPads()
            .assertPadState(9, TestTags.PAD_STATE_IN_SCALE)
            .assertPadState(10, TestTags.PAD_STATE_IDLE)
            .assertPadState(0, TestTags.PAD_STATE_IN_SCALE)
    }

    @Test
    fun rootNoteChange_recomputesInScalePads() {
        // Arrange
        composeTestRule.launchEP133App(connectedRepo())
        val app = AppRobot(composeTestRule)
        // Act: D Major
        app.goToDevice()
            .selectScale("Major")
            .selectRootNote("D")
        // Assert: pc 1 now in scale, pc 0 out
        app.goToPads()
            .assertPadState(10, TestTags.PAD_STATE_IN_SCALE)
            .assertPadState(9, TestTags.PAD_STATE_IDLE)
    }

    @Test
    fun clearingScale_returnsAllPadsToIdle() {
        // Arrange
        composeTestRule.launchEP133App(connectedRepo())
        val app = AppRobot(composeTestRule)
        // Act: select then clear
        app.goToDevice().selectScale("Major")
        app.goToPads().assertPadState(9, TestTags.PAD_STATE_IN_SCALE)
        app.goToDevice().selectScale("None")
        // Assert
        val pads = app.goToPads()
        (0 until 12).forEach { pads.assertPadState(it, TestTags.PAD_STATE_IDLE) }
    }
}
