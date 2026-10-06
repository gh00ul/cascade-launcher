package com.gh00ul.cascade.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Moving favorites right on the home screen: hold one until it lifts, and its menu opens at once, as a long press
 * always did; drag it up or down instead and the menu closes, the others slide out of its way, and letting go drops it
 * into its new place and saves the order. Rows are laid out by [ReorderableColumn], so while one moves nothing but
 * placement runs.
 */

/** Where a favorite's row is laid out, for the bounds its menu pops from. */
private class RowPlace {
    var coordinates: LayoutCoordinates? = null
}

/** How often a favorite held while another settles checks whether it can lift yet: about a frame. */
private const val SettleWaitMillis = 16L

/** How much a lifted favorite grows, and how strong the pill behind it gets. */
private const val LiftScale = 0.03f
private const val LiftBackdrop = 0.3f

/**
 * The favorites being moved: which row is held, where each row shows, and the order they'll be saved in. Placement
 * reads it, so a drag moves rows without recomposing them. [commit] saves an order (the rows' keys) once a row drops.
 */
@Stable
internal class FavoriteReorder(
    private val scope: CoroutineScope,
    private val haptics: () -> HapticFeedback,
    private val commit: () -> ((List<String>) -> Unit)?,
) {
    /** The row held up, from its long press until it has settled back down. */
    var held by mutableStateOf<String?>(null)
        private set

    /** How far [held] is lifted, 0 to 1: its scale and the pill behind it. */
    val lift = Animatable(0f)

    /** The order rows show in while one is moved and settles; null at rest, when the layout's own order holds. */
    private var order by mutableStateOf<List<String>?>(null)

    /** Each row's top within the favorites while [order] holds. */
    private val tops = HashMap<String, Animatable<Float, AnimationVector1D>>()

    // What the layout last measured: the rows in composition order, their heights, and the block's height.
    private var keys: List<String> = emptyList()
    private val heights = HashMap<String, Int>()
    private var total = 0

    /** The held row's top as the finger has it. */
    private var dragTop = 0f

    /** Whether favorites can move at all: something must save the order. */
    val enabled: Boolean get() = commit() != null

    internal fun measured(keys: List<String>, heights: IntArray) {
        this.keys = keys
        this.heights.clear()
        keys.forEachIndexed { i, key -> this.heights[key] = heights[i] }
        total = heights.sum()
    }

    /** Where [key] shows while rows are moving, or null to stack it in place. Read while placing. */
    internal fun topOf(key: String): Float? = if (order == null) null else tops[key]?.value

    /** Lifts [key] (its long press has fired); false while another row is still settling. */
    fun pick(key: String): Boolean {
        if (held != null || key !in heights) return false
        var y = 0f
        tops.clear()
        for (k in keys) {
            tops[k] = Animatable(y)
            y += heights.getValue(k)
        }
        dragTop = tops.getValue(key).value
        order = keys
        held = key
        haptics().performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { lift.animateTo(1f, Motion.Lift) }
        return true
    }

    /** The held row follows the finger by [dy]; the others make room where it's headed. */
    fun drag(dy: Float) {
        val key = held ?: return
        val current = order ?: return
        val height = heights[key] ?: return
        dragTop = (dragTop + dy).coerceIn(0f, (total - height).toFloat().coerceAtLeast(0f))
        val top = tops[key] ?: return
        val to = dragTop
        scope.launch { top.snapTo(to) }
        // Its place is the slot nearest where it is: after every other row whose middle its top has passed, with those
        // rows stacked as if it were gone.
        var index = 0
        var y = 0f
        for (k in current) {
            if (k == key) continue
            val h = heights[k] ?: 0
            if (dragTop > y + h / 2f) index++
            y += h
        }
        if (current.indexOf(key) == index) return
        val next = current.toMutableList().apply {
            remove(key)
            add(index, key)
        }
        order = next
        haptics().performHapticFeedback(HapticFeedbackType.TextHandleMove)
        slideTo(next, except = key)
    }

    /** Lets go of a row that was dragged: it settles into its new place and the order is saved. */
    fun drop() {
        val key = held ?: return
        val final = order ?: return
        if (final != keys) commit()?.invoke(final)
        scope.launch {
            coroutineScope {
                launch { tops[key]?.animateTo(slotTop(final, key), Motion.Reorder) }
                launch { lift.animateTo(0f, Motion.Reorder) }
            }
            // The saved order reaches the layout a frame or so later. Until it does the rows keep their places, and
            // should it never come (the save was refused), they glide back to the order the layout has.
            var frames = 0
            while (keys != final && frames++ < 30) withFrameNanos { }
            if (keys != final) {
                coroutineScope { for (k in keys) launch { tops[k]?.animateTo(slotTop(keys, k), Motion.Reorder) } }
            }
            // Rows that made room late may still be gliding: let them land before the layout takes over.
            while (tops.values.any { it.isRunning }) withFrameNanos { }
            clear()
        }
    }

    /** Lets go of a row that wasn't dragged: it sinks back, and nothing moved. */
    fun putDown() {
        if (held == null) return
        order = null
        scope.launch {
            lift.animateTo(0f, Motion.Reorder)
            clear()
        }
    }

    private fun clear() {
        order = null
        held = null
        tops.clear()
    }

    private fun slideTo(order: List<String>, except: String) {
        var y = 0f
        for (k in order) {
            val h = heights[k] ?: 0
            if (k != except) {
                val top = tops[k]
                val to = y
                if (top != null && top.targetValue != to) scope.launch { top.animateTo(to, Motion.Reorder) }
            }
            y += h
        }
    }

    private fun slotTop(order: List<String>, key: String): Float {
        var y = 0f
        for (k in order) {
            if (k == key) return y
            y += heights[k] ?: 0
        }
        return y
    }
}

/** The favorites' reorder state; [onReorder] saves a new order, and null leaves the favorites where they are. */
@Composable
internal fun rememberFavoriteReorder(onReorder: ((List<String>) -> Unit)?): FavoriteReorder {
    val scope = rememberCoroutineScope()
    // Read through states: the reorder outlives recompositions, and Settings can turn vibration off meanwhile.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val save by rememberUpdatedState(onReorder)
    return remember(scope) { FavoriteReorder(scope, { haptics }, { save }) }
}

/** How far a favorite rises as home settles in, and how much of the settle each row waits after the one above. */
private val EntranceRise = 14.dp
private const val EntranceStagger = 0.07f

/**
 * The favorites, stacked like a Column, each child keyed with [layoutId] by its row's key. While a row is moved they
 * are placed where [state] shows them, the held one above the rest. As home settles in ([entrance] going 0 to 1, read
 * while placing) they rise into place one after another, top to bottom: a cascade.
 */
@Composable
internal fun ReorderableColumn(
    state: FavoriteReorder,
    modifier: Modifier = Modifier,
    entrance: () -> Float = { 1f },
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables: List<Measurable>, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables: List<Placeable> = measurables.map { it.measure(loose) }
        val keys = measurables.map { it.layoutId as String }
        val heights = IntArray(placeables.size) { placeables[it].height }
        state.measured(keys, heights)
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val rise = EntranceRise.toPx()
        layout(width, heights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)) {
            val e = entrance()
            // Each row's share of the settle starts a little after the one above's and ends with the settle.
            val span = (1f - EntranceStagger * (placeables.size - 1)).coerceAtLeast(0.3f)
            var y = 0
            placeables.forEachIndexed { i, placeable ->
                val key = keys[i]
                val top = state.topOf(key)?.roundToInt() ?: y
                val shown = if (e >= 1f) 1f else ((e - EntranceStagger * i) / span).coerceIn(0f, 1f)
                placeable.place(0, top + ((1f - shown) * rise).roundToInt(), zIndex = if (key == state.held) 1f else 0f)
                y += placeable.height
            }
        }
    }
}

/**
 * A favorite that lifts on a long press and moves with the finger. [onMenu] opens its menu as it lifts, with the row's
 * bounds in root coordinates; should the finger then drag, [onMenuClose] closes it again and the row moves. Off
 * ([enabled] false), the row keeps its own long press. The row itself must leave the long press to this, or both would
 * fire.
 *
 * It watches the gesture before the row does (the initial pass), so the row's tap still works, a scroll or swipe that
 * starts before the long press is the list's or the row's, and once it lifts, everything after is this gesture's alone.
 */
@Composable
internal fun Modifier.liftToReorder(
    state: FavoriteReorder,
    key: String,
    enabled: Boolean,
    onMenuClose: () -> Unit = {},
    onMenu: (Rect?) -> Unit,
): Modifier {
    val style = LocalLauncherStyle.current
    val menu by rememberUpdatedState(onMenu)
    val menuClose by rememberUpdatedState(onMenuClose)
    // Where the row is, read only when it lifts: not state, so moving it redraws nothing.
    val place = remember { RowPlace() }
    val gesture = if (!enabled || !state.enabled) Modifier else Modifier.pointerInput(state, key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Held still until the long press: a lift. Up, moved past the slop, or taken by someone else: not this gesture.
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
            } == null
            if (!held) return@awaitEachGesture
            // Another row still settling from its drop: this one lifts once that's done, while the finger stays down and
            // still. The row leaves its long press to this, so a let-go now would be its tap: it's taken, opening nothing.
            while (!state.pick(key)) {
                if (state.held == null) return@awaitEachGesture
                val change = withTimeoutOrNull(SettleWaitMillis) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                }
                if (change != null) {
                    if (!change.pressed) {
                        change.consume()
                        return@awaitEachGesture
                    }
                    if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        return@awaitEachGesture
                    }
                }
            }
            menu(place.coordinates?.takeIf { it.isAttached }?.boundsInRoot())
            var travel = 0f
            var dragging = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    // Read before it's consumed: a consumed change reports no movement.
                    val dy = change.positionChange().y
                    // Everything from here is the move's: the row's tap and the list's scroll see it taken.
                    change.consume()
                    if (!change.pressed) break
                    travel += dy
                    if (!dragging && abs(travel) > viewConfiguration.touchSlop) {
                        dragging = true
                        menuClose()
                    }
                    if (dragging) state.drag(dy)
                }
            } finally {
                // Let go without dragging: the menu stays open, and the row sinks back under it.
                if (dragging) state.drop() else state.putDown()
            }
        }
    }
    return this
        .onPlaced { place.coordinates = it }
        .then(gesture)
        // Lifted: a little larger, on a pill of the scrim so it reads as picked up over whatever is behind it.
        .graphicsLayer {
            val l = if (state.held == key) state.lift.value else 0f
            scaleX = 1f + LiftScale * l
            scaleY = 1f + LiftScale * l
        }
        .drawBehind {
            val l = if (state.held == key) state.lift.value else 0f
            if (l > 0f) drawRoundRect(style.scrim, alpha = (LiftBackdrop * l).coerceIn(0f, 1f), cornerRadius = CornerRadius(16.dp.toPx()))
        }
}
