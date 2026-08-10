package dev.claudewear.wear.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * The same [Gallery] poses, photographed on the Wear emulator `scripts/e2e.sh` already boots.
 *
 * This is not a second gate — `ScreenshotTest` is the gate and it does the job in a unit
 * test. What this adds is what Robolectric cannot: the real device rendering, at the real
 * density, behind the real round bezel. `scripts/e2e.sh` pulls the PNGs off the device
 * afterwards and CI uploads them on every run, so a pull request that changes a screen
 * carries a photograph of what it now looks like.
 *
 * It rides in the existing `connectedDebugAndroidTest` invocation, so it costs no new job.
 * Structured pose-per-test exactly like `ScreenshotTest`, for the same reason: a shared
 * composition keeps the last screen's scroll position and photographs the wrong thing.
 */
@RunWith(Parameterized::class)
class ScreenTourTest(
    private val label: String,
    private val pose: Gallery.Pose,
) {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun photograph() {
        // Several poses hold an indeterminate spinner, and an infinite animation never lets
        // `waitForIdle` return while the clock advances by itself.
        compose.mainClock.autoAdvance = false

        compose.setContent { pose.content() }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.scrollUntilVisible(pose.reveal)
        compose.waitForIdle()

        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("the emulator returned no screenshot for $label")
        try {
            File(shotDirectory(), "$label.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screen.recycle()
        }
    }

    /**
     * The app's own internal files directory, which `scripts/e2e.sh` reads back with
     * `run-as`. Not external storage: Android 11 closed `/sdcard/Android/data/<pkg>` to the
     * shell user, so `adb pull` comes back empty from there — silently, which is worse.
     */
    private fun shotDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, SHOTS).apply { mkdirs() }
    }

    companion object {
        const val SHOTS = "screenshots"

        /** Enough for layout and the chat screen's scroll-to-newest to land. */
        private const val SETTLE_MS = 750L

        /**
         * Numbered, because these are pulled into a directory and looked through in order —
         * this is a tour of the app, and the order is the point.
         */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun poses(): List<Array<Any>> = Gallery.gallery.mapIndexed { index, pose ->
            arrayOf("%02d-%s".format(index + 1, pose.name), pose)
        }
    }
}
