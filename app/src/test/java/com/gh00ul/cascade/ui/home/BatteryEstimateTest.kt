package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.withFixedZone
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBatteryManager

/**
 * The battery chip's charge estimate ("full in 1h 30m") is a binder call into BatteryStats, and while charging the
 * battery broadcast comes with every voltage or temperature change. The header asks for it when the level or charging
 * state moves, and again while the system doesn't know it yet, but reuses it for a broadcast that changes neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [BatteryEstimateTest.CountingBatteryManager::class])
class BatteryEstimateTest {
    /** BatteryManager that counts the charge estimates asked of it and answers [remaining]. */
    @Implements(BatteryManager::class)
    class CountingBatteryManager : ShadowBatteryManager() {
        @Implementation
        override fun computeChargeTimeRemaining(): Long {
            asked++
            return remaining
        }

        companion object {
            var asked = 0
            /** Milliseconds until full, or -1 while the system doesn't know yet (just after plugging in). */
            var remaining = -1L
        }
    }

    /** As in ClockHeaderLifecycleTest: registers the bare host activity and pins Robolectric's clock and the time zone. */
    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                SystemClock.setCurrentTimeMillis(FIXED_NOW)
                withFixedZone { base.evaluate() }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val owner = ManualLifecycleOwner()
    private val asked get() = CountingBatteryManager.asked

    @Test fun aBroadcastThatChangesNothingShownReusesTheEstimate() {
        show()
        CountingBatteryManager.remaining = 90 * MINUTE
        charging(level = 50)
        assertEquals(1, asked)
        // Voltage changes: the same level, still charging.
        charging(level = 50, voltage = 4_120)
        charging(level = 50, voltage = 4_140)
        assertEquals("Asked once for the same level", 1, asked)
        compose.onNodeWithContentDescription("full in", substring = true).assertExists()
    }

    @Test fun aNewLevelOrPluggingBackInAsksAgain() {
        show()
        CountingBatteryManager.remaining = 90 * MINUTE
        charging(level = 50)
        charging(level = 51)
        assertEquals("Asked again for a new level", 2, asked)
        discharging(level = 51)
        assertEquals("Not asked while not charging", 2, asked)
        charging(level = 51)
        assertEquals("Asked again once plugged back in", 3, asked)
    }

    @Test fun anUnknownEstimateIsAskedAgainUntilItsKnown() {
        show()
        charging(level = 50)
        charging(level = 50, voltage = 4_120)
        assertEquals("Asked again while unknown", 2, asked)
        CountingBatteryManager.remaining = 2 * HOUR
        charging(level = 50, voltage = 4_140)
        charging(level = 50, voltage = 4_160)
        assertEquals("Known on the third, then reused", 3, asked)
    }

    /** The header with the battery chip on, started, as home is while visible; nothing asked of BatteryManager yet. */
    private fun show() {
        owner.registry.currentState = Lifecycle.State.STARTED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNow provides { FIXED_NOW }) {
                ClockHeader(LauncherSettings(showBattery = true))
            }
        }
        compose.waitForIdle()
        // Kotlin objects outlive a test inside the same Robolectric sandbox.
        CountingBatteryManager.asked = 0
        CountingBatteryManager.remaining = -1L
    }

    private fun charging(level: Int, voltage: Int = 4_100) =
        battery(level, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC, voltage)

    private fun discharging(level: Int) = battery(level, BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0, voltage = 3_900)

    /** ACTION_BATTERY_CHANGED as the system sends it, delivered to whoever is registered. */
    private fun battery(level: Int, status: Int, plugged: Int, voltage: Int) {
        app.sendBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
                .putExtra(BatteryManager.EXTRA_VOLTAGE, voltage),
        )
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** A lifecycle the test moves by hand, standing in for the activity's. */
    private class ManualLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
