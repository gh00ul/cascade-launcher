package com.gh00ul.cascade.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The building blocks every settings page shares, in the Android 16 style: rows are cards on a tinted page, grouped
 * under a small title, with the group's outer corners rounded and a 2dp gap between rows.
 */

/** The page behind the rows, and the rows themselves: one step lighter, in both themes. */
internal val PageColor @Composable get() = MaterialTheme.colorScheme.surfaceContainer
internal val RowColor @Composable get() = MaterialTheme.colorScheme.surfaceBright

private val GroupCorner = 24.dp
private val InnerCorner = 4.dp
internal val GroupShape = RoundedCornerShape(GroupCorner)
private val TileShape = RoundedCornerShape(16.dp)

/** A row's corners in a lazy group of [count]: the group's corners on its first and last row, small ones between. */
internal fun groupItemShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) GroupCorner else InnerCorner,
    topEnd = if (index == 0) GroupCorner else InnerCorner,
    bottomStart = if (index == count - 1) GroupCorner else InnerCorner,
    bottomEnd = if (index == count - 1) GroupCorner else InnerCorner,
)

/** The setting a search result points at, flashed and scrolled into view when its page opens. */
internal val LocalHighlight = compositionLocalOf<String?> { null }

/**
 * Scrolls the row into view and flashes it when it's [LocalHighlight]'s target. Waits for the page's slide-in first,
 * so the flash isn't spent while the page is still arriving.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.highlight(key: String?): Modifier {
    val target = LocalHighlight.current
    if (key == null || key != target) return this
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        requester.bringIntoView()
        flash.animateTo(1f, tween(250))
        flash.animateTo(0f, tween(900, delayMillis = 700))
    }
    val color = MaterialTheme.colorScheme.primary
    return bringIntoViewRequester(requester).drawBehind { drawRect(color, alpha = 0.18f * flash.value) }
}

/** Below this height (a phone in landscape) the bar and a pinned preview would leave no room for the settings. */
private const val PIN_MIN_HEIGHT_DP = 600

/**
 * A settings page: a large title that collapses as the page scrolls, [pinned] content that stays put under it (the
 * home preview) on screens tall enough for it and scrolls with the page otherwise, then [content] in a scrolling
 * column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    pinned: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = PageColor,
        topBar = { PageBar(title, onBack, actions, scrollBehavior) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val pin = LocalConfiguration.current.screenHeightDp >= PIN_MIN_HEIGHT_DP
        // Side insets too: a 3-button nav bar sits at the side in landscape. The bottom one is scrolled past instead,
        // so rows can pass behind a gesture bar.
        Column(
            Modifier.fillMaxSize().padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
            ),
        ) {
            if (pin) pinned?.invoke()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (!pin) pinned?.invoke()
                content()
                Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
            }
        }
    }
}

/** [SettingsPage] for long lists (apps): the same bar over a lazy list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsListPage(
    title: String,
    onBack: () -> Unit,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = PageColor,
        topBar = { PageBar(title, onBack, actions, scrollBehavior) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            state = state,
            // The keyboard's height is in the bottom padding while it's up, so the end of the list stays reachable.
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction) + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    LargeTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PageColor, scrolledContainerColor = PageColor),
        scrollBehavior = scrollBehavior,
    )
}

/** A titled group of rows. The title is a heading, so TalkBack can jump group to group. */
@Composable
internal fun SettingsGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp)) {
        if (title != null) GroupTitle(title) else Spacer(Modifier.height(16.dp))
        Column(Modifier.clip(GroupShape), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun GroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 20.dp, top = 24.dp, bottom = 10.dp),
    )
}

/**
 * Plain text on the page, between groups: an explanation or an empty list's message. It starts where the rows' text
 * does: [indent] from its container, which is 36dp on a page and 20dp inside a list page's padded list.
 */
@Composable
internal fun PageText(text: String, modifier: Modifier = Modifier, indent: Dp = 36.dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = indent, vertical = 12.dp),
    )
}

/** [PageText] inside a [SettingsListPage]'s list. */
@Composable
internal fun ListText(text: String) = PageText(text, indent = 20.dp)

/**
 * One row: an optional [icon], the title and summary, then [trailing] (a switch, a button) on the right, and [below]
 * (a picker) under the text. Clickable when [onClick] is given; dimmed and inert when not [enabled].
 */
@Composable
internal fun SettingRow(
    title: String,
    summary: String? = null,
    key: String? = null,
    icon: ImageVector? = null,
    iconColors: IconColors? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RowColor)
            .highlight(key)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (enabled) 1f else 0.38f)) {
            if (icon != null) {
                RowIcon(icon, iconColors)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        if (below != null) Column(Modifier.alpha(if (enabled) 1f else 0.38f)) { below() }
    }
}

/** A category icon's bubble colors: a container color and the icon's. */
internal class IconColors(val container: Color, val content: Color)

/** The three tonal pairs category icons take turns with, so neighbours differ. */
@Composable
internal fun categoryColors(index: Int): IconColors {
    val c = MaterialTheme.colorScheme
    return when (index % 3) {
        0 -> IconColors(c.primaryContainer, c.onPrimaryContainer)
        1 -> IconColors(c.secondaryContainer, c.onSecondaryContainer)
        else -> IconColors(c.tertiaryContainer, c.onTertiaryContainer)
    }
}

@Composable
private fun RowIcon(icon: ImageVector, colors: IconColors?) {
    if (colors == null) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    } else {
        Box(Modifier.size(40.dp).background(colors.container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
        }
    }
}

/** A row that is a switch: the whole row toggles, so TalkBack reads the title and the state as one item. */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    key: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        key = key,
        enabled = enabled,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
    )
}

/** One choice of several; put a group of them in a [Modifier.selectableGroup] container. */
@Composable
internal fun RadioRow(title: String, summary: String? = null, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        trailing = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** A group of [RadioRow]s for [options], titled, with the chosen one marked. */
@Composable
internal fun <T> RadioGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    key: String? = null,
    summary: (T) -> String? = { null },
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        GroupTitle(title)
        Column(
            Modifier.clip(GroupShape).selectableGroup().highlight(key),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (option in options) {
                RadioRow(label(option), summary(option), selected = option == selected) { onSelect(option) }
            }
        }
    }
}

/** Segmented buttons for a short choice of words, under a row's text. */
@Composable
internal fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/**
 * A choice shown as picture tiles (clock styles, text colors, icon sizes), each drawn by [tile] and named under it.
 * The chosen tile takes the primary container color and an outline.
 */
@Composable
internal fun <T> TilePicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    tileHeight: Dp = 72.dp,
    onSelect: (T) -> Unit,
    tile: @Composable BoxScope.(option: T, selected: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            val border by animateColorAsState(if (isSelected) colors.primary else Color.Transparent, label = "tileBorder")
            val name by animateColorAsState(if (isSelected) colors.primary else colors.onSurfaceVariant, label = "tileLabel")
            Column(
                Modifier
                    .weight(1f)
                    .clip(TileShape)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(tileHeight)
                        .clip(TileShape)
                        .background(if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh)
                        .border(2.dp, SolidColor(border), TileShape),
                    contentAlignment = Alignment.Center,
                ) { tile(option, isSelected) }
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** A search-like pill that opens settings search. */
@Composable
internal fun SearchPill(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(56.dp)
            .clip(CircleShape)
            .background(RowColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icons that aren't in material-icons-core (paths from Material Icons, Apache 2.0). */
internal object SettingsIcons {
    val Palette = icon(
        "Palette",
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 " +
            "-0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5L16,16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83," +
            "0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 " +
            "1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5," +
            "12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
    )
    val Schedule = icon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8," +
            "-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
    val TouchApp = icon(
        "TouchApp",
        "M9,11.24V7.5C9,6.12 10.12,5 11.5,5S14,6.12 14,7.5v3.74c1.21,-0.81 2,-2.18 2,-3.74C16,5.01 13.99,3 11.5,3S7,5.01 7," +
            "7.5c0,1.56 0.79,2.93 2,3.74zM18.84,15.87l-4.54,-2.26c-0.17,-0.07 -0.35,-0.11 -0.54,-0.11H13v-6c0,-0.83 -0.67,-1.5 " +
            "-1.5,-1.5S10,6.67 10,7.5v10.74l-3.43,-0.72c-0.08,-0.01 -0.15,-0.03 -0.24,-0.03 -0.31,0 -0.59,0.13 -0.79,0.33l-0.79," +
            "0.8 4.94,4.94c0.27,0.27 0.65,0.44 1.06,0.44h6.79c0.75,0 1.33,-0.55 1.44,-1.28l0.75,-5.27c0.01,-0.07 0.02,-0.14 " +
            "0.02,-0.2 0,-0.62 -0.38,-1.16 -0.91,-1.38z",
    )
    val BackupRestore = icon(
        "BackupRestore",
        "M14,12c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2 0.9,2 2,2 2,-0.9 2,-2zM12,3c-4.97,0 -9,4.03 -9,9L0,12l4,4 4,-4L5,12c0," +
            "-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.51,0 -2.91,-0.49 -4.06,-1.3l-1.42,1.44C8.04,20.3 9.94,21 12,21c4.97," +
            "0 9,-4.03 9,-9s-4.03,-9 -9,-9z",
    )
    val DragHandle = icon("DragHandle", "M20,9H4v2h16V9zM4,15h16v-2H4v2z")
    val Code = icon(
        "Code",
        "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
