package com.ep133.sampletool

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.domain.model.PermissionState
import com.ep133.sampletool.robots.DeviceRobot
import com.ep133.sampletool.support.FakeFirmwareCatalog
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Device screen UI: the three offline permission branches, the connected stats panel,
 * scale/channel controls, and the backup/restore SAF entry points. Firmware comparison
 * logic lives in DeviceViewModelFirmwareTest (unit); banner rendering in FirmwareBannerTest.
 */
class DeviceScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness(
        val midi: ScriptedMIDIRepository,
        val viewModel: DeviceViewModel,
        val robot: DeviceRobot,
    ) {
        val backupRequests = mutableListOf<String>()
        val restoreRequests = mutableListOf<Unit>()
    }

    private fun setUpDevice(
        midi: ScriptedMIDIRepository = ScriptedMIDIRepository(),
        catalog: FakeFirmwareCatalog = FakeFirmwareCatalog(),
    ): Harness {
        val viewModel = DeviceViewModel(midi, catalog)
        val harness = Harness(midi, viewModel, DeviceRobot(composeTestRule))
        viewModel.onRequestBackup = { name -> harness.backupRequests += name }
        viewModel.onRequestRestore = { harness.restoreRequests += Unit }
        composeTestRule.setContent {
            EP133Theme {
                DeviceScreen(viewModel = viewModel)
            }
        }
        return harness
    }

    private fun connected(midi: ScriptedMIDIRepository = ScriptedMIDIRepository(TestMIDIRepository.connectedState())) = midi

    // ── CONN-04: three-state offline UI ──

    @Test
    fun offline_unknownPermission_showsGrantPermission() {
        // Arrange
        val h = setUpDevice()
        // Assert
        h.robot.assertOfflineState()
            .assertPermissionAction("Grant Permission")
    }

    @Test
    fun offline_awaitingPermission_showsSpinnerText() {
        // Arrange
        val h = setUpDevice(ScriptedMIDIRepository(initialStateFor(PermissionState.AWAITING)))
        // Assert
        h.robot.assertOfflineState()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("waiting for USB permission…")).assertExists()
    }

    @Test
    fun offline_deniedPermission_showsOpenSettings() {
        // Arrange
        val h = setUpDevice(ScriptedMIDIRepository(initialStateFor(PermissionState.DENIED)))
        // Assert
        h.robot.assertPermissionAction("Open Settings")
    }

    @Test
    fun offline_grantPermission_requestsDeviceRefresh() {
        // Arrange
        val h = setUpDevice()
        // Act — Grant Permission triggers a device refresh (the USB permission path)
        h.robot.clickPermissionAction()
        // Assert: no crash, still offline (NoOp port has no devices)
        h.robot.assertOfflineState()
    }

    // ── Connected panel ──

    @Test
    fun connected_showsConnectionCard() {
        // Arrange
        val h = setUpDevice(connected())
        // Assert
        composeTestRule.onNode(androidx.compose.ui.test.hasText("CONNECTED OVER USB-OTG")).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("EP-133")).assertExists()
    }

    @Test
    fun connected_showsStatsFromDeviceState() {
        // Arrange
        val midi = ScriptedMIDIRepository(
            TestMIDIRepository.connectedState(
                firmwareVersion = "2.0.5",
                sampleCount = 42,
                storageUsedBytes = 12L * 1_048_576,
                storageTotalBytes = 64L * 1_048_576,
            )
        )
        val h = setUpDevice(midi)
        // Assert
        h.robot.assertStatsVisible()
            .assertStatInGrid("42")
            .assertStatInGrid("12/64MB")
            .assertStatInGrid("2.0.5")
    }

    @Test
    fun connected_statsPlaceholdersWhenNotQueried() {
        // Arrange: connected but stats never populated
        val h = setUpDevice(connected())
        // Assert: samples, storage, and firmware readouts all fall back to em dashes
        h.robot.assertStatsVisible()
        composeTestRule.onAllNodes(
            androidx.compose.ui.test.hasText("—") and
                androidx.compose.ui.test.hasAnyAncestor(
                    androidx.compose.ui.test.hasTestTag(com.ep133.sampletool.ui.TestTags.DEVICE_STATS_GRID)
                ),
            useUnmergedTree = true,
        ).assertCountEquals(3)
    }

    @Test
    fun connected_showsChannelSelector() {
        // Arrange
        val h = setUpDevice(connected())
        // Assert
        composeTestRule.onNode(androidx.compose.ui.test.hasText("MIDI CHANNEL")).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("A")).assertExists()
        composeTestRule.onNode(androidx.compose.ui.test.hasText("D")).assertExists()
    }

    @Test
    fun connected_showsScaleModeSection() {
        // Arrange
        val h = setUpDevice(connected())
        // Assert
        composeTestRule.onNode(androidx.compose.ui.test.hasText("SCALE MODE")).assertExists()
    }

    // ── Backup / restore SAF entry points ──

    @Test
    fun backupButton_firesSafRequestWithSuggestedName() {
        // Arrange
        val h = setUpDevice(connected())
        // Act
        h.robot.clickBackup()
        composeTestRule.waitForIdle()
        // Assert
        assertEquals(1, h.backupRequests.size)
        // Full-device backups are .pak archives (project backups are the .tar ones)
        assert(h.backupRequests.first().endsWith(".pak")) {
            "suggested backup name should be a .pak: ${h.backupRequests.first()}"
        }
    }

    @Test
    fun restoreButton_firesSafRequest() {
        // Arrange
        val h = setUpDevice(connected())
        // Act
        h.robot.clickRestore()
        composeTestRule.waitForIdle()
        // Assert
        assertEquals(1, h.restoreRequests.size)
        h.robot.assertNoRestoreConfirm() // confirm dialog only appears after a file is picked
    }

    private fun initialStateFor(state: PermissionState) =
        com.ep133.sampletool.domain.model.DeviceState(connected = false, permissionState = state)
}
