package com.gh00ul.cascade.ui.home.widgets

import android.os.Build
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.WIDGET_CALENDAR
import com.gh00ul.cascade.data.WIDGET_WEATHER
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.data.WidgetHostView
import com.gh00ul.cascade.data.appWidgetId
import com.gh00ul.cascade.data.isStackWidget
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

/** The stack's card: as tall as a 4x2 app widget, and no wider than reads well on a tablet. */
internal val StackHeight = 176.dp
internal val StackMaxWidth = 560.dp
/** HomePage's side padding, which the stack sits inside. */
private val HomeSidePadding = 64.dp
/** The home page's cards share the player's corners. */
private val StackShape = RoundedCornerShape(20.dp)
private val DotSize = 6.dp
private val DotGap = 6.dp

/**
 * The widget stack under the clock: up to four widgets on one card (the home page's card look), swiped between, with a
 * dot per widget under it while there's more than one. Draws nothing (zero size) while the stack is empty.
 *
 * A long press anywhere a widget doesn't take the press itself calls [onEdit], for WidgetStackSheet; so does a long
 * press on an app widget's buttons. The page it shows is kept for the process, so scrolling the home page away and back
 * or a return home keeps it, and a widget just added is the one shown next. Swipes are horizontal only, so the home
 * list still scrolls when a drag starts out vertical.
 */
@Composable
fun WidgetStack(settings: LauncherSettings, modifier: Modifier = Modifier, onEdit: () -> Unit) {
    val entries = remember(settings.widgetStack) { settings.widgetStack.filter(::isStackWidget).also(StackMemory::see) }
    if (entries.isEmpty()) return
    val style = LocalLauncherStyle.current
    val haptics = LocalHapticFeedback.current
    val currentOnEdit by rememberUpdatedState(onEdit)
    // Read through a state by the press handler below, which outlives recompositions (haptics can be switched off).
    val edit by rememberUpdatedState<() -> Unit> {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        currentOnEdit()
    }
    val pager = rememberPagerState(initialPage = entries.indexOf(StackMemory.page).coerceAtLeast(0)) { entries.size }
    val currentEntries by rememberUpdatedState(entries)
    // A widget added, removed or moved: show the one the memory points at (the one just added, or the one shown).
    LaunchedEffect(entries) {
        val target = entries.indexOf(StackMemory.page)
        if (target >= 0 && target != pager.currentPage) pager.scrollToPage(target)
    }
    // The first value is where the pager starts, which the memory already holds.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page -> currentEntries.getOrNull(page)?.let { StackMemory.page = it } }
    }

    Column(modifier.padding(top = 12.dp).widthIn(max = StackMaxWidth).fillMaxWidth()) {
        Surface(
            shape = StackShape,
            color = style.scrim.copy(alpha = 0.55f),
            contentColor = style.content,
            modifier = Modifier
                .fillMaxWidth()
                .height(StackHeight)
                // Keeps the home page's own long press (its menu) and double tap off the stack.
                .pointerInput(Unit) { detectTapGestures(onLongPress = { edit() }) }
                .semantics { customActions = listOf(CustomAccessibilityAction("Edit widgets") { currentOnEdit(); true }) },
        ) {
            HorizontalPager(
                state = pager,
                key = { entries[it] },
                // Every page stays composed: app widget views aren't rebuilt as they scroll off and back.
                beyondViewportPageCount = entries.size - 1,
                // The wave's fall is critically damped, so a page lands without overshoot.
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = Motion.WaveFall),
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val entry = entries[page]
                when (entry) {
                    WIDGET_CALENDAR -> CalendarWidget(settings, onLongPress = { edit() })
                    WIDGET_WEATHER -> WeatherWidget(settings, onLongPress = { edit() })
                    else -> appWidgetId(entry)?.let { AppWidgetPage(it, onLongPress = { edit() }) }
                }
            }
        }
        if (entries.size > 1) PageDots(pager, entries.size, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}

/**
 * The stack's page for the whole process, by entry, and the stack it last saw, to spot a widget added from anywhere
 * (the edit sheet, Settings) and show it next.
 */
private object StackMemory {
    var page: String? = null
    private var seen: List<String>? = null

    fun see(entries: List<String>) {
        seen?.let { before -> entries.lastOrNull { it !in before }?.let { page = it } }
        seen = entries
    }

    fun reset() {
        page = null
        seen = null
    }
}

/** Forgets the page the stack showed, so a screenshot starts on the first widget whatever ran before it. */
@VisibleForTesting
internal fun resetWidgetStackMemory() = StackMemory.reset()

/** A page's width when the stack is as wide as the home page lets it be, for binding an app widget at that size. */
@Composable
internal fun stackPageWidthDp(): Int =
    minOf(LocalConfiguration.current.screenWidthDp - HomeSidePadding.value.toInt(), StackMaxWidth.value.toInt()).coerceAtLeast(1)

/** A dot per widget, the shown one brighter; read in the draw, so a swipe redraws them without recomposing. */
@Composable
private fun PageDots(pager: PagerState, count: Int, modifier: Modifier = Modifier) {
    val color = LocalLauncherStyle.current.content
    Canvas(modifier.size(width = DotSize * count + DotGap * (count - 1), height = DotSize).clearAndSetSemantics {}) {
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val radius = size.height / 2
        val step = (DotSize + DotGap).toPx()
        for (i in 0 until count) {
            val near = (1f - abs(position - i)).coerceIn(0f, 1f)
            drawCircle(color, radius, Offset(radius + i * step, radius), alpha = 0.35f + 0.55f * near)
        }
    }
}

/**
 * An Android app widget filling its page. Its view is made once per activity (WidgetHost.view) and moved into this
 * page whenever the page is composed again; its long press, buttons included, is the stack's.
 */
@Composable
private fun AppWidgetPage(id: Int, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val info = remember(id) { WidgetHost.info(context, id) }
    val view = remember(id, info) { info?.let { WidgetHost.view(context, id, it) } }
    if (info == null || view == null) {
        WidgetMessage(Icons.Outlined.Info, "Widget unavailable", "Its app may have been removed. Long-press to edit the stack.")
        return
    }
    val darkText = LocalLauncherStyle.current.darkText
    val longPress by rememberUpdatedState(onLongPress)
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view.onLongPress = { longPress() }
            view
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.onLongPress = null },
        // Widgets that offer one switch to their look for light backgrounds under dark text.
        update = { if (Build.VERSION.SDK_INT >= 31) it.setOnLightBackground(darkText) },
    )
}

/** Text on the stack's card: the launcher's text color, without the shadow text on the bare wallpaper gets. */
@Immutable
internal class WidgetText(style: LauncherStyle) {
    val label = TextStyle(color = style.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    val title = TextStyle(color = style.content, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val secondary = TextStyle(color = style.content.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    val small = TextStyle(color = style.content.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val value = TextStyle(color = style.content, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val temperature = TextStyle(color = style.content, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
    val accent = secondary.copy(color = style.accent, fontWeight = FontWeight.Medium)
    /** A chance of rain, in a water blue that reads on either scrim. */
    val rain = small.copy(color = if (style.darkText) Color(0xFF1A5FB4) else Color(0xFF9CCBFF))
}

@Composable
internal fun rememberWidgetText(): WidgetText {
    val style = LocalLauncherStyle.current
    return remember(style) { WidgetText(style) }
}

/**
 * A widget's tap and long press: [onClick] on a tap; on a long press, the stack's edit sheet, as anywhere on the
 * stack. The long press haptic is the stack's own, so the default one is off.
 */
internal fun Modifier.widgetClick(label: String, onLongPress: () -> Unit, onClick: () -> Unit): Modifier = combinedClickable(
    onClickLabel = label,
    role = Role.Button,
    onLongClickLabel = "Edit widgets",
    onLongClick = onLongPress,
    hapticFeedbackEnabled = false,
    onClick = onClick,
)

/**
 * A widget with nothing to show yet: an icon, a line or two, and an optional [action] button, styled like the home
 * page's cards. A tap does [onClick] when given.
 */
@Composable
internal fun WidgetMessage(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onLongPress: () -> Unit = {},
) {
    val style = LocalLauncherStyle.current
    Column(
        modifier
            .fillMaxSize()
            .then(if (onClick != null) Modifier.widgetClick(title, onLongPress, onClick) else Modifier)
            .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(end = 8.dp)) {
            Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.padding(top = 2.dp).size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = style.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.content.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = style.content, contentColor = style.scrim),
                ) { Text(action) }
            }
        }
    }
}
