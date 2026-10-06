package com.gh00ul.cascade.ui.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.common.rememberEntry
import com.gh00ul.cascade.ui.theme.GlassEdgeWidth
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** An open folder: which one, and its row's bounds in root coordinates (null when unknown), where the pop-up grows from. */
@Immutable
internal class OpenFolder(val id: String, val anchor: Rect?)

/** The pop-up shares the player's and the home cards' corners. */
internal val PopupShape = RoundedCornerShape(20.dp)
/** Narrow enough to read as a pop-up beside the list, on a phone and on a tablet alike. */
private val PopupMaxWidth = 320.dp
/** Kept this far from the screen's edges, inside the system bars. */
internal val PopupMargin = 12.dp
/** Between the card and the row it grows from. */
private val PopupGap = 4.dp
/** Inside the card, before the rows' own 8dp, so the icons sit 16dp in. */
private val PopupInset = 8.dp

/** How small the card starts as it pops from the row, and how much a predictive back gesture shrinks it. */
private const val PopStartScale = 0.85f
private const val PopBackShrink = 0.08f
/** How much the home screen dims behind the card. */
private const val DimAlpha = 0.45f
/** The card: the overlay's tint lifted a step toward the text color, opaque, so the rows behind never show through. */
internal const val CardLift = 0.09f

/** The card grows in like an arrival and leaves quickly, as the layers it sits on do. */
private val PopIn: FiniteAnimationSpec<Float> = tween(Motion.ENTER, easing = Motion.Decelerate)
private val PopOut: FiniteAnimationSpec<Float> = tween(Motion.QUICK, easing = Motion.Accelerate)

/**
 * A folder's apps in a card over the home screen, popping from the folder's row: a list like the A–Z one (icon, name,
 * notification dot), titled with the folder's name, on a dim that closes it when tapped. It sits beside the row
 * ([anchor], root coordinates), above it when there's room since favorites sit low, kept inside [contentPadding] (the
 * system bars) and scrolling when the folder is long. Back closes it; on Android 13+ a predictive back gesture shrinks
 * it first. Shows while [folder] isn't null, and keeps the last one while it closes.
 *
 * A list rather than an icon grid: Cascade is a list launcher with names first and icons optional, and rows carry the
 * same dots, press feedback and long-press as everywhere else.
 */
@Composable
internal fun FolderPopup(
    folder: HomeFolder?,
    anchor: Rect?,
    icons: State<Map<String, IconImage>>,
    notifications: State<Map<String, List<AppNotification>>>,
    showIcons: Boolean,
    /** The favorites' icon size: the card grows from the folder icon's middle. */
    homeIconSize: Dp,
    /** The rows' icon size, the A–Z list's. */
    iconSize: Dp,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOptions: () -> Unit,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    // The last folder shown, so the card still has something to draw while it closes.
    val shown = remember { Latest<Pair<HomeFolder, Rect?>>() }.also { if (folder != null) it.value = folder to anchor }
    PopupLayer(visible = folder != null, title = { shown.value?.first?.label.orEmpty() }, onDismiss = onDismiss) { scale ->
        val (current, from) = shown.value ?: return@PopupLayer
        AnchoredCard(
            anchor = from,
            // The folder icon's middle: the row's 8dp padding, then half the icon.
            originX = 8.dp + homeIconSize / 2,
            scale = scale,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        ) {
            FolderCard(current, icons, notifications, showIcons, iconSize, onLaunch, onAppLongPress, onOptions)
        }
    }
}

/**
 * What every pop-up over home shares: a dim that closes it when tapped, a pane for TalkBack named [title], Back (on
 * Android 13+ a predictive back gesture shrinks the card first), and the card popping in and out. Shows while
 * [visible]. [content] lays the card out and draws it at the scale it's given, read only in the card's layer.
 */
@Composable
internal fun PopupLayer(visible: Boolean, title: () -> String, onDismiss: () -> Unit, content: @Composable (scale: () -> Float) -> Unit) {
    val style = LocalLauncherStyle.current
    AnimatedVisibility(visible = visible, enter = Motion.LayerIn, exit = Motion.LayerOut) {
        val open = transition.targetState == EnterExitState.Visible
        val pop = transition.animateFloat(transitionSpec = { if (targetState == EnterExitState.Visible) PopIn else PopOut }, label = "pop") {
            if (it == EnterExitState.Visible) 1f else 0f
        }
        // 0 at rest; follows a predictive back gesture. Read only in the card's layer.
        val back = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        if (Build.VERSION.SDK_INT >= 33) {
            PredictiveBackHandler(enabled = open) { events ->
                try {
                    events.collect { back.snapTo(it.progress) }
                } catch (e: CancellationException) {
                    // This coroutine is cancelled, so the spring back is launched outside it.
                    if (back.value != 0f) scope.launch { back.animateTo(0f, Motion.SwipeBack) }
                    throw e
                }
                // The exit shrinks the card on from where the gesture left it.
                onDismiss()
            }
        } else {
            BackHandler(enabled = open, onBack = onDismiss)
        }
        // Opened again while closing after a back gesture: grow back from where it was.
        LaunchedEffect(open) { if (open && back.value != 0f) back.animateTo(0f, Motion.SwipeBack) }

        val dim = style.scrim
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(dim, alpha = DimAlpha) }
                // A pane for TalkBack; it also keeps the home screen underneath out of reach.
                .semantics { paneTitle = title() }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            content { (PopStartScale + (1f - PopStartScale) * pop.value) * (1f - PopBackShrink * back.value) }
        }
    }
}

/**
 * Lays [content] (the card) out beside [anchor]: lined up with the row's start, above it if it fits there, else below,
 * else over it, always inside this layout less [PopupMargin]. It scales by [scale] (read only in its layer) around the
 * point it grows from: [originX] into the row, on the card's edge nearest the row.
 */
@Composable
private fun AnchoredCard(anchor: Rect?, originX: Dp, scale: () -> Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val margin = PopupMargin.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cardWidth = minOf(PopupMaxWidth.roundToPx(), width - 2 * margin).coerceAtLeast(0)
        val card = measurables.first().measure(Constraints.fixedWidth(cardWidth).copy(maxHeight = (height - 2 * margin).coerceAtLeast(0)))
        layout(width, height) {
            // The anchor came from the root's coordinates; this layout may sit below the status bar.
            val here = coordinates
            val row = anchor?.let { a ->
                if (here == null) a else {
                    val root = here.findRootCoordinates()
                    Rect(here.localPositionOf(root, a.topLeft), here.localPositionOf(root, a.bottomRight))
                }
            }
            val gap = PopupGap.roundToPx()
            val maxX = (width - margin - card.width).coerceAtLeast(margin)
            val maxY = (height - margin - card.height).coerceAtLeast(margin)
            val x: Int
            val y: Int
            val originY: Float
            if (row == null) {
                x = (width - card.width) / 2
                y = maxY
                originY = 1f
            } else {
                // The card's inset lines its icons up with the row's.
                x = (row.left.roundToInt() - PopupInset.roundToPx()).coerceIn(margin, maxX)
                val above = row.top.roundToInt() - gap - card.height
                val below = row.bottom.roundToInt() + gap
                // Clamped in every branch: a rotation keeps the folder open with the row's old position, which can now
                // lie past the bottom of the screen.
                when {
                    above >= margin -> { y = above.coerceIn(margin, maxY); originY = 1f }
                    below + card.height <= height - margin -> { y = below.coerceIn(margin, maxY); originY = 0f }
                    else -> {
                        y = (row.bottom.roundToInt() - card.height).coerceIn(margin, maxY)
                        originY = ((row.center.y - y) / card.height).coerceIn(0f, 1f)
                    }
                }
            }
            val fromX = row?.let { ((it.left + originX.toPx() - x) / card.width.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0.5f
            val origin = TransformOrigin(fromX, originY)
            card.placeWithLayer(x, y) {
                val s = scale()
                scaleX = s
                scaleY = s
                transformOrigin = origin
            }
        }
    }
}

/** The card: the folder's name and an options button, then its apps, scrolling when there are more than fit. */
@Composable
private fun FolderCard(
    folder: HomeFolder,
    icons: State<Map<String, IconImage>>,
    notifications: State<Map<String, List<AppNotification>>>,
    showIcons: Boolean,
    iconSize: Dp,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOptions: () -> Unit,
) {
    val style = LocalLauncherStyle.current
    Surface(
        shape = PopupShape,
        color = lerp(style.scrim, style.content, CardLift),
        contentColor = style.content,
        border = BorderStroke(GlassEdgeWidth, style.glassEdge),
        // Taps between the rows stay on the card instead of closing it.
        modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
    ) {
        Column(Modifier.padding(bottom = PopupInset)) {
            Row(Modifier.fillMaxWidth().padding(start = PopupInset + 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder.label.uppercase(),
                    style = style.section,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options", tint = style.content.copy(alpha = 0.8f)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (app in folder.apps) {
                    key(app.key) {
                        val icon by rememberEntry(icons, app.key, referentialEqualityPolicy())
                        val appNotifications by rememberEntry(notifications, app.notificationKey)
                        AppRow(
                            app = app,
                            icon = icon,
                            notifications = appNotifications.orEmpty(),
                            showIcon = showIcons,
                            showPreview = false,
                            large = false,
                            iconSize = iconSize,
                            onClick = { onLaunch(app, it) },
                            onLongClick = { onAppLongPress(app) },
                            onNotificationClick = {},
                            modifier = Modifier.padding(horizontal = PopupInset),
                        )
                    }
                }
            }
        }
    }
}
