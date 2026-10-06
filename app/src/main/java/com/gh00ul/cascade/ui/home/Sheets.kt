package com.gh00ul.cascade.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.addToFolder
import com.gh00ul.cascade.data.dissolveFolder
import com.gh00ul.cascade.data.folderLabel
import com.gh00ul.cascade.data.homeFolders
import com.gh00ul.cascade.data.removeFromFolder
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.AppShortcut
import com.gh00ul.cascade.util.LauncherActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder an app can go to, as [AppActionsContent] lists it: its id, name, and the icons its own icon shows. */
@Immutable
class FolderChoice(val id: String, val label: String, val icons: List<IconImage?>)

/** The home screen's folders as choices, in home order, with the icons of up to four of their apps that have one. */
internal fun folderChoices(settings: LauncherSettings, icons: Map<String, IconImage>): List<FolderChoice> =
    settings.homeFolders().map { (id, folder) -> FolderChoice(id, folderLabel(folder.name), folder.apps.mapNotNull { icons[it] }.take(4)) }

/**
 * Long-press menu for an app: its notifications, shortcuts, and favorite / folder / rename / hide / info / uninstall.
 * [folders] are the home screen's folders and [folderId] the one the app is in; [onNewFolder] asks for a new one's
 * name (the dialog is the caller's, as it outlives this sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionsSheet(
    app: AppEntry,
    icon: IconImage?,
    isFavorite: Boolean,
    isHidden: Boolean,
    notifications: List<AppNotification>,
    onOpenNotification: (AppNotification) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
    onHidePlayer: (() -> Unit)? = null,
    folders: List<FolderChoice> = emptyList(),
    folderId: String? = null,
    onNewFolder: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val shortcuts by produceState(emptyList<AppShortcut>(), app.key) {
        value = withContext(Dispatchers.IO) { LauncherActions.loadShortcuts(context, app) }
    }
    val canUninstall = remember(app.key) { !app.isWork && !LauncherActions.isSystemApp(context, app) }

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AppActionsContent(
            app = app,
            icon = icon,
            isFavorite = isFavorite,
            isHidden = isHidden,
            notifications = notifications,
            shortcuts = shortcuts,
            canUninstall = canUninstall,
            showHidePlayer = onHidePlayer != null,
            onOpenNotification = { n -> closeThen { onOpenNotification(n) } },
            onClearNotifications = { closeThen { NotificationStore.dismissAll(notifications) } },
            onShortcut = { shortcut -> closeThen { LauncherActions.launchShortcut(context, shortcut) } },
            onHidePlayer = { if (onHidePlayer != null) closeThen(onHidePlayer) },
            onToggleFavorite = { closeThen { prefs.toggleFavorite(app.key) } },
            onRename = { closeThen(onRename) },
            onToggleHidden = { closeThen { prefs.setHidden(app.key, !isHidden) } },
            onAppInfo = { closeThen { LauncherActions.openAppInfo(context, app) } },
            onUninstall = { closeThen { LauncherActions.uninstall(context, app) } },
            folderName = folders.firstOrNull { it.id == folderId }?.label,
            folders = folders.filter { it.id != folderId },
            onAddToFolder = { id -> closeThen { prefs.update { it.addToFolder(app.key, id) } } },
            onNewFolder = { closeThen(onNewFolder) },
            onRemoveFromFolder = { closeThen { prefs.update { it.removeFromFolder(app.key) } } },
        )
    }
}

/**
 * What [AppActionsSheet] shows, with its [shortcuts] already loaded and every action passed in, so it can be drawn
 * without the sheet or the app. All content starts on the sheet's 24dp edge, and shortcut icons and action glyphs
 * share one 28dp leading column, so their labels line up.
 *
 * An app in a folder ([folderName]) can leave it or move to another; any other app can go into one. The other
 * [folders] open under that action, with "New folder" last; with none yet, the action goes straight to [onNewFolder].
 * [foldersOpen] starts them open (screenshots).
 */
@Composable
internal fun AppActionsContent(
    app: AppEntry,
    icon: IconImage?,
    isFavorite: Boolean,
    isHidden: Boolean,
    notifications: List<AppNotification>,
    shortcuts: List<AppShortcut>,
    canUninstall: Boolean,
    showHidePlayer: Boolean,
    onOpenNotification: (AppNotification) -> Unit,
    onClearNotifications: () -> Unit,
    onShortcut: (AppShortcut) -> Unit,
    onHidePlayer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    folderName: String? = null,
    folders: List<FolderChoice> = emptyList(),
    onAddToFolder: (String) -> Unit = {},
    onNewFolder: () -> Unit = {},
    onRemoveFromFolder: () -> Unit = {},
    foldersOpen: Boolean = false,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, 44.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (notifications.isNotEmpty()) {
            SheetLabel("Notifications")
            for (n in notifications.take(5)) {
                SheetRow(
                    onClick = { onOpenNotification(n) },
                    headline = { Text(n.title.ifEmpty { app.label }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supporting = if (n.text.isNotEmpty()) {
                        { Text(n.text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    } else null,
                )
            }
            if (notifications.any { it.clearable }) {
                // TextButton pads its text 12dp more, which puts it on the 24dp edge too.
                TextButton(onClick = onClearNotifications, modifier = Modifier.padding(start = 12.dp)) { Text("Clear notifications") }
            }
        }

        // Shortcuts load after the sheet opens, so they grow it instead of jumping in. The last list stays for the exit.
        val lastShortcuts = remember { Latest<List<AppShortcut>>() }.also { if (shortcuts.isNotEmpty()) it.value = shortcuts }
        AnimatedVisibility(shortcuts.isNotEmpty(), enter = Motion.ExpandDown, exit = Motion.CollapseUp) {
            Column {
                SheetLabel("Shortcuts")
                for (shortcut in lastShortcuts.value.orEmpty()) {
                    SheetRow(
                        onClick = { onShortcut(shortcut) },
                        headline = { Text(shortcut.label) },
                        leading = { AppIcon(shortcut.icon, 28.dp) },
                    )
                }
            }
        }

        if (showHidePlayer) {
            SheetAction(Icons.Outlined.Close, "Hide player", onHidePlayer)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // An app is a favorite of its own or in a folder, never both: in one, it can only leave it or move on.
        var pickFolder by rememberSaveable { mutableStateOf(foldersOpen) }
        if (folderName != null) {
            SheetAction(FolderGlyphs.FolderMinus, "Remove from $folderName", onRemoveFromFolder)
        } else {
            SheetAction(
                if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                onToggleFavorite,
            )
        }
        SheetAction(
            if (folderName != null) FolderGlyphs.MoveToFolder else FolderGlyphs.NewFolder,
            if (folderName != null) "Move to another folder" else "Add to folder",
            onClick = { if (folders.isEmpty()) onNewFolder() else pickFolder = !pickFolder },
        )
        AnimatedVisibility(pickFolder && folders.isNotEmpty(), enter = Motion.ExpandDown, exit = Motion.CollapseUp) {
            // One step in from the actions, so they read as that action's choices.
            Column(Modifier.padding(start = 16.dp)) {
                for (folder in folders) {
                    SheetRow(
                        onClick = { onAddToFolder(folder.id) },
                        headline = { Text(folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leading = { FolderIcon(folder.icons, 28.dp, MaterialTheme.colorScheme.surfaceContainerHighest) },
                    )
                }
                SheetRow(
                    onClick = onNewFolder,
                    headline = { Text("New folder") },
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(24.dp)) },
                )
            }
        }
        SheetAction(Icons.Outlined.Edit, "Rename", onRename)
        SheetAction(
            if (isHidden) ExtraIcons.Visibility else ExtraIcons.VisibilityOff,
            if (isHidden) "Show in app list" else "Hide from app list",
            onToggleHidden,
        )
        SheetAction(Icons.Outlined.Info, "App info", onAppInfo)
        if (canUninstall) {
            SheetAction(Icons.Outlined.Delete, "Uninstall", onUninstall)
        }
    }
}

/** Long-press on empty home screen space. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeMenuSheet(onDismiss: () -> Unit, onWidgets: () -> Unit = {}) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        HomeMenuContent(
            onWallpaper = { closeThen { LauncherActions.openWallpaperPicker(context) } },
            onWidgets = { closeThen(onWidgets) },
            onEditFavorites = { closeThen { SettingsActivity.open(context, SettingsScreen.FAVORITES) } },
            onSettings = { closeThen { SettingsActivity.open(context) } },
        )
    }
}

/** What [HomeMenuSheet] shows, without the sheet. */
@Composable
internal fun HomeMenuContent(onWallpaper: () -> Unit, onEditFavorites: () -> Unit, onSettings: () -> Unit, onWidgets: () -> Unit = {}) {
    Column(Modifier.padding(bottom = 12.dp)) {
        SheetAction(ExtraIcons.Wallpaper, "Wallpaper", onWallpaper)
        // The way to a first widget: an empty stack takes no space on home, so there's nothing to long-press yet.
        SheetAction(ExtraIcons.Widgets, "Widgets", onWidgets)
        SheetAction(Icons.Outlined.FavoriteBorder, "Edit favorites", onEditFavorites)
        SheetAction(Icons.Outlined.Settings, "Launcher settings", onSettings)
    }
}

@Composable
fun RenameDialog(app: AppEntry, onDismiss: () -> Unit) {
    val prefs = LocalContext.current.launcher.prefs
    var text by remember { mutableStateOf(app.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(app.originalLabel) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.rename(app.key, text.takeIf { it.trim() != app.originalLabel })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    prefs.rename(app.key, null)
                    onDismiss()
                }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Long-press menu for a folder on the home screen: rename it, edit its apps (Settings' folder page), or remove it,
 * which puts its apps back among the favorites in its place, so nothing leaves the home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderActionsSheet(folder: HomeFolder, icons: State<Map<String, IconImage>>, onRename: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        FolderActionsContent(
            folder = folder,
            icons = remember(folder.apps, icons.value) { folder.apps.take(4).map { icons.value[it.key] } },
            onRename = { closeThen(onRename) },
            onEditApps = { closeThen { SettingsActivity.open(context, SettingsScreen.FOLDER, folder = folder.id) } },
            onRemove = { closeThen { prefs.update { it.dissolveFolder(folder.id) } } },
        )
    }
}

/** What [FolderActionsSheet] shows, without the sheet: the folder's icon, name and apps, then its actions. */
@Composable
internal fun FolderActionsContent(folder: HomeFolder, icons: List<IconImage?>, onRename: () -> Unit, onEditApps: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderIcon(icons, 44.dp, MaterialTheme.colorScheme.surfaceContainerHighest)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(folder.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder.apps.joinToString(", ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SheetAction(Icons.Outlined.Edit, "Rename", onRename)
        SheetRow(
            onClick = onEditApps,
            headline = { Text("Edit apps") },
            supporting = { Text("Add, remove or reorder what's in it") },
            leading = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, modifier = Modifier.size(24.dp)) },
        )
        SheetRow(
            onClick = onRemove,
            headline = { Text("Remove folder") },
            supporting = { Text("Its apps go back to your favorites") },
            leading = { Icon(FolderGlyphs.FolderMinus, contentDescription = null, modifier = Modifier.size(24.dp)) },
        )
    }
}

/**
 * Names a folder: a new one ([initial] is the suggested name, selected so typing replaces it) or one being renamed.
 * A blank name saves as none, which shows as "Folder".
 */
@Composable
fun FolderNameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun confirm() {
        onConfirm(text.text.trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::confirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Play category of [app], for a new folder's suggested name; -1 when it has none or can't be read. */
internal fun appCategory(context: Context, app: AppEntry): Int = runCatching {
    context.getSystemService(LauncherApps::class.java).getApplicationInfo(app.packageName, 0, app.user).category
}.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)

/** Folder glyphs that aren't in material-icons-core (paths from Material Icons, Apache 2.0). */
internal object FolderGlyphs {
    val NewFolder = glyph(
        "CreateNewFolder",
        "M20,6h-8l-2,-2L4,4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2L22,8c0,-1.11 -0.89,-2 " +
            "-2,-2zM20,18L4,18L4,6h5.17l2,2L20,8v10zM12,14h2v2h2v-2h2v-2h-2v-2h-2v2h-2z",
    )
    val MoveToFolder = glyph(
        "DriveFileMove",
        "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V6h5.17" +
            "l2,2H20V18zM12.16,12H8v2h4.16l-1.59,1.59L11.99,17L16,13.01L11.99,9l-1.41,1.41L12.16,12z",
    )
    /** The outlined folder with a minus in it. */
    val FolderMinus = glyph(
        "FolderMinus",
        "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V6h5.17" +
            "l2,2H20V18zM8,12h8v2H8z",
    )

    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    SheetRow(
        onClick = onClick,
        headline = { Text(label) },
        leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
    )
}

/**
 * A row of a sheet. ListItem insets its content 16dp; 8dp more puts it on the 24dp edge of the header and labels,
 * while the ripple still spans the full width. [leading] is centered in a 28dp column.
 */
@Composable
private fun SheetRow(
    onClick: () -> Unit,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = headline,
        supportingContent = supporting,
        leadingContent = if (leading != null) {
            { Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { leading() } }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}
