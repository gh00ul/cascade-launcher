package com.gh00ul.cascade.settings

import com.gh00ul.cascade.update.Updater
import androidx.core.app.ActivityCompat
import android.app.Activity
import com.gh00ul.cascade.util.hasCalendarAccess
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.ClockStyle
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.data.searchApps
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.gh00ul.cascade.util.LauncherActions

enum class SettingsScreen(val title: String) {
    MAIN("Cascade settings"),
    FAVORITES("Favorites"),
    ADD_FAVORITE("Add a favorite"),
    HIDDEN("Hidden apps"),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsApp(start: SettingsScreen, onExit: () -> Unit) {
    val launcher = LocalContext.current.launcher
    val settings by launcher.prefs.settings.collectAsStateWithLifecycle()
    val apps by launcher.repository.apps.collectAsStateWithLifecycle()
    val icons by launcher.repository.icons.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(start) }

    val back: () -> Unit = {
        val parent = when (screen) {
            SettingsScreen.MAIN -> null
            SettingsScreen.ADD_FAVORITE -> SettingsScreen.FAVORITES
            else -> SettingsScreen.MAIN
        }
        if (parent == null || screen == start) {
            onExit()
        } else {
            screen = parent
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        when (screen) {
            SettingsScreen.MAIN -> MainSettings(padding, settings, apps, onNavigate = { screen = it })
            SettingsScreen.FAVORITES -> FavoritesSettings(padding, settings, apps, icons, onAdd = { screen = SettingsScreen.ADD_FAVORITE })
            SettingsScreen.ADD_FAVORITE -> AddFavorite(padding, settings, apps, icons, onDone = { screen = SettingsScreen.FAVORITES })
            SettingsScreen.HIDDEN -> HiddenApps(padding, settings, apps, icons)
        }
    }
}

@Composable
private fun MainSettings(padding: PaddingValues, settings: LauncherSettings, apps: List<AppEntry>, onNavigate: (SettingsScreen) -> Unit) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    // Count only installed apps so the summaries match the screens they open; raw counts until the first load.
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.key } }
    val favoriteCount = if (apps.isEmpty()) settings.favorites.size else settings.favorites.count { it in installed }
    val hiddenCount = if (apps.isEmpty()) settings.hidden.size else settings.hidden.count { it in installed }
    var isDefault by remember { mutableStateOf(LauncherActions.isDefaultLauncher(context)) }
    var hasAccess by remember { mutableStateOf(LauncherActions.hasNotificationAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        hasAccess = LauncherActions.hasNotificationAccess(context)
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        if (!isDefault) LauncherActions.openHomeSettings(context)
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val update by Updater.state.collectAsStateWithLifecycle()
    var calendarAllowed by remember { mutableStateOf(hasCalendarAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendarAllowed = hasCalendarAccess(context) }
    // After "Don't allow" twice, Android stops asking and the request fails at once; send the user to App info then.
    var calendarBlocked by remember { mutableStateOf(false) }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
        val activity = context as? Activity
        calendarBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
        prefs.update { it.copy(showCalendar = granted) }
    }

    // Setup leads while something still needs doing; once both are granted it waits above About. Placement is
    // decided when the screen opens so a grant doesn't yank the row away; its summary and icon still update live.
    val setupFirst = remember { !(isDefault && hasAccess) }
    fun LazyListScope.setup() {
        item { Header("Setup") }
        item {
            SettingRow(
                title = "Default home app",
                summary = if (isDefault) "Cascade is your home screen" else "Tap to make Cascade your home screen",
                onClick = { LauncherActions.requestDefaultLauncher(context, roleRequest) },
                trailing = { StatusIcon(isDefault) },
            )
        }
        item {
            SettingRow(
                title = "Notification access",
                summary = if (hasAccess) "Notification dots and previews are on" else "Needed for dots, previews and music controls",
                onClick = { LauncherActions.openNotificationAccess(context) },
                trailing = { StatusIcon(hasAccess) },
            )
        }
    }

    LazyColumn(contentPadding = padding) {
        if (setupFirst) setup()

        item { Header("Home screen") }
        item {
            SettingRow(
                title = "Favorites",
                summary = "$favoriteCount apps · add, remove or reorder",
                onClick = { onNavigate(SettingsScreen.FAVORITES) },
            )
        }
        item {
            SettingRow(
                title = "Hidden apps",
                summary = if (hiddenCount == 0) "None. Long-press an app to hide it." else "$hiddenCount hidden · still found in search",
                onClick = { onNavigate(SettingsScreen.HIDDEN) },
            )
        }
        item {
            SwitchRow("Notification previews", "Show the latest notification under each favorite", settings.showNotificationPreviews) { on ->
                prefs.update { it.copy(showNotificationPreviews = on) }
            }
        }
        item {
            SwitchRow("Music controls", "Turn the playing app's row into a player", settings.showMediaControls) { on ->
                prefs.update { it.copy(showMediaControls = on) }
            }
        }
        item {
            ChoiceRow(
                title = "Swipe down on home",
                options = listOf("Notifications", "Search"),
                selected = settings.swipeDownAction.ordinal,
            ) { i -> prefs.update { it.copy(swipeDownAction = SwipeDownAction.entries[i]) } }
        }

        item { Header("Clock") }
        item {
            ChoiceRow(
                title = "Clock style",
                options = listOf("Classic", "Bold", "Stacked"),
                selected = settings.clockStyle.ordinal,
            ) { i -> prefs.update { it.copy(clockStyle = ClockStyle.entries[i]) } }
        }
        item {
            SwitchRow(
                "Next calendar event",
                when {
                    calendarBlocked -> "Calendar access is blocked. Tap to allow it in App info"
                    settings.showCalendar && !calendarAllowed -> "Calendar access is off; allow it in App info"
                    else -> "Show what's coming up under the clock"
                },
                settings.showCalendar && calendarAllowed,
            ) { on ->
                if (on && !hasCalendarAccess(context) && calendarBlocked) {
                    LauncherActions.openOwnAppInfo(context)
                } else if (on && !hasCalendarAccess(context)) {
                    calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                } else {
                    prefs.update { it.copy(showCalendar = on) }
                }
            }
        }
        item {
            SwitchRow("Charging and low battery", "Time to full, and a warning when low", settings.showBattery) { on ->
                prefs.update { it.copy(showBattery = on) }
            }
        }

        item { Header("Appearance") }
        item {
            ChoiceRow(
                title = "Text color",
                summary = "Automatic switches to dark text on light wallpapers",
                options = listOf("Automatic", "White", "Dark"),
                selected = settings.textColor.ordinal,
            ) { i -> prefs.update { it.copy(textColor = TextColor.entries[i]) } }
        }
        item {
            SwitchRow("App icons", "Show an icon next to each app name", settings.showIcons) { on -> prefs.update { it.copy(showIcons = on) } }
        }
        item {
            ChoiceRow(
                title = "Icon size",
                summary = if (settings.showIcons) null else "Turn on app icons to use this",
                options = listOf("Small", "Medium", "Large", "XL"),
                selected = settings.iconSize.ordinal,
                enabled = settings.showIcons,
            ) { i -> prefs.update { it.copy(iconSize = IconSize.entries[i]) } }
        }
        item {
            SwitchRow(
                "Monochrome icons",
                "Themed icons if available, grayscale otherwise",
                settings.monochromeIcons,
                enabled = settings.showIcons,
            ) { on -> prefs.update { it.copy(monochromeIcons = on) } }
        }
        item { SettingRow("Wallpaper", "Opens the wallpaper picker", onClick = { LauncherActions.openWallpaperPicker(context) }) }

        if (!setupFirst) setup()

        item { Header("About") }
        item {
            val release = Updater.current()
            SettingRow(
                title = "Cascade $version",
                summary = when (val u = update) {
                    Updater.State.Idle -> "Tap to check for updates"
                    Updater.State.Checking -> "Checking for updates…"
                    is Updater.State.UpToDate -> "Up to date. Tap to check again"
                    is Updater.State.Available -> "Version ${u.release.versionName} is available. Tap to update"
                    is Updater.State.Downloading -> "Downloading ${u.release.versionName}…" + (u.percent?.let { " $it%" } ?: "")
                    is Updater.State.Installing -> "Installing ${u.release.versionName}…"
                    is Updater.State.Failed -> u.message
                },
                onClick = {
                    val u = update
                    if (release != null && (u is Updater.State.Available || u is Updater.State.Failed)) Updater.install(context, release)
                    else if (u !is Updater.State.Downloading && u !is Updater.State.Installing) Updater.check(context, force = true)
                },
            )
        }
        item {
            SwitchRow("Check for updates automatically", "Checks GitHub when home opens", settings.autoUpdateCheck) { on ->
                prefs.update { it.copy(autoUpdateCheck = on) }
            }
        }
        item { SettingRow("Free and open source", "MIT License · github.com/gh00ul/cascade-launcher") }
    }
}

@Composable
private fun FavoritesSettings(
    padding: PaddingValues,
    settings: LauncherSettings,
    apps: List<AppEntry>,
    icons: Map<String, IconImage>,
    onAdd: () -> Unit,
) {
    val prefs = LocalContext.current.launcher.prefs
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val favorites = settings.favorites.mapNotNull { byKey[it] }

    fun move(from: Int, to: Int) {
        // Swaps the two keys in the stored list, so favorites of apps that are missing for now
        // (disabled, on an unmounted SD card, still restoring) keep their place.
        val a = favorites[from].key
        val b = favorites[to].key
        prefs.update { s ->
            val i = s.favorites.indexOf(a)
            val j = s.favorites.indexOf(b)
            if (i < 0 || j < 0) s else s.copy(favorites = s.favorites.toMutableList().also { it[i] = b; it[j] = a })
        }
    }

    LazyColumn(contentPadding = padding) {
        if (favorites.isEmpty()) {
            item { EmptyText("No favorites yet. They show up on your home screen, under the clock.") }
        }
        itemsIndexed(favorites, key = { _, app -> app.key }) { index, app ->
            ListItem(
                headlineContent = { Text(app.label) },
                leadingContent = { AppIcon(icons[app.key], 36.dp) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { move(index, index + 1) }, enabled = index < favorites.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(onClick = { prefs.update { it.copy(favorites = it.favorites - app.key) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Add a favorite") },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onAdd),
            )
        }
    }
}

@Composable
private fun AddFavorite(
    padding: PaddingValues,
    settings: LauncherSettings,
    apps: List<AppEntry>,
    icons: Map<String, IconImage>,
    onDone: () -> Unit,
) {
    val prefs = LocalContext.current.launcher.prefs
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(apps, settings.favorites, query) {
        (if (query.isBlank()) apps else searchApps(apps, query)).filter { it.key !in settings.favorites }
    }
    // The keyboard is up while searching; keep the end of the list scrollable above it.
    LazyColumn(contentPadding = padding, modifier = Modifier.consumeWindowInsets(padding).imePadding()) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(candidates, key = { it.key }) { app ->
            ListItem(
                headlineContent = { Text(app.label) },
                leadingContent = { AppIcon(icons[app.key], 36.dp) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable {
                    prefs.toggleFavorite(app.key)
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun HiddenApps(padding: PaddingValues, settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>) {
    val prefs = LocalContext.current.launcher.prefs
    val hidden = apps.filter { it.key in settings.hidden }
    LazyColumn(contentPadding = padding) {
        if (hidden.isEmpty()) {
            item { EmptyText("No hidden apps. Long-press an app and choose “Hide from app list”. Hidden apps still appear in search.") }
        }
        items(hidden, key = { it.key }) { app ->
            ListItem(
                headlineContent = { Text(app.label) },
                leadingContent = { AppIcon(icons[app.key], 36.dp) },
                trailingContent = { TextButton(onClick = { prefs.setHidden(app.key, false) }) { Text("Show") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun StatusIcon(ok: Boolean) {
    if (ok) {
        Icon(Icons.Filled.CheckCircle, contentDescription = "On", tint = MaterialTheme.colorScheme.primary)
    } else {
        Icon(Icons.Filled.Warning, contentDescription = "Off", tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SettingRow(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack reads the title and the state as one item.
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: Int,
    summary: String? = null,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    ListItem(
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                if (summary != null) Text(summary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
