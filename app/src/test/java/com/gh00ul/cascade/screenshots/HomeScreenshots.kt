package com.gh00ul.cascade.screenshots

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TempUnit
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.WeatherPlace
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.notifications.LastPlayed
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.ui.home.ResumeRow
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

    /** Listen mode: headphones on, nothing playing, so the app that played last offers to pick up where it stopped. */
    @Test fun resume() = snap("Home_Resume") {
        val music = FakeApps.byLabel("Music")
        HomeScreen(settings(), apps, favorites, icons, FakeNotifications.byApp(), resume = {
            ResumeRow(
                appLabel = music.label,
                icon = icons[music.key],
                played = LastPlayed(music.packageName, "Midnight City", "M83"),
                showIcon = true,
                iconSize = IconSize.MEDIUM.homeDp.dp,
                monochrome = false,
                resuming = false,
                onResume = {},
                onDismiss = {},
            )
        })
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
    /**
     * Mail held until it lifted, then dragged up most of a row: it rides above the rest on its pill, a little larger,
     * and Messages has slid down into its place. The finger is still down.
     */
    @Test fun reorder() = snap(
        "Home_Reorder",
        afterContent = {
            val mail = onAllNodesWithText(FakeApps.mail.label).onFirst()
            mail.performTouchInput {
                // The finger held for the variant before is still down: lift it, or down() is refused.
                if (currentPosition() != null) up()
                down(center)
            }
            mainClock.advanceTimeBy(1_000)
            mail.performTouchInput { repeat(12) { moveBy(Offset(0f, -height * 0.07f)) } }
        },
    ) {
        HomeScreen(settings(), apps, favorites, icons, FakeNotifications.byApp(), onReorderFavorites = {})
    }

    /**
     * Things under way as chips: a route in Maps, a download in Files filling its pill, and a ride from an app not on
     * this phone's list (its glyph instead of an icon), with the short status Android 16 lets it show.
     */
    @Test fun liveChips() {
        val context = compose.activity
        fun builder() = android.app.Notification.Builder(context, "live").setSmallIcon(android.R.drawable.sym_def_app_icon).setOngoing(true)
        val route = builder().setContentTitle("Turn left onto Pike St").setCategory(android.app.Notification.CATEGORY_NAVIGATION).build()
        val download = builder().setContentTitle("trip-photos.zip").setProgress(100, 64, false).build()
        val ride = builder().setContentTitle("Driver arriving").addExtras(
            android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putCharSequence("android.shortCriticalText", "3 min")
            },
        ).build()
        NotificationStore.reset(
            arrayOf(
                FakeNotifications.sbn(FakeApps.byLabel("Maps").packageName, route, id = 1),
                FakeNotifications.sbn(FakeApps.byLabel("Files").packageName, download, id = 2),
                FakeNotifications.sbn("com.example.rides", ride, id = 3),
            ),
            null,
            context.packageName,
        )
        try {
            snap("Home_LiveChips") { HomeScreen(settings(), apps, favorites, icons, FakeNotifications.byApp()) }
        } finally {
            NotificationStore.clear()
        }
    }

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
