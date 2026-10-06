package com.gh00ul.cascade.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.searchApps
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon

/** What's on the home page: which apps, and what their rows show. */
@Composable
internal fun HomeScreenPage(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    nav: SettingsNav,
) {
    val prefs = LocalContext.current.launcher.prefs
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.key } }
    val hiddenCount = if (apps.isEmpty()) settings.hidden.size else settings.hidden.count { it in installed }
    val renamedCount = if (apps.isEmpty()) settings.renames.size else settings.renames.keys.count { it in installed }
    val favoriteCount = if (apps.isEmpty()) settings.favorites.size else favorites.size

    SettingsPage(
        title = SettingsScreen.HOME.title,
        onBack = nav.back,
        pinned = { HomePreview(settings, favorites, icons, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) },
    ) {
        SettingsGroup("Apps") {
            SettingRow(
                title = "Favorites",
                summary = if (favoriteCount == 0) "None yet. Add the apps you use most." else "${count(favoriteCount, "app", "apps")} · add, remove or reorder",
                onClick = { nav.go(SettingsScreen.FAVORITES) },
            )
            SettingRow(
                title = "Hidden apps",
                summary = if (hiddenCount == 0) "None. Long-press an app to hide it." else "$hiddenCount hidden from the app list",
                onClick = { nav.go(SettingsScreen.HIDDEN) },
            )
            SettingRow(
                title = "Renamed apps",
                summary = if (renamedCount == 0) "None. Long-press an app to rename it." else count(renamedCount, "app", "apps") + " renamed",
                onClick = { nav.go(SettingsScreen.RENAMED) },
            )
        }
        SettingsGroup("Rows") {
            SwitchRow("Notification previews", "The latest notification under each favorite", settings.showNotificationPreviews, key = "previews") { on ->
                prefs.update { it.copy(showNotificationPreviews = on) }
            }
            SwitchRow("Music player", "The playing app's row turns into a player", settings.showMediaControls, key = "media") { on ->
                prefs.update { it.copy(showMediaControls = on) }
            }
        }
    }
}

private const val FAVORITE = "fav:"

/**
 * Favorites in home order. Drag a row by its handle to move it; TalkBack users get Move up and Move down actions.
 * The new order is saved when the row is dropped.
 */
@Composable
internal fun FavoritesPage(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    nav: SettingsNav,
) {
    val prefs = LocalContext.current.launcher.prefs
    val listState = rememberLazyListState()
    // While a row is dragged the list shows this copy, reordered as it goes; the stored order changes on drop.
    val order = remember { mutableStateListOf<AppEntry>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging != null) order else favorites

    // Writes [newOrder] over the installed favorites' places in the stored list, so favorites of apps that are missing
    // for now (disabled, on an unmounted SD card, still restoring) keep theirs.
    fun save(newOrder: List<AppEntry>) = prefs.update { s ->
        val keys = newOrder.mapTo(HashSet()) { it.key }
        if (s.favorites.count { it in keys } != newOrder.size) return@update s
        val next = newOrder.iterator()
        s.copy(favorites = s.favorites.map { key -> if (key in keys) next.next().key else key })
    }

    fun move(from: Int, to: Int) {
        if (to !in favorites.indices) return
        save(favorites.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun drag(dy: Float) {
        val key = dragging ?: return
        dragOffset += dy
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == FAVORITE + key } ?: return
        val center = current.offset + dragOffset + current.size / 2f
        val target = items.firstOrNull { info ->
            info.key != current.key && (info.key as? String)?.startsWith(FAVORITE) == true &&
                center >= info.offset && center < info.offset + info.size
        } ?: return
        val from = order.indexOfFirst { FAVORITE + it.key == current.key }
        val to = order.indexOfFirst { FAVORITE + it.key == target.key }
        if (from < 0 || to < 0) return
        order.add(to, order.removeAt(from))
        // The row now lays out in the target's place: at its top moving up, ending at its bottom moving down (rows
        // differ in height when a long name wraps). Shift the offset so the row stays under the finger.
        val newOffset = if (to > from) target.offset + target.size - current.size else target.offset
        dragOffset += current.offset - newOffset
    }

    SettingsListPage(
        title = SettingsScreen.FAVORITES.title,
        onBack = nav.back,
        state = listState,
        actions = {
            IconButton(onClick = { nav.go(SettingsScreen.ADD_FAVORITE) }) { Icon(Icons.Filled.Add, contentDescription = "Add a favorite") }
        },
    ) {
        if (shown.isEmpty()) {
            item(key = "empty") { ListText("No favorites yet. They sit at the bottom of your home screen, under the clock, where your thumb is.") }
        } else {
            item(key = "hint") { ListText("Drag the handle to reorder. The top one sits highest on the home screen.") }
        }
        itemsIndexed(shown, key = { _, app -> FAVORITE + app.key }) { index, app ->
            val isDragged = dragging == app.key
            FavoriteRow(
                app = app,
                icon = icons[app.key],
                shapeIndex = index,
                count = shown.size,
                dragged = isDragged,
                dragOffset = { if (isDragged) dragOffset else 0f },
                onRemove = { prefs.update { it.copy(favorites = it.favorites - app.key) } },
                onMoveUp = { move(index, index - 1) }.takeIf { index > 0 },
                onMoveDown = { move(index, index + 1) }.takeIf { index < shown.lastIndex },
                onDragStart = {
                    order.clear()
                    order.addAll(favorites)
                    dragOffset = 0f
                    dragging = app.key
                },
                onDrag = ::drag,
                onDragEnd = {
                    if (dragging != null) save(order.toList())
                    dragging = null
                    dragOffset = 0f
                },
            )
        }
        item(key = "add") {
            SettingRow(
                title = "Add a favorite",
                icon = Icons.Filled.Add,
                onClick = { nav.go(SettingsScreen.ADD_FAVORITE) },
                modifier = Modifier.padding(top = 12.dp).clip(GroupShape),
            )
        }
    }
}

@Composable
private fun LazyItemScope.FavoriteRow(
    app: AppEntry,
    icon: IconImage?,
    shapeIndex: Int,
    count: Int,
    dragged: Boolean,
    dragOffset: () -> Float,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // The drag handler below is keyed on the app and outlives recompositions, so it calls the latest callbacks: a
    // drag after a reorder or a removal must start from the list as it is now.
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            // The dragged row floats above the others and follows the finger; the rest glide to their new places.
            .then(if (dragged) Modifier.zIndex(1f) else Modifier.animateItem())
            .graphicsLayer {
                translationY = dragOffset()
                if (dragged) {
                    shadowElevation = 8.dp.toPx()
                    shape = groupItemShape(0, 1)
                    clip = true
                }
            }
            .fillMaxWidth()
            .clip(groupItemShape(shapeIndex, count))
            .background(if (dragged) MaterialTheme.colorScheme.surfaceContainerHighest else RowColor)
            // One item for TalkBack (icon and name), carrying the move actions; the Remove button stays its own.
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    onMoveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                    onMoveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
                )
            }
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, 40.dp)
        Spacer(Modifier.width(16.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${app.label}") }
        Box(
            Modifier
                .size(48.dp)
                .pointerInput(app.key) {
                    detectVerticalDragGestures(
                        onDragStart = { start() },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            move(dy)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(SettingsIcons.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Every app that isn't a favorite yet, with a search field; picking one adds it at the bottom and goes back. */
@Composable
internal fun AddFavoritePage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(apps, settings.favorites, query) {
        (if (query.isBlank()) apps else searchApps(apps, query)).filter { it.key !in settings.favorites }
    }
    SettingsListPage(title = SettingsScreen.ADD_FAVORITE.title, onBack = nav.back) {
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
                } else null,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = RowColor,
                    unfocusedContainerColor = RowColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
        }
        if (candidates.isEmpty()) {
            item(key = "none") { ListText(if (query.isBlank()) "Every app is already a favorite." else "No apps match “${query.trim()}”.") }
        }
        itemsIndexed(candidates, key = { _, app -> app.key }) { index, app ->
            AppListRow(app, icons[app.key], index, candidates.size, onClick = {
                prefs.update { s -> if (app.key in s.favorites) s else s.copy(favorites = s.favorites + app.key) }
                nav.back()
            })
        }
    }
}

/** Apps hidden from the A–Z list, each with Unhide. */
@Composable
internal fun HiddenAppsPage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    val hidden = remember(apps, settings.hidden) { apps.filter { it.key in settings.hidden } }
    SettingsListPage(title = SettingsScreen.HIDDEN.title, onBack = nav.back) {
        item(key = "about") {
            ListText(
                if (hidden.isEmpty()) "No hidden apps. Long-press an app and choose “Hide from app list”."
                else if (settings.hiddenInSearch) "Hidden apps stay out of the A–Z list but still show up in search."
                else "Hidden apps stay out of the A–Z list and out of search.",
            )
        }
        itemsIndexed(hidden, key = { _, app -> app.key }) { index, app ->
            AppListRow(app, icons[app.key], index, hidden.size) {
                TextButton(onClick = { prefs.setHidden(app.key, false) }) { Text("Unhide") }
            }
        }
    }
}

/** Apps with a name of your own, each with its original name and Reset. */
@Composable
internal fun RenamedAppsPage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    val renamed = remember(apps, settings.renames) { apps.filter { it.key in settings.renames } }
    SettingsListPage(title = SettingsScreen.RENAMED.title, onBack = nav.back) {
        if (renamed.isEmpty()) {
            item(key = "about") { ListText("No renamed apps. Long-press an app and choose “Rename” to give it a name of your own.") }
        }
        itemsIndexed(renamed, key = { _, app -> app.key }) { index, app ->
            AppListRow(app, icons[app.key], index, renamed.size, summary = "Originally ${app.originalLabel}") {
                TextButton(onClick = { prefs.rename(app.key, null) }) { Text("Reset") }
            }
        }
    }
}

/** An app in one of the lists above: icon, name, an optional [summary], and [trailing] (a button) or a row click. */
@Composable
private fun AppListRow(
    app: AppEntry,
    icon: IconImage?,
    index: Int,
    count: Int,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(groupItemShape(index, count))
            .background(RowColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
