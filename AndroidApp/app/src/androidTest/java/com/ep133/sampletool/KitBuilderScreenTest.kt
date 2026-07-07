package com.ep133.sampletool

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.domain.pack.KitCategory
import com.ep133.sampletool.domain.pack.KitPack
import com.ep133.sampletool.domain.pack.KitSample
import com.ep133.sampletool.robots.KitBuilderRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.kitbuilder.KitBuilderScreen
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import org.junit.Rule
import org.junit.Test

/**
 * Kit Builder UI: pack browser (category tabs + sample list), tap-to-assign onto the pad
 * canvas with auto-advance, the clear-pad confirmation dialog, and the load banner. Pack
 * loading and uploads normally require real SAF I/O and real audio decode, which an
 * instrumented test can't fake — so packs are injected via the loadPack(KitPack) testability
 * seam (bypasses SAF, mirrors KitViewModel's chopFromPcm/kitFromPcm), and upload/audition are
 * only exercised on their natural failure path (a synthetic content:// URI can't be decoded or
 * played) — matching the "no scripting needed, the fake genuinely can't do it" pattern used
 * for KitScreenTest's disconnected-repo cases. Upload *success* and the real device pad
 * read-back are out of scope here; they belong to a real-asset or protocol-level (simulator)
 * test.
 */
class KitBuilderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Harness(val viewModel: KitBuilderViewModel, val robot: KitBuilderRobot)

    private fun setUpBuilder(midi: ScriptedMIDIRepository = ScriptedMIDIRepository()): Harness {
        val manager = SampleImportManager(midi)
        val session = GroupSession() // in-memory — mirrors production Kit/KitBuilder wiring
        val viewModel = KitBuilderViewModel(midi, manager, session)
        composeTestRule.setContent {
            EP133Theme {
                KitBuilderScreen(viewModel = viewModel)
            }
        }
        return Harness(viewModel, KitBuilderRobot(composeTestRule))
    }

    private fun sample(name: String, category: String) =
        KitSample(name = name, uri = Uri.parse("content://x/$name.wav"), category = category, meta = "12 KB")

    private fun testPack() = KitPack(
        name = "Drum Breaks",
        categories = listOf(
            KitCategory("KICKS", listOf(sample("Kick 1", "KICKS"), sample("Kick 2", "KICKS"))),
            KitCategory("SNARES", listOf(sample("Snare 1", "SNARES"))),
        ),
    )

    @Test
    fun emptyState_showsPickPrompt() {
        // Arrange
        val h = setUpBuilder()
        // Assert
        h.robot.assertEmptyState()
    }

    @Test
    fun packLoaded_showsNameAndFirstCategorySamples() {
        // Arrange
        val h = setUpBuilder()
        // Act
        h.viewModel.loadPack(testPack())
        // Assert: first category (KICKS) is selected by default
        h.robot.assertPackName("Drum Breaks")
            .assertSampleRowVisible("Kick 1")
            .assertSampleRowVisible("Kick 2")
    }

    @Test
    fun categorySwitch_showsDifferentCategorysSamples() {
        // Arrange
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        // Act
        h.robot.selectCategory("SNARES")
        // Assert
        h.robot.assertSampleRowVisible("Snare 1")
            .assertSampleRowNotVisible("Kick 1")
    }

    @Test
    fun tapSample_assignsToSelectedPadAndAutoAdvances() {
        // Arrange: default selected pad is index 9 ("."), next fill-order pad is index 10 ("0")
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        // Act
        h.robot.tapSample("Kick 1")
        // Assert
        h.robot.assertPadAssigned(9, "Kick 1")
            .assertPadShowsAssignPrompt(10)
            .assertSampleAssignedToPad("Kick 1", ".")
    }

    @Test
    fun clearPad_cancelDismissesWithoutClearing() {
        // Arrange
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        h.robot.tapSample("Kick 1")
        // Act
        h.robot.clickClearPad()
        h.robot.assertClearConfirmVisible()
        h.robot.cancelClear()
        // Assert
        h.robot.assertClearConfirmDismissed()
            .assertPadAssigned(9, "Kick 1")
    }

    @Test
    fun clearPad_confirmOnDisconnectedShowsFailureSnackbar() {
        // Arrange: default disconnected repo — clearPad returns false with no scripting needed
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        // Act
        h.robot.clickClearPad()
        h.robot.assertClearConfirmVisible()
        h.robot.confirmClear()
        // Assert
        h.robot.assertClearConfirmDismissed()
        h.robot.waitForText("Couldn't clear pad . — is the EP-133 connected?")
    }

    @Test
    fun audition_bogusUriShowsCantPlaySnackbar() {
        // Arrange: the synthetic content:// URI can't actually be opened/decoded
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        // Act
        h.robot.tapAudition("Kick 1")
        // Assert
        h.robot.waitForText("Can't play Kick 1")
    }

    @Test
    fun uploadFailure_showsFailedBanner() {
        // Arrange: assign one pad, then push — manager.convert can't decode the synthetic URI
        val h = setUpBuilder()
        h.viewModel.loadPack(testPack())
        h.robot.tapSample("Kick 1")
        // Act
        h.viewModel.onLoadKit(context)
        // Assert
        h.robot.waitForLoadBanner("FAILED")
    }
}
