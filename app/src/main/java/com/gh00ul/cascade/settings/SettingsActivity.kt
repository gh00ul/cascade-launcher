package com.gh00ul.cascade.settings

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.window.SplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.homeItems
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.gh00ul.cascade.ui.theme.Motion

/** Settings pages. Their names are what [SettingsActivity.open] passes, so other screens can open one directly. */
enum class SettingsScreen(val title: String) {
    MAIN("Settings"),
    HOME("Home screen"),
    FAVORITES("Favorites"),
    ADD_FAVORITE("Add a favorite"),
    /** One folder's name and apps; which folder is [SettingsNav.folder]. */
    FOLDER("Folder"),
    ADD_TO_FOLDER("Add apps"),
    HIDDEN("Hidden apps"),
    RENAMED("Renamed apps"),
    WIDGETS("Widgets"),
    LOOK("Appearance"),
    CLOCK("Clock & glance"),
    GESTURES("Gestures"),
    SEARCH("Search"),
    BACKUP("Backup & restore"),
    ABOUT("About Cascade"),
    FIND("Search settings");

    /** The page Back returns to from here on a page opened from search. */
    val parent: SettingsScreen?
        get() = when (this) {
            MAIN -> null
            FAVORITES, HIDDEN, RENAMED, WIDGETS -> HOME
            ADD_FAVORITE, FOLDER -> FAVORITES
            ADD_TO_FOLDER -> FOLDER
            else -> MAIN
        }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_SCREEN)
            ?.let { name -> SettingsScreen.entries.firstOrNull { it.name == name } }
            ?: SettingsScreen.MAIN
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        setContent { LauncherTheme(dark = isSystemInDarkTheme()) { SettingsApp(start, onExit = ::finish, startFolder = folder) } }
    }

    // An app widget's own setup screen reports here when it's added from Settings, so a cancelled setup removes it.
    @Deprecated("Deprecated in ComponentActivity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!WidgetHost.onActivityResult(this, requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_FOLDER = "folder"

        /** Opens Settings on [screen]; the folder pages need the [folder]'s id. */
        fun open(context: Context, screen: SettingsScreen = SettingsScreen.MAIN, folder: String? = null) {
            // A new task started from home gets the splash screen with Cascade's icon, as if an app were starting. The
            // plain one is just the theme's window background, which is the pages' own color.
            val options = if (Build.VERSION.SDK_INT >= 33) {
                ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR).toBundle()
            } else {
                null
            }
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SCREEN, screen.name)
                    .putExtra(EXTRA_FOLDER, folder)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                options,
            )
        }
    }
}

/**
 * Moving between pages: [go] deeper, [back] up a level, or [open] a search result's page and highlight its row.
 * [openFolder] goes to a folder's page; [folder] is the folder the folder pages show.
 */
internal class SettingsNav(
    val go: (SettingsScreen) -> Unit,
    val back: () -> Unit,
    val open: (SettingEntry) -> Unit,
    val folder: String? = null,
    val openFolder: (String) -> Unit = {},
)

// Saves the state itself, not its value: restoring nothing then means "start over" (rememberSaveable runs init), where
// a stateSaver would restore a state holding nothing.
private val StackSaver = listSaver<MutableState<List<SettingsScreen>>, String>(
    save = { stack -> stack.value.map { it.name } },
    // No page left (renamed since it was saved): the stack starts over instead of being empty.
    restore = { names ->
        names.mapNotNull { name -> SettingsScreen.entries.firstOrNull { it.name == name } }.takeIf { it.isNotEmpty() }?.let { mutableStateOf(it) }
    },
)

/**
 * The pages as a stack: each slides in over the one it was opened from and back out on Back. Opened straight onto a
 * page (from the home screen's menu), Back from that page leaves Settings.
 */
@Composable
internal fun SettingsApp(start: SettingsScreen, onExit: () -> Unit, startFolder: String? = null) {
    val launcher = LocalContext.current.launcher
    val settings by launcher.prefs.settings.collectAsStateWithLifecycle()
    val apps by launcher.repository.apps.collectAsStateWithLifecycle()
    val icons by launcher.repository.icons.collectAsStateWithLifecycle()
    val favorites = remember(apps, settings.favorites) {
        val byKey = apps.associateBy { it.key }
        settings.favorites.mapNotNull { byKey[it] }
    }
    // Home's rows: apps and folders, for the favorites page.
    val homeItems = remember(apps, settings.favorites, settings.folders) { homeItems(settings, apps.associateBy { it.key }) }
    var stack by rememberSaveable(saver = StackSaver) { mutableStateOf(listOf(start)) }
    // The folder the folder pages are about; one at a time, so it needn't be part of the stack.
    var folder by rememberSaveable { mutableStateOf(startFolder) }
    var forward by remember { mutableStateOf(true) }
    // The row a search result points at, on its page; cleared by the next move.
    var highlight by remember { mutableStateOf<Pair<SettingsScreen, String>?>(null) }

    val nav = SettingsNav(
        go = { screen ->
            forward = true
            highlight = null
            stack = stack + screen
        },
        back = {
            highlight = null
            if (stack.size <= 1) {
                onExit()
            } else {
                forward = false
                stack = stack.dropLast(1)
            }
        },
        open = { entry ->
            forward = true
            highlight = entry.key?.let { entry.screen to it }
            // The page's own path from the top, so Back climbs through its parents rather than back into search.
            stack = generateSequence(entry.screen) { it.parent }.toList().reversed()
        },
        folder = folder,
        openFolder = { id ->
            forward = true
            highlight = null
            folder = id
            stack = stack + SettingsScreen.FOLDER
        },
    )
    BackHandler(enabled = stack.size > 1, onBack = nav.back)

    // The pages' color behind them too: mid cross-fade both are partly see-through, and the window would show.
    AnimatedContent(
        targetState = stack.last(),
        modifier = Modifier.fillMaxSize().background(PageColor),
        transitionSpec = { pageTransition(forward) },
        label = "settingsPage",
    ) { screen ->
        CompositionLocalProvider(LocalHighlight provides highlight?.takeIf { it.first == screen }?.second) {
            when (screen) {
                SettingsScreen.MAIN -> MainPage(settings, apps, favorites, icons, nav)
                SettingsScreen.HOME -> HomeScreenPage(settings, apps, favorites, icons, nav)
                SettingsScreen.FAVORITES -> FavoritesPage(settings, apps, homeItems, icons, nav)
                SettingsScreen.ADD_FAVORITE -> AddFavoritePage(settings, apps, icons, nav)
                SettingsScreen.FOLDER -> FolderPage(settings, apps, icons, nav)
                SettingsScreen.ADD_TO_FOLDER -> AddToFolderPage(settings, apps, icons, nav)
                SettingsScreen.HIDDEN -> HiddenAppsPage(settings, apps, icons, nav)
                SettingsScreen.RENAMED -> RenamedAppsPage(settings, apps, icons, nav)
                SettingsScreen.WIDGETS -> WidgetsPage(settings, nav)
                SettingsScreen.LOOK -> AppearancePage(settings, favorites, icons, nav)
                SettingsScreen.CLOCK -> ClockPage(settings, favorites, icons, nav)
                SettingsScreen.GESTURES -> GesturesPage(settings, apps, icons, nav)
                SettingsScreen.SEARCH -> SearchPage(settings, nav)
                SettingsScreen.BACKUP -> BackupPage(nav)
                SettingsScreen.ABOUT -> AboutPage(settings, nav)
                SettingsScreen.FIND -> FindPage(nav)
            }
        }
    }
}

/** Forward, the new page slides in a little from the end as the old one drifts back and fades; Back reverses it. */
private fun pageTransition(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (slideInHorizontally(tween(Motion.SCREEN, easing = Motion.Decelerate)) { dir * it / 5 } + fadeIn(tween(Motion.ENTER, delayMillis = Motion.EXIT / 2))) togetherWith
        (slideOutHorizontally(tween(Motion.SCREEN, easing = Motion.Decelerate)) { -dir * it / 10 } + fadeOut(tween(Motion.EXIT)))
}
