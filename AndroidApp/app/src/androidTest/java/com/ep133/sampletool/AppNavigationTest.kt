package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.robots.AppRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.support.launchEP133App
import org.junit.Rule
import org.junit.Test

/**
 * App shell: bottom-tab navigation, header title/connection indicator, SKU + theme toggles,
 * and cross-tab state retention. Replaces the pre-redesign NavigationTest (5-tab shell).
 */
class AppNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun defaultTab_isPadsWithTitle() {
        // Arrange
        composeTestRule.launchEP133App()
        // Assert
        AppRobot(composeTestRule)
            .assertTitle("PADS")
            .goToPads() // no-op navigation, but proves the grid renders
            .assertGridVisible()
    }

    @Test
    fun tabSwitch_updatesHeaderTitlePerScreen() {
        // Arrange
        composeTestRule.launchEP133App()
        val app = AppRobot(composeTestRule)
        // Act + Assert
        app.goToProjects()
        app.assertTitle("PROJ")
        app.goToDevice()
        app.assertTitle("DEVICE")
        app.goToImport()
        app.assertTitle("IMPORT")
        app.goToPads()
        app.assertTitle("PADS")
    }

    @Test
    fun tabSwitch_showsEachScreenBody() {
        // Arrange
        composeTestRule.launchEP133App()
        val app = AppRobot(composeTestRule)
        // Act + Assert
        app.goToProjects().assertOfflinePanel()
        app.goToDevice().assertOfflineState()
        app.goToImport().assertPickButton("PICK FILES")
        app.goToPads().assertPadLabel("A.")
    }

    @Test
    fun groupSelection_survivesTabRoundTrip() {
        // Arrange
        composeTestRule.launchEP133App()
        val app = AppRobot(composeTestRule)
        // Act: select group B, bounce through Device, come back
        app.goToPads().selectGroup("B").assertPadLabel("B.")
        app.goToDevice()
        // Assert
        app.goToPads().assertPadLabel("B.")
    }

    @Test
    fun connectionIndicator_reflectsDeviceState() {
        // Arrange
        val repo = ScriptedMIDIRepository()
        composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        app.assertConnectionLabel("NO DEVICE")
        // Act: device connects mid-session
        repo.updateDeviceState { it.copy(connected = true) }
        // Assert
        app.assertConnectionLabel("CONNECTED")
    }

    @Test
    fun skuBadge_togglesBetweenModels() {
        // Arrange
        composeTestRule.launchEP133App()
        val app = AppRobot(composeTestRule)
        app.assertSkuBadge("EP·133")
        // Act + Assert
        app.toggleSkuBadge().assertSkuBadge("EP·1320")
        app.toggleSkuBadge().assertSkuBadge("EP·133")
    }

    @Test
    fun themeToggle_cyclesWithoutBreakingLayout() {
        // Arrange
        composeTestRule.launchEP133App()
        val app = AppRobot(composeTestRule)
        // Act: cycle system → light → dark → system
        app.cycleTheme().assertHeaderVisible()
        app.cycleTheme().assertHeaderVisible()
        app.cycleTheme().assertHeaderVisible()
    }
}
