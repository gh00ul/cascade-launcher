package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gh00ul.cascade.data.HourForecast
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TempUnit
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.withFixedZone
import com.gh00ul.cascade.ui.home.widgets.WeatherWidget
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * The weather widget's hours on a return home with no new reading (fetches failing offline, say): the hours that
 * passed while away drop. The widget runs under a lifecycle the test moves by hand, as in ClockHeaderLifecycleTest.
 *
 * The hours sit under the widget's one TalkBack label, so they can't be read back here; the test watches the time the
 * widget picks them by instead ([LocalNow]), which it must read again on the return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeatherHoursTest {
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

    private val owner = ManualLifecycleOwner()
    private val place = WeatherPlace("Testville, Nowhere", 47.6, -122.3)

    /** What [LocalNow] returns; [reads] keeps every value it returned. */
    private var wallTime = FIXED_NOW
    private val reads = ArrayList<Long>()
    private val clock: () -> Long = { wallTime.also { reads += it } }

    @After fun resetWeather() {
        resetWeatherObject()
    }

    @Test fun aReturnHomeReadsTheTimeAgainWithoutANewReading() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        Weather.showForTest(reading())
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNow provides clock) {
                LauncherTheme {
                    // The readout's setting on, so the readout owns the refresh loop and this widget fetches nothing.
                    WeatherWidget(LauncherSettings(showWeather = true, weatherPlace = place, tempUnit = TempUnit.CELSIUS), onLongPress = {})
                }
            }
        }
        compose.onNodeWithContentDescription("in Testville", substring = true).assertExists()
        assertEquals("The hours were picked at 9:41", FIXED_NOW, reads.lastOrNull())

        // Away for four hours, with no reading coming meanwhile: nothing reads the time.
        reads.clear()
        moveTo(Lifecycle.State.CREATED)
        wallTime = FIXED_NOW + 4 * HOUR
        assertEquals(emptyList<Long>(), reads)

        // Back home: the hours are picked again at 1:41, so 10, 11, 12 and 1 o'clock drop.
        moveTo(Lifecycle.State.STARTED)
        assertEquals(FIXED_NOW + 4 * HOUR, reads.lastOrNull())
        compose.onNodeWithContentDescription("in Testville", substring = true).assertExists()
    }

    /** Partly cloudy at 9:41, with the next eight hours from 10 o'clock. */
    private fun reading() = WeatherNow(
        temperature = 18,
        high = 21,
        low = 12,
        code = 2,
        isDay = true,
        fahrenheit = false,
        fetchedAt = FIXED_NOW,
        place = place,
        hours = List(8) { i -> HourForecast(FIXED_NOW + 19 * MINUTE + i * HOUR, 18 - i / 2, 2, isDay = true, precipitation = null) },
    )

    /** Moves the widget's lifecycle, as leaving home and coming back moves the activity's. */
    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { owner.registry.currentState = state }
        compose.waitForIdle()
    }

    /**
     * Puts [Weather] back as a new process has it. It's one object for the whole Robolectric sandbox, which every test
     * class with this config shares, and the stored-reading load this widget starts binds it, for good, to this test's
     * preferences: a later test's Weather.load would read nothing and go to the network. The queued load runs first, so
     * it can't bind it again afterwards. Its fields are private, so they're reached by reflection.
     */
    private fun resetWeatherObject() {
        val fields = Weather::class.java.declaredFields.onEach { it.isAccessible = true }.associateBy { it.name }
        val queue = fields.getValue("queue").get(null) as CoroutineScope
        runBlocking { queue.launch {}.join() }
        fields.getValue("store").set(null, null)
        fields.getValue("loadQueued").setBoolean(null, false)
        fields.getValue("holding").setBoolean(null, false)
        for (field in fields.values) {
            if (field.type != MutableStateFlow::class.java) continue
            // Every MutableStateFlow in Weather holds a nullable value that starts out null.
            @Suppress("UNCHECKED_CAST")
            val flow = field.get(null) as MutableStateFlow<Any?>
            flow.value = null
        }
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
