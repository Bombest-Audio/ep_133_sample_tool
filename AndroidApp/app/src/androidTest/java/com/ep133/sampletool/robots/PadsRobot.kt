package com.ep133.sampletool.robots

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ep133.sampletool.ui.TestTags

/** Robot for the Pads screen: group chips, the 12-pad grid, and pad visual states. */
class PadsRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun selectGroup(group: String) = apply {
        tag(TestTags.groupChip(group)).performClick()
    }

    /**
     * Touch-down on a pad without releasing. The grid handles touch via a parent
     * pointerInteropFilter, so this injects at the pad cell's center and lets the
     * parent's coordinate math resolve the index — the same path a finger takes.
     */
    fun pressPad(index: Int) = apply {
        tag(TestTags.pad(index)).performTouchInput { down(center) }
    }

    fun releasePad(index: Int) = apply {
        tag(TestTags.pad(index)).performTouchInput { up() }
    }

    fun tapPad(index: Int) = apply {
        tag(TestTags.pad(index)).performTouchInput {
            down(center)
            up()
        }
    }

    /** Assert a pad's visual state via its stateDescription: "pressed" / "in_scale" / "idle". */
    fun assertPadState(index: Int, state: String) = apply {
        tag(TestTags.pad(index)).assert(hasStateDescription(state))
    }

    fun assertPadLabel(label: String) = apply {
        text(label).assertIsDisplayed()
    }

    fun assertGridVisible() = apply {
        tag(TestTags.PADS_GRID).assertIsDisplayed()
    }
}
