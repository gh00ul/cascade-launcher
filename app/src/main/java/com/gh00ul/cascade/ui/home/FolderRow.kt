package com.gh00ul.cascade.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlin.math.roundToInt

/** Rows' press ripple shape, as AppRow's. */
private val FolderRowShape = RoundedCornerShape(16.dp)

/**
 * A folder's icon: a rounded square of [background] holding the first four of its apps' [icons] in a 2x2 grid. [size]
 * is the whole square, so it lines up with the app icons around it.
 */
@Composable
internal fun FolderIcon(icons: List<IconImage?>, size: Dp, background: Color, modifier: Modifier = Modifier, outline: Color? = null) {
    val shape = RoundedCornerShape(size * 0.3f)
    val inset = size * 0.13f
    val gap = size * 0.06f
    val cell = (size - inset * 2 - gap) / 2
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(background)
            .then(if (outline != null) Modifier.border(1.dp, outline, shape) else Modifier)
            .padding(inset),
    ) {
        icons.take(4).forEachIndexed { i, icon ->
            AppIcon(icon, cell, Modifier.offset(x = (cell + gap) * (i % 2), y = (cell + gap) * (i / 2)))
        }
    }
}

/** The folder icon's square on the home screen: the overlay's tint, see-through, with a faint edge so it holds on any wallpaper. */
@Composable
internal fun HomeFolderIcon(icons: List<IconImage?>, size: Dp, modifier: Modifier = Modifier) {
    val style = LocalLauncherStyle.current
    FolderIcon(icons, size, style.scrim.copy(alpha = if (style.darkText) 0.45f else 0.3f), modifier, outline = style.content.copy(alpha = 0.16f))
}

/** What a folder row says about its apps' notifications: the newest one and whose it is, how many in all, and whether to badge. */
@Immutable
internal data class FolderNotifications(val app: AppEntry, val latest: AppNotification, val count: Int, val badge: Boolean)

/** The newest notification among [apps] (each app's own list is newest first), or null when they have none. */
internal fun folderNotifications(apps: List<AppEntry>, byApp: Map<String, List<AppNotification>>): FolderNotifications? {
    var newest: Pair<AppEntry, AppNotification>? = null
    var count = 0
    var badge = false
    // Two launcher entries of one package share its notifications: count them once.
    for (app in apps.distinctBy { it.notificationKey }) {
        val list = byApp[app.notificationKey].orEmpty()
        val first = list.firstOrNull() ?: continue
        count += list.size
        badge = badge || list.any { it.showBadge }
        if (newest == null || first.postTime > newest.second.postTime) newest = app to first
    }
    return newest?.let { (app, n) -> FolderNotifications(app, n, count, badge) }
}

/**
 * Where a row was laid out, for the pop-up to grow from. Rows move on every scroll frame, so this keeps the
 * coordinates and works out the bounds only when the row is tapped.
 */
private class RootBoundsHolder {
    var coordinates: LayoutCoordinates? = null
    val rect: Rect? get() = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/**
 * A folder among the favorites, laid out like an app row: its icon (up to four of its apps), its name in the favorite
 * style, and under it the newest notification of its apps (with previews on) or the apps it holds. The dot shows when
 * any of them has notifications. Tapping opens the pop-up from the row's bounds ([onClick] gets them in root
 * coordinates); a long press gives its options.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: HomeFolder,
    icons: State<Map<String, IconImage>>,
    notifications: State<Map<String, List<AppNotification>>>,
    showIcon: Boolean,
    showPreview: Boolean,
    iconSize: Dp,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    onNotificationClick: (AppEntry, AppNotification) -> Unit,
    modifier: Modifier = Modifier,
    /** On home, where [liftToReorder] watches for the long press: the row leaves it alone, but TalkBack keeps it. */
    longPressInParent: Boolean = false,
) {
    val style = LocalLauncherStyle.current
    val bounds = remember { RootBoundsHolder() }
    val haptics = LocalHapticFeedback.current
    val press = rememberPressIndication()
    // Read entry by entry: an icon publish or a notification regroup recomposes this row only when what it shows changes.
    val shownKeys = remember(folder.apps) { folder.apps.take(4).map { it.key } }
    val previewIcons by remember(shownKeys, icons) { derivedStateOf { shownKeys.map { icons.value[it] } } }
    val news by remember(folder.apps, notifications) { derivedStateOf { folderNotifications(folder.apps, notifications.value) } }
    val showDot = news?.badge == true
    val labelStyle = if (showIcon) style.favoriteFor(iconSize) else style.favorite
    val capMiddle = with(LocalDensity.current) { (labelStyle.fontSize.toPx() * CapMiddle).roundToInt() }

    Row(
        modifier
            .fillMaxWidth()
            .onPlaced { bounds.coordinates = it }
            .clip(FolderRowShape)
            .combinedClickable(
                interactionSource = null,
                indication = press ?: LocalIndication.current,
                hapticFeedbackEnabled = false,
                onClickLabel = "Open folder",
                onLongClickLabel = "Folder options",
                onClick = { onClick(bounds.rect) },
                onLongClick = if (longPressInParent) null else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                },
            )
            .pressScale(press)
            .semantics {
                if (longPressInParent) {
                    onLongClick("Folder options") {
                        onLongClick()
                        true
                    }
                }
                news?.let { stateDescription = notificationCount(it.count) }
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            // As in an app's row: centered on the name's capitals, with what's inside hanging below the name.
            Box(Modifier.alignBy { it.measuredHeight / 2 + capMiddle }) {
                HomeFolderIcon(previewIcons, iconSize)
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
        Column(Modifier.weight(1f).alignBy(FirstBaseline)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder.label,
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!showIcon && showDot) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(8.dp).background(style.accent, CircleShape))
                }
            }
            val latest = news?.takeIf { showPreview }
            if (latest != null) {
                val n = latest.latest
                val text = listOf(n.title, n.text).firstOrNull { it.isNotBlank() }.orEmpty()
                Row(
                    Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNotificationClick(latest.app, n) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The app a step stronger than what it says, as under an app's own row.
                    val preview = remember(latest.app.label, text, style) { previewText(latest.app.label, text, style) }
                    Text(
                        preview,
                        style = style.small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (latest.count > 1) Text("  +${latest.count - 1}", style = style.small.copy(color = style.accent))
                }
            } else {
                // What's inside, so the folder reads as one even with icons off.
                Text(
                    folder.apps.joinToString(", ") { it.label },
                    style = style.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
