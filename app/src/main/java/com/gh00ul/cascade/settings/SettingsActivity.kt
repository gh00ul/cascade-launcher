package com.gh00ul.cascade.settings

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
private fun SettingsApp(start: SettingsScreen, onExit: () -> Unit) {
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
            SettingsScreen.MAIN -> MainSettings(padding, settings, onNavigate = { screen = it })
            SettingsScreen.FAVORITES -> FavoritesSettings(padding, settings, apps, icons, onAdd = { screen = SettingsScreen.ADD_FAVORITE })
            SettingsScreen.ADD_FAVORITE -> AddFavorite(padding, settings, apps, icons, onDone = { screen = SettingsScreen.FAVORITES })
            SettingsScreen.HIDDEN -> HiddenApps(padding, settings, apps, icons)
        }
    }
}

@Composable
private fun MainSettings(padding: PaddingValues, settings: LauncherSettings, onNavigate: (SettingsScreen) -> Unit) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
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

    LazyColumn(contentPadding = padding) {
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
                summary = if (hasAccess) "Notification dots and previews are on" else "Needed for notification dots and previews",
                onClick = { LauncherActions.openNotificationAccess(context) },
                trailing = { StatusIcon(hasAccess) },
            )
        }

        item { Header("Home screen") }
        item {
            SettingRow(
                title = "Favorites",
                summary = "${settings.favorites.size} apps · add, remove or reorder",
                onClick = { onNavigate(SettingsScreen.FAVORITES) },
            )
        }
        item {
            ChoiceRow(
                title = "Swipe down on home",
                options = listOf("Notifications", "Search"),
                selected = settings.swipeDownAction.ordinal,
            ) { i -> prefs.update { it.copy(swipeDownAction = SwipeDownAction.entries[i]) } }
        }
        item {
            SwitchRow("Music controls", "Show what's playing above your favorites", settings.showMediaControls) { on ->
                prefs.update { it.copy(showMediaControls = on) }
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
            SwitchRow("App icons", null, settings.showIcons) { on -> prefs.update { it.copy(showIcons = on) } }
        }
        item {
            SwitchRow(
                "Monochrome icons",
                "Uses themed icons where apps provide them; other icons turn grayscale",
                settings.monochromeIcons,
                enabled = settings.showIcons,
            ) { on -> prefs.update { it.copy(monochromeIcons = on) } }
        }
        item {
            SwitchRow("Notification previews", "Show the latest notification under each favorite", settings.showNotificationPreviews) { on ->
                prefs.update { it.copy(showNotificationPreviews = on) }
            }
        }
        item { SettingRow("Wallpaper", "Change your wallpaper", onClick = { LauncherActions.openWallpaperPicker(context) }) }

        item { Header("Apps") }
        item {
            SettingRow(
                title = "Hidden apps",
                summary = if (settings.hidden.isEmpty()) "None. Long-press an app to hide it." else "${settings.hidden.size} hidden",
                onClick = { onNavigate(SettingsScreen.HIDDEN) },
            )
        }

        item { Header("About") }
        item { SettingRow("Cascade $version", "Free and open source under the MIT License") }
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
        // Rebuilt from the installed favorites, which also drops entries for uninstalled apps.
        val keys = favorites.map { it.key }.toMutableList()
        keys.add(to, keys.removeAt(from))
        prefs.setFavorites(keys)
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
                        IconButton(onClick = { prefs.setFavorites(favorites.map { it.key } - app.key) }) {
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
    LazyColumn(contentPadding = padding) {
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
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, summary: String? = null, onSelect: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                if (summary != null) Text(summary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = i == selected,
                            onClick = { onSelect(i) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
