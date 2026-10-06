package com.gh00ul.cascade.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.data.WallpaperDim
import com.gh00ul.cascade.data.homeItems
import com.gh00ul.cascade.update.Updater
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.LockService

/**
 * The top page: search, setup while it's unfinished, the home preview (tap it for Appearance), then each page with
 * a one-line summary of what's set there.
 */
@Composable
internal fun MainPage(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    nav: SettingsNav,
) {
    val setup = rememberSetupState()
    val roleRequest = rememberDefaultHomeRequest(setup)
    val context = LocalContext.current
    val update by Updater.state.collectAsStateWithLifecycle()
    val version = rememberVersionName()
    // Counts only installed apps, so they match the pages they open; raw counts until the first load.
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.key } }
    val hiddenCount = if (apps.isEmpty()) settings.hidden.size else settings.hidden.count { it in installed }

    SettingsPage(title = "Settings", onBack = nav.back) {
        SearchPill("Search settings", Icons.Filled.Search, onClick = { nav.go(SettingsScreen.FIND) }, modifier = Modifier.padding(top = 8.dp))
        AnimatedVisibility(!(setup.isDefault && setup.hasAccess)) {
            SetupCard(
                setup = setup,
                onDefault = { LauncherActions.requestDefaultLauncher(context, roleRequest) },
                onAccess = { LauncherActions.openNotificationAccess(context) },
            )
        }
        HomePreview(
            settings = settings,
            favorites = favorites,
            icons = icons,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            onClick = { nav.go(SettingsScreen.LOOK) },
        )
        SettingsGroup {
            SettingRow(
                title = "Home screen",
                summary = listOfNotNull(
                    // Folders count as one each, as on home.
                    count(
                        if (apps.isEmpty()) settings.favorites.size else homeItems(settings, apps.associateBy { it.key }).size,
                        "favorite",
                        "favorites",
                    ),
                    "$hiddenCount hidden".takeIf { hiddenCount > 0 },
                    "previews".takeIf { settings.showNotificationPreviews },
                ).joinToString(" · "),
                icon = Icons.Filled.Home,
                iconColors = categoryColors(0),
                onClick = { nav.go(SettingsScreen.HOME) },
            )
            SettingRow(
                title = "Appearance",
                summary = appearanceSummary(settings),
                icon = SettingsIcons.Palette,
                iconColors = categoryColors(1),
                onClick = { nav.go(SettingsScreen.LOOK) },
            )
            SettingRow(
                title = "Clock & glance",
                summary = clockSummary(settings),
                icon = SettingsIcons.Schedule,
                iconColors = categoryColors(2),
                onClick = { nav.go(SettingsScreen.CLOCK) },
            )
            SettingRow(
                title = "Gestures",
                summary = gesturesSummary(settings),
                icon = SettingsIcons.TouchApp,
                iconColors = categoryColors(0),
                onClick = { nav.go(SettingsScreen.GESTURES) },
            )
            SettingRow(
                title = "Search",
                summary = listOfNotNull(
                    "Web search".takeIf { settings.searchWeb },
                    if (settings.hiddenInSearch) "hidden apps included" else "hidden apps left out",
                    "opens single matches".takeIf { settings.autoLaunchSingleMatch },
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                icon = Icons.Filled.Search,
                iconColors = categoryColors(1),
                onClick = { nav.go(SettingsScreen.SEARCH) },
            )
        }
        SettingsGroup {
            SettingRow(
                title = "Backup & restore",
                summary = "Save your setup to a file, or load one",
                icon = SettingsIcons.BackupRestore,
                iconColors = categoryColors(2),
                onClick = { nav.go(SettingsScreen.BACKUP) },
            )
            SettingRow(
                title = "About Cascade",
                summary = "Version $version · ${updateSummary(update)}",
                icon = Icons.Filled.Info,
                iconColors = categoryColors(0),
                onClick = { nav.go(SettingsScreen.ABOUT) },
            )
        }
    }
}

/** "1 favorite", "6 favorites". */
internal fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

internal fun appearanceSummary(s: LauncherSettings): String = listOfNotNull(
    when (s.textColor) {
        TextColor.AUTO -> "Automatic text"
        TextColor.LIGHT -> "White text"
        TextColor.DARK -> "Dark text"
    },
    if (s.showIcons) "${iconSizeName(s.iconSize).lowercase()} icons" else "no icons",
    "monochrome".takeIf { s.showIcons && s.monochromeIcons },
    "dimmed".takeIf { s.wallpaperDim != WallpaperDim.OFF },
).joinToString(" · ")

internal fun clockSummary(s: LauncherSettings): String {
    val glance = listOfNotNull(
        "weather".takeIf { s.showWeather && s.weatherPlace != null },
        "alarm".takeIf { s.showAlarm },
        "timers".takeIf { s.showTimers },
        "calendar".takeIf { s.showCalendar },
        "battery".takeIf { s.showBattery },
    )
    val style = when (s.clockStyle) {
        ClockStyle.CLASSIC -> "Classic"
        ClockStyle.BOLD -> "Bold"
        ClockStyle.STACKED -> "Stacked"
    }
    return if (glance.isEmpty()) "$style clock" else "$style · ${glance.joinToString(", ")}"
}

internal fun gesturesSummary(s: LauncherSettings): String {
    val down = when (s.swipeDownAction) {
        SwipeDownAction.NOTIFICATIONS -> "notifications"
        SwipeDownAction.QUICK_SETTINGS -> "quick settings"
        SwipeDownAction.SEARCH -> "search"
        SwipeDownAction.OPEN_APP -> "an app"
        SwipeDownAction.NOTHING -> "nothing"
    }
    val tap = when (s.doubleTapAction) {
        DoubleTapAction.NOTHING -> null
        DoubleTapAction.LOCK_SCREEN -> "lock"
        DoubleTapAction.NOTIFICATIONS -> "notifications"
        DoubleTapAction.SEARCH -> "search"
        DoubleTapAction.OPEN_APP -> "an app"
    }
    return listOfNotNull("Swipe down: $down", tap?.let { "double-tap: $it" }).joinToString(" · ")
}

internal fun updateSummary(update: Updater.State): String = when (update) {
    Updater.State.Idle -> "tap to check for updates"
    Updater.State.Checking -> "checking for updates…"
    is Updater.State.UpToDate -> "up to date"
    is Updater.State.Available -> "${update.release.versionName} is available"
    is Updater.State.Downloading -> "downloading ${update.release.versionName}…"
    is Updater.State.Installing -> "installing ${update.release.versionName}…"
    is Updater.State.Failed -> "update didn't finish"
}

/** Whether Cascade is the home app, has notification access and has its lock service on; read again on each return. */
@Stable
internal class SetupState(context: Context) {
    var isDefault by mutableStateOf(LauncherActions.isDefaultLauncher(context))
    var hasAccess by mutableStateOf(LauncherActions.hasNotificationAccess(context))
    var lockEnabled by mutableStateOf(LockService.isEnabled(context))

    fun refresh(context: Context) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        hasAccess = LauncherActions.hasNotificationAccess(context)
        lockEnabled = LockService.isEnabled(context)
    }
}

@Composable
internal fun rememberSetupState(): SetupState {
    val context = LocalContext.current
    val state = remember { SetupState(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh(context) }
    return state
}

/** Asks for the home role; Android skips the dialog after repeated refusals, so fall back to the home-app setting. */
@Composable
internal fun rememberDefaultHomeRequest(setup: SetupState) = LocalContext.current.let { context ->
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setup.refresh(context)
        if (!setup.isDefault) LauncherActions.openHomeSettings(context)
    }
}

@Composable
internal fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
}

/** What's left to set up, each with its button. It folds away once both are done. */
@Composable
private fun SetupCard(setup: SetupState, onDefault: () -> Unit, onAccess: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .background(colors.tertiaryContainer)
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Text("Finish setting up", style = MaterialTheme.typography.titleMedium, color = colors.onTertiaryContainer)
        if (!setup.isDefault) SetupStep("Make Cascade your home app", "So the Home button brings you here", "Set", onDefault)
        if (!setup.hasAccess) SetupStep("Allow notification access", "For dots, previews and the music player", "Allow", onAccess)
    }
}

@Composable
private fun SetupStep(title: String, body: String, action: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onTertiaryContainer)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
        ) { Text(action) }
    }
}

/** Settings search: type, and every setting whose words start with what you typed is listed with its page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindPage(nav: SettingsNav) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { findSettings(query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = PageColor,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search settings") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(nav.open) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PageColor, scrolledContainerColor = PageColor),
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = padding.calculateStartPadding(LocalLayoutDirection.current) + 16.dp + wideMargin(),
                end = padding.calculateEndPadding(LocalLayoutDirection.current) + 16.dp + wideMargin(),
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            when {
                query.isBlank() -> item { ListText("Find a setting by name, like “icon size”, “weather” or “lock”.") }
                results.isEmpty() -> item { ListText("No settings match “${query.trim()}”.") }
                else -> items(results.size) { i ->
                    val entry = results[i]
                    SettingRow(
                        title = entry.title,
                        summary = breadcrumb(entry.screen),
                        onClick = { nav.open(entry) },
                        modifier = Modifier.clip(groupItemShape(i, results.size)),
                    )
                }
            }
        }
    }
}

/** "Home screen › Favorites": the pages leading to [screen], below the top page. */
private fun breadcrumb(screen: SettingsScreen): String =
    generateSequence(screen) { it.parent }.takeWhile { it != SettingsScreen.MAIN }.toList().reversed().joinToString(" › ") { it.title }
