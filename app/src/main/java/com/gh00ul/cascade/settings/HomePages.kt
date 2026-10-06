package com.gh00ul.cascade.settings

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeApp
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.HomeItem
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.addToFolder
import com.gh00ul.cascade.data.defaultFolderName
import com.gh00ul.cascade.data.dissolveFolder
import com.gh00ul.cascade.data.folderLabel
import com.gh00ul.cascade.data.homeAppKeys
import com.gh00ul.cascade.data.homeFolders
import com.gh00ul.cascade.data.homeItems
import com.gh00ul.cascade.data.isFolderKey
import com.gh00ul.cascade.data.newFolder
import com.gh00ul.cascade.data.nextFolderId
import com.gh00ul.cascade.data.removeFavorite
import com.gh00ul.cascade.data.removeFromFolder
import com.gh00ul.cascade.data.renameFolder
import com.gh00ul.cascade.data.reorderFavorites
import com.gh00ul.cascade.data.reorderFolder
import com.gh00ul.cascade.data.searchApps
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.home.FolderGlyphs
import com.gh00ul.cascade.ui.home.FolderIcon
import com.gh00ul.cascade.ui.home.FolderNameDialog

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
    // Folders count as rows of their own; before the first load, the stored keys stand in.
    val homeRows = remember(apps, settings.favorites, settings.folders) { homeItems(settings, apps.associateBy { it.key }) }
    val folderCount = if (apps.isEmpty()) settings.favorites.count(::isFolderKey) else homeRows.count { it is HomeFolder }
    val favoriteCount = if (apps.isEmpty()) settings.favorites.size else homeRows.size

    SettingsPage(
        title = SettingsScreen.HOME.title,
        onBack = nav.back,
        pinned = { HomePreview(settings, favorites, icons, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) },
    ) {
        SettingsGroup("Apps") {
            SettingRow(
                title = "Favorites",
                summary = if (favoriteCount == 0) "None yet. Add the apps you use most." else favoritesSummary(favoriteCount - folderCount, folderCount),
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
        SettingsGroup("Widgets") {
            SettingRow(
                title = "Widget stack",
                summary = if (settings.widgetStack.isEmpty()) "None yet. Add calendar, weather or app widgets under the clock."
                else count(settings.widgetStack.size, "widget", "widgets") + " · swipe between them on home",
                onClick = { nav.go(SettingsScreen.WIDGETS) },
            )
        }
        SettingsGroup("Rows") {
            SwitchRow("Notification previews", "The latest notification under each favorite", settings.showNotificationPreviews, key = "previews") { on ->
                prefs.update { it.copy(showNotificationPreviews = on) }
            }
            SwitchRow(
                "Copy login codes",
                "A verification code in a notification is ready to paste the moment it arrives",
                settings.copyLoginCodes,
                key = "codes",
            ) { on ->
                prefs.update { it.copy(copyLoginCodes = on) }
            }
            SwitchRow("Music player", "The playing app's row turns into a player", settings.showMediaControls, key = "media") { on ->
                prefs.update { it.copy(showMediaControls = on) }
            }
            SwitchRow("Glow with the music", "While music plays, your favorites pick up the album's color", settings.musicGlow, key = "musicGlow") { on ->
                prefs.update { it.copy(musicGlow = on) }
            }
            SwitchRow(
                "Resume with headphones",
                "With headphones connected and nothing playing, offers to resume the app that played last",
                settings.resumePrompt,
                key = "resume",
            ) { on -> prefs.update { it.copy(resumePrompt = on) } }
        }
    }
}

private const val FAVORITE = "fav:"
private const val MEMBER = "app:"

/**
 * Drag-to-reorder by a row's handle in a lazy list: while a row is dragged the list shows [order], a copy reordered as
 * it goes, and the new order is handed to the page's save when the row is dropped. Rows' list keys are [prefix] plus
 * the item's key.
 */
private class Reorder<T>(private val prefix: String, private val keyOf: (T) -> String) {
    val order = mutableStateListOf<T>()
    var dragging by mutableStateOf<String?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun shown(items: List<T>): List<T> = if (dragging != null) order else items

    fun start(items: List<T>, key: String) {
        order.clear()
        order.addAll(items)
        offset = 0f
        dragging = key
    }

    fun drag(dy: Float, listState: LazyListState) {
        val key = dragging ?: return
        offset += dy
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == prefix + key } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = items.firstOrNull { info ->
            info.key != current.key && (info.key as? String)?.startsWith(prefix) == true &&
                center >= info.offset && center < info.offset + info.size
        } ?: return
        val from = order.indexOfFirst { prefix + keyOf(it) == current.key }
        val to = order.indexOfFirst { prefix + keyOf(it) == target.key }
        if (from < 0 || to < 0) return
        order.add(to, order.removeAt(from))
        // The row now lays out in the target's place: at its top moving up, ending at its bottom moving down (rows
        // differ in height when a long name wraps). Shift the offset so the row stays under the finger.
        val newOffset = if (to > from) target.offset + target.size - current.size else target.offset
        offset += current.offset - newOffset
    }

    fun end(save: (List<T>) -> Unit) {
        if (dragging != null) save(order.toList())
        dragging = null
        offset = 0f
    }
}

/**
 * Favorites in home order, apps and folders alike. Drag a row by its handle to move it; TalkBack users get Move up
 * and Move down actions. The new order is saved when the row is dropped. Tapping a folder opens it; removing one puts
 * its apps back here in its place.
 */
@Composable
internal fun FavoritesPage(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    favorites: List<HomeItem>,
    icons: Map<String, IconImage>,
    nav: SettingsNav,
) {
    val prefs = LocalContext.current.launcher.prefs
    val listState = rememberLazyListState()
    val reorder = remember { Reorder<HomeItem>(FAVORITE) { it.key } }
    val shown = reorder.shown(favorites)
    var naming by rememberSaveable { mutableStateOf(false) }

    fun move(from: Int, to: Int) {
        if (to !in favorites.indices) return
        val order = favorites.toMutableList().also { it.add(to, it.removeAt(from)) }
        prefs.update { it.reorderFavorites(order.map(HomeItem::key)) }
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
            item(key = "hint") {
                ListText(
                    if (shown.any { it is HomeFolder }) "Drag the handle to reorder. The top one sits highest on the home screen. Tap a folder to change what's in it."
                    else "Drag the handle to reorder. The top one sits highest on the home screen.",
                )
            }
        }
        itemsIndexed(shown, key = { _, item -> FAVORITE + item.key }) { index, item ->
            val isDragged = reorder.dragging == item.key
            ReorderRow(
                key = item.key,
                leading = {
                    when (item) {
                        is HomeApp -> AppIcon(icons[item.key], 40.dp)
                        is HomeFolder -> SettingsFolderIcon(item.apps.take(4).map { icons[it.key] }, 40.dp)
                    }
                },
                title = when (item) {
                    is HomeApp -> item.app.label
                    is HomeFolder -> item.label
                },
                summary = (item as? HomeFolder)?.let { count(it.apps.size, "app", "apps") },
                onClick = (item as? HomeFolder)?.let { folder -> { nav.openFolder(folder.id) } },
                onClickLabel = "Edit folder",
                removeLabel = when (item) {
                    is HomeApp -> "Remove ${item.app.label}"
                    is HomeFolder -> "Remove folder ${item.label}, keeping its apps"
                },
                shapeIndex = index,
                count = shown.size,
                dragged = isDragged,
                dragOffset = { if (isDragged) reorder.offset else 0f },
                onRemove = { prefs.update { it.removeFavorite(item.key) } },
                onMoveUp = { move(index, index - 1) }.takeIf { index > 0 },
                onMoveDown = { move(index, index + 1) }.takeIf { index < shown.lastIndex },
                onDragStart = { reorder.start(favorites, item.key) },
                onDrag = { reorder.drag(it, listState) },
                onDragEnd = { reorder.end { order -> prefs.update { it.reorderFavorites(order.map(HomeItem::key)) } } },
            )
        }
        item(key = "add") {
            Column(Modifier.padding(top = 12.dp).clip(GroupShape), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingRow(title = "Add a favorite", icon = Icons.Filled.Add, onClick = { nav.go(SettingsScreen.ADD_FAVORITE) })
                SettingRow(title = "New folder", icon = FolderGlyphs.NewFolder, onClick = { naming = true })
            }
        }
    }
    if (naming) {
        val taken = settings.folders.values.map { it.name }
        FolderNameDialog(
            title = "New folder",
            initial = remember(taken) { defaultFolderName(ApplicationInfo.CATEGORY_UNDEFINED, taken) },
            confirmLabel = "Create",
            onConfirm = { name ->
                var created: String? = null
                prefs.update { s -> nextFolderId(s.folders).let { id -> created = id; s.newFolder(id, name, emptyList()) } }
                created?.let(nav.openFolder)
            },
            onDismiss = { naming = false },
        )
    }
}

/**
 * One folder: its name, then its apps in order, each with a handle to drag and a button to take it out (back among
 * the favorites, after the folder). "Add apps" picks more; "Remove folder" ends it and keeps its apps on home.
 */
@Composable
internal fun FolderPage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    val id = nav.folder
    val folder = id?.let { settings.folders[it] }
    // Removed here or elsewhere (or opened for a folder that's gone): nothing left to edit.
    LaunchedEffect(folder == null) { if (folder == null) nav.back() }
    if (id == null || folder == null) return
    val listState = rememberLazyListState()
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val members = remember(folder.apps, byKey) { folder.apps.mapNotNull { byKey[it] } }
    val reorder = remember { Reorder<AppEntry>(MEMBER) { it.key } }
    val shown = reorder.shown(members)
    var renaming by rememberSaveable { mutableStateOf(false) }

    fun move(from: Int, to: Int) {
        if (to !in members.indices) return
        val order = members.toMutableList().also { it.add(to, it.removeAt(from)) }
        prefs.update { it.reorderFolder(id, order.map(AppEntry::key)) }
    }

    SettingsListPage(
        title = folderLabel(folder.name),
        onBack = nav.back,
        state = listState,
        actions = {
            IconButton(onClick = { nav.go(SettingsScreen.ADD_TO_FOLDER) }) { Icon(Icons.Filled.Add, contentDescription = "Add apps") }
        },
    ) {
        item(key = "name") {
            SettingRow(
                title = "Name",
                summary = folderLabel(folder.name),
                icon = Icons.Filled.Edit,
                onClick = { renaming = true },
                onClickLabel = "Rename",
                modifier = Modifier.padding(top = 8.dp).clip(GroupShape),
            )
        }
        item(key = "hint") {
            ListText(
                if (shown.isEmpty()) "Nothing in it yet. Add some apps; until then it stays off the home screen."
                else "Drag the handle to reorder. An app you take out goes back to your favorites, after the folder.",
            )
        }
        itemsIndexed(shown, key = { _, app -> MEMBER + app.key }) { index, app ->
            val isDragged = reorder.dragging == app.key
            ReorderRow(
                key = app.key,
                leading = { AppIcon(icons[app.key], 40.dp) },
                title = app.label,
                summary = null,
                onClick = null,
                removeLabel = "Take ${app.label} out of the folder",
                shapeIndex = index,
                count = shown.size,
                dragged = isDragged,
                dragOffset = { if (isDragged) reorder.offset else 0f },
                onRemove = { prefs.update { it.removeFromFolder(app.key, keepEmptyFolder = true) } },
                onMoveUp = { move(index, index - 1) }.takeIf { index > 0 },
                onMoveDown = { move(index, index + 1) }.takeIf { index < shown.lastIndex },
                onDragStart = { reorder.start(members, app.key) },
                onDrag = { reorder.drag(it, listState) },
                onDragEnd = { reorder.end { order -> prefs.update { it.reorderFolder(id, order.map(AppEntry::key)) } } },
            )
        }
        item(key = "actions") {
            Column(Modifier.padding(top = 12.dp).clip(GroupShape), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingRow(title = "Add apps", icon = Icons.Filled.Add, onClick = { nav.go(SettingsScreen.ADD_TO_FOLDER) })
                SettingRow(
                    title = "Remove folder",
                    summary = "Its apps go back to your favorites",
                    icon = FolderGlyphs.FolderMinus,
                    onClick = { prefs.update { it.dissolveFolder(id) } },
                )
            }
        }
    }
    if (renaming) {
        FolderNameDialog(
            title = "Rename folder",
            initial = folder.name,
            confirmLabel = "Save",
            onConfirm = { name -> prefs.update { it.renameFolder(id, name) } },
            onDismiss = { renaming = false },
        )
    }
}

/**
 * Every app, with a search field, to fill a folder: a tap puts the app in (moving it from the favorites or another
 * folder) or takes it back out. The page stays open, so several can go in at once; each says where it is now.
 */
@Composable
internal fun AddToFolderPage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    val id = nav.folder
    val folder = id?.let { settings.folders[it] }
    LaunchedEffect(folder == null) { if (folder == null) nav.back() }
    if (id == null || folder == null) return
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(apps, query) { if (query.isBlank()) apps else searchApps(apps, query) }
    val members = folder.apps.toHashSet()
    // Where each app is now: on home by itself, or in another folder.
    val places = remember(settings.favorites, settings.folders, id) {
        buildMap {
            settings.homeFolders().forEach { (fid, f) -> if (fid != id) f.apps.forEach { put(it, "In ${folderLabel(f.name)}") } }
            settings.favorites.forEach { if (!isFolderKey(it)) put(it, "A favorite") }
        }
    }
    SettingsListPage(title = "Add to ${folderLabel(folder.name)}", onBack = nav.back) {
        item(key = "search") { AppSearchField(query) { query = it } }
        if (candidates.isEmpty()) {
            item(key = "none") { ListText("No apps match “${query.trim()}”.") }
        }
        itemsIndexed(candidates, key = { _, app -> app.key }) { index, app ->
            val inFolder = app.key in members
            AppListRow(
                app,
                icons[app.key],
                index,
                candidates.size,
                summary = if (inFolder) "In this folder" else places[app.key],
                onClick = {
                    prefs.update { if (inFolder) it.removeFromFolder(app.key, keepEmptyFolder = true) else it.addToFolder(app.key, id) }
                },
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (inFolder) Icon(Icons.Filled.Check, contentDescription = "In the folder", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** A folder's icon on a settings row: its apps' icons on the page's raised tone. */
@Composable
private fun SettingsFolderIcon(icons: List<IconImage?>, size: Dp) =
    FolderIcon(icons, size, MaterialTheme.colorScheme.surfaceContainerHighest)

/**
 * A row of a reorderable list: [leading] (an icon), [title] and an optional [summary], a remove button, and the drag
 * handle. Tapping it runs [onClick] when given.
 */
@Composable
private fun LazyItemScope.ReorderRow(
    key: String,
    leading: @Composable () -> Unit,
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    removeLabel: String,
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
    onClickLabel: String? = null,
) {
    // The drag handler below is keyed on the row and outlives recompositions, so it calls the latest callbacks: a
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
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
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
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = removeLabel) }
        Box(
            Modifier
                .size(48.dp)
                .pointerInput(key) {
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

/** The search field over a list of apps. */
@Composable
private fun AppSearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
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

/** "5 apps, 2 folders · add, remove or reorder": the favorites row's summary, each folder counted as one row. */
internal fun favoritesSummary(apps: Int, folders: Int): String =
    listOfNotNull(count(apps, "app", "apps").takeIf { apps > 0 || folders == 0 }, count(folders, "folder", "folders").takeIf { folders > 0 })
        .joinToString(", ") + " · add, remove or reorder"

/** Every app that isn't a favorite yet, with a search field; picking one adds it at the bottom and goes back. */
@Composable
internal fun AddFavoritePage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val prefs = LocalContext.current.launcher.prefs
    var query by rememberSaveable { mutableStateOf("") }
    // Apps in folders are on the home screen already: they move from their folder's page or long-press menu.
    val candidates = remember(apps, settings.favorites, settings.folders, query) {
        val onHome = settings.homeAppKeys().toHashSet()
        (if (query.isBlank()) apps else searchApps(apps, query)).filter { it.key !in onHome }
    }
    SettingsListPage(title = SettingsScreen.ADD_FAVORITE.title, onBack = nav.back) {
        item(key = "search") { AppSearchField(query) { query = it } }
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
