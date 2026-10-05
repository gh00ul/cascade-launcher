package com.gh00ul.cascade.ui.home

import androidx.compose.foundation.clickable
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
                    ListItem(
                        headlineContent = { Text(n.title.ifEmpty { app.label }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (n.text.isNotEmpty()) {
                            { Text(n.text, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { closeThen { onOpenNotification(n) } },
                    )
                }
                if (notifications.any { it.clearable }) {
                    TextButton(
                        onClick = { closeThen { NotificationStore.dismissAll(notifications) } },
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text("Clear notifications") }
                }
            }

            if (shortcuts.isNotEmpty()) {
                SheetLabel("Shortcuts")
                for (shortcut in shortcuts) {
                    ListItem(
                        headlineContent = { Text(shortcut.label) },
                        leadingContent = { AppIcon(shortcut.icon, 28.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { closeThen { LauncherActions.launchShortcut(context, shortcut) } },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetAction(
                if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
            ) { closeThen { prefs.toggleFavorite(app.key) } }
            SheetAction(Icons.Outlined.Edit, "Rename") { closeThen(onRename) }
            SheetAction(
                if (isHidden) ExtraIcons.Visibility else ExtraIcons.VisibilityOff,
                if (isHidden) "Show in app list" else "Hide from app list",
            ) { closeThen { prefs.setHidden(app.key, !isHidden) } }
            SheetAction(Icons.Outlined.Info, "App info") { closeThen { LauncherActions.openAppInfo(context, app) } }
            if (canUninstall) {
                SheetAction(Icons.Outlined.Delete, "Uninstall") { closeThen { LauncherActions.uninstall(context, app) } }
            }
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
        Column(Modifier.padding(bottom = 12.dp)) {
            SheetAction(ExtraIcons.Wallpaper, "Wallpaper") { closeThen { LauncherActions.openWallpaperPicker(context) } }
            SheetAction(Icons.Outlined.FavoriteBorder, "Edit favorites") {
                closeThen { SettingsActivity.open(context, SettingsScreen.FAVORITES) }
            }
            SheetAction(Icons.Outlined.Settings, "Launcher settings") { closeThen { SettingsActivity.open(context) } }
        }
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
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}
