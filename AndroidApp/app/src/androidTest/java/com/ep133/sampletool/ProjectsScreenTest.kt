package com.ep133.sampletool

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.ep133.sampletool.domain.midi.MIDIRepository
import com.ep133.sampletool.domain.midi.ProjectBackupManager
import com.ep133.sampletool.robots.ProjectsRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.projects.ProjectsScreen
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Projects screen UI: slot list per connection state, the backup library, and the
 * restore gate. Tar/rebuild logic is unit-tested (BackupManagerRebuildTest etc.);
 * the library here is seeded with real files in the app's backups dir.
 */
class ProjectsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun backupsDir(): File =
        (context.getExternalFilesDir("backups") ?: context.filesDir.resolve("backups"))
            .also { it.mkdirs() }

    @Before
    fun clearBackups() {
        backupsDir().listFiles()?.forEach { it.delete() }
    }

    @After
    fun cleanUpBackups() {
        backupsDir().listFiles()?.forEach { it.delete() }
    }

    private class Harness(val viewModel: ProjectsViewModel, val robot: ProjectsRobot)

    private fun setUpProjects(midi: ScriptedMIDIRepository = ScriptedMIDIRepository()): Harness {
        val viewModel = ProjectsViewModel(midi, ProjectBackupManager(midi))
        composeTestRule.setContent {
            EP133Theme {
                ProjectsScreen(viewModel = viewModel)
            }
        }
        return Harness(viewModel, ProjectsRobot(composeTestRule))
    }

    private fun slot(nodeId: Int, name: String, active: Boolean = false) =
        MIDIRepository.ProjectSlot(nodeId = nodeId, name = name, sizeBytes = 4096, isActive = active)

    @Test
    fun disconnected_showsOfflinePanel() {
        // Arrange
        val h = setUpProjects()
        // Assert
        h.robot.assertOfflinePanel()
    }

    @Test
    fun connected_rendersScriptedSlots() {
        // Arrange
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
            scriptedSlots = listOf(slot(11, "01", active = true), slot(12, "02"))
        }
        val h = setUpProjects(midi)
        // Assert
        h.robot.assertSlotVisible(11, "01")
            .assertSlotVisible(12, "02")
            .assertSlotCount(2)
    }

    @Test
    fun connected_activeSlotShowsActiveBadge() {
        // Arrange
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
            scriptedSlots = listOf(slot(11, "01", active = true))
        }
        val h = setUpProjects(midi)
        // Assert
        h.robot.assertSlotVisible(11, "01")
        composeTestRule.onNode(androidx.compose.ui.test.hasText("ACTIVE")).assertExists()
    }

    @Test
    fun connected_noSlots_showsEmptyNote() {
        // Arrange
        val h = setUpProjects(ScriptedMIDIRepository(TestMIDIRepository.connectedState()))
        // Assert
        h.robot.assertEmptySlots()
    }

    @Test
    fun backupLibrary_emptyState() {
        // Arrange
        val h = setUpProjects()
        // Assert
        h.robot.assertEmptyBackupLibrary()
    }

    @Test
    fun backupLibrary_rendersSeededBackupWithEnabledRestore() {
        // Arrange: a real .tar in the app's backups dir
        File(backupsDir(), "P01.tar").writeBytes(ByteArray(128))
        val h = setUpProjects()
        // Assert: card renders, share is live, restore is enabled (RESTORE_ENABLED=true, 999.10)
        h.robot.assertBackupCardVisible("P01.tar")
            .assertShareEnabled("P01.tar")
            .assertRestoreEnabled("P01.tar")
    }

    @Test
    fun restoreConfirmDialog_appearsViaRequestAndCancels() {
        // Arrange
        val h = setUpProjects()
        // Act: drive the VM seam the RESTORE button uses
        h.viewModel.requestRestore(File(backupsDir(), "P01.tar"))
        // Assert
        h.robot.assertRestoreConfirmVisible()
        // Act: cancel
        composeTestRule.onNode(androidx.compose.ui.test.hasText("Cancel")).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(
            androidx.compose.ui.test.hasTestTag(
                com.ep133.sampletool.ui.TestTags.PROJECTS_RESTORE_CONFIRM_DIALOG
            )
        ).assertDoesNotExist()
    }
}
