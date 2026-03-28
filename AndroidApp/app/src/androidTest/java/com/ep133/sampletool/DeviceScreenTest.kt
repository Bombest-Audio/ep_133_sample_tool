package com.ep133.sampletool

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Rule
import org.junit.Test

class DeviceScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpDevice(midi: MIDIRepository = TestMIDIRepository()) {
        composeTestRule.setContent {
            EP133Theme {
                DeviceScreen(viewModel = DeviceViewModel(midi))
            }
        }
    }

    @Test
    fun deviceScreen_displaysModelName() {
        setUpDevice()
        composeTestRule.onNodeWithText("EP-133").assertIsDisplayed()
    }

    @Test
    fun deviceScreen_showsOfflineWhenNoDevice() {
        setUpDevice()
        composeTestRule.onNodeWithText("OFFLINE").assertIsDisplayed()
    }

    @Test
    fun deviceScreen_displaysChannelSelector() {
        setUpDevice()
        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
        composeTestRule.onNodeWithText("C").assertIsDisplayed()
        composeTestRule.onNodeWithText("D").assertIsDisplayed()
    }

    @Test
    fun deviceScreen_displaysActionButtons() {
        setUpDevice()
        composeTestRule.onNodeWithText("Backup Device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sync Samples").assertIsDisplayed()
    }

    // ── CONN-04: Three-state connection UI ──────────────────────────────

    @Test
    fun deviceScreen_showsGrantPermissionButtonWhenDisconnected() {
        setUpDevice()
        // Disconnected with UNKNOWN permission state → shows "Grant Permission" button
        composeTestRule.onNodeWithText("Grant Permission").assertIsDisplayed()
    }

    @Test
    fun deviceScreen_showsAwaitingStateWhenPermissionPending() {
        setUpDevice(TestMIDIRepository.withPermissionState(PermissionState.AWAITING))
        composeTestRule.onNodeWithText("Waiting for USB permission…").assertIsDisplayed()
    }

    @Test
    fun deviceScreen_showsDeniedStateWhenPermissionDenied() {
        setUpDevice(TestMIDIRepository.withPermissionState(PermissionState.DENIED))
        composeTestRule.onNodeWithText("Open Settings").assertIsDisplayed()
    }
}
