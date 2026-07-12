package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.robots.DeviceRobot
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * UI-branch pinning for the debug-only Simulated EP-133 toggle: visibility behind
 * simToggleAvailable and the onSimToggle callback contract. Cheap rendering tests with an
 * inert TestMIDIRepository — the real simulator round trip (swapPort over the wire-level
 * protocol) is SimulatedDeviceTest territory and is NOT duplicated here.
 */
class DeviceSimToggleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpDevice(wireToggle: Boolean): Pair<DeviceRobot, MutableList<Boolean>> {
        val toggleInvocations = mutableListOf<Boolean>()
        val viewModel = DeviceViewModel(TestMIDIRepository())
        if (wireToggle) {
            viewModel.onSimToggle = { enabled -> toggleInvocations += enabled }
        }
        composeTestRule.setContent {
            EP133Theme {
                DeviceScreen(viewModel = viewModel)
            }
        }
        return DeviceRobot(composeTestRule) to toggleInvocations
    }

    @Test
    fun offline_withCallbackWired_toggleDisplayed_flipInvokesCallbackAndTurnsOn() {
        // Arrange — offline (inert repo, no hardware) with MainActivity-style callback wired
        val (robot, invocations) = setUpDevice(wireToggle = true)

        // Act — flip the switch on the offline Device screen
        robot.assertOfflineState()
            .assertSimToggleDisplayed()
            .assertSimToggleOff()
            .flipSimToggle()

        // Assert — callback got true and the Switch reflects simModeActive
        composeTestRule.waitForIdle()
        assertEquals(listOf(true), invocations)
        robot.assertSimToggleOn()
    }

    @Test
    fun offline_withoutCallback_toggleAbsent() {
        // Arrange — onSimToggle = null (release-equivalent: provider unavailable)
        val (robot, invocations) = setUpDevice(wireToggle = false)

        // Assert — no toggle anywhere on the screen
        robot.assertOfflineState()
            .assertSimToggleAbsent()
        assertEquals(emptyList<Boolean>(), invocations)
    }
}
