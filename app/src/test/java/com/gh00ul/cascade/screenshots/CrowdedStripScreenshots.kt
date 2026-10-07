package com.gh00ul.cascade.screenshots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Test
import kotlin.math.min

/** App names with a real phone's worth of apps, where S alone has 16: more than fit below it on the strip. */
class CrowdedStripScreenshots : ScreenshotTest() {
    private val crowded = FakeApps.crowded
    private val letters = crowded.map { it.section }.distinct()

    /** Where S rests on the strip, before anything opens. */
    private fun TouchInjectionScope.restingS(): Float {
        val slot = min(height.toFloat() / letters.size, 22.dp.toPx())
        return (height - slot * letters.size) / 2f + slot * (letters.indexOf("S") + 0.5f)
    }

    /**
     * A finger just put down on S: S stays under it, its first app just below, and the apps that don't fit run past
     * the strip's end, fading out, with the letters after them.
     */
    @Test fun crowdedTouch() = snap(
        "AlphabetWave_AppNamesCrowdedTouch",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                down(Offset(centerX, restingS()))
            }
        },
    ) {
        HomeScreen(LauncherSettings(stripApps = true), crowded, emptyList(), icons, firstItem = FIRST_APP_ROW)
    }

    /** The finger moved from S down to its tenth app, Shazam, the strip following it one to one. */
    @Test fun crowdedTenth() = snap(
        "AlphabetWave_AppNamesCrowdedTenth",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                val y = restingS()
                down(Offset(centerX, y))
                // S stays under the finger, its apps below it in slots of their own.
                val opened = crowded.count { it.section == "S" }
                val slot = min(height / (letters.size + opened * 1.3f), 22.dp.toPx())
                val tenth = y + slot * 0.5f + slot * 1.3f * 9.5f
                repeat(24) { moveBy(Offset(0f, (tenth - y) / 24)) }
            }
        },
    ) {
        HomeScreen(LauncherSettings(stripApps = true), crowded, emptyList(), icons, firstItem = FIRST_APP_ROW)
    }
}
