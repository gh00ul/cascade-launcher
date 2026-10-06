package com.gh00ul.cascade.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PrefsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun defaultsWhenNothingIsStored() {
        assertEquals(LauncherSettings(), Prefs(context).settings.value)
    }

    @Test fun everySettingSurvivesAReload() {
        val stored = LauncherSettings(
            favorites = listOf("b/b.Main#0", "a/a.Main#0", "c/c.Main#10"),
            hidden = setOf("h/h.Main#0"),
            renames = mapOf("a/a.Main#0" to "Alpha", "b/b.Main#0" to "Café ☕"),
            showIcons = false,
            monochromeIcons = true,
            showNotificationPreviews = false,
            showMediaControls = false,
            textColor = TextColor.DARK,
            iconSize = IconSize.XL,
            clockStyle = ClockStyle.STACKED,
            showCalendar = true,
            showBattery = false,
            autoUpdateCheck = false,
            swipeDownAction = SwipeDownAction.QUICK_SETTINGS,
            favoritesSeeded = true,
            notificationPromptDismissed = true,
            timeFormat = TimeFormat.H24,
            showDate = false,
            showAlarm = false,
            showTimers = false,
            wallpaperDim = WallpaperDim.MEDIUM,
            hideStatusBar = true,
            haptics = false,
            doubleTapAction = DoubleTapAction.LOCK_SCREEN,
            searchWeb = false,
            hiddenInSearch = false,
            autoLaunchSingleMatch = true,
            batteryAlways = true,
            showWeather = true,
            weatherPlace = WeatherPlace("Zürich", 47.3769, 8.5417),
            tempUnit = TempUnit.FAHRENHEIT,
        )
        Prefs(context).update { stored }
        assertEquals(stored, Prefs(context).settings.value)
    }

    @Test fun favoritesKeepTheirOrder() {
        val prefs = Prefs(context)
        prefs.toggleFavorite("x")
        prefs.toggleFavorite("y")
        prefs.toggleFavorite("z")
        prefs.toggleFavorite("y")
        prefs.setFavorites(listOf("z", "x"))
        assertEquals(listOf("z", "x"), Prefs(context).settings.value.favorites)
    }

    @Test fun blankRenameRemovesIt() {
        val prefs = Prefs(context)
        prefs.rename("k", "  Short  ")
        assertEquals(mapOf("k" to "Short"), prefs.settings.value.renames)
        prefs.rename("k", " ")
        assertEquals(emptyMap<String, String>(), Prefs(context).settings.value.renames)
    }

    @Test fun unknownEnumNamesFallBackToDefaults() {
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
            .putString("icon_size", "HUGE")
            .putString("clock_style", "FLIP")
            .putString("favorites", "not json")
            .putString("time_format", "H36")
            .putString("wallpaper_dim", "MAX")
            .putString("double_tap", "WAVE")
            .putString("temp_unit", "KELVIN")
            .putString("weather_place", """{"name":"Nowhere","lat":95,"lon":0}""")
            .commit()
        val settings = Prefs(context).settings.value
        assertEquals(IconSize.MEDIUM, settings.iconSize)
        assertEquals(ClockStyle.CLASSIC, settings.clockStyle)
        assertEquals(emptyList<String>(), settings.favorites)
        assertEquals(TimeFormat.SYSTEM, settings.timeFormat)
        assertEquals(WallpaperDim.OFF, settings.wallpaperDim)
        assertEquals(DoubleTapAction.NOTHING, settings.doubleTapAction)
        assertEquals(TempUnit.AUTO, settings.tempUnit)
        assertNull(settings.weatherPlace)
    }

    @Test fun swipeDownStoredBeforeTheNewActionsStillLoads() {
        // Stored by name, so adding QUICK_SETTINGS ahead of SEARCH doesn't change what an older install chose.
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("swipe_down", "SEARCH").commit()
        assertEquals(SwipeDownAction.SEARCH, Prefs(context).settings.value.swipeDownAction)
    }
}
