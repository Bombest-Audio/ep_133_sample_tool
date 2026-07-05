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
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.ui.TestTags

/** Robot for the Projects screen: device slots, backup library, restore gate. */
class ProjectsRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    fun assertOfflinePanel() = apply {
        text("Connect your EP-133 via USB to browse and back up projects.").assertIsDisplayed()
        text("OFFLINE").assertIsDisplayed()
    }

    fun assertSlotCount(count: Int) = apply {
        text("$count SLOTS").assertIsDisplayed()
    }

    fun assertSlotVisible(nodeId: Int, name: String) = apply {
        waitForTag(TestTags.projectSlot(nodeId))
        tag(TestTags.projectSlot(nodeId)).assertIsDisplayed()
        text(name).assertIsDisplayed()
    }

    fun assertEmptySlots() = apply {
        waitForText("No projects found.")
    }

    fun clickBackupOnSlot(nodeId: Int) = apply {
        rule.onNode(
            hasText("BACKUP") and hasClickAction() and
                hasAnyAncestor(hasTestTag(TestTags.projectSlot(nodeId))),
            useUnmergedTree = true,
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

    fun assertRestoreGated(backupName: String) = apply {
        actionIn(backupName, "RESTORE · SOON").assertIsNotEnabled()
    }

    fun assertRestoreConfirmVisible() = apply {
        tag(TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG).assertIsDisplayed()
    }

    private fun actionIn(backupName: String, label: String) = rule.onNode(
        hasText(label) and hasClickAction() and
            hasAnyAncestor(hasTestTag(TestTags.backupCard(backupName))),
        useUnmergedTree = true,
    )
}
