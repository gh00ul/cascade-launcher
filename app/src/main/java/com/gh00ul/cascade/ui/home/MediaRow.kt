package com.gh00ul.cascade.ui.home

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NowPlayingState
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.GlassEdgeWidth
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/** Colours for the player, derived from the album art and guaranteed readable in both text modes. */
@Immutable
internal class MediaColors(
    val washStart: Color,
    val washEnd: Color,
    val button: Color,
    val onButton: Color,
    val line: Color,
    val track: Color,
)

internal fun mediaColors(seed: Int?, style: LauncherStyle, monochrome: Boolean): MediaColors {
    val dark = style.darkText
    val track = style.content.copy(alpha = if (dark) 0.20f else 0.24f)
    val neutral = MediaColors(
        washStart = style.scrim.copy(alpha = if (dark) 0.34f else 0.22f),
        washEnd = style.scrim.copy(alpha = if (dark) 0.26f else 0.20f),
        button = style.content,
        onButton = style.scrim,
        line = style.content,
        track = track,
    )
    if (seed == null || monochrome) return neutral
    val hct = FloatArray(3).also { ColorUtils.colorToM3HCT(seed, it) }
    if (hct[1] < 12f) return neutral
    fun tone(chroma: Float, tone: Float) = Color(ColorUtils.M3HCTToColor(hct[0], chroma, tone))
    val accentChroma = hct[1].coerceIn(32f, 48f)
    // HCT tone distance guarantees contrast: 82 vs 12 is about 10:1, 32 vs 98 about 8:1.
    return if (dark) {
        MediaColors(
            washStart = tone(min(hct[1], 28f), 92f).copy(alpha = 0.60f),
            washEnd = Color.White.copy(alpha = 0.36f),
            button = tone(accentChroma, 32f),
            onButton = tone(min(hct[1], 8f), 98f),
            line = tone(accentChroma, 32f),
            track = track,
        )
    } else {
        MediaColors(
            washStart = tone(min(hct[1], 36f), 22f).copy(alpha = 0.45f),
            washEnd = Color.Black.copy(alpha = 0.30f),
            button = tone(accentChroma, 82f),
            onButton = tone(min(hct[1], 16f), 12f),
            line = tone(accentChroma, 82f),
            track = track,
        )
    }
}

/**
 * The color the wash behind the favorites takes on while [seed]'s art plays: a deep tone of it under white text, a pale
 * one under dark text, and soft enough that the favorites over it still read. Null for art with too little color, or
 * with [monochrome] icons, which get no glow.
 */
internal fun glowColor(seed: Int?, style: LauncherStyle, monochrome: Boolean): Color? {
    if (seed == null || monochrome) return null
    val hct = FloatArray(3).also { ColorUtils.colorToM3HCT(seed, it) }
    if (hct[1] < 12f) return null
    val color = Color(ColorUtils.M3HCTToColor(hct[0], hct[1].coerceIn(24f, 48f), if (style.darkText) 88f else 32f))
    return color.copy(alpha = if (style.darkText) 0.55f else 0.5f)
}

/** Holds the last non-null value so an exit animation still has something to draw. */
internal class Latest<T>(var value: T? = null)

private val PlayerShape = RoundedCornerShape(20.dp)
/** With the controls tier showing, the row's lower corners tuck in toward it. */
private val PlayerTopShape = RoundedCornerShape(20.dp, 20.dp, 8.dp, 8.dp)

/**
 * The music app's row turned into a player: art, title and artist, play/pause, then previous / seek bar / next.
 * Swipe right for notifications (like every row), swipe left for the next track. When [resting], only the first
 * tier shows, with a static line marking where playback stopped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    state: NowPlayingState,
    appLabel: String?,
    icon: IconImage?,
    notifications: List<AppNotification>,
    showArt: Boolean,
    /** Icon size of the favorites around it; art, text and controls line up with their columns. */
    iconSize: Dp,
    monochrome: Boolean,
    resting: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    onToggleExpand: () -> Unit,
    onNotificationClick: (AppNotification) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLauncherStyle.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val target = remember(state.artSeed, style, monochrome) { mediaColors(state.artSeed, style, monochrome) }
    val washStart by animateColorAsState(target.washStart, tween(Motion.BLEND), label = "washStart")
    val washEnd by animateColorAsState(target.washEnd, tween(Motion.BLEND), label = "washEnd")
    val button by animateColorAsState(target.button, tween(Motion.BLEND), label = "button")
    val onButton by animateColorAsState(target.onButton, tween(Motion.BLEND), label = "onButton")
    val line by animateColorAsState(target.line, tween(Motion.BLEND), label = "line")
    // No Motion match for the pause dim: it follows a tap, so it is quicker than the art's BLEND, but a QUICK one flashes.
    val washAlpha by animateFloatAsState(if (state.isPaused) 0.6f else 1f, tween(400), label = "washAlpha")

    var skipDir by remember { mutableIntStateOf(0) }
    LaunchedEffect(skipDir) {
        if (skipDir != 0) {
            delay(3_000)
            skipDir = 0
        }
    }
    var scrubMs by remember(state.trackKey) { mutableStateOf<Long?>(null) }
    val next: () -> Unit = {
        skipDir = 1
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        state.next()
    }
    val previous: () -> Unit = {
        skipDir = -1
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        state.previous()
    }
    val toggle: (Boolean) -> Unit = { play ->
        haptics.performHapticFeedback(if (play) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        if (play) state.play() else state.pause()
    }
    val longPress by rememberUpdatedState(onLongClick)
    val hasNotifications = notifications.isNotEmpty()
    val showDot = notifications.any { it.showBadge }
    val tier2 = !resting && (state.hasDuration || state.isLive || state.canSkipPrevious || state.canSkipNext)
    val restFraction = if (resting && state.hasDuration) {
        (state.positionAt(SystemClock.elapsedRealtime()).toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else null
    val press = rememberPressIndication()

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { longPress?.invoke() }) }
                // Redrawn with every progress tick and scroll frame; the gradient is rebuilt only while its colors animate.
                .drawWithCache {
                    val brush = Brush.horizontalGradient(
                        listOf(washStart, washEnd),
                        startX = if (rtl) size.width else 0f,
                        endX = if (rtl) 0f else size.width,
                    )
                    val corner = CornerRadius(20.dp.toPx())
                    // The glass edge, inset by half its width so the round rect's stroke stays inside the card.
                    val edge = GlassEdgeWidth.toPx()
                    val edgeCorner = CornerRadius(20.dp.toPx() - edge / 2)
                    val edgeStroke = Stroke(edge)
                    onDrawBehind {
                        drawRoundRect(brush, cornerRadius = corner, alpha = washAlpha)
                        drawRoundRect(
                            style.glassEdge,
                            topLeft = Offset(edge / 2, edge / 2),
                            size = Size(size.width - edge, size.height - edge),
                            cornerRadius = edgeCorner,
                            style = edgeStroke,
                            alpha = washAlpha,
                        )
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .rowSwipe(onSwipeRight = onToggleExpand.takeIf { hasNotifications }, onSwipeLeft = next.takeIf { state.canSkipNext })
                    .clip(if (tier2) PlayerTopShape else PlayerShape)
                    .combinedClickable(
                        // Built on the first press, as on AppRow.
                        interactionSource = null,
                        indication = press ?: LocalIndication.current,
                        onClickLabel = "Open player",
                        onLongClickLabel = onLongClickLabel,
                        hapticFeedbackEnabled = false,
                        onLongClick = onLongClick?.let { longClick ->
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Answered (the app's menu opens over the row, or the player hides): the press ends
                                // now, as on an app row, not when the finger lifts.
                                press?.cancel()
                                longClick()
                            }
                        },
                        onClick = onOpen,
                    )
                    // After the clickable, so the ripple still fills the card's shape; the play/pause button has its own
                    // clickable, so pressing it doesn't shrink the row.
                    .pressScale(press)
                    .semantics {
                        contentDescription = listOfNotNull(state.title, state.subtitle.ifEmpty { null }, appLabel).joinToString(", ")
                        val playback = when {
                            state.isBuffering -> "Loading"
                            state.isPlaying -> "Playing"
                            else -> "Paused"
                        }
                        // The badge dot is visual only.
                        stateDescription = if (hasNotifications) "$playback, ${notificationCount(notifications.size)}" else playback
                        customActions = buildList {
                            if (state.canPlayPause) add(CustomAccessibilityAction(if (state.isPaused) "Play" else "Pause") { toggle(state.isPaused); true })
                            if (state.canSkipNext) add(CustomAccessibilityAction("Next track") { next(); true })
                            if (state.canSkipPrevious) add(CustomAccessibilityAction("Previous track") { previous(); true })
                            if (hasNotifications) {
                                add(CustomAccessibilityAction(if (expanded) "Hide notifications" else "Show notifications") { onToggleExpand(); true })
                            }
                            if (!state.isPlaying) add(CustomAccessibilityAction("Hide player") { onHide(); true })
                        }
                    }
                    .then(
                        if (restFraction != null) {
                            Modifier.drawBehind {
                                // Both ends stay clear of the 20dp corner clip, which cuts in about 11dp at this height.
                                val inset = 16.dp.toPx()
                                val start = maxOf((if (showArt) iconSize + 24.dp else 8.dp).toPx(), inset)
                                val end = size.width - inset
                                val y = size.height - 3.dp.toPx()
                                val stroke = 2.dp.toPx()
                                val stop = start + (end - start) * restFraction
                                fun x(v: Float) = if (rtl) size.width - v else v
                                drawLine(target.track, Offset(x(start), y), Offset(x(end), y), stroke, StrokeCap.Round)
                                drawLine(line, Offset(x(start), y), Offset(x(stop), y), stroke, StrokeCap.Round)
                            }
                        } else Modifier,
                    )
                    .heightIn(min = maxOf(64.dp, iconSize + 24.dp))
                    .padding(start = if (showArt) 4.dp else 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showArt) {
                    MediaArt(state.art, icon, showDot, monochrome, iconSize + 8.dp)
                    Spacer(Modifier.width(12.dp))
                }
                TrackText(state, appLabel, scrubMs, skipDir, rtl, Modifier.weight(1f))
                if (!showArt && showDot) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).background(style.accent, CircleShape))
                }
                if (state.canPlayPause) {
                    Spacer(Modifier.width(8.dp))
                    PlayPauseButton(state, button, onButton, toggle)
                }
            }
            AnimatedVisibility(
                visible = tier2,
                enter = Motion.ExpandDown,
                exit = Motion.CollapseUp,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = if (showArt) iconSize + 12.dp else 0.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val skips = state.canSkipPrevious || state.canSkipNext
                    if (skips) {
                        IconButton(onClick = previous, enabled = state.canSkipPrevious) {
                            Icon(ExtraIcons.SkipPrevious, contentDescription = "Previous track")
                        }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            state.canSeek -> key(state.trackKey) { SeekBar(state, line, target.track) { scrubMs = it } }
                            state.hasDuration -> ProgressLine(state, line, target.track)
                            state.isLive -> LiveLabel(line)
                        }
                    }
                    if (skips) {
                        IconButton(onClick = next, enabled = state.canSkipNext) {
                            Icon(ExtraIcons.SkipNext, contentDescription = "Next track")
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded && hasNotifications,
            enter = Motion.ExpandDown,
            exit = Motion.CollapseUp,
        ) {
            // The title's x: 4dp padding, the art (iconSize + 8dp) and its 12dp gap; 8dp padding without art.
            ExpandedNotifications(notifications, textStart = if (showArt) iconSize + 24.dp else 8.dp, onOpen = onNotificationClick, onHide = onToggleExpand)
        }
    }
}

private data class TrackLabel(val key: String, val title: String, val subtitle: String)

@Composable
private fun TrackText(state: NowPlayingState, appLabel: String?, scrubMs: Long?, skipDir: Int, rtl: Boolean, modifier: Modifier) {
    val style = LocalLauncherStyle.current
    val label = TrackLabel(state.trackKey, state.title, state.subtitle.ifEmpty { appLabel.orEmpty() })
    AnimatedContent(
        targetState = label,
        modifier = modifier.clearAndSetSemantics {},
        transitionSpec = {
            val dir = if (rtl) -skipDir else skipDir
            if (dir == 0) {
                Motion.swap()
            } else {
                // Each slide runs with its fade: the new title decelerates in from the skip's side, the old one leaves fast.
                (slideInHorizontally(tween(Motion.ENTER, Motion.EXIT, Motion.Decelerate)) { dir * it / 6 } + Motion.FadeIn) togetherWith
                    (slideOutHorizontally(tween(Motion.EXIT, easing = Motion.Accelerate)) { -dir * it / 6 } + Motion.FadeOut)
            }
        },
        contentAlignment = Alignment.CenterStart,
        label = "track",
        contentKey = { it.key },
    ) { current ->
        Column {
            Text(current.title, style = style.mediaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Keyed on whether a scrub is running, not its value, so each drag step updates the readout in place.
            AnimatedContent(
                targetState = scrubMs,
                transitionSpec = { (fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK))) using null },
                label = "subtitle",
                contentKey = { it != null },
            ) { scrub ->
                if (scrub != null) {
                    Text("${formatTime(scrub)} / ${formatTime(state.durationMs)}", style = style.mediaTime, maxLines = 1)
                } else if (current.subtitle.isNotEmpty()) {
                    Text(current.subtitle, style = style.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MediaArt(art: ImageBitmap?, icon: IconImage?, showDot: Boolean, monochrome: Boolean, size: Dp) {
    val style = LocalLauncherStyle.current
    Box {
        // Both arts show at once, so the crossfade spans a whole swap (EXIT, then ENTER) rather than splitting into the two.
        Crossfade(art, animationSpec = tween(Motion.EXIT + Motion.ENTER), label = "art") { bitmap ->
            when {
                bitmap != null -> Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (monochrome) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
                )
                icon != null -> AppIcon(icon, size)
                else -> Box(
                    Modifier.size(size).background(style.content.copy(alpha = 0.12f), RoundedCornerShape(size / 4)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ExtraIcons.MusicNote, contentDescription = null, tint = style.content, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (art != null && icon != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(20.dp)
                    .background(style.scrim.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { AppIcon(icon, 17.dp) }
        }
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
}

@Composable
private fun PlayPauseButton(state: NowPlayingState, container: Color, content: Color, onToggle: (play: Boolean) -> Unit) {
    var ring by remember { mutableStateOf(false) }
    LaunchedEffect(state.isBuffering) {
        ring = state.isBuffering
        if (ring) {
            delay(10_000) // never spin forever on a stuck session
            ring = false
        }
    }
    // Flip the glyph right away; fall back to the session's real state if it never confirms.
    var optimistic by remember(state.status) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(optimistic) {
        if (optimistic != null) {
            delay(1_500)
            optimistic = null
        }
    }
    val paused = optimistic ?: state.isPaused
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button) {
                optimistic = !paused
                onToggle(paused)
            }
            .semantics { contentDescription = if (paused) "Play" else "Pause" },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(paused, animationSpec = tween(Motion.QUICK), label = "playPause") { isPaused ->
            Icon(if (isPaused) ExtraIcons.Play else ExtraIcons.Pause, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        }
        if (ring) {
            CircularProgressIndicator(Modifier.size(40.dp), color = content.copy(alpha = 0.7f), strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
    }
}

/** Playback position, ticking only while the launcher is visible and music is playing. */
@Composable
private fun rememberPlaybackPosition(state: NowPlayingState, tickMs: Long): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(state.positionAt(SystemClock.elapsedRealtime()), state, tickMs, lifecycle) {
        value = state.positionAt(SystemClock.elapsedRealtime())
        if (!state.isPlaying) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = state.positionAt(SystemClock.elapsedRealtime())
                delay(tickMs)
            }
        }
    }
}

/** One update per pixel the bar moves, between 4 and 0.5 times a second. */
private fun tickFor(durationMs: Long, widthPx: Int) = if (widthPx > 0) (durationMs / widthPx).coerceIn(250L, 2_000L) else 1_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: NowPlayingState, line: Color, track: Color, onScrub: (Long?) -> Unit) {
    // Leaving mid-drag skips onValueChangeFinished; make sure the readout still clears.
    val currentOnScrub by rememberUpdatedState(onScrub)
    DisposableEffect(Unit) { onDispose { currentOnScrub(null) } }
    var widthPx by remember { mutableIntStateOf(0) }
    val position by rememberPlaybackPosition(state, tickFor(state.durationMs, widthPx))
    var scrub by remember { mutableStateOf<Float?>(null) }
    // Hold the target until the app confirms the seek, so the thumb doesn't snap back.
    var pending by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(2_000)
            pending = null
        }
    }
    LaunchedEffect(state) {
        pending?.let { if (abs(state.positionAt(SystemClock.elapsedRealtime()) - it) < 1_500) pending = null }
    }
    val duration = state.durationMs.toFloat()
    val shown = (scrub ?: pending?.toFloat() ?: position.toFloat()).coerceIn(0f, duration)
    val thumbRadius by animateDpAsState(if (scrub != null) 8.dp else 4.dp, tween(Motion.QUICK), label = "thumb")
    Slider(
        value = shown,
        onValueChange = {
            scrub = it
            onScrub(it.toLong())
        },
        onValueChangeFinished = {
            scrub?.let {
                pending = it.toLong()
                state.seekTo(it.toLong())
            }
            scrub = null
            onScrub(null)
        },
        valueRange = 0f..duration,
        // A fixed 16dp box keeps the track geometry stable while the dot grows.
        thumb = { Canvas(Modifier.size(16.dp)) { drawCircle(line, radius = thumbRadius.toPx()) } },
        track = { sliderState ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val y = size.height / 2
                val fraction = sliderState.coercedValueAsFraction
                val rtl = layoutDirection == LayoutDirection.Rtl
                val from = if (rtl) size.width else 0f
                val to = if (rtl) size.width * (1 - fraction) else size.width * fraction
                drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(line, Offset(from, y), Offset(to, y), size.height, StrokeCap.Round)
            }
        },
        // Outermost semantics win, replacing the Slider's raw "71000.0" readout.
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .semantics {
                contentDescription = "Playback position"
                stateDescription = "${spokenTime(shown.toLong())} of ${spokenTime(state.durationMs)}"
            },
    )
}

@Composable
private fun ProgressLine(state: NowPlayingState, line: Color, track: Color) {
    var widthPx by remember { mutableIntStateOf(0) }
    val position = rememberPlaybackPosition(state, tickFor(state.durationMs, widthPx))
    LinearProgressIndicator(
        progress = { (position.value.toFloat() / state.durationMs).coerceIn(0f, 1f) },
        color = line,
        trackColor = track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(4.dp)
            .onSizeChanged { widthPx = it.width },
    )
}

@Composable
private fun LiveLabel(line: Color) {
    val style = LocalLauncherStyle.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Live broadcast" },
    ) {
        Box(Modifier.size(6.dp).background(line, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            "LIVE",
            style = TextStyle(color = style.content.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        )
    }
}

private fun formatTime(ms: Long): String = DateUtils.formatElapsedTime(ms / 1_000)

private fun spokenTime(ms: Long): String {
    val total = ms / 1_000
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val seconds = total % 60
    fun unit(n: Long, name: String) = "$n $name" + if (n == 1L) "" else "s"
    return buildList {
        if (hours > 0) add(unit(hours, "hour"))
        if (minutes > 0) add(unit(minutes, "minute"))
        if (seconds > 0 || (hours == 0L && minutes == 0L)) add(unit(seconds, "second"))
    }.joinToString(" ")
}
