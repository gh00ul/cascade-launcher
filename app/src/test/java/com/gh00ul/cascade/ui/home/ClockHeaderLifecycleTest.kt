package com.gh00ul.cascade.ui.home

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.testing.withFixedZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The clock header does nothing while home isn't visible: its receivers are registered only while started, the timer
 * chips stop ticking and the calendar isn't queried. The header runs under a lifecycle the test moves by hand, while
 * the activity hosting it stays resumed, so Compose keeps composing and only the header's own effects see the change.
 *
 * Delays inside effects (the timer chips' ticker) run on the compose test clock, so `mainClock` drives them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ClockHeaderLifecycleTest {
    /** As in ScreenshotTest: registers the bare host activity and pins Robolectric's clock and the time zone. */
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

    /** What [LocalNow] returns; [clockCalls] counts every read, from any thread. */
    @Volatile private var wallTime = FIXED_NOW
    private val clockCalls = AtomicInteger()
    private val clock: () -> Long = {
        clockCalls.incrementAndGet()
        wallTime
    }

    @After fun resetGlobals() {
        // Kotlin objects outlive a test inside the same Robolectric sandbox.
        NotificationStore.clear()
    }

    @Test fun receiversAreRegisteredOnlyWhileStarted() {
        owner.registry.currentState = Lifecycle.State.STARTED
        show(LauncherSettings(showBattery = true))
        compose.runOnIdle {
            assertTrue(registered(Intent.ACTION_TIME_TICK))
            assertTrue(registered(Intent.ACTION_BATTERY_CHANGED))
        }

        moveTo(Lifecycle.State.CREATED)
        assertFalse(registered(Intent.ACTION_TIME_TICK))
        assertFalse(registered(Intent.ACTION_BATTERY_CHANGED))

        moveTo(Lifecycle.State.STARTED)
        assertTrue(registered(Intent.ACTION_TIME_TICK))
        assertTrue(registered(Intent.ACTION_BATTERY_CHANGED))
    }

    @Test fun timeCatchesUpOnStartAfterTicksWereMissed() {
        owner.registry.currentState = Lifecycle.State.STARTED
        show(LauncherSettings(showBattery = false))
        compose.onNodeWithText("9:41").assertExists()

        // While visible, each tick updates the time.
        wallTime = FIXED_NOW + MINUTE
        tick()
        compose.onNodeWithText("9:42").assertExists()

        // While away, nothing listens: the tick reads no clock and the header keeps the old time.
        moveTo(Lifecycle.State.CREATED)
        val stopped = clockCalls.get()
        wallTime = FIXED_NOW + 2 * HOUR
        tick()
        assertEquals(stopped, clockCalls.get())
        compose.onNodeWithText("9:42").assertExists()

        // Coming back catches up at once, without waiting for the next tick.
        moveTo(Lifecycle.State.STARTED)
        compose.onNodeWithText("11:41").assertExists()
    }

    @Test fun timerChipsTickOnlyWhileStarted() {
        NotificationStore.reset(
            arrayOf(FakeNotifications.sbn("com.example.clock", FakeNotifications.timer(app, "Pasta", FIXED_NOW + 5 * MINUTE))),
            null,
            app.packageName,
        )
        owner.registry.currentState = Lifecycle.State.CREATED
        show(LauncherSettings())
        // The chip shows even before anything collects: the collector starts from the store's current value.
        compose.onNodeWithContentDescription("Pasta", substring = true).assertExists()

        compose.mainClock.autoAdvance = false
        val stopped = clockCalls.get()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals("No ticks while stopped", stopped, clockCalls.get())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        // Let the recomposition that starting causes (the alarm re-read) run before counting.
        compose.mainClock.advanceTimeByFrame()
        val started = clockCalls.get()
        compose.mainClock.advanceTimeBy(3_000)
        assertTrue("Ticks once a second while started", clockCalls.get() - started >= 2)
    }

    @Test fun calendarIsQueriedOnlyWhileVisible() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(CountingCalendarProvider::class.java, CalendarContract.AUTHORITY)
        owner.registry.currentState = Lifecycle.State.CREATED
        show(LauncherSettings(showCalendar = true, showBattery = false))

        // Time moves past the five-minute re-query while away.
        wallTime = FIXED_NOW + 10 * MINUTE
        tick()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        Thread.sleep(100) // A query, if one started, would run on Dispatchers.IO.
        assertEquals(0, provider.queries.get())

        moveTo(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { provider.queries.get() > 0 }
    }

    @Test fun calendarIsQueriedOnceWhenFirstShownResumed() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(CountingCalendarProvider::class.java, CalendarContract.AUTHORITY)
        // Home first composes after it resumes, and the resume counter's effect then replays ON_RESUME.
        owner.registry.currentState = Lifecycle.State.RESUMED
        show(LauncherSettings(showCalendar = true, showBattery = false))
        compose.waitUntil(5_000) { provider.queries.get() > 0 }
        settle()
        assertEquals(1, provider.queries.get())
    }

    @Test fun calendarIsNotQueriedOnTheWayOut() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(CountingCalendarProvider::class.java, CalendarContract.AUTHORITY)
        owner.registry.currentState = Lifecycle.State.CREATED
        show(LauncherSettings(showCalendar = true, showBattery = false))

        moveTo(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { provider.queries.get() > 0 }
        settle()
        val shown = provider.queries.get()

        // Pausing comes first while home is still started (an app opening on top); the next query waits for a return.
        moveTo(Lifecycle.State.STARTED)
        settle()
        moveTo(Lifecycle.State.CREATED)
        settle()
        assertEquals(shown, provider.queries.get())

        // Coming back queries once.
        moveTo(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { provider.queries.get() > shown }
        settle()
        assertEquals(shown + 1, provider.queries.get())
    }

    private fun show(settings: LauncherSettings) = compose.setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNow provides clock) {
            ClockHeader(settings)
        }
    }

    /** Moves the header's lifecycle, as leaving home and coming back moves the activity's. */
    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
        compose.waitForIdle()
    }

    /** Lets a query that may have started on Dispatchers.IO land. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(100)
        compose.waitForIdle()
    }

    private fun registered(action: String) = shadowOf(app).hasReceiverForIntent(Intent(action))

    /** The broadcast the system sends every minute, delivered to whoever is registered. */
    private fun tick() {
        app.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** Counts calendar queries and answers each with no events. */
    class CountingCalendarProvider : ContentProvider() {
        val queries = AtomicInteger()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            queries.incrementAndGet()
            return MatrixCursor(projection ?: emptyArray())
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
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
