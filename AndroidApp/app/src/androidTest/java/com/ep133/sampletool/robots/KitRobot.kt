package com.ep133.sampletool.robots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Robot for the SAMPLES (Kit) screen. Deliberately minimal for now — the chop/kit-builder
 * flows are new and untested (the coverage report flags them); grow this alongside those tests.
 */
class KitRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    /** The chop-mode slice counter is the screen's stable unique anchor. */
    fun assertModeChipsVisible() = apply {
        text("SLICE COUNT").assertIsDisplayed()
    }
}
