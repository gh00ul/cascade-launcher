package com.gh00ul.cascade.screenshots

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.test.hasContentDescription
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TempUnit
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.OnboardingCard
import com.gh00ul.cascade.ui.home.UpdateCard
import com.gh00ul.cascade.update.Updater
import org.junit.Test

/** The first screen: clock, chips and favorites over the wallpaper. */
class HomeScreenshots : ScreenshotTest() {
    private fun settings(clockStyle: ClockStyle = ClockStyle.CLASSIC, iconSize: IconSize = IconSize.MEDIUM, showCalendar: Boolean = false) =
        LauncherSettings(favorites = favorites.map { it.key }, clockStyle = clockStyle, iconSize = iconSize, showCalendar = showCalendar)

    /** An update as Updater would report it. Built here: no shot checks GitHub or downloads anything. */
    private val release = Updater.Release("v0.6.0", "0.6.0", 600, "https://example.invalid/cascade.apk")

    /** Every chip: timer, next event, alarm, charging. The Messages favorite previews its latest message. */
    @Test fun classicWithChips() {
        ClockFixtures.install(compose.activity)
        snap("Home_Classic_Chips", afterContent = ClockFixtures.awaitEventChip) {
            HomeScreen(settings(showCalendar = true), apps, favorites, icons, FakeNotifications.byApp())
        }
    }

    /**
     * The glance options on: weather beside the date (a fresh reading, so nothing is fetched), a 24-hour clock, and the
     * battery level shown while it's neither charging nor low.
     */
    @Test fun glance() {
        val seattle = WeatherPlace("Seattle, Washington, United States", 47.61, -122.33)
        Weather.showForTest(WeatherNow(18, 21, 12, code = 2, isDay = true, fahrenheit = false, fetchedAt = FIXED_NOW, place = seattle))
        @Suppress("DEPRECATION") // The only way to fake the battery broadcast.
        compose.activity.application.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 82)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
        val glance = settings().copy(
            timeFormat = TimeFormat.H24,
            showWeather = true,
            weatherPlace = seattle,
            tempUnit = TempUnit.CELSIUS,
            batteryAlways = true,
        )
        snap("Home_Glance", afterContent = {
            waitUntil(5_000) { onAllNodes(hasContentDescription("Weather:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }) {
            HomeScreen(glance, apps, favorites, icons, FakeNotifications.byApp())
        }
    }

    @Test fun bold() = snap("Home_Bold") {
        HomeScreen(settings(ClockStyle.BOLD), apps, favorites, icons, FakeNotifications.byApp())
    }

    @Test fun stacked() = snap("Home_Stacked") {
        HomeScreen(settings(ClockStyle.STACKED), apps, favorites, icons, FakeNotifications.byApp())
    }

    @Test fun iconsSmall() = snap("Home_IconsSmall") {
        HomeScreen(settings(iconSize = IconSize.SMALL), apps, favorites, icons, FakeNotifications.byApp())
    }

    @Test fun iconsXL() = snap("Home_IconsXL") {
        HomeScreen(settings(iconSize = IconSize.XL), apps, favorites, icons, FakeNotifications.byApp())
    }

    /** The Music favorite turns into the player. */
    @Test fun musicPlaying() {
        val playing = media.playing()
        snap("Home_MusicPlaying") {
            HomeScreen(settings(), apps, favorites, icons, FakeNotifications.byApp(), media = playing)
        }
    }

    /** A swipe on the Messages favorite: all three notifications inline, with "Clear all". */
    @Test fun notificationsExpanded() = snap("Home_NotificationsExpanded") {
        HomeScreen(settings(), apps, favorites, icons, FakeNotifications.byApp(), expandedKey = "fav:${FakeApps.messages.key}")
    }

    @Test fun onboarding() = snap("Home_Onboarding") {
        HomeScreen(settings(), apps, favorites, icons, onboarding = {
            OnboardingCard(
                title = "Make Cascade your home screen",
                body = "Set it as your default home app so the Home button brings you here.",
                action = "Set as default",
                onAction = {},
                onDismiss = {},
            )
        })
    }

    /** The update offer, in the onboarding card's place. */
    @Test fun updateCard() = snap("Home_UpdateCard") {
        HomeScreen(settings(), apps, favorites, icons, onboarding = {
            UpdateCard(Updater.State.Available(release), onUpdate = {}, onDismiss = {})
        })
    }

    @Test fun updateDownloading() = snap("Home_UpdateDownloading") {
        HomeScreen(settings(), apps, favorites, icons, onboarding = {
            UpdateCard(Updater.State.Downloading(release, percent = 42), onUpdate = {}, onDismiss = {})
        })
    }

    /** First run: no favorites yet, so the hint sits where they will go. */
    @Test fun empty() = snap("Home_Empty") {
        HomeScreen(LauncherSettings(), apps, emptyList(), icons)
    }
}
