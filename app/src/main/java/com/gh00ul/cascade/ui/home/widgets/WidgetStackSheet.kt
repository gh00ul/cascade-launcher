package com.gh00ul.cascade.ui.home.widgets

import android.content.Context
import android.content.pm.LauncherApps
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.MAX_STACK_WIDGETS
import com.gh00ul.cascade.data.WIDGET_CALENDAR
import com.gh00ul.cascade.data.WIDGET_WEATHER
import com.gh00ul.cascade.data.WeatherKind
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.data.appWidgetId
import com.gh00ul.cascade.data.isStackWidget
import com.gh00ul.cascade.data.moveWidget
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.home.weatherIcon
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A widget in the stack as the editors list it: what it is, a line about it, and its icon (a glyph or an app's). */
@Immutable
internal class StackEntry(val entry: String, val title: String, val summary: String, val glyph: ImageVector?, val icon: ImageBitmap?)

/**
 * The stack's widgets as the editors list them, in order. An app widget's name and app are read off the main thread,
 * and say so until they come.
 */
@Composable
internal fun rememberStackEntries(settings: LauncherSettings): List<StackEntry> {
    val context = LocalContext.current
    val stack = settings.widgetStack.filter(::isStackWidget)
    val apps by produceState(emptyMap<Int, StackEntry>(), stack) {
        val ids = stack.mapNotNull(::appWidgetId)
        if (ids.isNotEmpty()) value = withContext(Dispatchers.IO) { ids.associateWith { describeAppWidget(context, it) } }
    }
    val place = settings.weatherPlace?.name?.substringBefore(',')?.trim()
    return stack.map { entry ->
        when (entry) {
            WIDGET_CALENDAR -> StackEntry(entry, "Calendar", "Your agenda for today and the next two days", ExtraIcons.Event, null)
            WIDGET_WEATHER -> StackEntry(
                entry,
                "Weather",
                place?.let { "Now and the hours ahead in $it" } ?: "Pick a place in Clock & glance",
                weatherIcon(WeatherKind.PARTLY_CLOUDY, isDay = true),
                null,
            )
            else -> apps[appWidgetId(entry)]?.let { StackEntry(entry, it.title, it.summary, it.glyph, it.icon) }
                ?: StackEntry(entry, "App widget", "Loading…", Icons.Outlined.Info, null)
        }
    }
}

/** App widget [id]'s name, its app and size, and the app's icon. Blocking: call off the main thread. */
private fun describeAppWidget(context: Context, id: Int): StackEntry {
    val entry = "app:$id"
    val info = WidgetHost.info(context, id) ?: return StackEntry(entry, "Widget unavailable", "Its app may have been removed", Icons.Outlined.Info, null)
    val pm = context.packageManager
    val density = context.resources.displayMetrics.density
    val app = runCatching { context.getSystemService(LauncherApps::class.java).getApplicationInfo(info.provider.packageName, 0, info.profile) }.getOrNull()
    val appName = app?.loadLabel(pm)?.toString() ?: info.provider.packageName
    val iconPx = (40 * density).roundToInt()
    val icon = app?.let { runCatching { pm.getUserBadgedIcon(it.loadIcon(pm), info.profile).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull() }
    return StackEntry(entry, info.loadLabel(pm)?.trim().orEmpty().ifEmpty { appName }, "$appName · ${cellSize(info, density)}", null, icon)
}

/**
 * The widget stack's editor on home, from a long press on the stack: its widgets in swipe order, each to move up or
 * down or take out, and Add widget, which turns the sheet into the picker. Sheets on home are dark, like the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetStackSheet(settings: LauncherSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val adder = rememberWidgetAdder()
    var picking by rememberSaveable { mutableStateOf(false) }
    val catalog = rememberWidgetCatalog(picking)
    val entries = rememberStackEntries(settings)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedContent(picking, transitionSpec = { Motion.swap() }, label = "widgetSheet") { showPicker ->
            if (showPicker) {
                WidgetPickerContent(
                    stack = settings.widgetStack,
                    catalog = catalog,
                    onBack = { picking = false },
                    onPickBuiltIn = { entry ->
                        adder.builtIn(entry)
                        picking = false
                    },
                    onPickApp = { info ->
                        picking = false
                        adder.app(info)
                    },
                )
            } else {
                WidgetStackContent(
                    entries = entries,
                    onMove = { from, to -> prefs.update { it.copy(widgetStack = moveWidget(it.widgetStack, from, to)) } },
                    onRemove = { entry -> WidgetHost.remove(context, entry) },
                    onAdd = { picking = true },
                )
            }
        }
    }
}

/**
 * What [WidgetStackSheet] shows while it isn't picking, without the sheet: the stack's [entries] in order, each with
 * Move up, Move down and Remove, then Add widget, which is off while the stack is full.
 */
@Composable
internal fun WidgetStackContent(
    entries: List<StackEntry>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val full = entries.size >= MAX_STACK_WIDGETS
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            "Widget stack",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            if (entries.isEmpty()) "No widgets yet. Add one to show it under the clock."
            else "Swipe between them on home, in this order. Up to $MAX_STACK_WIDGETS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        entries.forEachIndexed { i, e ->
            StackEntryRow(
                e,
                onMoveUp = { onMove(i, i - 1) }.takeIf { i > 0 },
                onMoveDown = { onMove(i, i + 1) }.takeIf { i < entries.lastIndex },
                onRemove = { onRemove(e.entry) },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !full, onClick = onAdd)
                .heightIn(min = 56.dp)
                .padding(horizontal = 24.dp)
                .alpha(if (full) 0.38f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Add widget", style = MaterialTheme.typography.bodyLarge)
                if (full) {
                    Text("The stack is full. Remove one to add another.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A widget in the editor: its icon, name and line, then Move up, Move down and Remove. The arrows are left out at the
 * ends, keeping their place; TalkBack gets the moves as actions on the row too.
 */
@Composable
private fun StackEntryRow(e: StackEntry, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    onMoveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                    onMoveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
                )
            }
            .heightIn(min = 64.dp)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(e)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MoveButtons(e.title, onMoveUp, onMoveDown)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${e.title}") }
    }
}

/** Move up and Move down; an arrow that can't move leaves its place empty, so the rows' buttons line up. */
@Composable
internal fun MoveButtons(title: String, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    if (onMoveUp != null) {
        IconButton(onClick = onMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up") }
    } else {
        Spacer(Modifier.size(48.dp))
    }
    if (onMoveDown != null) {
        IconButton(onClick = onMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down") }
    } else {
        Spacer(Modifier.size(48.dp))
    }
}

/** An entry's icon in a 40dp column: the app's icon for an app widget, else a glyph in the theme's primary color. */
@Composable
internal fun EntryIcon(e: StackEntry) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (e.icon != null) AppIcon(e.icon, 40.dp)
        else if (e.glyph != null) Icon(e.glyph, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
    }
}
