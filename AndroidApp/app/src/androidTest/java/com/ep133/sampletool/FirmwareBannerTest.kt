package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.domain.firmware.FirmwareVersion
import com.ep133.sampletool.robots.DeviceRobot
import com.ep133.sampletool.support.FakeFirmwareCatalog
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Firmware update banner rendering per FirmwareUpdateState. Version comparison and
 * catalog parsing are unit-tested (DeviceViewModelFirmwareTest, TeFirmwareCatalogParserTest);
 * this covers what the user actually sees on the Device screen.
 */
class FirmwareBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness(val viewModel: DeviceViewModel, val robot: DeviceRobot) {
        val updaterOpens = mutableListOf<Unit>()
    }

    private fun setUpConnected(
        deviceFirmware: String,
        catalog: FakeFirmwareCatalog,
    ): Harness {
        val midi = ScriptedMIDIRepository(
            TestMIDIRepository.connectedState(firmwareVersion = deviceFirmware),
        )
        val viewModel = DeviceViewModel(midi, catalog)
        val harness = Harness(viewModel, DeviceRobot(composeTestRule))
        viewModel.onOpenFirmwareUpdater = { harness.updaterOpens += Unit }
        composeTestRule.setContent {
            EP133Theme {
                DeviceScreen(viewModel = viewModel)
            }
        }
        return harness
    }

    @Test
    fun olderFirmware_showsUpdateBannerAndOpensUpdater() {
        // Arrange
        val h = setUpConnected("2.0.5", FakeFirmwareCatalog(FirmwareVersion.parse("2.5")))
        // Assert: banner names the newer version
        h.robot.assertFirmwareBanner("2.5")
        // Act
        h.robot.clickOpenUpdater()
        composeTestRule.waitForIdle()
        // Assert
        assertEquals(1, h.updaterOpens.size)
    }

    @Test
    fun currentFirmware_showsNoBanner() {
        // Arrange
        val h = setUpConnected("2.5", FakeFirmwareCatalog(FirmwareVersion.parse("2.5")))
        composeTestRule.waitForIdle()
        // Assert
        h.robot.assertNoFirmwareBanner()
    }

    @Test
    fun unparseableFirmware_showsNoBanner() {
        // Arrange
        val h = setUpConnected("garbage", FakeFirmwareCatalog(FirmwareVersion.parse("2.5")))
        composeTestRule.waitForIdle()
        // Assert: Unknown state renders nothing
        h.robot.assertNoFirmwareBanner()
    }

    @Test
    fun catalogUnavailable_showsNoBanner() {
        // Arrange
        val h = setUpConnected("2.0.5", FakeFirmwareCatalog(result = null))
        composeTestRule.waitForIdle()
        // Assert
        h.robot.assertNoFirmwareBanner()
    }

    @Test
    fun whileChecking_showsSpinnerThenBanner() {
        // Arrange: gate the catalog so the Checking state is observable
        val catalog = FakeFirmwareCatalog(FirmwareVersion.parse("2.5"))
        catalog.gate = CompletableDeferred()
        val h = setUpConnected("2.0.5", catalog)
        // Assert: held in Checking
        composeTestRule.onNode(androidx.compose.ui.test.hasText("CHECKING FIRMWARE…")).assertExists()
        // Act: release the catalog
        catalog.gate!!.complete(Unit)
        // Assert
        h.robot.assertFirmwareBanner("2.5")
    }
}
