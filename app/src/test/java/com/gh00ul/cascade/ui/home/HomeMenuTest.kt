package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home menu: a long press on empty space pops it from the finger; a tile does its job and closes it, as do a tap
 * outside and Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
class HomeMenuTest {
    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var at by mutableStateOf<Offset?>(null)
    private val ran = ArrayList<String>()

    /** The pop-up alone, its tiles recording what they do and closing it, as LauncherScreen's do. */
    private fun showMenu() {
        compose.setContent {
            LauncherTheme {
                LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        fun tile(name: String): () -> Unit = {
                            ran += name
                            at = null
                        }
                        HomeMenuPopup(at, tile("wallpaper"), tile("widgets"), tile("favorites"), tile("settings"), onDismiss = { at = null })
                    }
                }
            }
        }
    }

    @Test fun aLongPressOnEmptySpaceOpensItAtTheFinger() {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(LauncherSettings(favorites = FakeApps.favorites.map { it.key }, showBattery = false), FakeApps.all, FakeApps.favorites, emptyMap())
                    }
                }
            }
        }
        var finger = Offset.Zero
        compose.onRoot().performTouchInput {
            finger = Offset(width * 0.6f, height * 0.4f)
            longClick(finger)
        }
        compose.waitForIdle()
        val card = compose.onNodeWithText("Wallpaper").getBoundsInRoot()
        val px = with(compose.density) { card.bottom.toPx() }
        assertTrue("Above the finger: the card ends at $px, the finger is at ${finger.y}", px < finger.y)
    }

    @Test fun eachTileDoesItsJobAndCloses() {
        showMenu()
        for ((label, name) in listOf("Wallpaper" to "wallpaper", "Widgets" to "widgets", "Favorites" to "favorites", "Settings" to "settings")) {
            compose.runOnIdle { at = Offset(500f, 1200f) }
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            assertEquals(name, ran.last())
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    /**
     * The frame right after the long press already shows the pop-up (dim and card, partly faded in), rather than the
     * layer starting from nothing. Shared by every pop-up over home: the folder, and the app and folder menus.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun itShowsOnItsFirstFrame() {
        showMenu()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = drawn()
        compose.runOnUiThread { at = Offset(500f, 1200f) }
        // Frame by frame until the one that first composes it.
        var frames = 0
        while (compose.onAllNodesWithText("Wallpaper").fetchSemanticsNodes().isEmpty()) {
            check(frames++ < 10) { "The menu didn't open" }
            compose.mainClock.advanceTimeByFrame()
        }
        val card = compose.onNodeWithText("Wallpaper").getBoundsInRoot()
        val first = drawn()
        val (x, y) = with(compose.density) { ((card.left + card.right) / 2).roundToPx() to ((card.top + card.bottom) / 2).roundToPx() }
        assertNotEquals("Drawn on the first frame", before.getPixel(x, y), first.getPixel(x, y))
    }

    /** The window as it draws now, without waiting for another frame (captureToImage would wait for one). */
    private fun drawn(): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    @Test fun aTapOutsideOrBackClosesIt() {
        showMenu()
        compose.runOnIdle { at = Offset(500f, 1200f) }
        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Wallpaper").assertDoesNotExist()

        compose.runOnIdle { at = Offset(500f, 1200f) }
        compose.onNodeWithText("Wallpaper").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Wallpaper").assertDoesNotExist()
        assertTrue("Neither ran a tile", ran.isEmpty())
    }
}
