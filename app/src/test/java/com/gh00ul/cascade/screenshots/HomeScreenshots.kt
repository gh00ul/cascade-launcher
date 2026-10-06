package com.gh00ul.cascade.screenshots

import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.OnboardingCard
import org.junit.Test

/** The first screen: clock, chips and favorites over the wallpaper. */
class HomeScreenshots : ScreenshotTest() {
    private fun settings(clockStyle: ClockStyle = ClockStyle.CLASSIC, iconSize: IconSize = IconSize.MEDIUM, showCalendar: Boolean = false) =
        LauncherSettings(favorites = favorites.map { it.key }, clockStyle = clockStyle, iconSize = iconSize, showCalendar = showCalendar)

    /** Every chip: timer, next event, alarm, charging. The Messages favorite previews its latest message. */
    @Test fun classicWithChips() {
        ClockFixtures.install(compose.activity)
        snap("Home_Classic_Chips", afterContent = ClockFixtures.awaitEventChip) {
            HomeScreen(settings(showCalendar = true), apps, favorites, icons, FakeNotifications.byApp())
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
}
