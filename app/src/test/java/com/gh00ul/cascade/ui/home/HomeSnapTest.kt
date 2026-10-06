package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.gh00ul.cascade.data.HomeApp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.runBlocking
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
 * Between home and the A–Z list, the list comes to rest on home or on the list's top (the search pill at the top of
 * the screen), never in between; flings inside the list, or in a home taller than the screen, are left alone. Drags go
 * down the left of the screen, clear of the alphabet strip. On a phone-sized screen, as the shots use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
class HomeSnapTest {
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

    private val list = LazyListState()

    @Test fun flickUpFromHomeLandsOnTheListsTop() {
        show()
        flick(from = 0.7f, to = 0.6f)
        assertAtListTop()
    }

    /** A hard one too: it stops on the search pill rather than running on into the letters. */
    @Test fun hardFlickUpFromHomeStopsAtTheListsTop() {
        show()
        flick(from = 0.9f, to = 0.2f, durationMillis = 40)
        assertAtListTop()
    }

    @Test fun shortSlowDragFromHomeGoesBackHome() {
        show()
        slowDrag(from = 0.7f, to = 0.45f)
        assertAtHome()
    }

    @Test fun longSlowDragFromHomeGoesOnToTheList() {
        show()
        slowDrag(from = 0.9f, to = 0.2f)
        assertAtListTop()
    }

    @Test fun flickDownFromTheListsTopGoesHome() {
        show(firstItem = 1)
        flick(from = 0.3f, to = 0.4f)
        assertAtHome()
    }

    @Test fun shortSlowDragDownFromTheListsTopStaysOnTheList() {
        show(firstItem = 1)
        slowDrag(from = 0.3f, to = 0.5f)
        assertAtListTop()
    }

    @Test fun flicksInsideTheListAreLeftAlone() {
        show(firstItem = 12)
        flick(from = 0.6f, to = 0.5f)
        assertTrue("Flung on down the list: ${where()}", list.firstVisibleItemIndex > 12)

        val deep = list.firstVisibleItemIndex
        flick(from = 0.5f, to = 0.6f)
        assertTrue("Flung back up, still in the list: ${where()}", list.firstVisibleItemIndex in 2 until deep)
    }

    /** However hard, a fling heading home from inside the list ends on one of the two rests. */
    @Test fun flingsHeadingHomeFromTheListEndOnARest() {
        show()
        for ((durationMillis, distance) in listOf(200L to 0.2f, 120L to 0.3f, 80L to 0.4f, 40L to 0.6f)) {
            scrollTo(6)
            flick(from = 0.2f, to = 0.2f + distance, durationMillis = durationMillis)
            assertTrue("Came to rest at ${where()}", where() == "row 0 + 0px" || where() == "row 1 + 0px")
        }
    }

    /**
     * Gentler ones may stop in the list, but never with the header part way off the top: one that would, settles on
     * the list's top instead.
     */
    @Test fun flingsUpTheListNeverLeaveTheHeaderCutOff() {
        show()
        for (start in 2..5) for (durationMillis in listOf(400L, 250L, 150L, 100L)) {
            scrollTo(start)
            flick(from = 0.4f, to = 0.5f, durationMillis = durationMillis)
            assertTrue("From row $start in ${durationMillis}ms: ${where()}", list.firstVisibleItemIndex != 1 || list.firstVisibleItemScrollOffset == 0)
        }
    }

    /**
     * The same with a status bar over the list's top, as on a phone: home's last pixels are laid out under it while the
     * header sits a little off the top, and a gentle fling toward home from there still settles rather than stopping with
     * the search pill cut off.
     */
    @Test fun underAStatusBarFlingsStillNeverLeaveTheHeaderCutOff() {
        show(statusTop = 24.dp)
        val px = with(compose.density) { 1.dp.toPx() }
        for (off in listOf(8, 12, 18)) {
            scrollTo(1, (off * px).toInt())
            // A gentle fling toward home that would stop short of the list's top.
            flick(from = 0.5f, to = 0.52f, durationMillis = 400)
            assertEquals("From ${off}dp off the top", "row 1 + 0px", where())
        }
    }

    /** With more favorites than fit, home's own overflow scrolls freely; only its last screen snaps. */
    @Test fun aTallHomeScrollsItsOverflowFreely() {
        show(favorites = FakeApps.all.take(20).map { it.key })
        val home = list.layoutInfo.visibleItemsInfo.first().size
        val screen = list.layoutInfo.viewportSize.height
        assertTrue("Home overflows: $home of $screen", home > screen * 1.4f)

        slowDrag(from = 0.6f, to = 0.4f)
        assertEquals(0, list.firstVisibleItemIndex)
        assertTrue("Rests where it was let go: ${where()}", list.firstVisibleItemScrollOffset in 1 until home - screen)

        // Its last screen settles like any other home's.
        scrollTo(0, home - screen + screen / 4)
        slowDrag(from = 0.6f, to = 0.5f)
        assertEquals("Back to home's rest, favorites in view", home - screen, list.firstVisibleItemScrollOffset)
        flick(from = 0.7f, to = 0.6f)
        assertAtListTop()
    }

    @Test fun theScrimFollowsTheListOverHome() {
        show()
        val screen = list.layoutInfo.viewportSize.height.toFloat()
        assertEquals(0f, list.listCover(screen))
        scrollTo(0, (screen / 2).toInt())
        assertEquals(0.5f, list.listCover(screen), 0.01f)
        scrollTo(1)
        assertEquals(1f, list.listCover(screen))
        scrollTo(20)
        assertEquals(1f, list.listCover(screen))
    }

    private fun show(firstItem: Int = 0, favorites: List<String> = FakeApps.favorites.map { it.key }, statusTop: Dp = 0.dp) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(
                            settings = LauncherSettings(favorites = favorites, showBattery = false),
                            apps = FakeApps.all,
                            favorites = FakeApps.favorites,
                            icons = emptyMap(),
                            items = favorites.map { key -> HomeApp(FakeApps.all.first { it.key == key }) },
                            listState = list,
                            statusTop = statusTop,
                        )
                    }
                }
            }
        }
        if (firstItem > 0) scrollTo(firstItem)
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int, offset: Int = 0) {
        compose.runOnIdle { runBlocking { list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    /** A quick swipe between two heights (fractions of the screen), let go while still moving. */
    private fun flick(from: Float, to: Float, durationMillis: Long = 60) {
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.3f, height * from), Offset(width * 0.3f, height * to), durationMillis)
        }
        compose.waitForIdle()
    }

    /** A slow drag that stops before letting go, so the release carries no speed. */
    private fun slowDrag(from: Float, to: Float) {
        compose.onRoot().performTouchInput {
            val x = width * 0.3f
            down(Offset(x, height * from))
            val steps = 40
            for (i in 1..steps) moveTo(Offset(x, height * (from + (to - from) * i / steps)))
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
    }

    private fun where() = "row ${list.firstVisibleItemIndex} + ${list.firstVisibleItemScrollOffset}px"

    private fun assertAtHome() = assertEquals("At home", "row 0 + 0px", where())

    private fun assertAtListTop() = assertEquals("On the list's top", "row 1 + 0px", where())
}
