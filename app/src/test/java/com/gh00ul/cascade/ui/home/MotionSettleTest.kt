package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.testing.withFixedZone
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.gh00ul.cascade.ui.theme.Motion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Motion on the home screen ends: after each event the screen stops asking for frames and stays that way, so nothing
 * animates while home is idle (BATTERY.md). "Settled" means no recomposer has pending work, which includes anyone
 * waiting for a frame: every running animation, whether a Compose animation or a Modifier.Node's.
 *
 * The test clock is driven by hand from before the first composition: while it auto-advances, the test's
 * InfiniteAnimationPolicy cancels infinite animations, which would hide one. The clock stays still ([LocalNow]) and no
 * media plays, so no ticker shows up as work in the idle second after each event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MotionSettleTest {
    @get:Rule(order = 0)
    val hostActivity = hostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val home = HomeMotionState()

    @After fun resetGlobals() {
        // Kotlin objects outlive a test inside the same Robolectric sandbox.
        NotificationStore.clear()
    }

    @Test fun homeAtRestSettles() {
        show()
        assertSettles()
    }

    @Test fun tapOnAFavoriteSettles() {
        show()
        assertSettles()
        compose.onAllNodesWithText(FakeApps.phone.label).onFirst().performTouchInput {
            down(center)
            up()
        }
        assertSettles()
    }

    /** The press shrink runs once and holds still under a finger that stays down (past the long press), then springs back. */
    @Test fun heldPressSettles() {
        show()
        assertSettles()
        val row = compose.onAllNodesWithText(FakeApps.phone.label).onFirst()
        row.performTouchInput { down(center) }
        // In a scrolling list the press lands after the tap delay; it must start the shrink, or this tests nothing.
        compose.mainClock.advanceTimeUntil(500) { !idle() }
        assertSettles()
        row.performTouchInput { up() }
        compose.mainClock.advanceTimeUntil(500) { !idle() }
        assertSettles()
    }

    /**
     * The shrink itself, wired as the rows wire it (the ripple's own frames would pass the checks above): a press eases
     * it all the way in and holds it, and the release eases it all the way back.
     */
    @Test fun pressShrinksUntilReleased() {
        lateinit var press: PressIndication
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LauncherTheme {
                press = rememberPressIndication()!!
                Box(Modifier.size(48.dp).testTag("row").clickable(interactionSource = null, indication = press) {}.pressScale(press))
            }
        }
        assertSettles()
        val row = compose.onNodeWithTag("row")
        row.performTouchInput { down(center) }
        assertSettles()
        assertEquals(1f, press.pressed)
        row.performTouchInput { up() }
        assertSettles()
        assertEquals(0f, press.pressed)
    }

    @Test fun rowSwipeSettles() {
        show()
        assertSettles()
        compose.onAllNodesWithText(FakeApps.messages.label).onFirst().performTouchInput { swipeRight() }
        assertSettles()
    }

    /** A flick up from home settles on the A–Z list's top, and one back down settles on home. */
    @Test fun flickBetweenHomeAndTheListSettles() {
        show()
        assertSettles()
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.3f, height * 0.7f), Offset(width * 0.3f, height * 0.6f), 60) }
        assertSettles()
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.3f, height * 0.3f), Offset(width * 0.3f, height * 0.4f), 60) }
        assertSettles()
    }

    /** The home menu pops open from a long press and closes with Back, and nothing keeps animating after either. */
    @Test fun homeMenuOpeningAndClosingSettles() {
        show()
        assertSettles()
        // The clock is driven by hand here, so the finger is held down past the long press while it runs. On this
        // small screen the favorites fill the middle: the empty space is beside the clock.
        compose.onRoot().performTouchInput { down(Offset(width * 0.8f, height * 0.08f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        assertSettles()
        compose.onNodeWithText("Wallpaper").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertSettles()
        compose.onNodeWithText("Wallpaper").assertDoesNotExist()
    }

    @Test fun alphabetDragSettles() {
        show()
        assertSettles()
        compose.onNodeWithContentDescription("Alphabet index").performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 40f))
            up()
        }
        assertSettles()
    }

    @Test fun searchOpeningAndClosingSettles() {
        show()
        assertSettles()
        change { home.searchOpen = true }
        assertSettles()
        compose.onNode(hasSetTextAction()).assertExists()

        change { home.searchOpen = false }
        assertSettles()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun notificationsExpandingAndCollapsingSettle() {
        show()
        assertSettles()
        change { home.expandedKey = "fav:${FakeApps.messages.key}" }
        assertSettles()
        change { home.expandedKey = null }
        assertSettles()
    }

    @Test fun clockStyleSwitchSettles() {
        show()
        assertSettles()
        change { home.settings = home.settings.copy(clockStyle = ClockStyle.BOLD) }
        assertSettles()
        change { home.settings = home.settings.copy(clockStyle = ClockStyle.CLASSIC) }
        assertSettles()
    }

    /** The chip's ticker runs once a second while a timer shows, but with the clock still it changes nothing. */
    @Test fun timerChipComingAndGoingSettles() {
        show()
        assertSettles()
        change {
            NotificationStore.reset(
                arrayOf(FakeNotifications.sbn("com.example.clock", FakeNotifications.timer(app, "Pasta", FIXED_NOW + 5 * MINUTE))),
                null,
                app.packageName,
            )
        }
        assertSettles()
        compose.onNodeWithContentDescription("Pasta", substring = true).assertExists()

        change { NotificationStore.clear() }
        assertSettles()
        compose.onNodeWithContentDescription("Pasta", substring = true).assertDoesNotExist()
    }

    @Test fun returnSettleSkipsFirstCompositionRecompositionAndPause() {
        val owner = HandLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var generation by mutableIntStateOf(0)
        var compositions = 0
        lateinit var settle: Animatable<Float, AnimationVector1D>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (generation >= 0) compositions++
                settle = rememberReturnSettle()
            }
        }
        // Composed resumed: the start replayed to the new observer is skipped.
        assertAtRest(settle)

        val composed = compositions
        change { generation++ }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Recomposed", compositions > composed)
        assertAtRest(settle)

        // A dialog-like activity on top pauses home without stopping it.
        moveTo(owner, Lifecycle.State.STARTED)
        moveTo(owner, Lifecycle.State.RESUMED)
        assertAtRest(settle)
    }

    @Test fun returnSettlePlaysOnStartAfterStop() {
        val owner = HandLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        lateinit var settle: Animatable<Float, AnimationVector1D>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { settle = rememberReturnSettle() }
        }
        assertAtRest(settle)

        moveTo(owner, Lifecycle.State.CREATED)
        assertAtRest(settle)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        // Started undispatched, so the first frame after the return already draws the start state.
        assertEquals(0f, settle.value)
        compose.mainClock.advanceTimeByFrame()
        assertTrue(settle.value < 1f)
        compose.mainClock.advanceTimeUntil(Motion.SCREEN + 200L) { settle.value == 1f }
        assertSettles()
    }

    /** Bounds in the root include the overlay's layer, so they show the shrink and the spring back. */
    @Test fun cancelledBackGestureSpringsSearchBack() {
        home.searchOpen = true
        show()
        assertSettles()
        val field = compose.onNode(hasSetTextAction())
        val atRest = field.getBoundsInRoot()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        }
        compose.mainClock.advanceTimeByFrame()
        assertNotEquals("The gesture shrank the overlay", atRest, field.getBoundsInRoot())
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertSettles()
        assertTrue(home.searchOpen)
        assertEquals("Sprang back", atRest, field.getBoundsInRoot())
    }

    @Test fun committedBackGestureClosesSearch() {
        home.searchOpen = true
        show()
        assertSettles()
        val field = compose.onNode(hasSetTextAction())
        val atRest = field.getBoundsInRoot()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        }
        compose.mainClock.advanceTimeByFrame()
        assertNotEquals("The gesture shrank the overlay", atRest, field.getBoundsInRoot())
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertSettles()
        assertFalse(home.searchOpen)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TestHome(home) }
    }

    /** Makes a change on the UI thread and hands it to the recomposer, without running a frame. */
    private fun change(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun moveTo(owner: HandLifecycleOwner, state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
        compose.waitForIdle()
    }

    /** At 1, not animating, and nothing waiting for a frame, even after one goes by. */
    private fun assertAtRest(settle: Animatable<Float, AnimationVector1D>) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, settle.value)
        assertFalse(settle.isRunning)
        assertTrue("Animating at rest", idle())
    }

    /**
     * Runs frames until nothing is pending, within [withinMs], then lets a second go by and checks that nothing started
     * again: a loop, or a ticker that recomposes, would.
     */
    private fun assertSettles(withinMs: Long = 2_000) {
        // Hands any state change to the recomposer before the first frame.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeUntil(withinMs) { idle() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("Still animating a second after settling", idle())
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}

/**
 * The list's back handler, alone: switched off, back falls through to the activity's own callback; on, a predictive
 * back gesture shrinks the list ([scale]) and springs it back when cancelled, or goes back once and eases it back when
 * committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ListBackHandlerTest {
    @get:Rule(order = 0)
    val hostActivity = hostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var enabled by mutableStateOf(true)
    private val scale = Animatable(1f)
    private var backs = 0

    @Test fun switchedOffBackFallsThrough() {
        enabled = false
        show()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertSettles()
        assertEquals(0, backs)
        assertEquals(1f, scale.value)
    }

    @Test fun cancelledGestureSpringsTheListBack() {
        show()
        startGesture()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertSettles()
        assertEquals(1f, scale.value)
        assertEquals(0, backs)
    }

    @Test fun committedGestureGoesBackOnceAndEasesTheListBack() {
        show()
        startGesture()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertSettles()
        assertEquals(1, backs)
        assertEquals(1f, scale.value)
    }

    private fun show() {
        // As in MainActivity: a back nothing else takes keeps the activity open.
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            })
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { ListBackHandler(enabled = { enabled }, scale = scale, onBack = { backs++ }) }
        assertSettles()
    }

    /** Halfway through a gesture, the list is smaller. */
    private fun startGesture() {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("The gesture shrank the list", scale.value < 1f)
    }

    /** As in MotionSettleTest: frames until nothing is pending, then a second with nothing starting again. */
    private fun assertSettles() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeUntil(2_000) { idle() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("Still animating a second after settling", idle())
    }
}

private fun backEvent(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

/**
 * With "Remove animations" (animator duration scale 0, which Compose reads as its MotionDurationScale), motion that
 * takes hundreds of milliseconds finishes within a few frames: every spec is scaled, nothing waits on a raw delay.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AnimationsOffTest {
    @get:Rule(order = 0)
    val hostActivity = hostActivityRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>(object : MotionDurationScale { override val scaleFactor = 0f })

    private val home = HomeMotionState()

    @Test fun returnSettleIsInstant() {
        val owner = HandLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        lateinit var settle: Animatable<Float, AnimationVector1D>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { settle = rememberReturnSettle() }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        assertEquals(0f, settle.value)
        assertFinishesAtOnce()
        assertEquals(1f, settle.value)
    }

    @Test fun searchOpensAndClosesAtOnce() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TestHome(home) }
        assertFinishesAtOnce()

        compose.runOnIdle { home.searchOpen = true }
        assertFinishesAtOnce()
        compose.onNode(hasSetTextAction()).assertExists()

        compose.runOnIdle { home.searchOpen = false }
        assertFinishesAtOnce()
        // The overlay leaves only once its exit has finished.
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    /** A row outside a scrolling list, so the press lands with the finger rather than after the tap delay. */
    @Test fun pressIsInstant() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ListAppRow(
                app = FakeApps.phone,
                expandKey = "all:${FakeApps.phone.key}",
                icons = remember { mutableStateOf(home.icons) },
                notifications = remember { mutableStateOf(home.notifications) },
                showIcon = true,
                iconSize = 40.dp,
                expanded = false,
                onLaunch = { _, _ -> },
                onLongPress = { _, _ -> },
                onOpenNotification = { _, _ -> },
                onToggleExpand = {},
            )
        }
        assertFinishesAtOnce()
        val row = compose.onNodeWithText(FakeApps.phone.label)

        row.performTouchInput { down(center) }
        assertFalse("The press started the shrink", idle())
        compose.mainClock.advanceTimeUntil(PRESS_MS) { idle() }

        row.performTouchInput { up() }
        assertFalse("The release started the spring back", idle())
        compose.mainClock.advanceTimeUntil(PRESS_MS) { idle() }
    }

    private fun assertFinishesAtOnce() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(INSTANT_MS) { idle() }
    }

    private companion object {
        /** A few frames. The shortest screen motion here, search's fade out, would take eight at full scale. */
        const val INSTANT_MS = 5 * 16L
        /** Two frames. At full scale the press springs take four or more. */
        const val PRESS_MS = 2 * 16L
    }
}

/** As in ClockHeaderLifecycleTest: registers the bare host activity and pins Robolectric's clock and the time zone. */
private fun hostActivityRule() = TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            SystemClock.setCurrentTimeMillis(FIXED_NOW)
            withFixedZone { base.evaluate() }
        }
    }
}

/** Nothing left for Compose to do: no recomposition pending and nothing waiting for a frame, so no animation running. */
private fun idle() = Recomposer.runningRecomposers.value.none { it.hasPendingWork }

/** The home screen's state the tests change. */
private class HomeMotionState {
    var settings by mutableStateOf(LauncherSettings(favorites = FakeApps.favorites.map { it.key }, showBattery = false))
    var searchOpen by mutableStateOf(false)
    var expandedKey by mutableStateOf<String?>(null)
    val notifications = FakeNotifications.byApp()
    val icons: Map<String, IconImage> by lazy { FakeApps.icons(sizePx = 48) }
}

/** A still clock, so the time and the timer chips never change while the tests look for idle. */
private val StillClock: () -> Long = { FIXED_NOW }

/** The screenshot frame of LauncherScreen, as a shot renders it; closing search sets [HomeMotionState.searchOpen]. */
@Composable
private fun TestHome(home: HomeMotionState) {
    CompositionLocalProvider(LocalNow provides StillClock) {
        LauncherTheme {
            LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                HomeScreen(
                    settings = home.settings,
                    apps = FakeApps.all,
                    favorites = FakeApps.favorites,
                    icons = home.icons,
                    notifications = home.notifications,
                    expandedKey = home.expandedKey,
                    searchOpen = home.searchOpen,
                    onSearchDismiss = { home.searchOpen = false },
                )
            }
        }
    }
}

/** A lifecycle the test moves by hand, standing in for the activity's. */
private class HandLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}
