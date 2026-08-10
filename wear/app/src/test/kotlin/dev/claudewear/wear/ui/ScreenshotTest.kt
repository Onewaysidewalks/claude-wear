package dev.claudewear.wear.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every pose in [Gallery], drawn on a small round Wear device and compared against the PNG
 * committed beside this file.
 *
 * Robolectric does the drawing, so there is no emulator and no new CI job — this rides along
 * in the unit test task. A plain `testDebugUnitTest` does nothing here: Roborazzi only writes
 * or compares when its own Gradle task has set the system property, which is why `make
 * android` did not get slower and why these two exist:
 *
 *   make screenshots        # re-record after an intended change, then eyeball the diff
 *   make screenshots-check  # what CI runs; diffs land in app/build/outputs/roborazzi
 *
 * One pose per test rather than a loop, and that is not a style preference: sharing a
 * composition between poses leaves the previous screen's scroll position and text fields
 * behind, and the picture that comes out is of neither state.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound)
class ScreenshotTest(
    @Suppress("unused") private val label: String,
    private val pose: Gallery.Pose,
) {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun looksLikeItsScreenshot() {
        // Hand-wound clock. Several of these hold an indeterminate spinner, and an infinite
        // animation never lets the frame loop settle while the clock advances on its own —
        // left to itself, one of those screens takes five minutes instead of five seconds.
        compose.mainClock.autoAdvance = false

        compose.setContent { pose.content() }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.scrollUntilVisible(pose.reveal)

        try {
            compose.onRoot().captureRoboImage(filePath = "$GOLDEN_DIR/${pose.name}.png")
        } catch (mismatch: AssertionError) {
            throw AssertionError(
                "${pose.name} no longer matches its screenshot. The side-by-side diff is in " +
                    "app/build/outputs/roborazzi/. If the change was intended, run " +
                    "`make screenshots` and commit the result.",
                mismatch,
            )
        }
    }

    companion object {
        /** Relative to the module: these are committed source, not build output. */
        private const val GOLDEN_DIR = "src/test/screenshots"

        /** Enough for layout and the chat screen's scroll-to-newest to land. */
        private const val SETTLE_MS = 750L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun poses(): List<Array<Any>> = Gallery.gallery.map { arrayOf(it.name, it) }
    }
}
