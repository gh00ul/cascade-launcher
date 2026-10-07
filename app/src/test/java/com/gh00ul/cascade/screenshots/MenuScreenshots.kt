package com.gh00ul.cascade.screenshots

import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.Folder
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.folderKey
import com.gh00ul.cascade.data.homeItems
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.AppMenuCard
import com.gh00ul.cascade.ui.home.FolderChoice
import com.gh00ul.cascade.ui.home.FolderMenuCard
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.AppShortcut
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * The long-press menus' cards alone, as they pop over home: the app's (with shortcuts, which a shot can't load from
 * LauncherApps) and the folder's. Home_AppMenu shows one popped from its row.
 */
class MenuScreenshots : ScreenshotTest() {
    /** Messages, a favorite: its buttons, three conversations, two conversation shortcuts, and the rest. */
    @Test fun appMenu() {
        val context = RuntimeEnvironment.getApplication()
        val shortcuts = listOf("Maya Chen" to 0xFF8E24AA, "Sam Ortiz" to 0xFF00897B).map { (label, color) ->
            val info = ShortcutInfo.Builder(context, label).setShortLabel(label).setIntent(Intent(Intent.ACTION_VIEW)).build()
            AppShortcut(info, label, FakeApps.iconBitmap(label, color, sizePx = 96).asImageBitmap())
        }
        snap("Menu_App", Frame.Component) {
            MenuBackdrop {
                AppMenuCard(
                    app = FakeApps.messages,
                    icon = icons[FakeApps.messages.key],
                    showIcon = true,
                    isFavorite = true,
                    isHidden = false,
                    notifications = FakeNotifications.messages(),
                    shortcuts = shortcuts,
                    canUninstall = true,
                    showHidePlayer = false,
                    onOpenNotification = {},
                    onClearNotifications = {},
                    onShortcut = {},
                    onHidePlayer = {},
                    onToggleFavorite = {},
                    onRename = {},
                    onToggleHidden = {},
                    onAppInfo = {},
                    onUninstall = {},
                )
            }
        }
    }

    /** Calendar, in the Work folder and not removable: Take out and Hide among the buttons, the other folders open. */
    @Test fun appMenuInFolder() {
        val calendar = FakeApps.byLabel("Calendar")
        val folders = listOf(
            FolderChoice("2", "Utilities", listOf("Calculator", "Clock", "Files", "Recorder").map { icons[FakeApps.byLabel(it).key] }),
            FolderChoice("3", "Travel", listOf("Maps", "Weather", "Translate").map { icons[FakeApps.byLabel(it).key] }),
        )
        snap("Menu_AppInFolder", Frame.Component) {
            MenuBackdrop {
                AppMenuCard(
                    app = calendar,
                    icon = icons[calendar.key],
                    showIcon = true,
                    isFavorite = false,
                    isHidden = false,
                    notifications = emptyList(),
                    shortcuts = emptyList(),
                    canUninstall = false,
                    showHidePlayer = false,
                    onOpenNotification = {},
                    onClearNotifications = {},
                    onShortcut = {},
                    onHidePlayer = {},
                    onToggleFavorite = {},
                    onRename = {},
                    onToggleHidden = {},
                    onAppInfo = {},
                    onUninstall = {},
                    folderName = "Work",
                    folders = folders,
                    foldersOpen = true,
                )
            }
        }
    }

    /** The Work folder: rename it, edit its apps, or remove it (its apps stay on home). */
    @Test fun folderMenu() {
        val work = HomeFolder("1", "Work", listOf("Mail", "Calendar", "Docs", "Tasks", "Notes").map { FakeApps.byLabel(it) })
        snap("Menu_Folder", Frame.Component) {
            MenuBackdrop { FolderMenuCard(work, work.apps.take(4).map { icons[it.key] }, onRename = {}, onEditApps = {}, onRemove = {}) }
        }
    }

    // At One UI's largest font size (see LargeFont): button labels shrink to fit their quarter of the card rather than
    // lose their ends, and section names stay on one line.

    /** As Menu_App, at 2x. */
    @Test fun appMenuLargeFont() {
        val context = RuntimeEnvironment.getApplication()
        val shortcuts = listOf("Maya Chen" to 0xFF8E24AA, "Sam Ortiz" to 0xFF00897B).map { (label, color) ->
            val info = ShortcutInfo.Builder(context, label).setShortLabel(label).setIntent(Intent(Intent.ACTION_VIEW)).build()
            AppShortcut(info, label, FakeApps.iconBitmap(label, color, sizePx = 96).asImageBitmap())
        }
        snap("Menu_App_Font2x", Frame.Component) {
            LargeFont {
                MenuBackdrop {
                    AppMenuCard(
                        app = FakeApps.messages,
                        icon = icons[FakeApps.messages.key],
                        showIcon = true,
                        isFavorite = true,
                        isHidden = false,
                        notifications = FakeNotifications.messages(),
                        shortcuts = shortcuts,
                        canUninstall = true,
                        showHidePlayer = false,
                        onOpenNotification = {},
                        onClearNotifications = {},
                        onShortcut = {},
                        onHidePlayer = {},
                        onToggleFavorite = {},
                        onRename = {},
                        onToggleHidden = {},
                        onAppInfo = {},
                        onUninstall = {},
                    )
                }
            }
        }
    }

    /** As Menu_AppInFolder, at 2x: Take out among the buttons, the other folders open. */
    @Test fun appMenuInFolderLargeFont() {
        val calendar = FakeApps.byLabel("Calendar")
        val folders = listOf(
            FolderChoice("2", "Utilities", listOf("Calculator", "Clock", "Files", "Recorder").map { icons[FakeApps.byLabel(it).key] }),
            FolderChoice("3", "Travel", listOf("Maps", "Weather", "Translate").map { icons[FakeApps.byLabel(it).key] }),
        )
        snap("Menu_AppInFolder_Font2x", Frame.Component) {
            LargeFont {
                MenuBackdrop {
                    AppMenuCard(
                        app = calendar,
                        icon = icons[calendar.key],
                        showIcon = true,
                        isFavorite = false,
                        isHidden = false,
                        notifications = emptyList(),
                        shortcuts = emptyList(),
                        canUninstall = false,
                        showHidePlayer = false,
                        onOpenNotification = {},
                        onClearNotifications = {},
                        onShortcut = {},
                        onHidePlayer = {},
                        onToggleFavorite = {},
                        onRename = {},
                        onToggleHidden = {},
                        onAppInfo = {},
                        onUninstall = {},
                        folderName = "Work",
                        folders = folders,
                        foldersOpen = true,
                    )
                }
            }
        }
    }

    /** As Menu_Folder, at 2x. */
    @Test fun folderMenuLargeFont() {
        val work = HomeFolder("1", "Work", listOf("Mail", "Calendar", "Docs", "Tasks", "Notes").map { FakeApps.byLabel(it) })
        snap("Menu_Folder_Font2x", Frame.Component) {
            LargeFont { MenuBackdrop { FolderMenuCard(work, work.apps.take(4).map { icons[it.key] }, onRename = {}, onEditApps = {}, onRemove = {}) } }
        }
    }

    /**
     * The folder pop-up at 2x: Utilities tapped open on home (as Home_FolderOpenLarge, at the medium icon size), its
     * name and options button over its rows.
     */
    @Test fun folderOpenLargeFont() {
        val keys = { labels: List<String> -> labels.map { FakeApps.byLabel(it).key } }
        val settings = LauncherSettings(
            favorites = keys(listOf("Phone", "Messages")) + folderKey("2") + keys(listOf("Music")),
            folders = mapOf("2" to Folder("Utilities", keys(listOf("Calculator", "Clock", "Files", "Recorder", "Translate", "Weather")))),
        )
        snap("Home_FolderOpen_Font2x", afterContent = { onNodeWithText("Utilities").performClick() }) {
            LargeFont { HomeScreen(settings, apps, favorites, icons, items = homeItems(settings, apps.associateBy { it.key })) }
        }
    }
}

/** Home's dim under a pop-up, and the card at a pop-up's width. */
@Composable
private fun MenuBackdrop(content: @Composable () -> Unit) {
    val style = LocalLauncherStyle.current
    Box(Modifier.fillMaxWidth().drawBehind { drawRect(style.scrim, alpha = 0.45f) }.padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(320.dp)) { content() }
    }
}
