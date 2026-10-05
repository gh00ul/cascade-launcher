package com.gh00ul.cascade.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.theme.LauncherText

private class BoundsHolder {
    var rect: Rect? = null
}

/** One app in a list: icon, name, and (for favorites) the latest notification underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    app: AppEntry,
    icon: ImageBitmap?,
    notifications: List<AppNotification>,
    showIcon: Boolean,
    showPreview: Boolean,
    large: Boolean,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    onNotificationClick: (AppNotification) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = remember { BoundsHolder() }
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val hasNotifications = notifications.isNotEmpty()

    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { onClick(bounds.rect) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 8.dp, vertical = if (large) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Box {
                AppIcon(icon, if (large) 40.dp else 34.dp)
                if (hasNotifications) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(11.dp)
                            .border(1.5.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
                            .background(accent, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = if (large) LauncherText.favorite else LauncherText.app,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isWork) Text("  work", style = LauncherText.small.copy(color = Color.White.copy(alpha = 0.55f)))
                if (!showIcon && hasNotifications) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                }
            }
            val latest = notifications.firstOrNull()
            if (showPreview && latest != null) {
                NotificationPreview(latest, more = notifications.size - 1, onClick = { onNotificationClick(latest) })
            }
        }
    }
}

@Composable
private fun NotificationPreview(notification: AppNotification, more: Int, onClick: () -> Unit) {
    val text = listOf(notification.title, notification.text).filter { it.isNotBlank() }.joinToString(": ")
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LauncherText.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (more > 0) Text("  +$more", style = LauncherText.small.copy(color = MaterialTheme.colorScheme.primary))
    }
}
