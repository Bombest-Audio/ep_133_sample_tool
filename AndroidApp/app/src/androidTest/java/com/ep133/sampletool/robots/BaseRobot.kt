package com.ep133.sampletool.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText

/**
 * Base for the robot page objects. Every robot method returns `this` (or the destination
 * robot for navigation) so tests read as a single Act/Assert chain:
 *
 *     PadsRobot(rule)
 *         .selectGroup("B")
 *         .assertPadLabel("B.")
 */
abstract class BaseRobot(protected val rule: ComposeContentTestRule) {

    protected fun tag(testTag: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(testTag)

    protected fun text(text: String): SemanticsNodeInteraction =
        rule.onNodeWithText(text)

    /** Matcher for the stateDescription semantics set by the pads grid. */
    protected fun hasStateDescription(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    /** Block until a node with [testTag] exists — for async flows. 10s default: loaded
     *  emulators (full-suite runs, CI) can stretch a sub-second recomposition past 5s. */
    fun waitForTag(testTag: String, timeoutMillis: Long = 10_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.TestTag, testTag),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Block until a node showing [text] exists (default 10s — see [waitForTag]). */
    fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        rule.waitUntil(timeoutMillis) {
            rule.onAllNodes(
                SemanticsMatcher("has text '$text'") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.any { it.text == text } == true
                },
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
