package com.gh00ul.cascade.screenshots

import android.Manifest
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.notifications.NotificationListener
import com.gh00ul.cascade.settings.SettingsApp
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The settings screen, which reads LauncherApplication's prefs and repository. Robolectric lists no launchable
 * apps, so the favorites and hidden counts come straight from the stored keys. Cascade is the default home app
 * (the manifest's HOME filter is the only one) and has notification access, so Setup sits above Backup and About;
 * SetupNeeded takes the access away. The About row shows [VERSION], not the build's `git describe` version, so the shot doesn't
 * change with every commit.
 */
@Config(application = LauncherApplication::class)
class SettingsScreenshots : ScreenshotTest() {
    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication() as LauncherApplication
        app.prefs.update {
            it.copy(
                favorites = FakeApps.favorites.map { a -> a.key },
                hidden = setOf(FakeApps.byLabel("Authenticator").key, FakeApps.byLabel("Keyboard").key),
                clockStyle = ClockStyle.BOLD,
                iconSize = IconSize.LARGE,
                showCalendar = true,
            )
        }
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = VERSION
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            ComponentName(app, NotificationListener::class.java).flattenToString())
    }

    @Test fun main() = snap("Settings_Main", variants = Variant.Settings, wallpaper = false) {
        SettingsApp(SettingsScreen.MAIN, onExit = {})
    }

    /**
     * The Appearance header scrolled up to where the first header ("Home screen") starts, so the whole section is in
     * view ("Text color" is cut off at the bottom of Main and scrolled away in MainBottom). Measured rather than a
     * fixed item index.
     */
    @Test fun mainMiddle() = snap(
        "Settings_MainMiddle",
        variants = Variant.Settings,
        wallpaper = false,
        afterContent = {
            val list = onNode(hasScrollToNodeAction())
            val listStart = onNodeWithText("Home screen").fetchSemanticsNode().boundsInRoot.top
            list.performScrollToNode(hasText("Appearance"))
            val header = onNodeWithText("Appearance").fetchSemanticsNode().boundsInRoot.top
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, header - listStart) }
        },
    ) {
        SettingsApp(SettingsScreen.MAIN, onExit = {})
    }

    @Test fun mainBottom() = snap(
        "Settings_MainBottom",
        variants = Variant.Settings,
        wallpaper = false,
        afterContent = { onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Free and open source")) },
    ) {
        SettingsApp(SettingsScreen.MAIN, onExit = {})
    }

    /** No notification access yet: Setup moves to the top, with the warning on that row. */
    @Test fun setupNeeded() {
        Settings.Secure.putString(RuntimeEnvironment.getApplication().contentResolver, "enabled_notification_listeners", "")
        snap("Settings_SetupNeeded", variants = Variant.Settings, wallpaper = false) {
            SettingsApp(SettingsScreen.MAIN, onExit = {})
        }
    }

    private companion object {
        const val VERSION = "1.0.0"
    }
}
