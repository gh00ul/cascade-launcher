package com.gh00ul.cascade.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.MAX_STACK_WIDGETS
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.data.moveWidget
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.home.widgets.EntryIcon
import com.gh00ul.cascade.ui.home.widgets.MoveButtons
import com.gh00ul.cascade.ui.home.widgets.StackEntry
import com.gh00ul.cascade.ui.home.widgets.WidgetPickerContent
import com.gh00ul.cascade.ui.home.widgets.rememberStackEntries
import com.gh00ul.cascade.ui.home.widgets.rememberWidgetAdder
import com.gh00ul.cascade.ui.home.widgets.rememberWidgetCatalog

/**
 * The widget stack: its widgets in swipe order, each to move up or down or take out, and Add widget, which opens the
 * same picker as the stack's sheet on home (WidgetStackSheet). An app widget's own setup screen opens from here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetsPage(settings: LauncherSettings, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val entries = rememberStackEntries(settings)
    val adder = rememberWidgetAdder()
    var picking by rememberSaveable { mutableStateOf(false) }
    val catalog = rememberWidgetCatalog(picking)
    val full = entries.size >= MAX_STACK_WIDGETS

    SettingsPage(title = SettingsScreen.WIDGETS.title, onBack = nav.back) {
        PageText(
            "Up to $MAX_STACK_WIDGETS widgets on one card under the clock. Swipe between them on home, and long-press " +
                "the stack there to change it too.",
        )
        if (entries.isNotEmpty()) {
            SettingsGroup("In the stack") {
                entries.forEachIndexed { i, e ->
                    StackRow(
                        e,
                        onMoveUp = { prefs.update { it.copy(widgetStack = moveWidget(it.widgetStack, i, i - 1)) } }.takeIf { i > 0 },
                        onMoveDown = { prefs.update { it.copy(widgetStack = moveWidget(it.widgetStack, i, i + 1)) } }.takeIf { i < entries.lastIndex },
                        onRemove = { WidgetHost.remove(context, e.entry) },
                    )
                }
            }
        }
        SettingsGroup {
            // Laid out like the widgets above it, so its name lines up with theirs.
            WidgetRow(
                StackEntry("add", "Add widget", if (full) "The stack is full. Remove one to add another." else "Calendar, weather, or any app's widget", Icons.Filled.Add, null),
                modifier = Modifier.clickable(enabled = !full) { picking = true }.alpha(if (full) 0.38f else 1f),
            )
        }
    }

    if (picking) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = sheetState) {
            WidgetPickerContent(
                stack = settings.widgetStack,
                catalog = catalog,
                onBack = null,
                onPickBuiltIn = { entry ->
                    adder.builtIn(entry)
                    picking = false
                },
                onPickApp = { info ->
                    picking = false
                    adder.app(info)
                },
            )
        }
    }
}

/** A widget in the stack: its icon, name and line, then Move up, Move down and Remove; TalkBack gets the moves as actions. */
@Composable
private fun StackRow(e: StackEntry, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onRemove: () -> Unit) {
    WidgetRow(
        e,
        Modifier.semantics(mergeDescendants = true) {
            customActions = listOfNotNull(
                onMoveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                onMoveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
            )
        },
    ) {
        MoveButtons(e.title, onMoveUp, onMoveDown)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${e.title}") }
    }
}

/** A row of this page: an entry's icon in a 40dp column, its name and line, then [trailing] buttons. */
@Composable
private fun WidgetRow(e: StackEntry, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RowColor)
            .then(modifier)
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(e)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
