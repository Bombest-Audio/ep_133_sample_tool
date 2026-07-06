package com.ep133.sampletool

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import com.ep133.sampletool.domain.midi.SampleImportManager
import com.ep133.sampletool.robots.KitRobot
import com.ep133.sampletool.support.ScriptedMIDIRepository
import com.ep133.sampletool.ui.kit.GroupSession
import com.ep133.sampletool.ui.kit.KitScreen
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * KitScreen UI: mode toggle (Loop Chopper / Kit Builder), the shared group + choke bar, the
 * slice-count selector, the pick/staged-loop panel, and chop upload progress. Per-slice
 * upload order, byte-budget guards, and storage preflight are unit-tested (KitViewModelTest)
 * via the same SAF-free chopFromPcm/kitFromPcm seams used here — this suite only asserts on
 * what the screen renders. Kit Builder's own pack-browser/pad-canvas internals are out of
 * scope; KIT mode gets a rendering smoke check only.
 */
class KitScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness(val viewModel: KitViewModel, val robot: KitRobot)

    private fun setUpKit(midi: ScriptedMIDIRepository = ScriptedMIDIRepository()): Harness {
        val manager = SampleImportManager(midi)
        val session = GroupSession() // in-memory — shared with the builder VM like production wiring
        val kitViewModel = KitViewModel(midi, manager, session)
        val builderViewModel = KitBuilderViewModel(midi, manager, session)
        composeTestRule.setContent {
            EP133Theme {
                KitScreen(viewModel = kitViewModel, builderViewModel = builderViewModel)
            }
        }
        return Harness(kitViewModel, KitRobot(composeTestRule))
    }

    private fun pcm(frames: Int, channels: Int = 1): ByteArray =
        ByteArray(frames * 2 * channels) { (it % 256).toByte() }

    // ── Mode toggle ────────────────────────────────────────────────────────────

    @Test
    fun defaultState_startsInChopModeWithSliceSelectorVisible() {
        // Arrange
        val h = setUpKit()
        // Assert
        h.robot.assertModeChipsVisible()
            .assertChopModeVisible()
    }

    @Test
    fun modeToggle_switchingToKitShowsKitBuilderChrome() {
        // Arrange
        val h = setUpKit()
        // Act
        h.robot.selectKitMode()
        // Assert
        h.robot.assertKitModeVisible()
            .assertKitBuilderContentVisible()
    }

    @Test
    fun modeToggle_switchingBackToChopRestoresSliceSelector() {
        // Arrange
        val h = setUpKit()
        // Act
        h.robot.selectKitMode()
        h.robot.selectChopMode()
        // Assert
        h.robot.assertChopModeVisible()
            .assertModeChipsVisible()
    }

    // ── Group selection ────────────────────────────────────────────────────────

    @Test
    fun groupSelection_allFourChipsRenderAndAreTappable() {
        // Arrange
        val h = setUpKit()
        // Act + Assert
        h.robot.selectGroup("A")
            .selectGroup("B")
            .selectGroup("C")
            .selectGroup("D")
            .selectGroup("A")
    }

    @Test
    fun groupSelection_perGroupDesignationReflectedInScreen() {
        // Arrange: designate group B as KIT
        val h = setUpKit()
        h.robot.selectGroup("B").selectKitMode()
        // Act: switch to C (still CHOP by default), then back to B
        h.robot.selectGroup("C")
        h.robot.assertChopModeVisible()
        h.robot.selectGroup("B")
        // Assert: B's KIT designation persisted and the screen reflects it
        h.robot.assertKitModeVisible()
            .assertKitBuilderContentVisible()
    }

    // ── Choke toggle ───────────────────────────────────────────────────────────

    @Test
    fun chokeToggle_defaultsOnAndTogglesOff() {
        // Arrange
        val h = setUpKit()
        // Assert
        h.robot.assertChokeOn()
        // Act
        h.robot.toggleChoke()
        // Assert
        h.robot.assertChokeOff()
    }

    // ── Slice count selector ───────────────────────────────────────────────────

    @Test
    fun sliceCount_defaultsToEight() {
        // Arrange
        val h = setUpKit()
        // Assert
        h.robot.assertSliceCount(8)
    }

    @Test
    fun sliceCount_incrementAndDecrementAdjustCount() {
        // Arrange
        val h = setUpKit()
        // Act + Assert
        h.robot.incrementSliceCount().assertSliceCount(9)
        h.robot.decrementSliceCount().decrementSliceCount().assertSliceCount(7)
    }

    @Test
    fun sliceCount_tappingAPadSetsCountToItsFillRank() {
        // Arrange
        val h = setUpKit()
        // Act
        h.robot.tapSlicePad(4)
        // Assert
        h.robot.assertSliceCount(4)
    }

    @Test
    fun sliceCount_decrementClampsAtOne() {
        // Arrange
        val h = setUpKit()
        // Act: from 8, decrement 8 times — should clamp at 1, not go negative
        repeat(8) { h.robot.decrementSliceCount() }
        // Assert
        h.robot.assertSliceCount(1)
    }

    @Test
    fun sliceCount_incrementClampsAtTwelve() {
        // Arrange
        val h = setUpKit()
        // Act: from 8, increment 6 times — should clamp at MAX_SLICES (12)
        repeat(6) { h.robot.incrementSliceCount() }
        // Assert
        h.robot.assertSliceCount(12)
    }

    // ── Staged loop display ────────────────────────────────────────────────────

    @Test
    fun pickPanel_idleShowsPickPrompt() {
        // Arrange
        val h = setUpKit()
        // Assert
        h.robot.assertPickPanelVisible()
            .assertPickPrompt("pick loop to chop")
    }

    @Test
    fun stagedLoop_afterPick_showsFileNameAndPushLabel() {
        // Arrange
        val h = setUpKit()
        // Act: bypass the real SAF picker — onLoopFilePicked derives the name from the URI
        h.viewModel.onLoopFilePicked(Uri.parse("content://x/LOOP1.wav"))
        // Assert
        h.robot.assertPickPrompt("LOOP1.wav")
            .assertPushLabel("PUSH TO DEVICE")
    }

    // ── Chop progress rendering (SAF-free seam) ────────────────────────────────

    @Test
    fun chopProgress_uploadHeld_showsUploadingStatus() {
        // Arrange: hold the upload open so UPLOADING is observable
        val gate = CompletableDeferred<Int?>()
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
            putSampleScript = { gate.await() }
        }
        val h = setUpKit(midi)
        // Act
        h.viewModel.chopFromPcm("loop.wav", pcm(800))
        // Assert: status bucket, plus the first slot's progress pad rendered on the grid
        h.robot.waitForProgressStatus("UPLOADING")
            .assertProgressPadVisible(1)
        // Cleanup: release the gate so the coroutine doesn't leak past the test
        gate.complete(1)
    }

    @Test
    fun chopProgress_allSlicesSucceed_showsDoneStatus() {
        // Arrange: connected repo, default scripts succeed for both put and assign
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState())
        val h = setUpKit(midi)
        // Act
        h.viewModel.chopFromPcm("loop.wav", pcm(800))
        // Assert
        h.robot.waitForProgressStatus("DONE")
    }

    @Test
    fun chopProgress_assignRejected_showsFailedStatus() {
        // Arrange: ScriptedMIDIRepository overrides assignSampleToPad outright (no super call),
        // so connection state alone doesn't reject it — script the rejection explicitly.
        val midi = ScriptedMIDIRepository(TestMIDIRepository.connectedState()).apply {
            assignSampleScript = { _, _, _, _, _, _ -> false }
        }
        val h = setUpKit(midi)
        // Act
        h.viewModel.chopFromPcm("loop.wav", pcm(800))
        // Assert
        h.robot.waitForProgressStatus("FAILED")
    }

    // ── KIT mode smoke (KitBuilderScreen embedded) ─────────────────────────────

    @Test
    fun kitMode_rendersKitBuilderScreenContent() {
        // Arrange
        val h = setUpKit()
        // Act
        h.robot.selectKitMode()
        // Assert
        h.robot.assertKitBuilderContentVisible()
            .assertPushLabel("PICK PACK FOLDER")
    }
}
