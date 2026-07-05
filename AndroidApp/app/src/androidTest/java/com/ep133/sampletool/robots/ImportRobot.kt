package com.ep133.sampletool.robots

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.ui.TestTags

/** Robot for the Import screen: pick action and per-file staged rows. */
class ImportRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun clickPickFiles() = apply {
        tag(TestTags.IMPORT_PICK_BUTTON).performClick()
    }

    fun assertPickButton(label: String) = apply {
        text(label).assertIsDisplayed()
    }

    fun assertRowVisible(name: String) = apply {
        waitForTag(TestTags.importRow(name))
        tag(TestTags.importRow(name)).assertIsDisplayed()
    }

    /** Assert a staged row shows the given state label (PENDING/CONVERTING/LOADING/DONE/ERROR). */
    fun assertRowState(name: String, stateLabel: String) = apply {
        tag(TestTags.importRow(name)).assert(hasAnyDescendant(hasText(stateLabel)))
    }

    /** Wait until a staged row reaches the given state label. */
    fun waitForRowState(name: String, stateLabel: String, timeoutMillis: Long = 10_000) = apply {
        rule.waitUntil(timeoutMillis) {
            try {
                tag(TestTags.importRow(name)).assert(hasAnyDescendant(hasText(stateLabel)))
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    /** Assert a row's foot label shows this error message (unique within the row). */
    fun assertRowError(name: String, message: String) = apply {
        tag(TestTags.importRow(name)).assert(hasAnyDescendant(hasText(message)))
    }

    fun assertBatchLabel(label: String) = apply {
        text(label).assertIsDisplayed()
    }

    fun assertProtocolNoteVisible() = apply {
        text("i").assertIsDisplayed()
    }
}
