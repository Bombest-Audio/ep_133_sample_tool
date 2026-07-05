package com.ep133.sampletool.robots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.ui.TestTags

/** Robot for the Device screen: connection states, stats, firmware banner, scale mode, backup. */
class DeviceRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    // ── Connection / permission states ──

    fun assertOfflineState() = apply {
        text("no device").assertIsDisplayed()
    }

    fun assertPermissionAction(label: String) = apply {
        tag(TestTags.DEVICE_PERMISSION_ACTION).assertIsDisplayed()
        text(label).assertIsDisplayed()
    }

    fun clickPermissionAction() = apply {
        tag(TestTags.DEVICE_PERMISSION_ACTION).performClick()
    }

    // ── Connected stats ──

    fun assertStatsVisible() = apply {
        tag(TestTags.DEVICE_STATS_GRID).assertIsDisplayed()
    }

    fun assertStatValue(value: String) = apply {
        text(value).assertIsDisplayed()
    }

    // ── Firmware banner ──

    fun assertFirmwareBanner(latest: String) = apply {
        waitForTag(TestTags.DEVICE_FIRMWARE_BANNER)
        tag(TestTags.DEVICE_FIRMWARE_BANNER).assertIsDisplayed()
        text("Firmware $latest available").assertIsDisplayed()
    }

    fun assertNoFirmwareBanner() = apply {
        tag(TestTags.DEVICE_FIRMWARE_BANNER).assertDoesNotExist()
    }

    fun clickOpenUpdater() = apply {
        text("Open updater").performClick()
    }

    // ── Scale mode ──

    fun selectScale(name: String) = apply {
        tag(TestTags.DEVICE_SCALE_DROPDOWN).performClick()
        rule.onNode(hasText(name) and hasAnyAncestor(isPopup())).performClick()
    }

    fun selectRootNote(note: String) = apply {
        tag(TestTags.DEVICE_ROOT_DROPDOWN).performClick()
        rule.onNode(hasText(note) and hasAnyAncestor(isPopup())).performClick()
    }

    // ── Channel chips ──

    fun selectChannel(group: String) = apply {
        text(group).performClick()
    }

    // ── Backup / restore ──

    fun clickBackup() = apply {
        text("Backup").performClick()
    }

    fun clickRestore() = apply {
        text("Restore").performClick()
    }

    fun assertRestoreConfirmVisible() = apply {
        tag(TestTags.DEVICE_RESTORE_CONFIRM_DIALOG).assertIsDisplayed()
    }

    fun cancelRestoreConfirm() = apply {
        text("Cancel").performClick()
    }

    fun assertNoRestoreConfirm() = apply {
        tag(TestTags.DEVICE_RESTORE_CONFIRM_DIALOG).assertDoesNotExist()
    }
}
