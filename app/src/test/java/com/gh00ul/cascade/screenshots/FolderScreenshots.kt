package com.gh00ul.cascade.screenshots

import android.content.ComponentName
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.Folder
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.folderKey
import com.gh00ul.cascade.data.homeItems
import com.gh00ul.cascade.notifications.NotificationListener
import com.gh00ul.cascade.settings.SettingsApp
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeLauncherApps
import com.gh00ul.cascade.testing.FakeNotifications
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Folders on the home screen: rows among the favorites (Mail's notification dots and previews the Work folder), and a
 * folder popped open from its row by a tap, as a user would.
 */
class FolderScreenshots : ScreenshotTest() {
    private fun keys(vararg labels: String) = labels.map { FakeApps.byLabel(it).key }

    private fun settings(showIcons: Boolean = true, iconSize: IconSize = IconSize.MEDIUM) = LauncherSettings(
        favorites = keys("Phone", "Messages") + folderKey("1") + keys("Camera") + folderKey("2") + keys("Music"),
        folders = mapOf(
            "1" to Folder("Work", keys("Mail", "Calendar", "Docs", "Tasks", "Notes")),
            "2" to Folder("Utilities", keys("Calculator", "Clock", "Files", "Recorder", "Translate", "Weather")),
        ),
        showIcons = showIcons,
        iconSize = iconSize,
    )

    @Composable
    private fun home(settings: LauncherSettings) = HomeScreen(
        settings,
        apps,
        favorites,
        icons,
        FakeNotifications.byApp(),
        items = homeItems(settings, apps.associateBy { it.key }),
    )

    @Test fun folders() = snap("Home_Folders") { home(settings()) }

    @Test fun foldersWithoutIcons() = snap("Home_FoldersNoIcons") { home(settings(showIcons = false)) }

    /**
     * A tap on Work's icon (the row's middle is its notification preview, which opens the notification): its apps pop
     * up above the row, Mail with its dot.
     */
    @Test fun folderOpen() = snap("Home_FolderOpen", afterContent = {
        onNodeWithText("Work").performTouchInput { click(Offset(width * 0.08f, height / 2f)) }
    }) { home(settings()) }

    /** Utilities, with large icons: the card keeps the rows' look at the list's size. */
    @Test fun folderOpenLarge() = snap("Home_FolderOpenLarge", afterContent = { onNodeWithText("Utilities").performClick() }) {
        home(settings(iconSize = IconSize.LARGE))
    }
}

/**
 * The settings side: Favorites with a folder among the apps, the folder's own page, and the page that adds apps to it.
 * Like SettingsScreenshots, these run on LauncherApplication with fake apps loaded by the real repository.
 */
@Config(application = LauncherApplication::class)
class FolderSettingsScreenshots : ScreenshotTest() {
    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication

    @Before
    fun seed() {
        loadApps()
        app.prefs.update {
            it.copy(
                favorites = listOf(key("Phone"), key("Messages"), folderKey("1"), key("Camera"), key("Music")),
                folders = mapOf("1" to Folder("Work", listOf(key("Calendar"), key("Notes"), key("Maps")))),
                favoritesSeeded = true,
                iconSize = IconSize.LARGE,
            )
        }
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, NotificationListener::class.java).flattenToString())
    }

    private fun page(name: String, screen: SettingsScreen) = snap(name, variants = Variant.Settings, wallpaper = false) {
        SettingsApp(screen, onExit = {}, startFolder = "1")
    }

    @Test fun favoritesWithFolder() = page("Settings_FavoritesFolders", SettingsScreen.FAVORITES)
    @Test fun folderPage() = page("Settings_Folder", SettingsScreen.FOLDER)
    @Test fun addToFolder() = page("Settings_AddToFolder", SettingsScreen.ADD_TO_FOLDER)

    private val serial get() = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())

    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }

    /** As SettingsScreenshots does: installs the apps and waits for the repository to publish them and their icons. */
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
        val LABELS = listOf("Phone", "Messages", "Camera", "Maps", "Music", "Photos", "Calendar", "Notes")
    }
}
