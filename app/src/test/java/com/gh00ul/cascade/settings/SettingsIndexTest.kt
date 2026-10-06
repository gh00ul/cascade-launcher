package com.gh00ul.cascade.settings

import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.WeatherPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings search and the one-line page summaries on the top settings page. */
class SettingsIndexTest {
    private fun titles(query: String) = findSettings(query).map { it.title }

    @Test fun aTitleStartingWithTheQueryComesFirstThenTitleWords() {
        assertEquals(listOf("Icon size", "App icons", "Monochrome icons"), titles("icon").take(3))
    }

    @Test fun keywordsFindSettingsByOtherNames() {
        assertEquals("Time format", titles("24").first())
        assertEquals("Double-tap", titles("lock").first())
        assertTrue("Temperature units" in titles("fahrenheit"))
    }

    @Test fun everyWordMustMatchCaseAndAccentsAside() {
        assertEquals("Weather", titles("WEATHER").first())
        assertEquals(listOf("Weather location"), titles("weather city"))
        assertEquals("Wallpaper dimming", titles("dîm").first())
    }

    @Test fun blankOrUnmatchedQueriesFindNothing() {
        assertEquals(emptyList<String>(), titles("  "))
        assertEquals(emptyList<String>(), titles("zzz"))
    }

    @Test fun highlightKeysAreUnique() {
        val keys = SettingsIndex.mapNotNull { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun summariesNameWhatIsSet() {
        val s = LauncherSettings(
            clockStyle = ClockStyle.STACKED,
            showWeather = true,
            weatherPlace = WeatherPlace("Oslo", 59.9, 10.7),
            showTimers = false,
            showCalendar = false,
            swipeDownAction = SwipeDownAction.QUICK_SETTINGS,
            doubleTapAction = DoubleTapAction.LOCK_SCREEN,
        )
        assertEquals("Stacked · weather, alarm, battery", clockSummary(s))
        assertEquals("Swipe down: quick settings · double-tap: lock", gesturesSummary(s))
        assertEquals("Automatic text · medium icons", appearanceSummary(LauncherSettings()))
        // Weather needs a place to show, so it isn't listed without one.
        assertEquals("Classic · alarm, timers, battery", clockSummary(LauncherSettings(showWeather = true)))
    }
}
