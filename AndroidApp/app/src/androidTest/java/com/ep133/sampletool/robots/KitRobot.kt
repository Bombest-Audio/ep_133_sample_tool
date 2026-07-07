package com.ep133.sampletool.robots

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ep133.sampletool.ui.TestTags

/**
 * Robot for the SAMPLES (Kit) screen — the loop chopper's chrome plus a smoke check that KIT
 * mode renders the embedded Kit Builder. Kit Builder's own internals (pack browser, pad canvas)
 * are out of scope here; see a future KitBuilderRobot for that.
 */
class KitRobot(rule: ComposeContentTestRule) : BaseRobot(rule) {

    /** The chop-mode slice counter is the screen's stable unique anchor. */
    fun assertModeChipsVisible() = apply {
        text("SLICE COUNT").assertIsDisplayed()
    }

    // Mode toggle — Ep133SectionLabel uppercases its text, so match the rendered case.
    fun selectChopMode() = apply { tag(TestTags.KIT_MODE_CHOP).performClick() }
    fun selectKitMode() = apply { tag(TestTags.KIT_MODE_KIT).performClick() }
    fun assertChopModeVisible() = apply { text("LOOP CHOPPER").assertIsDisplayed() }
    fun assertKitModeVisible() = apply { text("KIT BUILDER").assertIsDisplayed() }

    // Group + choke bar (shared with Pads via TestTags.groupChip)
    fun selectGroup(group: String) = apply { tag(TestTags.groupChip(group)).performClick() }
    fun toggleChoke() = apply { tag(TestTags.GROUP_CHOKE_TOGGLE).performClick() }
    fun assertChokeOn() = apply { text("ON").assertIsDisplayed() }
    fun assertChokeOff() = apply { text("OFF").assertIsDisplayed() }

    // Slice count selector
    fun incrementSliceCount() = apply { tag(TestTags.KIT_SLICE_COUNT_INC).performClick() }
    fun decrementSliceCount() = apply { tag(TestTags.KIT_SLICE_COUNT_DEC).performClick() }
    fun tapSlicePad(rank: Int) = apply { tag(TestTags.kitSlicePad(rank)).performClick() }
    fun assertSliceCount(count: Int) = apply {
        // A dedicated tag avoids ambiguity with a filled pad's rank-overlay digit, which reads
        // identically to the padded readout for any double-digit count (10, 11, 12).
        tag(TestTags.KIT_SLICE_COUNT_READOUT).assert(hasText(count.toString().padStart(2, '0')))
    }

    // Pick / staged loop panel — inside the chop content's scrollable column, so bring it into
    // view first; on a short viewport it can sit below the fold beneath the slice-pad selector.
    // The panel's Column is itself clickable, so its label Text merges into its own semantics
    // node (same reasoning as the push button) — assert directly on the tagged node.
    fun assertPickPanelVisible() = apply {
        tag(TestTags.KIT_PICK_PANEL).performScrollTo().assertIsDisplayed()
    }
    fun assertPickPrompt(text: String) = apply {
        tag(TestTags.KIT_PICK_PANEL).performScrollTo().assert(hasText(text, substring = true))
    }

    // Push button — label is dynamic ("PUSH TO DEVICE · N SLICES → A", "PICK LOOP", etc.). The
    // button's clickable Box merges its label Text into its own semantics node, so assert the
    // substring directly on the tagged node rather than searching for a separate descendant.
    fun clickPush() = apply { tag(TestTags.KIT_PUSH_BUTTON).performClick() }
    fun assertPushLabel(substring: String) = apply {
        tag(TestTags.KIT_PUSH_BUTTON).assert(hasText(substring, substring = true))
    }

    // Chop progress — status text carries dynamic counts ("UPLOADING · 02 / 04",
    // "01 FAILED · 0 OK", "DONE · 04 / 04"), so assert/wait on a substring bucket.
    fun assertProgressPadVisible(rank: Int) = apply {
        tag(TestTags.kitProgressPad(rank)).assertIsDisplayed()
    }
    fun waitForProgressStatus(bucket: String, timeoutMillis: Long = 10_000) = apply {
        rule.waitUntil(timeoutMillis) {
            try {
                rule.onNode(
                    hasText(bucket, substring = true) and hasAnyAncestor(hasTestTag(TestTags.KIT_PROGRESS_STATUS)),
                ).assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    // KitBuilder smoke (KIT mode content) — deliberately minimal, out of scope beyond this.
    fun assertKitBuilderContentVisible() = apply {
        text("KIT CANVAS · TAP A PAD").assertIsDisplayed()
    }
}
