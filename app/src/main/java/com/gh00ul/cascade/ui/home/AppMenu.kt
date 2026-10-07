package com.gh00ul.cascade.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.addToFolder
import com.gh00ul.cascade.data.dissolveFolder
import com.gh00ul.cascade.data.removeFromFolder
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.GlassEdgeWidth
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.AppShortcut
import com.gh00ul.cascade.util.LauncherActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Long-press menus for an app or a folder: a card that pops from the row that was pressed, in home's own window like
 * the folder pop-up, so it opens the moment the long press lands (a bottom sheet is a window of its own, and creating
 * one cost a good part of a second). The actions people reach for most are round buttons at its head; notifications,
 * shortcuts and the rest follow as rows.
 */

/** What a long-press menu is for, and where its row is (root coordinates), which it pops from. */
@Immutable
internal sealed interface MenuTarget {
    val anchor: Rect?
}

@Immutable
internal class AppMenuTarget(val app: AppEntry, override val anchor: Rect?) : MenuTarget

@Immutable
internal class FolderMenuTarget(val folderId: String, override val anchor: Rect?) : MenuTarget

/**
 * The menu for [target] over home, from the row it's anchored to, while [target] isn't null; it keeps the last one
 * while it closes. [card] draws the card for a target. [iconOrigin] is how far into the row its icon's middle is,
 * where the card grows from.
 */
@Composable
internal fun ContextMenuPopup(
    target: MenuTarget?,
    iconOrigin: Dp,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    card: @Composable (MenuTarget) -> Unit,
) {
    val shown = remember { Latest<MenuTarget>() }.also { if (target != null) it.value = target }
    PopupLayer(visible = target != null, title = { menuTitle(shown.value) }, onDismiss = onDismiss) { scale ->
        val current = shown.value ?: return@PopupLayer
        // Keyed by the target: a menu opened for another one (before this one finished closing) starts afresh, its
        // remembered state (shortcuts, the folder list open) never carried over from the last.
        AnchoredCard(current.anchor, iconOrigin, scale, Modifier.fillMaxSize().padding(contentPadding)) { key(current) { card(current) } }
    }
}

private fun menuTitle(target: MenuTarget?) = when (target) {
    is AppMenuTarget -> target.app.label
    is FolderMenuTarget -> "Folder options"
    null -> ""
}

/**
 * Apps' shortcuts as last loaded for their menus, by app key (the 24 used last), so a menu opens with them rather than
 * growing as they arrive. A press held on an app's row loads them ([prefetchMenuShortcuts]) before its long press
 * lands; a menu that opens without a fresh load shows the last one at once and loads again. Main thread only; the loads
 * themselves run on the IO dispatcher.
 */
private object MenuShortcuts {
    private class Loaded(val shortcuts: List<AppShortcut>, val at: Long)

    /** A load this recent came from the press that is opening the menu now: asking again would find the same. */
    private const val FRESH_MILLIS = 2_000L

    private val cache = object : LinkedHashMap<String, Loaded>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>?) = size > 24
    }

    fun last(app: AppEntry): List<AppShortcut>? = cache[app.key]?.shortcuts

    fun isFresh(app: AppEntry): Boolean = cache[app.key]?.let { SystemClock.uptimeMillis() - it.at < FRESH_MILLIS } == true

    /** Loads [app]'s shortcuts. Unchanged since the last load, it returns that same list, so a card showing it skips. */
    suspend fun load(context: Context, app: AppEntry): List<AppShortcut> {
        val fresh = withContext(Dispatchers.IO) { LauncherActions.loadShortcuts(context, app) }
        val kept = last(app)?.takeIf { sameShortcuts(it, fresh) } ?: fresh
        cache[app.key] = Loaded(kept, SystemClock.uptimeMillis())
        return kept
    }

    fun clear() = cache.clear()
}

/** Forgets every app's shortcuts, so a test starts with none known whatever ran before it. */
@VisibleForTesting
internal fun resetMenuShortcuts() = MenuShortcuts.clear()

/**
 * Loads [app]'s shortcuts for its menu ahead of time, unless they were just loaded: a press on its row is being held,
 * and the long press that opens the menu may follow. Runs only on that press, so never while home is out of sight.
 */
internal suspend fun prefetchMenuShortcuts(context: Context, app: AppEntry) {
    if (!MenuShortcuts.isFresh(app)) MenuShortcuts.load(context, app)
}

/**
 * [app]'s menu with its actions wired: shortcuts come from the press that opened it when it loaded them in time (see
 * [prefetchMenuShortcuts]); otherwise the last ones known show at once while they load again off the main thread.
 * Every action closes the menu first. Dialogs (rename, a new folder's name) are the caller's, as they outlive the menu.
 */
@Composable
internal fun AppMenu(
    app: AppEntry,
    icon: IconImage?,
    showIcon: Boolean,
    isFavorite: Boolean,
    isHidden: Boolean,
    notifications: List<AppNotification>,
    folders: List<FolderChoice>,
    folderId: String?,
    onOpenNotification: (AppNotification) -> Unit,
    onRename: () -> Unit,
    onNewFolder: () -> Unit,
    onHidePlayer: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val shortcuts by produceState(MenuShortcuts.last(app).orEmpty(), app.key) {
        // An unchanged load returns the list already shown, which leaves the card alone mid-pop.
        if (!MenuShortcuts.isFresh(app)) value = MenuShortcuts.load(context, app)
    }
    // Known from the app list: no binder call while the card composes.
    val canUninstall = !app.isWork && !app.isSystem
    // Every action closes the menu first. Plain lambdas (not built by a helper), so they're remembered and the card
    // skips when nothing it shows has changed.
    val otherFolders = remember(folders, folderId) { folders.filter { it.id != folderId } }
    AppMenuCard(
        app = app,
        icon = icon,
        showIcon = showIcon,
        isFavorite = isFavorite,
        isHidden = isHidden,
        notifications = notifications,
        shortcuts = shortcuts,
        canUninstall = canUninstall,
        showHidePlayer = onHidePlayer != null,
        onOpenNotification = { n -> onDismiss(); onOpenNotification(n) },
        onClearNotifications = { onDismiss(); NotificationStore.dismissAll(notifications) },
        onShortcut = { shortcut -> onDismiss(); LauncherActions.launchShortcut(context, shortcut) },
        onHidePlayer = { onDismiss(); onHidePlayer?.invoke() },
        onToggleFavorite = { onDismiss(); prefs.toggleFavorite(app.key) },
        onRename = { onDismiss(); onRename() },
        onToggleHidden = { onDismiss(); prefs.setHidden(app.key, !isHidden) },
        onAppInfo = { onDismiss(); LauncherActions.openAppInfo(context, app) },
        onUninstall = { onDismiss(); LauncherActions.uninstall(context, app) },
        folderName = folders.firstOrNull { it.id == folderId }?.label,
        folders = otherFolders,
        onAddToFolder = { id -> onDismiss(); prefs.update { it.addToFolder(app.key, id) } },
        onNewFolder = { onDismiss(); onNewFolder() },
        onRemoveFromFolder = { onDismiss(); prefs.update { it.removeFromFolder(app.key) } },
    )
}

/** Whether two loads found the same shortcuts, unchanged since: same ids, labels and last change, in the same order. */
private fun sameShortcuts(a: List<AppShortcut>, b: List<AppShortcut>) = a.size == b.size && a.indices.all { i ->
    a[i].info.id == b[i].info.id && a[i].label == b[i].label && a[i].info.lastChangedTimestamp == b[i].info.lastChangedTimestamp
}

/** [folder]'s menu with its actions wired; renaming is the caller's dialog. */
@Composable
internal fun FolderMenu(folder: HomeFolder, icons: List<IconImage?>, onRename: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    FolderMenuCard(
        folder = folder,
        icons = icons,
        onRename = {
            onDismiss()
            onRename()
        },
        onEditApps = {
            onDismiss()
            SettingsActivity.open(context, SettingsScreen.FOLDER, folder = folder.id)
        },
        onRemove = {
            onDismiss()
            prefs.update { it.dissolveFolder(folder.id) }
        },
    )
}

/**
 * The app menu's card, everything passed in so it can be drawn alone: the app's icon and name, round buttons for
 * Favorite (lit when it is one; Take out for an app in a folder), Rename, App info and Uninstall (Hide for an app that
 * can't be uninstalled), then its notifications, its shortcuts and the rest as rows. It scrolls when it's taller than
 * the screen allows.
 *
 * An app in a folder ([folderName]) can leave it or move to another; any other app can go into one. The other
 * [folders] open under that row, with "New folder" last; with none yet, it goes straight to [onNewFolder].
 * [foldersOpen] starts them open (screenshots).
 */
@Composable
internal fun AppMenuCard(
    app: AppEntry,
    icon: IconImage?,
    showIcon: Boolean,
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
    MenuSurface {
        MenuHeader(icon = if (showIcon) ({ AppIcon(icon, MenuIconSize) }) else null, title = app.label)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            // An app is a favorite of its own or in a folder, never both: in one, the first button takes it out.
            if (folderName != null) {
                MenuButton(FolderGlyphs.FolderMinus, "Take out", "Remove from $folderName", onRemoveFromFolder)
            } else {
                MenuButton(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    "Favorite",
                    if (isFavorite) "Remove from favorites" else "Add to favorites",
                    onToggleFavorite,
                    selected = isFavorite,
                )
            }
            MenuButton(Icons.Outlined.Edit, "Rename", onClick = onRename)
            MenuButton(Icons.Outlined.Info, "App info", onClick = onAppInfo)
            if (canUninstall) {
                MenuButton(Icons.Outlined.Delete, "Uninstall", onClick = onUninstall)
            } else {
                MenuButton(
                    if (isHidden) ExtraIcons.Visibility else ExtraIcons.VisibilityOff,
                    if (isHidden) "Show" else "Hide",
                    if (isHidden) "Show in app list" else "Hide from app list",
                    onToggleHidden,
                )
            }
        }

        if (notifications.isNotEmpty()) {
            MenuLabel("Notifications", action = if (notifications.any { it.clearable }) "Clear all" to onClearNotifications else null)
            for (n in notifications.take(3)) {
                MenuRow(
                    onClick = { onOpenNotification(n) },
                    title = n.title.ifEmpty { app.label },
                    detail = n.text.ifEmpty { null },
                )
            }
        }

        // Shortcuts may come a moment after the card opens: they grow it rather than jumping in. The last list stays
        // while it closes.
        val lastShortcuts = remember { Latest<List<AppShortcut>>() }.also { if (shortcuts.isNotEmpty()) it.value = shortcuts }
        AnimatedVisibility(shortcuts.isNotEmpty(), enter = Motion.ExpandDown, exit = Motion.CollapseUp) {
            Column {
                MenuLabel("Shortcuts")
                for (shortcut in lastShortcuts.value.orEmpty()) {
                    MenuRow(onClick = { onShortcut(shortcut) }, title = shortcut.label, leading = { AppIcon(shortcut.icon, 24.dp) })
                }
            }
        }

        MenuDivider()
        var pickFolder by rememberSaveable { mutableStateOf(foldersOpen) }
        MenuRow(
            onClick = { if (folders.isEmpty()) onNewFolder() else pickFolder = !pickFolder },
            title = if (folderName != null) "Move to another folder" else "Add to folder",
            leading = { MenuGlyph(if (folderName != null) FolderGlyphs.MoveToFolder else FolderGlyphs.NewFolder) },
        )
        AnimatedVisibility(pickFolder && folders.isNotEmpty(), enter = Motion.ExpandDown, exit = Motion.CollapseUp) {
            // One step in, so they read as that row's choices.
            Column(Modifier.padding(start = 16.dp)) {
                val style = LocalLauncherStyle.current
                for (folder in folders) {
                    MenuRow(
                        onClick = { onAddToFolder(folder.id) },
                        title = folder.label,
                        leading = { FolderIcon(folder.icons, 24.dp, style.content.copy(alpha = 0.12f)) },
                    )
                }
                MenuRow(onClick = onNewFolder, title = "New folder", leading = { MenuGlyph(Icons.Outlined.Add) })
            }
        }
        // With Uninstall among the buttons, hiding is a row.
        if (canUninstall) {
            MenuRow(
                onClick = onToggleHidden,
                title = if (isHidden) "Show in app list" else "Hide from app list",
                leading = { MenuGlyph(if (isHidden) ExtraIcons.Visibility else ExtraIcons.VisibilityOff) },
            )
        }
        if (showHidePlayer) MenuRow(onClick = onHidePlayer, title = "Hide player", leading = { MenuGlyph(Icons.Outlined.Close) })
    }
}

/** The folder menu's card: the folder's icon, name and apps, then Rename, Edit apps and Remove as round buttons. */
@Composable
internal fun FolderMenuCard(folder: HomeFolder, icons: List<IconImage?>, onRename: () -> Unit, onEditApps: () -> Unit, onRemove: () -> Unit) {
    val style = LocalLauncherStyle.current
    MenuSurface {
        MenuHeader(
            icon = { FolderIcon(icons, MenuIconSize, style.content.copy(alpha = 0.12f)) },
            title = folder.label,
            subtitle = folder.apps.joinToString(", ") { it.label },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            MenuButton(Icons.Outlined.Edit, "Rename", onClick = onRename)
            MenuButton(Icons.AutoMirrored.Outlined.List, "Edit apps", "Edit apps: add, remove or reorder what's in it", onEditApps)
            MenuButton(FolderGlyphs.FolderMinus, "Remove", "Remove folder: its apps go back to your favorites", onRemove)
        }
    }
}

/** An icon at the head of a menu. */
private val MenuIconSize = 36.dp
private val RowShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(16.dp)

/** The card itself: the folder pop-up's glass, opaque, and a column that scrolls when it must. */
@Composable
private fun MenuSurface(content: @Composable ColumnScope.() -> Unit) {
    val style = LocalLauncherStyle.current
    Surface(
        shape = PopupShape,
        color = lerp(style.scrim, style.content, CardLift),
        contentColor = style.content,
        border = BorderStroke(GlassEdgeWidth, style.glassEdge),
        // Taps between the rows stay on the card instead of closing it.
        modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp), content = content)
    }
}

@Composable
private fun MenuHeader(icon: (@Composable () -> Unit)?, title: String, subtitle: String? = null) {
    val style = LocalLauncherStyle.current
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MenuTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MenuDetail.copy(color = style.content.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A round button and its [label], for an action people reach for often. [selected] (a favorite) lights the disc with
 * the accent. TalkBack reads [description], which can say more than the label has room for.
 */
@Composable
private fun RowScope.MenuButton(icon: ImageVector, label: String, description: String = label, onClick: () -> Unit, selected: Boolean = false) {
    val style = LocalLauncherStyle.current
    val press = rememberPressIndication()
    Column(
        Modifier
            .weight(1f)
            .clip(ButtonShape)
            .clickable(interactionSource = null, indication = press ?: LocalIndication.current, role = Role.Button, onClick = onClick)
            .pressScale(press)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (selected) stateDescription = "On"
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).background(if (selected) style.accent.copy(alpha = 0.28f) else style.content.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) style.accent else LocalContentColor.current, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        // A quarter of the card's width: at a large font size the label shrinks to fit rather than lose its end, inset
        // so it never runs into the next one. Centered, so at the default size, with room to spare, it doesn't move.
        Text(
            label,
            style = MenuSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = MenuSmallFit,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** A section's name in the folder pop-up's heading style, with [action] (a label and what it does) at its end. */
@Composable
private fun MenuLabel(text: String, action: Pair<String, () -> Unit>? = null) {
    val style = LocalLauncherStyle.current
    val fit = remember(style) { TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.section.fontSize) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // One line, shrinking to fit at a large font size, rather than breaking the word.
        Text(
            text.uppercase(),
            style = style.section.copy(shadow = null),
            maxLines = 1,
            autoSize = fit,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null) {
            Text(
                action.first,
                style = MenuSmall.copy(color = style.accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RowShape)
                    .clickable(role = Role.Button, onClick = action.second)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** A row: [leading] on a 24dp column, [title], and [detail] under it. */
@Composable
private fun MenuRow(onClick: () -> Unit, title: String, detail: String? = null, leading: (@Composable () -> Unit)? = null) {
    val style = LocalLauncherStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (detail == null) 11.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(title, style = MenuText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MenuDetail.copy(color = style.content.copy(alpha = 0.7f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MenuGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalLauncherStyle.current.content.copy(alpha = 0.1f)),
    )
}

// Text on the card: no shadow (it isn't over the wallpaper), in the card's content color.
private val MenuTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
private val MenuText = TextStyle(fontSize = 16.sp)
private val MenuDetail = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
private val MenuSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
// Down from the full size only when it doesn't fit, which takes a large font size: at the default it lands on the max.
private val MenuSmallFit = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MenuSmall.fontSize)
