package com.ep133.sampletool

import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.robots.AppRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.support.launchEP133App
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * E2E import flow through the full app shell: navigate to IMPORT, stage a sample, watch
 * the row advance to DONE — and the batch readout + scripted device agree on the outcome.
 */
class ImportFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stagedImport_advancesToDoneAcrossTheShell() {
        // Arrange
        val repo = ScriptedMIDIRepository(TestMIDIRepository.connectedState())
        val ctx = composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act
        val import = app.goToImport()
        ctx.sampleImportViewModel.importStagedBytes("PERC7", ByteArray(64))
        // Assert
        import.assertRowVisible("PERC7")
            .waitForRowState("PERC7", "DONE")
            .assertBatchLabel("ALL DONE")
            .assertPickButton("PICK MORE FILES")
        assertEquals(listOf("PERC7.wav"), repo.putSampleNames) // sanitizeName appends .wav
    }

    @Test
    fun failedImport_surfacesErrorRowInShell() {
        // Arrange
        val repo = ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
            putSampleScript = { false } // device never confirms
        }
        val ctx = composeTestRule.launchEP133App(repo)
        val app = AppRobot(composeTestRule)
        // Act
        val import = app.goToImport()
        ctx.sampleImportViewModel.importStagedBytes("BASS3", ByteArray(64))
        // Assert
        import.assertRowVisible("BASS3")
            .waitForRowState("BASS3", "ERROR")
            .assertBatchLabel("1 ERR · 0 OK")
    }
}
