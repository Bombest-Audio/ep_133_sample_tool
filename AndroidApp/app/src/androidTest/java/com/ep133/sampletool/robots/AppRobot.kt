package com.ep133.sampletool.robots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.ui.TestTags

/** Robot for the app shell: bottom-tab navigation, header title, connection indicator. */
class AppRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun goToPads(): PadsRobot {
        tag(TestTags.NAV_PADS).performClick()
        return PadsRobot(rule)
    }

    fun goToProjects(): ProjectsRobot {
        tag(TestTags.NAV_PROJECTS).performClick()
        return ProjectsRobot(rule)
    }

    fun goToDevice(): DeviceRobot {
        tag(TestTags.NAV_DEVICE).performClick()
        return DeviceRobot(rule)
    }

    fun goToImport(): ImportRobot {
        tag(TestTags.NAV_IMPORT).performClick()
        return ImportRobot(rule)
    }

    fun assertTitle(title: String) = apply {
        tag(TestTags.HEADER_TITLE).assertTextEquals(title)
    }

    fun assertConnectionLabel(label: String) = apply {
        tag(TestTags.HEADER_CONNECTION).assert(hasAnyDescendant(hasText(label)))
    }

    fun toggleSkuBadge() = apply {
        tag(TestTags.HEADER_SKU_BADGE).performClick()
    }

    fun assertSkuBadge(label: String) = apply {
        tag(TestTags.HEADER_SKU_BADGE).assertTextEquals(label)
    }

    fun cycleTheme() = apply {
        tag(TestTags.HEADER_THEME_TOGGLE).performClick()
    }

    fun assertHeaderVisible() = apply {
        tag(TestTags.HEADER_TITLE).assertIsDisplayed()
    }
}
