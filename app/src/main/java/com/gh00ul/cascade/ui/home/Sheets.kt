package com.gh00ul.cascade.ui.home

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
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

/** Long-press menu for an app: its notifications, shortcuts, and favorite / rename / hide / info / uninstall. */
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
        )
    }
}

/**
 * What [AppActionsSheet] shows, with its [shortcuts] already loaded and every action passed in, so it can be drawn
 * without the sheet or the app. All content starts on the sheet's 24dp edge, and shortcut icons and action glyphs
 * share one 28dp leading column, so their labels line up.
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
        SheetAction(
            if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            if (isFavorite) "Remove from favorites" else "Add to favorites",
            onToggleFavorite,
        )
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
fun HomeMenuSheet(onDismiss: () -> Unit) {
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
            onEditFavorites = { closeThen { SettingsActivity.open(context, SettingsScreen.FAVORITES) } },
            onSettings = { closeThen { SettingsActivity.open(context) } },
        )
    }
}

/** What [HomeMenuSheet] shows, without the sheet. */
@Composable
internal fun HomeMenuContent(onWallpaper: () -> Unit, onEditFavorites: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        SheetAction(ExtraIcons.Wallpaper, "Wallpaper", onWallpaper)
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
