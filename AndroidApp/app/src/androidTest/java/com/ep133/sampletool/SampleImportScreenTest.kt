package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.robots.ImportRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.`import`.SampleImportScreen
import com.ep133.sampletool.ui.`import`.SampleImportViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Import screen UI: pick action, per-file state badges/progress driven through the
 * importStagedBytes seam with a scripted upload. Name sanitization and the PUT protocol
 * are unit-tested (SampleImportTest, SampleImportViewModelTest).
 */
class SampleImportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness(
        val midi: ScriptedMIDIRepository,
        val viewModel: SampleImportViewModel,
        val robot: ImportRobot,
    ) {
        val pickRequests = mutableListOf<Unit>()
    }

    private fun setUpImport(
        midi: ScriptedMIDIRepository = ScriptedMIDIRepository(TestMIDIRepository.connectedState()),
    ): Harness {
        val viewModel = SampleImportViewModel(midi, SampleImportManager(midi))
        val harness = Harness(midi, viewModel, ImportRobot(composeTestRule))
        viewModel.onRequestPick = { harness.pickRequests += Unit }
        composeTestRule.setContent {
            EP133Theme {
                SampleImportScreen(viewModel = viewModel)
            }
        }
        return harness
    }

    @Test
    fun emptyState_showsPickButtonAndProtocolNote() {
        // Arrange
        val h = setUpImport()
        // Assert
        h.robot.assertPickButton("PICK FILES")
            .assertProtocolNoteVisible()
            .assertBatchLabel("IDLE")
    }

    @Test
    fun pickButton_firesSafRequest() {
        // Arrange
        val h = setUpImport()
        // Act
        h.robot.clickPickFiles()
        composeTestRule.waitForIdle()
        // Assert
        assertEquals(1, h.pickRequests.size)
    }

    @Test
    fun importedFile_progressesToDoneRow() {
        // Arrange
        val h = setUpImport()
        // Act
        h.viewModel.importStagedBytes("KICK2", ByteArray(64))
        // Assert
        h.robot.assertRowVisible("KICK2")
            .waitForRowState("KICK2", "DONE")
            .assertBatchLabel("ALL DONE")
        // sanitizeName appends the .wav extension before upload
        assertEquals(listOf("KICK2.wav"), h.midi.putSampleNames)
    }

    @Test
    fun uploadHeld_showsLoadingState() {
        // Arrange: hold the upload open so LOADING is observable
        val gate = CompletableDeferred<Int?>()
        val h = setUpImport(
            ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
                putSampleScript = { gate.await() }
            }
        )
        // Act
        h.viewModel.importStagedBytes("SNARE2", ByteArray(64))
        // Assert: held in LOADING
        h.robot.assertRowVisible("SNARE2")
            .waitForRowState("SNARE2", "LOADING")
        // Act: release
        gate.complete(1)
        // Assert
        h.robot.waitForRowState("SNARE2", "DONE")
    }

    @Test
    fun failedUpload_showsErrorRowAndBatchCount() {
        // Arrange
        val h = setUpImport(
            ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
                putSampleScript = { throw RuntimeException("port wedged") }
            }
        )
        // Act
        h.viewModel.importStagedBytes("HAT2", ByteArray(64))
        // Assert
        h.robot.assertRowVisible("HAT2")
            .waitForRowState("HAT2", "ERROR")
            .assertBatchLabel("1 ERR · 0 OK")
    }

    @Test
    fun disconnected_importFailsWithNoDeviceError() {
        // Arrange: no output port
        val h = setUpImport(ScriptedMIDIRepository())
        // Act
        h.viewModel.importStagedBytes("CLAP2", ByteArray(64))
        // Assert
        h.robot.assertRowVisible("CLAP2")
            .waitForRowState("CLAP2", "ERROR")
            .assertRowError("CLAP2", "No EP-133 connected")
    }
}
