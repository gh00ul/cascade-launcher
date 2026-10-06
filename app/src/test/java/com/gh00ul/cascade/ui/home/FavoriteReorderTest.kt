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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeApp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
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
 * A favorite with its notifications open is held the same way from beside them.
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
    private var menuCloses = 0
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
        // The menu opened as Phone lifted, and closed as soon as it moved.
        assertEquals(listOf(FakeApps.phone.key), menus)
        assertEquals(1, menuCloses)
        assertTrue("Nothing launched", launched.isEmpty())
        assertSettles()
        compose.onNodeWithContentDescription("App info").assertDoesNotExist()
    }

    @Test fun holdAndDragUpMovesAFavorite() {
        show()
        val music = row(FakeApps.music)
        hold(music)
        drag(music, dy = -pitch() * 3.3f)
        assertEquals(listOf("Phone", "Messages", "Music", "Mail", "Camera", "Photos"), labels)
        assertSettles()
    }

    @Test fun theMenuOpensWhileStillHeld() {
        show()
        val mail = row(FakeApps.mail)
        hold(mail)
        // No waiting for the finger to lift: the menu is there as the row lifts.
        assertEquals(listOf(FakeApps.mail.key), menus)
        compose.onNodeWithContentDescription("App info").assertExists()
        mail.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("App info").assertExists()
        assertEquals("Letting go doesn't close it", 0, menuCloses)
        assertTrue("Not launched, not moved", launched.isEmpty() && saved.isEmpty())
        assertSettles()
    }

    /**
     * Another favorite held while the one just dropped is still settling: it lifts (and its menu opens) once the settle
     * ends, and letting go never counts as a tap that opens it.
     */
    @Test fun holdingAnotherWhileOneSettlesLiftsIt() {
        show()
        val phone = row(FakeApps.phone)
        val pitch = pitch()
        hold(phone)
        compose.mainClock.autoAdvance = false
        phone.performTouchInput {
            repeat(24) { moveBy(Offset(0f, pitch * 2.2f / 24)) }
            up()
        }
        // A couple of frames into Phone's settle.
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val camera = row(FakeApps.camera)
        camera.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        camera.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Camera wasn't opened by letting go", launched.isEmpty())
        assertEquals("Phone's menu, then Camera's", listOf(FakeApps.phone.key, FakeApps.camera.key), menus)
        assertEquals(listOf("Messages", "Mail", "Phone", "Camera", "Photos", "Music"), labels)
        assertSettles()
    }

    /** Let go before the settle ends: no lift, and no tap either. */
    @Test fun lettingGoWhileOneSettlesOpensNothing() {
        show()
        val phone = row(FakeApps.phone)
        val pitch = pitch()
        hold(phone)
        compose.mainClock.autoAdvance = false
        phone.performTouchInput {
            repeat(24) { moveBy(Offset(0f, pitch * 2.2f / 24)) }
            up()
        }
        val camera = row(FakeApps.camera)
        camera.performTouchInput { down(center) }
        // Past the long press, still inside the settle.
        compose.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 16)
        camera.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Camera wasn't opened", launched.isEmpty())
        assertEquals(listOf(FakeApps.phone.key), menus)
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

    /**
     * A long press in the gutter of a favorite's open notifications (under its icon, beside them) is the favorite's: it
     * lifts and its menu opens, and the empty-space long press behind it (the home menu) stays out of it.
     */
    @Test fun aLongPressBesideAFavoritesOpenNotificationsLiftsOnlyIt() {
        show(expandedKey = "fav:${FakeApps.messages.key}")
        val gutter = gutterBeside("Sam Ortiz")
        compose.onRoot().performTouchInput { down(gutter) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("Messages' menu opened", listOf(FakeApps.messages.key), menus)
        compose.onNodeWithContentDescription("App info").assertExists()
        compose.onNodeWithText("Wallpaper").assertDoesNotExist()
    }

    /** The gutter takes its touches as a row does, and a swipe from it still scrolls the list. */
    @Test fun aSwipeFromBesideTheOpenNotificationsStillScrolls() {
        show(expandedKey = "fav:${FakeApps.messages.key}")
        val gutter = gutterBeside("Sam Ortiz")
        compose.onRoot().performTouchInput { swipe(gutter, gutter - Offset(0f, height * 0.6f), 60) }
        compose.waitForIdle()
        assertEquals("On the list's top", 1, list.firstVisibleItemIndex)
        assertTrue("Nothing moved or opened", saved.isEmpty() && launched.isEmpty() && menus.isEmpty())
    }

    /** In the open notifications' start gutter, level with the notification that says [text]. */
    private fun gutterBeside(text: String): Offset {
        val row = row(FakeApps.messages).getBoundsInRoot()
        val notification = compose.onNodeWithText(text, substring = true).getBoundsInRoot()
        return with(compose.density) { Offset(row.left.toPx() + 12.dp.toPx(), (notification.top.toPx() + notification.bottom.toPx()) / 2) }
    }

    private fun show(expandedKey: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(
                            settings = LauncherSettings(favorites = favorites.map { it.key }, showBattery = false),
                            apps = FakeApps.all,
                            favorites = favorites,
                            icons = emptyMap(),
                            notifications = if (expandedKey != null) FakeNotifications.byApp() else emptyMap(),
                            expandedKey = expandedKey,
                            items = favorites.map(::HomeApp),
                            listState = list,
                            onLaunch = { launched += it.key },
                            onAppLongPress = { menus += it.key },
                            onAppMenuClose = { menuCloses++ },
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
