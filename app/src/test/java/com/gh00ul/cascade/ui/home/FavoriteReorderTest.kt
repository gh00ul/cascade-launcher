package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeApp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
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

/**
 * Moving favorites on home: hold one until it lifts and drag it to a new place, and the new order is saved. Held and
 * let go without dragging, its menu opens; a tap still opens the app, and a swipe that starts on a row still scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
class FavoriteReorderTest {
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

    private var favorites by mutableStateOf(FakeApps.favorites)
    private val list = LazyListState()
    private val launched = ArrayList<String>()
    private val menus = ArrayList<String>()
    private val saved = ArrayList<List<String>>()

    private val labels get() = favorites.map { it.label }

    @Test fun holdAndDragDownMovesAFavorite() {
        show()
        val phone = row(FakeApps.phone)
        val pitch = pitch()
        hold(phone)
        // Down past Messages and Mail: Phone lands third.
        drag(phone, dy = pitch * 2.2f)
        assertEquals(listOf("Messages", "Mail", "Phone", "Camera", "Photos", "Music"), labels)
        assertEquals("Saved once, on the drop", 1, saved.size)
        assertTrue("Nothing opened", launched.isEmpty() && menus.isEmpty())
        assertSettles()
    }

    @Test fun holdAndDragUpMovesAFavorite() {
        show()
        val music = row(FakeApps.music)
        hold(music)
        drag(music, dy = -pitch() * 3.3f)
        assertEquals(listOf("Phone", "Messages", "Music", "Mail", "Camera", "Photos"), labels)
        assertSettles()
    }

    @Test fun holdAndLetGoOpensTheMenu() {
        show()
        val mail = row(FakeApps.mail)
        hold(mail)
        mail.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(FakeApps.mail.key), menus)
        assertTrue("Not launched, not moved", launched.isEmpty() && saved.isEmpty())
        assertSettles()
    }

    @Test fun aTapStillOpensTheApp() {
        show()
        row(FakeApps.camera).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(FakeApps.camera.key), launched)
        assertTrue(menus.isEmpty() && saved.isEmpty())
    }

    @Test fun aSwipeThatStartsOnARowStillScrolls() {
        show()
        row(FakeApps.photos).performTouchInput { swipe(center, center - Offset(0f, height * 1.5f), 60) }
        compose.waitForIdle()
        assertEquals("On the list's top", 1, list.firstVisibleItemIndex)
        assertTrue("Nothing moved or opened", saved.isEmpty() && launched.isEmpty() && menus.isEmpty())
    }

    @Test fun draggingBackToWhereItWasSavesNothing() {
        show()
        val phone = row(FakeApps.phone)
        hold(phone)
        drag(phone, dy = pitch() * 1.4f, thenBack = true)
        assertEquals(FakeApps.favorites.map { it.label }, labels)
        assertTrue("Nothing saved", saved.isEmpty())
        assertSettles()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(
                            settings = LauncherSettings(favorites = favorites.map { it.key }, showBattery = false),
                            apps = FakeApps.all,
                            favorites = favorites,
                            icons = emptyMap(),
                            items = favorites.map(::HomeApp),
                            listState = list,
                            onLaunch = { launched += it.key },
                            onAppLongPress = { menus += it.key },
                            onReorderFavorites = { keys ->
                                saved += keys
                                favorites = keys.map { key -> FakeApps.all.first { it.key == key } }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row(app: AppEntry): SemanticsNodeInteraction = compose.onAllNodesWithText(app.label).onFirst()

    /** The distance between two plain favorites' tops. */
    private fun pitch(): Float {
        val camera = row(FakeApps.camera).getBoundsInRoot().top
        val photos = row(FakeApps.photos).getBoundsInRoot().top
        return with(compose.density) { (photos - camera).toPx() }
    }

    /** A finger down on [row] and held past the long press. */
    private fun hold(row: SemanticsNodeInteraction) {
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The held finger moved by [dy] in small steps, a frame apart, then lifted; [thenBack] returns it first. */
    private fun drag(row: SemanticsNodeInteraction, dy: Float, thenBack: Boolean = false) {
        val steps = 24
        row.performTouchInput {
            repeat(steps) { moveBy(Offset(0f, dy / steps)) }
            if (thenBack) repeat(steps) { moveBy(Offset(0f, -dy / steps)) }
            up()
        }
        compose.waitForIdle()
    }

    /** Nothing left animating a second after the drop: the rows have settled and nothing loops. */
    private fun assertSettles() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("Still animating", Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }
}
