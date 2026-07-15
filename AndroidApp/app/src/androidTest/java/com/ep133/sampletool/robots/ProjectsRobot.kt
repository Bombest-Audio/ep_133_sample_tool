package com.ep133.sampletool.robots

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import com.ep133.sampletool.ui.TestTags

/** Robot for the Projects screen: device slots, backup library, restore gate. */
class ProjectsRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun assertOfflinePanel() = apply {
        text("Connect your EP-133 via USB to browse and back up projects.").assertIsDisplayed()
        text("OFFLINE").assertIsDisplayed()
    }

    fun assertSlotCount(count: Int) = apply {
        // The count header is the list's first item — scroll back up in case a
        // prior assertion scrolled to a slot below the fold.
        rule.onNodeWithTag(TestTags.PROJECTS_SLOT_LIST).performScrollToIndex(0)
        text("$count SLOTS").assertIsDisplayed()
    }

    /**
     * Scrolls the (lazy) slot list to the slot, then asserts it. Off-screen LazyColumn items
     * are not composed at all, so a bare tag wait would time out for slots below the fold.
     */
    fun assertSlotVisible(nodeId: Int, name: String) = apply {
        val slotTag = TestTags.projectSlot(nodeId)
        rule.waitUntil(10_000) {
            try {
                rule.onNodeWithTag(TestTags.PROJECTS_SLOT_LIST)
                    .performScrollToNode(hasTestTag(slotTag))
                true
            } catch (e: Throwable) {
                false // list empty or still loading — retry
            }
        }
        tag(slotTag).assertIsDisplayed()
        // Cards title untitled slots as "PROJECT <NN>" (custom names replace it).
        rule.onNode(
            hasText("PROJECT $name") and hasAnyAncestor(hasTestTag(slotTag)),
        ).assertIsDisplayed()
    }

    fun assertEmptySlots() = apply {
        waitForText("No projects found.")
    }

    fun clickBackupOnSlot(nodeId: Int) = apply {
        // Merged tree: the clickable row merges its "BACKUP" label into one node.
        rule.onNode(
            hasText("BACKUP") and hasClickAction() and
                hasAnyAncestor(hasTestTag(TestTags.projectSlot(nodeId))),
        ).performClick()
    }

    fun assertBackupCardVisible(name: String) = apply {
        tag(TestTags.backupCard(name)).assertIsDisplayed()
    }

    fun assertEmptyBackupLibrary() = apply {
        text("No backups yet. Back up a slot to add one.").assertIsDisplayed()
    }

    fun assertShareEnabled(backupName: String) = apply {
        actionIn(backupName, "SHARE").assertIsEnabled()
    }

    fun assertRestoreEnabled(backupName: String) = apply {
        actionIn(backupName, "RESTORE").assertIsEnabled()
    }

    fun assertRestoreConfirmVisible() = apply {
        tag(TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG).assertIsDisplayed()
    }

    private fun actionIn(backupName: String, label: String) = rule.onNode(
        hasText(label) and hasClickAction() and
            hasAnyAncestor(hasTestTag(TestTags.backupCard(backupName))),
    )
}
