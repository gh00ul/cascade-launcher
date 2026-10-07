package com.gh00ul.cascade.screenshots

import android.Manifest
import android.content.ComponentName
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.notifications.NotificationListener
import com.gh00ul.cascade.settings.SettingsApp
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.testing.FakeLauncherApps
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every settings page, in the light and dark theme. They read LauncherApplication's prefs and repository, so the
 * apps are installed through [FakeLauncherApps] (plain colour icons) and loaded by the real repository. Cascade is the
 * default home app (the manifest's HOME filter is the only one) and has notification access, so the setup card is
 * gone; SetupNeeded takes the access away. About shows [VERSION], not the build's `git describe` version, so the shot
 * doesn't change with every commit. The preview's wallpaper is the stand-in gradient: Robolectric reports no
 * wallpaper colors.
 */
@Config(application = LauncherApplication::class)
class SettingsScreenshots : ScreenshotTest() {
    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication

    @Before
    fun seed() {
        loadApps()
        app.prefs.update {
            it.copy(
                favorites = listOf("Phone", "Messages", "Camera", "Maps", "Music").map(::key),
                hidden = setOf(key("Authenticator"), key("Keyboard")),
                renames = mapOf(key("Notes") to "Journal"),
                favoritesSeeded = true,
                clockStyle = ClockStyle.BOLD,
                iconSize = IconSize.LARGE,
                showCalendar = true,
                // A place, but weather off: the readout would otherwise go to the network.
                weatherPlace = WeatherPlace("Seattle, Washington, United States", 47.61, -122.33),
                showWeather = false,
            )
        }
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = VERSION
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, NotificationListener::class.java).flattenToString())
    }

    private fun page(name: String, screen: SettingsScreen, scrollTo: String? = null) = snap(
        name,
        variants = Variant.Settings,
        wallpaper = false,
        afterContent = scrollTo?.let { text -> { onNodeWithText(text).performScrollTo() } },
    ) { SettingsApp(screen, onExit = {}) }

    @Test fun main() = page("Settings_Main", SettingsScreen.MAIN)
    @Test fun mainBottom() = page("Settings_MainBottom", SettingsScreen.MAIN, scrollTo = "About Cascade")

    /** No notification access yet: the setup card leads, with its button. */
    @Test fun setupNeeded() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        page("Settings_SetupNeeded", SettingsScreen.MAIN)
    }

    @Test fun home() = page("Settings_Home", SettingsScreen.HOME)
    @Test fun favorites() = page("Settings_Favorites", SettingsScreen.FAVORITES)
    @Test fun hidden() = page("Settings_Hidden", SettingsScreen.HIDDEN)
    @Test fun appearance() = page("Settings_Appearance", SettingsScreen.LOOK)
    @Test fun appearanceBottom() = page("Settings_AppearanceBottom", SettingsScreen.LOOK, scrollTo = "Wallpaper")
    @Test fun clock() = page("Settings_Clock", SettingsScreen.CLOCK)
    @Test fun clockBottom() = page("Settings_ClockBottom", SettingsScreen.CLOCK, scrollTo = "Always show battery")

    /** Double-tap set to lock while the lock service is off: the prompt to turn it on shows under the choices. */
    @Test fun gestures() {
        app.prefs.update { it.copy(doubleTapAction = DoubleTapAction.LOCK_SCREEN) }
        page("Settings_Gestures", SettingsScreen.GESTURES)
    }

    @Test fun search() = page("Settings_Search", SettingsScreen.SEARCH)
    @Test fun backup() = page("Settings_Backup", SettingsScreen.BACKUP)
    @Test fun about() = page("Settings_About", SettingsScreen.ABOUT)

    @Test fun find() = snap(
        "Settings_Find",
        variants = Variant.Settings,
        wallpaper = false,
        afterContent = { onNode(hasSetTextAction()).performTextInput("icon") },
    ) { SettingsApp(SettingsScreen.FIND, onExit = {}) }

    /*
     * The pages again at the largest font size (200%): text should wrap, shrink or scroll, never be cut off. Robolectric
     * sets the configuration's font scale before the activity starts, so Compose scales text as a phone does, less for
     * large sizes than small ones.
     */
    @Config(fontScale = 2f) @Test fun mainFont2x() = page("Settings_Main_Font2x", SettingsScreen.MAIN)
    @Config(fontScale = 2f) @Test fun mainBottomFont2x() = page("Settings_MainBottom_Font2x", SettingsScreen.MAIN, scrollTo = "About Cascade")
    @Config(fontScale = 2f) @Test fun homeFont2x() = page("Settings_Home_Font2x", SettingsScreen.HOME)
    @Config(fontScale = 2f) @Test fun favoritesFont2x() = page("Settings_Favorites_Font2x", SettingsScreen.FAVORITES)
    @Config(fontScale = 2f) @Test fun addFavoriteFont2x() = page("Settings_AddFavorite_Font2x", SettingsScreen.ADD_FAVORITE)
    @Config(fontScale = 2f) @Test fun hiddenFont2x() = page("Settings_Hidden_Font2x", SettingsScreen.HIDDEN)
    @Config(fontScale = 2f) @Test fun appearanceFont2x() = page("Settings_Appearance_Font2x", SettingsScreen.LOOK)
    @Config(fontScale = 2f) @Test fun appearanceBottomFont2x() = page("Settings_AppearanceBottom_Font2x", SettingsScreen.LOOK, scrollTo = "Wallpaper")

    /** The four-tile pickers (dimming, icon size), where a name like Medium has to shrink to fit its tile. */
    @Config(fontScale = 2f) @Test fun appearanceTilesFont2x() = page("Settings_AppearanceTiles_Font2x", SettingsScreen.LOOK, scrollTo = "XL")

    /** The Stacked clock, the tallest, in the preview, and the three style tiles. */
    @Config(fontScale = 2f)
    @Test fun clockFont2x() {
        app.prefs.update { it.copy(clockStyle = ClockStyle.STACKED) }
        page("Settings_Clock_Font2x", SettingsScreen.CLOCK)
    }

    /** Time format's segmented buttons, Automatic chosen and checked. */
    @Config(fontScale = 2f) @Test fun clockFormatFont2x() = page("Settings_ClockFormat_Font2x", SettingsScreen.CLOCK, scrollTo = "24-hour")
    @Config(fontScale = 2f) @Test fun clockBottomFont2x() = page("Settings_ClockBottom_Font2x", SettingsScreen.CLOCK, scrollTo = "Always show battery")

    @Config(fontScale = 2f)
    @Test fun gesturesFont2x() {
        app.prefs.update { it.copy(doubleTapAction = DoubleTapAction.LOCK_SCREEN) }
        page("Settings_Gestures_Font2x", SettingsScreen.GESTURES)
    }

    @Config(fontScale = 2f) @Test fun searchFont2x() = page("Settings_Search_Font2x", SettingsScreen.SEARCH)
    @Config(fontScale = 2f) @Test fun backupFont2x() = page("Settings_Backup_Font2x", SettingsScreen.BACKUP)
    @Config(fontScale = 2f) @Test fun aboutFont2x() = page("Settings_About_Font2x", SettingsScreen.ABOUT)

    @Config(fontScale = 2f)
    @Test fun findFont2x() = snap(
        "Settings_Find_Font2x",
        variants = Variant.Settings,
        wallpaper = false,
        afterContent = { onNode(hasSetTextAction()).performTextInput("icon") },
    ) { SettingsApp(SettingsScreen.FIND, onExit = {}) }

    private val serial get() = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())

    /** The repository's key for the app [FakeLauncherApps] installs under [label]. */
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }

    /** Installs the apps and waits for the repository to publish them and their icons (it loads off the main thread). */
    private fun loadApps() {
        FakeLauncherApps.install(app, *LABELS.toTypedArray())
        app.repository.refresh()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (app.repository.apps.value.size == LABELS.size && app.repository.icons.value.size == LABELS.size) return
            Thread.sleep(20)
        }
        error("The repository didn't load the fake apps")
    }

    private companion object {
        const val VERSION = "1.0.0"
        val LABELS = listOf("Phone", "Messages", "Camera", "Maps", "Music", "Photos", "Calendar", "Authenticator", "Keyboard", "Notes")
    }
}
