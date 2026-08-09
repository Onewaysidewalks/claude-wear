package dev.claudewear.wear.ui

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToIndex

/**
 * Scroll a [Gallery.Pose]'s list until [text] is on screen, an item at a time. Null is
 * "the top already says it" and does nothing.
 *
 * Shared by `ScreenshotTest` and `ScreenTourTest` so the Robolectric picture and the emulator
 * picture are of the same thing. `performScrollToNode` would say this in one line, but it
 * scrolls with an animation, and both callers hold the clock still so that an indeterminate
 * spinner cannot run away with the frame loop — a scroll that waits for an animation then
 * never finishes. `performScrollToIndex` is the instant one, so walking the indices is what
 * is left.
 */
internal fun ComposeTestRule.scrollUntilVisible(text: String?) {
    if (text == null) return

    val list = onNode(hasScrollToIndexAction())
    val onScreen = { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    var index = 0
    while (!onScreen() && index <= LAST_PLAUSIBLE_ITEM) {
        // Past the end of the list this throws rather than clamping, which is the signal
        // that the text is not in this screen at all.
        if (runCatching { list.performScrollToIndex(index++) }.isFailure) break
    }

    check(onScreen()) {
        "scrolled the whole list without finding \"$text\" — has the screen's copy changed?"
    }
}

/** No screen here is anywhere near this long; it only exists so a typo cannot loop forever. */
private const val LAST_PLAUSIBLE_ITEM = 24
