package com.gh00ul.cascade.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private class BoundsHolder {
    var rect: Rect? = null
}

/**
 * One app in a list: icon, name, and (for favorites) the latest notification underneath.
 * Swiping the row to the right opens all of its notifications inline (when [onToggleExpand] is given).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    app: AppEntry,
    icon: IconImage?,
    notifications: List<AppNotification>,
    showIcon: Boolean,
    showPreview: Boolean,
    large: Boolean,
    iconSize: Dp,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    onNotificationClick: (AppNotification) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    val style = LocalLauncherStyle.current
    val bounds = remember { BoundsHolder() }
    val haptics = LocalHapticFeedback.current
    val hasNotifications = notifications.isNotEmpty()
    val showDot = notifications.any { it.showBadge }
    val canExpand = hasNotifications && onToggleExpand != null
    val showExpanded = expanded && hasNotifications

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .rowSwipe(onSwipeRight = onToggleExpand.takeIf { canExpand })
                .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    // The long-press haptic is fired by hand below; the default would fire it twice.
                    hapticFeedbackEnabled = false,
                    onLongClickLabel = "App options",
                    onClick = { onClick(bounds.rect) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .semantics {
                    // The badge dot is visual only.
                    if (hasNotifications) stateDescription = notificationCount(notifications.size)
                    if (canExpand) {
                        customActions = listOf(
                            CustomAccessibilityAction(if (showExpanded) "Hide notifications" else "Show notifications") {
                                onToggleExpand?.invoke()
                                true
                            },
                        )
                    }
                }
                .padding(horizontal = 8.dp, vertical = if (large) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Box {
                    AppIcon(icon, iconSize)
                    if (showDot) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-3).dp)
                                .size(11.dp)
                                .border(1.5.dp, style.scrim.copy(alpha = 0.35f), CircleShape)
                                .background(style.accent, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = if (large) style.favorite else style.app,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isManagedProfile) Text("  work", style = style.small.copy(color = style.content.copy(alpha = 0.55f)))
                    if (!showIcon && showDot) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(8.dp).background(style.accent, CircleShape))
                    }
                }
                val latest = notifications.firstOrNull()
                if (showPreview && latest != null && !showExpanded) {
                    NotificationPreview(latest, more = notifications.size - 1, onClick = { onNotificationClick(latest) })
                }
            }
        }

        AnimatedVisibility(
            visible = showExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ExpandedNotifications(
                notifications = notifications,
                startPadding = if (showIcon) iconSize + 24.dp else 8.dp,
                onOpen = onNotificationClick,
            )
        }
    }
}

@Composable
private fun NotificationPreview(notification: AppNotification, more: Int, onClick: () -> Unit) {
    val style = LocalLauncherStyle.current
    val text = listOf(notification.title, notification.text).filter { it.isNotBlank() }.joinToString(": ")
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = style.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (more > 0) Text("  +$more", style = style.small.copy(color = style.accent))
    }
}

/** Spoken state for a row's notification badge. */
internal fun notificationCount(n: Int) = if (n == 1) "1 notification" else "$n notifications"

/** Every notification of one app; tap to open, swipe sideways to dismiss. */
@Composable
internal fun ExpandedNotifications(notifications: List<AppNotification>, startPadding: androidx.compose.ui.unit.Dp, onOpen: (AppNotification) -> Unit) {
    val style = LocalLauncherStyle.current
    Column(Modifier.fillMaxWidth().padding(start = startPadding, end = 8.dp, bottom = 6.dp)) {
        for (n in notifications.take(8)) {
            key(n.key) { NotificationItem(n, onOpen) }
        }
        if (notifications.count { it.clearable } > 1) {
            TextButton(onClick = { NotificationStore.dismissAll(notifications) }) {
                Text("Clear all", style = style.small.copy(color = style.accent, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationItem(notification: AppNotification, onOpen: (AppNotification) -> Unit) {
    val style = LocalLauncherStyle.current
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {},
        enableDismissFromStartToEnd = notification.clearable,
        enableDismissFromEndToStart = notification.clearable,
        // Runs only once the swipe settles, so dragging back before letting go cancels nothing.
        onDismiss = {
            NotificationStore.dismiss(notification)
            // The row leaves when the store drops the notification; if the cancel didn't go through, bring it back.
            scope.launch {
                delay(1_000)
                state.reset()
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpen(notification) }
                .semantics {
                    if (notification.clearable) {
                        customActions = listOf(
                            CustomAccessibilityAction("Dismiss") {
                                NotificationStore.dismiss(notification)
                                true
                            },
                        )
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title.ifEmpty { notification.text },
                    style = style.small.copy(color = style.content, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val now = System.currentTimeMillis()
                val age = if (now - notification.postTime < DateUtils.MINUTE_IN_MILLIS) "now" else {
                    DateUtils.getRelativeTimeSpanString(notification.postTime, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                }
                Text(
                    "  $age",
                    style = style.small.copy(color = style.content.copy(alpha = 0.55f)),
                    maxLines = 1,
                )
            }
            if (notification.title.isNotEmpty() && notification.text.isNotEmpty()) {
                Text(notification.text, style = style.small, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Horizontal swipe shared by app rows and the music player row: the row follows the finger (heavily damped when
 * the direction has no action), ticks when it crosses the threshold, and runs the action on release.
 */
@Composable
internal fun Modifier.rowSwipe(onSwipeRight: (() -> Unit)?, onSwipeLeft: (() -> Unit)? = null): Modifier {
    val dragX = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    // Keyed on Unit and reading the latest actions, so a callback appearing mid-drag doesn't restart the gesture.
    return pointerInput(Unit) {
        var crossed = false
        detectHorizontalDragGestures(
            onDragStart = { crossed = false },
            onDragEnd = {
                if (crossed) (if (dragX.value > 0f) right else left)?.invoke()
                scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
            },
            onDragCancel = { scope.launch { dragX.animateTo(0f) } },
            onHorizontalDrag = { change, amount ->
                change.consume()
                val hasRight = right != null
                val hasLeft = left != null
                val active = if (dragX.value + amount > 0f) hasRight else hasLeft
                // Rows without a left action keep the old right-only behaviour.
                val min = if (hasLeft) -threshold * 1.5f else 0f
                val next = (dragX.value + amount * if (active) 0.6f else 0.15f).coerceIn(min, threshold * 1.5f)
                scope.launch { dragX.snapTo(next) }
                val now = active && abs(next) >= threshold
                if (now != crossed) {
                    crossed = now
                    if (now) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
        )
    }.graphicsLayer { translationX = dragX.value }
}
