package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.testing.PressRecorder
import org.junit.Assert.assertEquals
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
 * A row's press feedback: a press held past a tap runs its hook once (a tap never does), and cancelling it ends the
 * press for the ripple too while the finger is still down, with the release that follows changing nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PressIndicationTest {
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

    private val ripple = PressRecorder()
    private val press = PressIndication(ripple)
    private var held = 0
    private var longClicks = 0

    /** A row of its own, outside any scrolling list, so its press starts as the finger lands. */
    private fun show(cancelOnLongClick: Boolean) {
        press.onHeld = { held++ }
        compose.setContent {
            Box(
                Modifier
                    .size(200.dp, 56.dp)
                    .testTag("row")
                    .combinedClickable(
                        interactionSource = null,
                        indication = press,
                        onClick = {},
                        onLongClick = {
                            longClicks++
                            if (cancelOnLongClick) press.cancel()
                        },
                    ),
            )
        }
        compose.waitForIdle()
    }

    private fun down() = compose.onNodeWithTag("row").performTouchInput { down(center) }
    private fun up() = compose.onNodeWithTag("row").performTouchInput { up() }

    @Test fun aTapIsNotAHeldPress() {
        show(cancelOnLongClick = true)
        down()
        compose.mainClock.advanceTimeBy(48)
        up()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("Pressed once", 1, ripple.presses)
        assertEquals("Not held", 0, held)
    }

    @Test fun aHeldPressRunsItsHookOnce() {
        show(cancelOnLongClick = true)
        down()
        compose.mainClock.advanceTimeBy(250)
        assertEquals("Held past a tap", 1, held)
        assertEquals("Before the long press", 0, longClicks)
        compose.mainClock.advanceTimeBy(1_000)
        up()
        compose.waitForIdle()
        assertEquals("Once per press", 1, held)
    }

    @Test fun cancelEndsThePressWhileTheFingerIsDown() {
        show(cancelOnLongClick = true)
        down()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, longClicks)
        assertEquals("The ripple heard the press end", emptyList<Any>(), ripple.held)
        assertEquals("The shrink eased back", 0f, press.pressed)
        // Clickable releases the press it still holds: nothing more ends, nothing breaks.
        up()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), ripple.held)
        assertEquals(0f, press.pressed)
    }

    /** What cancel changes: left alone, the press (and its ripple) lasts until the finger lifts. */
    @Test fun withoutCancelThePressLastsUntilTheFingerLifts() {
        show(cancelOnLongClick = false)
        down()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(1, longClicks)
        assertEquals("Still pressed", 1, ripple.held.size)
        up()
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), ripple.held)
    }
}
