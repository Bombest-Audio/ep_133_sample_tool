package com.ep133.sampletool.robots

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.ep133.sampletool.ui.TestTags

/**
 * Robot for the Kit Builder (embedded in KitScreen's KIT mode): pack browser, category tabs,
 * sample list, pad canvas, clear-pad confirmation, and the load banner. Most containers here
 * are clickable Rows/Columns, which merge their label Text children into their own semantics
 * node — so assertions target the tagged node directly (`.assert(hasText(...))`) rather than
 * searching for a separate descendant.
 */
class KitBuilderRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun assertEmptyState() = apply {
        text("pick a sample-pack folder to start").assertIsDisplayed()
    }

    fun assertPackName(name: String) = apply {
        text(name).assertIsDisplayed()
    }

    // Category tabs
    fun selectCategory(id: String) = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.kbCategoryTab(id)))
        tag(TestTags.kbCategoryTab(id)).performClick()
    }

    // Sample list — tap the row to assign, tap the circle to audition.
    fun tapSample(name: String) = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.kbSampleRow(name)))
        tag(TestTags.kbSampleRow(name)).performClick()
    }

    fun tapAudition(name: String) = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.kbSampleRow(name)))
        tag(TestTags.kbAuditionButton(name)).performClick()
    }

    fun assertSampleRowVisible(name: String) = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.kbSampleRow(name)))
        tag(TestTags.kbSampleRow(name)).assertIsDisplayed()
    }
    fun assertSampleRowNotVisible(name: String) = apply {
        tag(TestTags.kbSampleRow(name)).assertDoesNotExist()
    }

    fun assertSampleAssignedToPad(name: String, padLabel: String) = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.kbSampleRow(name)))
        tag(TestTags.kbSampleRow(name)).assert(hasText("◉ $padLabel", substring = true))
    }

    // Pad canvas
    fun selectPad(index: Int) = apply { tag(TestTags.kbPadCell(index)).performClick() }
    fun assertPadEmpty(index: Int) = apply {
        tag(TestTags.kbPadCell(index)).assert(hasText("empty", substring = true))
    }
    fun assertPadAssigned(index: Int, sampleName: String) = apply {
        tag(TestTags.kbPadCell(index)).assert(hasText(sampleName, substring = true))
    }
    fun assertPadShowsAssignPrompt(index: Int) = apply {
        tag(TestTags.kbPadCell(index)).assert(hasText("assign →", substring = true))
    }

    // Clear pad
    fun clickClearPad() = apply {
        rule.onNodeWithTag(TestTags.KB_SAMPLE_LIST)
            .performScrollToNode(hasTestTag(TestTags.KB_CLEAR_PAD_BUTTON))
        tag(TestTags.KB_CLEAR_PAD_BUTTON).performClick()
    }
    fun assertClearConfirmVisible() = apply {
        tag(TestTags.KB_CLEAR_CONFIRM_DIALOG).assertIsDisplayed()
    }
    fun confirmClear() = apply {
        rule.onNode(hasText("Clear") and hasAnyAncestor(hasTestTag(TestTags.KB_CLEAR_CONFIRM_DIALOG)))
            .performClick()
    }
    fun cancelClear() = apply {
        rule.onNode(hasText("Cancel") and hasAnyAncestor(hasTestTag(TestTags.KB_CLEAR_CONFIRM_DIALOG)))
            .performClick()
    }
    fun assertClearConfirmDismissed() = apply {
        tag(TestTags.KB_CLEAR_CONFIRM_DIALOG).assertDoesNotExist()
    }

    // Footer — fill meter + pack switcher
    fun assertAssignedCount(count: Int) = apply {
        tag(TestTags.KB_ASSIGNED_COUNT).assert(hasText(count.toString().padStart(2, '0')))
    }
    fun clickSwitchPack() = apply { tag(TestTags.KB_SWITCH_PACK_BUTTON).performClick() }

    // Load banner — async (upload runs in a coroutine), so wait rather than assert immediately.
    fun waitForLoadBanner(substring: String, timeoutMillis: Long = 10_000) = apply {
        rule.waitUntil(timeoutMillis) {
            try {
                rule.onNode(
                    hasText(substring, substring = true) and hasAnyAncestor(hasTestTag(TestTags.KB_LOAD_BANNER)),
                ).assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }
}
