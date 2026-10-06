package com.gh00ul.cascade.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.gh00ul.cascade.ui.theme.Motion

/** Settings pages. Their names are what [SettingsActivity.open] passes, so other screens can open one directly. */
enum class SettingsScreen(val title: String) {
    MAIN("Settings"),
    HOME("Home screen"),
    FAVORITES("Favorites"),
    ADD_FAVORITE("Add a favorite"),
    HIDDEN("Hidden apps"),
    RENAMED("Renamed apps"),
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
            FAVORITES, HIDDEN, RENAMED -> HOME
            ADD_FAVORITE -> FAVORITES
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
        setContent { LauncherTheme(dark = isSystemInDarkTheme()) { SettingsApp(start, onExit = ::finish) } }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"

        fun open(context: Context, screen: SettingsScreen = SettingsScreen.MAIN) {
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .putExtra(EXTRA_SCREEN, screen.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }
}

/** Moving between pages: [go] deeper, [back] up a level, or [open] a search result's page and highlight its row. */
internal class SettingsNav(
    val go: (SettingsScreen) -> Unit,
    val back: () -> Unit,
    val open: (SettingEntry) -> Unit,
)

private val StackSaver = listSaver<List<SettingsScreen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.mapNotNull { name -> SettingsScreen.entries.firstOrNull { it.name == name } } },
)

/**
 * The pages as a stack: each slides in over the one it was opened from and back out on Back. Opened straight onto a
 * page (from the home screen's menu), Back from that page leaves Settings.
 */
@Composable
internal fun SettingsApp(start: SettingsScreen, onExit: () -> Unit) {
    val launcher = LocalContext.current.launcher
    val settings by launcher.prefs.settings.collectAsStateWithLifecycle()
    val apps by launcher.repository.apps.collectAsStateWithLifecycle()
    val icons by launcher.repository.icons.collectAsStateWithLifecycle()
    val favorites = remember(apps, settings.favorites) {
        val byKey = apps.associateBy { it.key }
        settings.favorites.mapNotNull { byKey[it] }
    }
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf(start)) }
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
    )
    BackHandler(enabled = stack.size > 1, onBack = nav.back)

    AnimatedContent(targetState = stack.last(), transitionSpec = { pageTransition(forward) }, label = "settingsPage") { screen ->
        CompositionLocalProvider(LocalHighlight provides highlight?.takeIf { it.first == screen }?.second) {
            when (screen) {
                SettingsScreen.MAIN -> MainPage(settings, apps, favorites, icons, nav)
                SettingsScreen.HOME -> HomeScreenPage(settings, apps, favorites, icons, nav)
                SettingsScreen.FAVORITES -> FavoritesPage(settings, apps, favorites, icons, nav)
                SettingsScreen.ADD_FAVORITE -> AddFavoritePage(settings, apps, icons, nav)
                SettingsScreen.HIDDEN -> HiddenAppsPage(settings, apps, icons, nav)
                SettingsScreen.RENAMED -> RenamedAppsPage(settings, apps, icons, nav)
                SettingsScreen.LOOK -> AppearancePage(settings, favorites, icons, nav)
                SettingsScreen.CLOCK -> ClockPage(settings, favorites, icons, nav)
                SettingsScreen.GESTURES -> GesturesPage(settings, nav)
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
