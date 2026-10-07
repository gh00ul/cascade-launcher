package com.gh00ul.cascade.ui.home

import android.annotation.SuppressLint
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gh00ul.cascade.data.normalizedForSearch
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val MaxSlot = 22.dp
/** How far in from the screen edge the letters rest (from the left one in RTL). */
private val LetterInset = 14.dp
private val WaveSpread = 52.dp
private val WaveDepth = 64.dp
/** The glow is baked this much stronger and faded back by alpha, so the rise spring's overshoot past 1 still fits. */
private const val GlowHeadroom = 1.25f
/**
 * How strong letters and names draw: at rest (stronger over a light wallpaper, where faint dark text washes out), and at
 * most, under the finger, unless it's the one picked, which alone draws at full strength.
 */
private const val RestInk = 0.55f
private const val RestInkDark = 0.7f
private const val NearInk = 0.7f
/** The accent moving from one letter to the next. */
private val Handover = tween<Float>(Motion.QUICK)
/** Second letters opening out of their letter (and folding back), the strip making room as they do. */
private val Unfold = tween<Float>(Motion.QUICK)
/** How much a letter under the finger swells at most; less where its slot is too short to leave [SwellGap] around it. */
private const val LetterSwell = 2.6f
private val SwellGap = 3.dp
/**
 * A second letter's slot against a letter's: taller, as two letters swell wider and taller than one, and a little
 * easier to land on. Under the finger it swells less than a letter's 2.6x, to fit.
 */
private const val OptionSlot = 1.3f
private const val OptionSwell = 2f
/** An app's name under the finger swells less again: it's a word, not two letters. */
private const val NameSwell = 1.5f
/**
 * How far into a letter's slot the finger is kept, at least, from the edge it came in by, when the letter opens or the
 * one before it folds: a finger resting on the boundary stays on one letter rather than flicking between two.
 */
private const val EntryMargin = 0.15f
/**
 * How far past what's under the finger, as a share of its slot, the finger goes before the next thing is picked: a thumb
 * rolling as it lifts, or resting on a boundary, stays on what it's on rather than flick to its neighbour.
 */
private const val LeaveMargin = 0.3f
/**
 * App names: a lift opens the app under it only if the finger had all but stopped (under this speed), was still on the
 * strip's length (no more than [LiftReach] past either end), and hadn't slid off sideways more than [LiftReach] past the
 * app's name. A swipe that only passes over the strip on its way somewhere, and one slid away to call it off, open nothing.
 */
private val LiftSpeed = 300.dp
private val LiftReach = 24.dp
/** How far past the letters' column, toward the screen edge, app names end (they grow inward from there). */
private val NameEdge = 5.dp
/**
 * How close to the screen's far edge an app's name may come, fully swollen under the finger; a longer one ends in an
 * ellipsis there (large font sizes), rather than running off screen.
 */
private val NameMargin = 16.dp
/** How far the pill an app's name is picked on reaches past it at each end. */
private val PillPad = 6.dp
/**
 * Over how far past the strip's ends a letter or name fades out, when the strip has scrolled it there: two or three of
 * them, easing out, so it's plain there's more.
 */
private val EdgeFade = 56.dp
/**
 * Holding the finger this close to the strip's end (or past it) scrolls in what's past the end, from [ScrollSlow] at the
 * zone's edge, about four apps a second, up to [ScrollFast] at the strip's end; the same at the top.
 */
private val ScrollZone = 48.dp
private val ScrollSlow = 90.dp
private val ScrollFast = 420.dp

/**
 * How much an app's name swells under the finger: [NameSwell], or less where the pill it's picked on (as tall as its
 * line) would overflow its [slot].
 */
private fun nameSwell(layout: TextLayoutResult, slot: Float) = min(NameSwell, slot / layout.size.height)

/**
 * A second letter or app name measured as the strip draws it. Wider than [maxWidth] px it's measured again, ending in
 * an ellipsis there; anything that fits is measured exactly as before.
 */
private fun TextMeasurer.measureOption(text: String, style: TextStyle, maxWidth: Int): TextLayoutResult {
    val whole = measure(text, style)
    if (whole.size.width <= maxWidth) return whole
    return measure(text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = maxWidth))
}

/** A second-letter step under a strip letter: "Ma", and the A–Z list row its first app is at. */
@Immutable
data class LetterPrefix(val text: String, val row: Int)

/** An app row of the A–Z list as [letterPrefixes] reads it: its section, its name, and its row in the list. */
internal class PrefixSource(val section: String, val label: String, val row: Int)

/**
 * Each section's second letters in list order, each with the row of its first app: "M" over Mail, Maps, Messages and
 * Music gives Ma (at Mail), Me and Mu. Accents count as their plain letter. A name whose second character isn't a letter
 * or digit offers none, nor does one whose first doesn't spell its section ("#", stroke-count sections).
 */
internal fun letterPrefixes(entries: List<PrefixSource>): Map<String, List<LetterPrefix>> {
    val bySection = LinkedHashMap<String, MutableList<LetterPrefix>>()
    for (entry in entries) {
        val section = entry.section.normalizedForSearch()
        val name = entry.label.trim().normalizedForSearch()
        if (section.length != 1 || name.length < 2 || name[0] != section[0] || !name[1].isLetterOrDigit()) continue
        val text = entry.section + name[1]
        val prefixes = bySection.getOrPut(entry.section) { ArrayList() }
        if (prefixes.none { it.text == text }) prefixes += LetterPrefix(text, entry.row)
    }
    return bySection
}

/**
 * Each section's apps in list order, by name, each with its row: for the App names setting, where a letter opens up to
 * its apps rather than their second letters.
 */
internal fun letterApps(entries: List<PrefixSource>): Map<String, List<LetterPrefix>> {
    val bySection = LinkedHashMap<String, MutableList<LetterPrefix>>()
    for (entry in entries) bySection.getOrPut(entry.section) { ArrayList() } += LetterPrefix(entry.label, entry.row)
    return bySection
}

/**
 * Where the strip's letters sit, with the open letter's second letters in slots of their own between it and the next
 * letter: the layout that picks read, and that drawing glides each letter to from where it was ([progress]).
 */
private class StripPlan(val count: Int) {
    /** The height laid out for; below 0 until the first layout. */
    var height = -1f
    /** The letter whose second letters are out, and how many there are: -1 and 0 with none. */
    var open = -1
    var openCount = 0
    var top = 0f
    var slot = 0f
    val fromY = FloatArray(count)
    val toY = FloatArray(count)
    /** Where the open letter's second letters come out of: their letter, as it was when they opened. */
    var openFrom = 0f
    /** Second letters folding back into their letter: whose (a letter index, or -1), how many, and where they started. */
    var closing = -1
    var closingCount = 0
    var closingFirst = 0f
    var closingStep = 0f
    /** How far the glide from the last layout has come, 0 to 1. State, so a drawing that reads it follows it. */
    var progress by mutableFloatStateOf(1f)
    /**
     * Bumped by each [scroll] step. A step moves only plain fields, and with the finger held still at the strip's end
     * nothing else the drawing reads changes, so the drawing reads this to follow the scroll a frame at a time.
     */
    var scrolled by mutableIntStateOf(0)
        private set
    /** How tall the laid-out letters and second letters are, all told. */
    private var total = 0f

    /** The second letters' slot height. */
    val optionSlot get() = slot * OptionSlot
    /** The letters' slot as drawn: gliding from the last layout's, like everything else. */
    private var fromSlot = 0f
    val slotNow get() = if (progress >= 1f) slot else lerp(fromSlot, slot, progress)

    /** Letter [i]'s center below the strip's top: in its slot, below the second letters out above it. */
    fun offset(i: Int) = slot * (i + 0.5f) + if (open in 0 until i) openCount * optionSlot else 0f

    fun letterY(i: Int) = if (progress >= 1f) toY[i] else lerp(fromY[i], toY[i], progress)

    private fun optionTarget(j: Int) = top + slot * (open + 1) + optionSlot * (j + 0.5f)

    fun optionY(j: Int) = if (progress >= 1f) optionTarget(j) else lerp(openFrom, optionTarget(j), progress)

    fun closingY(j: Int) = lerp(closingFirst + closingStep * j, toY[closing], progress)

    /**
     * What's at [y] in the layout: a letter's index, or -2 - j for the open letter's j-th second letter. Past the last
     * letter's second letters, there being nothing after them, it's still the last of them.
     */
    fun hit(y: Float): Int {
        val down = y - top
        if (open < 0) return (down / slot).toInt().coerceIn(0, count - 1)
        val options = slot * (open + 1)
        val after = options + openCount * optionSlot
        return when {
            down < options -> (down / slot).toInt().coerceIn(0, open)
            down < after || open == count - 1 -> -2 - ((down - options) / optionSlot).toInt().coerceIn(0, openCount - 1)
            else -> (open + 1 + ((down - after) / slot).toInt()).coerceIn(open + 1, count - 1)
        }
    }

    /** Where in letter [i]'s slot [y] is, from 0 at its top to 1 at its bottom. */
    fun within(i: Int, y: Float) = ((y - top - (offset(i) - slot / 2f)) / slot).coerceIn(0f, 1f)

    /**
     * Whether [y] is still on [item] (a letter's index, or -2 - j for the open letter's j-th second letter), or no more
     * than [margin] of its slot past it.
     */
    fun holds(item: Int, y: Float, margin: Float): Boolean {
        val center = if (item <= -2) optionTarget(-2 - item) else toY.getOrElse(item) { return false }
        val size = if (item <= -2) optionSlot else slot
        return abs(y - center) <= size * (0.5f + margin)
    }

    /**
     * Lays the strip out with [newOpen]'s [newCount] second letters out (-1 and 0 for none), or centers it all with no
     * [anchor] (-1). With one, the finger at [anchorY] stays on [anchor] (a letter's index, or -2 - j for the newly open
     * letter's j-th second letter), at the same point in its slot ([at], 0 at its top to 1 at its bottom): slots change
     * size as letters open and fold, and a letter kept where it was, rather
     * than around the finger, could leave a finger that had only just reached it back on the letter before. Slots shrink
     * from [maxSlot] only as far as needed to fit [height]. With [glide], everything moves from where it is now.
     *
     * Kept under the finger, a letter opened low on the strip can push its last apps and the letters after them past
     * the strip's end, to be scrolled in by holding the finger at the end ([edgeSpeed], [scroll]). (Clamping the strip
     * to fit instead would move the letter out from under the finger, onto one of its apps halfway down; scrolling it
     * past the finger faster than the finger moves, to bring the end within its reach, left the items there too close
     * together for a finger to pick.)
     */
    fun layOut(height: Float, maxSlot: Float, newOpen: Int, newCount: Int, anchor: Int, anchorY: Float, at: Float, glide: Boolean) {
        if (glide) {
            for (i in 0 until count) fromY[i] = letterY(i)
            fromSlot = slotNow
            if (open >= 0 && open != newOpen && openCount > 0) {
                closing = open
                closingCount = openCount
                closingFirst = optionY(0)
                closingStep = if (openCount > 1) optionY(1) - closingFirst else 0f
            } else {
                closing = -1
                closingCount = 0
            }
            if (newOpen >= 0 && newOpen != open) openFrom = letterY(newOpen)
        } else {
            closing = -1
            closingCount = 0
        }
        this.height = height
        open = newOpen
        openCount = if (newOpen >= 0) newCount else 0
        // With none open, exactly the plain strip's layout: count slots, centered.
        slot = if (openCount == 0) min(height / count, maxSlot) else min(height / (count + openCount * OptionSlot), maxSlot)
        total = slot * count + openCount * optionSlot
        top = when {
            anchor == -1 -> (height - total) / 2f
            anchor <= -2 -> anchorY - (slot * (open + 1) + optionSlot * (-2 - anchor + at))
            else -> anchorY - (offset(anchor) + slot * (at - 0.5f))
        }
        place()
        progress = if (glide) 0f else 1f
    }

    /**
     * How fast to scroll the strip for a finger at [y], in px a second: toward the top (negative) while the finger is
     * within [zone] of the strip's end with more past it, toward the bottom while it's within [zone] of the top with more
     * above, and 0 otherwise. From [slow] at the zone's edge to [fast] at the strip's end, easing in, so a finger just
     * inside it can stop on each item as it comes, and one pressed to the end sweeps along.
     */
    fun edgeSpeed(y: Float, zone: Float, slow: Float, fast: Float): Float {
        fun speed(depth: Float) = depth.coerceIn(0f, 1f).let { slow + (fast - slow) * it * it }
        return when {
            y > height - zone && top + total > height + 0.5f -> -speed((y - (height - zone)) / zone)
            y < zone && top < -0.5f -> speed((zone - y) / zone)
            else -> 0f
        }
    }

    /** Scrolls the strip by [dy] px, no further than its last item reaching the strip's end, or its first, the top. */
    fun scroll(dy: Float) {
        top = if (dy < 0f) max(top + dy, min(top, height - total)) else min(top + dy, max(top, 0f))
        place()
        scrolled++
    }

    private fun place() {
        for (i in 0 until count) toY[i] = top + offset(i)
    }
}

/**
 * The letter strip on the end edge. Dragging along it makes the letters near your finger swell and bulge
 * out in a wave, and jumps the app list to whichever letter you're on. [restAlpha] fades the strip while it rests
 * (read while drawing its layer, so it can follow a scroll); under the finger it's always at full strength.
 *
 * With [prefixes] (the Second letters setting), the letter under the finger opens up in the strip to its second letters
 * (M: Ma, Me, Mu, each in a slot of its own before N), and dragging on through them jumps the list to each with
 * [onPrefix]. Reaching the next letter folds them back and opens that one's; the letter just reached stays under the
 * finger while the strip makes room around it. Lifting the finger folds them all back. With [names] (the App names
 * setting), [prefixes] are the letter's apps by name instead: they end at the strip's edge and grow inward, a letter
 * with even one app opens, and lifting the finger on an app's name opens it with [onOpen], with where the name was in
 * the window to open from. Lifting it on a letter just leaves the list there, and a lift on the move, past the strip's
 * ends or slid off sideways opens nothing (see LiftSpeed).
 *
 * The strip follows the finger one to one. What doesn't fit between the finger and the strip's ends scrolls in while the
 * finger is held near that end (see ScrollZone). Coming back onto a letter just left, from the next one, opens it with
 * its last second letter under the finger.
 */
@Composable
fun AlphabetWave(
    letters: List<String>,
    onLetter: (String) -> Unit,
    modifier: Modifier = Modifier,
    restAlpha: () -> Float = { 1f },
    prefixes: Map<String, List<LetterPrefix>> = emptyMap(),
    onPrefix: (LetterPrefix) -> Unit = {},
    names: Boolean = false,
    onOpen: (LetterPrefix, Rect) -> Unit = { _, _ -> },
) {
    // Read through a state: the gesture below outlives recompositions, and Settings can turn vibration off meanwhile.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val style = LocalLauncherStyle.current
    val measurer = rememberTextMeasurer()
    val layouts = remember(letters, measurer, style) { letters.map { measurer.measure(it, style.letter) } }
    // App names end, fully bulged, a set way in from the screen's end edge, where the strip sits. Fully swollen and on
    // their pill, they're no wider than fits from there to NameMargin short of the far edge.
    // Lint's ConfigurationScreenWidthHeight: the activity's own Configuration already reports its window's width, which
    // is all this needs.
    @SuppressLint("ConfigurationScreenWidthHeight")
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val nameMax = with(LocalDensity.current) {
        ((screenWidth - (LetterInset + WaveDepth - NameEdge) - NameMargin) / NameSwell - PillPad).roundToPx().coerceAtLeast(1)
    }
    val prefixLayouts: Map<String, List<TextLayoutResult>> = remember(prefixes, measurer, style, names, nameMax) {
        prefixes.mapValues { (_, list) -> list.map { measurer.measureOption(it.text, style.letter, if (names) nameMax else Int.MAX_VALUE) } }
    }
    val currentPrefixLayouts by rememberUpdatedState(prefixLayouts)
    val currentLayoutDirection by rememberUpdatedState(LocalLayoutDirection.current)
    val currentLetters by rememberUpdatedState(letters)
    val currentOnLetter by rememberUpdatedState(onLetter)
    val currentPrefixes by rememberUpdatedState(prefixes)
    val currentOnPrefix by rememberUpdatedState(onPrefix)
    val currentNames by rememberUpdatedState(names)
    val currentOnOpen by rememberUpdatedState(onOpen)
    // The strip's top-left in the window, for the bounds an app opens from. Kept as it is laid out, not as state: only a
    // lift reads it.
    val origin = remember { floatArrayOf(0f, 0f) }
    val scope = rememberCoroutineScope()
    val plan = remember(letters.size) { StripPlan(letters.size) }

    var touchY by remember { mutableFloatStateOf(Float.NaN) }
    var selected by remember { mutableIntStateOf(-1) }
    // The open letter's second letter under the finger, -1 while it's on a letter. Kept after release for the fade.
    var picked by remember { mutableIntStateOf(-1) }
    // For TalkBack the strip is a slider, one letter per step; this is the letter it's on.
    var a11yIndex by remember { mutableIntStateOf(0) }
    // Swells with a little bounce under the finger and settles back without dipping past rest when it lifts.
    val wave by animateFloatAsState(
        targetValue = if (selected >= 0) 1f else 0f,
        animationSpec = if (selected >= 0) Motion.WaveRise else Motion.WaveFall,
        label = "wave",
    )
    // The accent follows the finger: as the handover runs, the letter it lands on fades to the accent and every other
    // letter fades back, each from the share it showed when the finger moved, so a fast drag never jumps. The shares are
    // kept as drawn (already scaled by the wave), so a drag that starts from rest carries nothing over from the last one.
    // After release the last letter keeps the accent and fades out with the wave.
    var accented by remember { mutableIntStateOf(-1) }
    var handover by remember { mutableFloatStateOf(1f) }
    val accentFrom = remember(letters.size) { FloatArray(letters.size) }
    val currentAccentFrom by rememberUpdatedState(accentFrom)

    Spacer(
        modifier
            .width(36.dp)
            .onGloballyPositioned { coordinates ->
                val at = coordinates.positionInWindow()
                origin[0] = at.x
                origin[1] = at.y
            }
            // Its own layer, so a resting fade that follows the scroll changes the layer's alpha, not the letters.
            .graphicsLayer {
                val rest = restAlpha()
                alpha = rest + (1f - rest) * wave.coerceIn(0f, 1f)
            }
            .semantics {
                val last = (letters.size - 1).coerceAtLeast(0)
                val current = a11yIndex.coerceIn(0, last)
                contentDescription = "Alphabet index"
                stateDescription = letters.getOrElse(current) { "" }
                progressBarRangeInfo = ProgressBarRangeInfo(current.toFloat(), 0f..last.toFloat(), steps = (letters.size - 2).coerceAtLeast(0))
                setProgress { value ->
                    if (letters.isEmpty()) return@setProgress false
                    // Round away from the current letter, so even a small step moves one.
                    val index = (if (value > current) ceil(value) else floor(value)).toInt().coerceIn(0, last)
                    a11yIndex = index
                    currentOnLetter(letters[index])
                    true
                }
                customActions = letters.mapIndexed { i, letter ->
                    CustomAccessibilityAction("Jump to $letter") {
                        a11yIndex = i
                        currentOnLetter(letter)
                        true
                    }
                }
            }
            .pointerInput(plan) {
                var handoverJob: Job? = null
                var unfoldJob: Job? = null
                fun accent(index: Int) {
                    if (index == accented) return
                    val from = currentAccentFrom
                    val swellNow = wave.coerceIn(0f, 1f)
                    for (i in from.indices) from[i] = lerp(from[i], if (i == accented) swellNow else 0f, handover)
                    accented = index
                    // Reset here rather than in the coroutine, so no frame draws the new letter at the old progress.
                    handover = 0f
                    handoverJob?.cancel()
                    handoverJob = scope.launch { animate(0f, 1f, animationSpec = Handover) { value, _ -> handover = value } }
                }
                fun layOut(open: Int, count: Int, anchor: Int, anchorY: Float, at: Float = 0.5f) {
                    plan.layOut(size.height.toFloat(), MaxSlot.toPx(), open, count, anchor, anchorY, at, glide = true)
                    unfoldJob?.cancel()
                    unfoldJob = scope.launch { animate(0f, 1f, animationSpec = Unfold) { value, _ -> plan.progress = value } }
                }
                // The letter open before the one open now, for a finger that overshoots it and comes back.
                var lastOpen = -1
                // The second letters the pick was made among: should they change under the finger (an app installed
                // mid-drag), the pick no longer says which app, and a lift opens nothing.
                var pickedFrom: Map<String, List<LetterPrefix>>? = null
                // A finger past either end of the strip picks what's at that end, where it can be seen. [first]: just put
                // down, the letter under it centers on it, so a tap can't reach the second letters around it.
                fun move(finger: Float, first: Boolean = false) {
                    val count = currentLetters.size
                    if (count == 0) return
                    if (plan.height != size.height.toFloat()) {
                        plan.layOut(size.height.toFloat(), MaxSlot.toPx(), -1, 0, -1, 0f, 0.5f, glide = false)
                    }
                    val y = finger.coerceIn(0f, size.height.toFloat())
                    touchY = y
                    if (plan.holds(if (picked >= 0) -2 - picked else selected, y, LeaveMargin)) return
                    val hit = plan.hit(y)
                    if (hit <= -2) {
                        // On one of the open letter's second letters.
                        val options = currentLetters.getOrNull(plan.open)?.let { currentPrefixes[it] }.orEmpty()
                        val j = -2 - hit
                        if (j != picked && j in options.indices) {
                            picked = j
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            pickedFrom = currentPrefixes
                            currentOnPrefix(options[j])
                            accent(-1)
                        }
                        return
                    }
                    val index = hit.coerceIn(0, count - 1)
                    if (index == selected && picked < 0) return
                    val options = currentPrefixes[currentLetters[index]].orEmpty()
                    val opening = if (options.size >= (if (currentNames) 1 else 2)) options.size else 0
                    // Back up onto the letter just left, from the next one before going into its second letters: an
                    // overshoot. The letter opens again with its last second letter under the finger, where it left off.
                    val back = opening > 0 && index != plan.open && index == lastOpen && selected == index + 1 && picked < 0
                    if (index != plan.open && plan.open >= 0) lastOpen = plan.open
                    if (back) {
                        layOut(index, opening, -2 - (opening - 1), y, 1f - EntryMargin)
                        selected = index
                        a11yIndex = index
                        picked = opening - 1
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        pickedFrom = currentPrefixes
                        currentOnPrefix(options[opening - 1])
                        accent(-1)
                        return
                    }
                    selected = index
                    a11yIndex = index
                    picked = -1
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    currentOnLetter(currentLetters[index])
                    accent(index)
                    // Open this letter's second letters (folding any others back), keeping it under the finger.
                    if (index != plan.open && (opening > 0 || plan.open >= 0)) {
                        val at = if (first) 0.5f else plan.within(index, y).coerceIn(EntryMargin, 1f - EntryMargin)
                        layOut(if (opening > 0) index else -1, opening, index, y, at)
                    }
                }
                // Held near either end with more past it, the strip scrolls that in, a frame at a time, picking as it
                // goes, until the finger moves away from the end or there's no more.
                var fingerY = 0f
                var scrollJob: Job? = null
                fun scrollSpeed() = plan.edgeSpeed(fingerY.coerceIn(0f, size.height.toFloat()), ScrollZone.toPx(), ScrollSlow.toPx(), ScrollFast.toPx())
                fun follow(finger: Float, first: Boolean = false) {
                    fingerY = finger
                    move(finger, first)
                    if (scrollJob?.isActive == true || scrollSpeed() == 0f) return
                    scrollJob = scope.launch {
                        var last = withFrameNanos { it }
                        while (true) {
                            val now = withFrameNanos { it }
                            val speed = scrollSpeed()
                            if (speed == 0f) break
                            plan.scroll(speed * (now - last) / 1_000_000_000f)
                            last = now
                            move(fingerY)
                        }
                    }
                }
                // App names: the app whose name the finger lifted on (at [lift], in the strip's coordinates, moving at
                // [speed] px a second) opens, out of that name as drawn under the finger (fully swollen, bulged inward,
                // ending past the letters' column); see LiftSpeed for when it doesn't.
                fun openPicked(lift: Offset, speed: Float) {
                    if (pickedFrom !== currentPrefixes) return
                    val letter = currentLetters.getOrNull(plan.open) ?: return
                    val app = currentPrefixes[letter]?.getOrNull(picked) ?: return
                    val layout = currentPrefixLayouts[letter]?.getOrNull(picked) ?: return
                    val reach = LiftReach.toPx()
                    if (speed > LiftSpeed.toPx() || lift.y < -reach || lift.y > size.height + reach) return
                    val rtl = currentLayoutDirection == LayoutDirection.Rtl
                    val inward = if (rtl) 1f else -1f
                    val end = (if (rtl) LetterInset.toPx() else size.width - LetterInset.toPx()) + inward * (WaveDepth.toPx() - NameEdge.toPx())
                    val swell = nameSwell(layout, plan.optionSlot)
                    val width = layout.size.width * swell
                    val height = layout.size.height * swell
                    val left = if (rtl) end else end - width
                    if (if (rtl) lift.x > left + width + reach else lift.x < left - reach) return
                    val top = plan.optionY(picked) - height / 2f
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    currentOnOpen(app, Rect(origin[0] + left, origin[1] + top, origin[0] + left + width, origin[1] + top + height))
                }
                val velocity = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    picked = -1
                    lastOpen = -1
                    velocity.resetTracking()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    // Finally, so a gesture cut short (the strip itself replaced as its letters change, say) still lets go.
                    try {
                        follow(down.position.y, first = true)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            velocity.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                // A lift, unless the system took the gesture over (a back swipe from this edge, say): that
                                // comes as a lift already consumed, and opens nothing.
                                scrollJob?.cancel()
                                if (!change.isConsumed && currentNames) openPicked(change.position, abs(velocity.calculateVelocity().y))
                                break
                            }
                            change.consume()
                            follow(change.position.y)
                        }
                    } finally {
                        scrollJob?.cancel()
                        selected = -1
                        // Whatever opened folds back, and the strip settles back to its resting layout.
                        if (currentPrefixes.isNotEmpty() && plan.height >= 0f) layOut(-1, 0, -1, 0f)
                    }
                }
            }
            // Drawn on every frame of a drag, so nothing here allocates: the glow is built once per size and style, the
            // finger moves it (translate) and the wave fades it in (alpha); the accent is a Color lerp, a value class.
            .drawWithCache {
                val glowRadius = 170.dp.toPx()
                val glow = Brush.radialGradient(
                    listOf(style.scrim.copy(alpha = 0.6f * GlowHeadroom), Color.Transparent),
                    center = Offset.Zero,
                    radius = glowRadius,
                )
                // The picked one stands out from the others swollen around it: the accent, darkened over a light wallpaper
                // to read as well as the dark text beside it, and an app's name sits on a pill of it.
                val accentInk = if (style.darkText) lerp(style.accent, style.content, 0.35f) else style.accent
                val pill = style.accent.copy(alpha = 0.24f)
                val restInk = if (style.darkText) RestInkDark else RestInk
                onDrawBehind {
                    val count = letters.size
                    if (count == 0) return@onDrawBehind
                    if (plan.height != size.height) plan.layOut(size.height, MaxSlot.toPx(), -1, 0, -1, 0f, 0.5f, glide = false)
                    val slot = plan.slot
                    val glide = plan.progress
                    // Read only so each scroll step redraws (see StripPlan.scrolled); never changes while idle.
                    plan.scrolled
                    // Letters rest 14dp in from the screen edge and bulge inward, toward the list (mirrored in RTL).
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val restX = if (rtl) LetterInset.toPx() else size.width - LetterInset.toPx()
                    val inward = if (rtl) 1f else -1f
                    val spread = WaveSpread.toPx()
                    val depth = WaveDepth.toPx()
                    // Large fonts in a short strip: shrink resting letters to their slot (capitals are ~0.71em tall).
                    val fit = (slot / (style.letter.fontSize.toPx() * 0.8f)).coerceAtMost(1f)
                    val touching = !touchY.isNaN() && wave > 0f
                    val swell = wave.coerceIn(0f, 1f)
                    // Swollen letters keep a gap between them, however short their slots get to make room for an open
                    // letter's apps (capitals are ~0.72em tall).
                    val letterSwell = ((plan.slotNow - SwellGap.toPx()) / (style.letter.fontSize.toPx() * 0.72f)).coerceIn(1f, LetterSwell)
                    // Second letters likewise in their own, taller slots; app names in drawItem, by their pill.
                    val optionSlot = plan.slotNow * OptionSlot
                    val optionSwell = ((optionSlot - SwellGap.toPx()) / (style.letter.fontSize.toPx() * 0.72f)).coerceIn(1f, OptionSwell)

                    if (touching) {
                        translate(restX + inward * depth * 0.6f, touchY) {
                            drawCircle(glow, glowRadius, Offset.Zero, alpha = (wave / GlowHeadroom).coerceAtMost(1f))
                        }
                        // App names: the same shade, stretched down the open letter's names, so they read over the list's
                        // own labels beside them.
                        if (names && plan.openCount > 0) {
                            val first = plan.optionY(0)
                            val last = plan.optionY(plan.openCount - 1)
                            val stretch = max(1f, ((last - first) / 2f + 48.dp.toPx()) / glowRadius)
                            translate(restX + inward * (depth + 60.dp.toPx()), (first + last) / 2f) {
                                scale(1f, stretch, pivot = Offset.Zero) {
                                    drawCircle(glow, glowRadius, Offset.Zero, alpha = (wave / GlowHeadroom).coerceAtMost(1f) * glide)
                                }
                            }
                        }
                    }

                    // A local function, called rather than passed around, so it allocates nothing. [accent] is how far
                    // toward the accent color it is; [fade] scales its alpha (second letters coming and going).
                    fun drawItem(layout: TextLayoutResult, cy: Float, accent: Float, fade: Float, swellTo: Float, name: Boolean = false) {
                        // Scrolled past the strip's ends (see StripPlan.scroll), it fades out rather than spill over the list.
                        val past = max(-cy, cy - size.height)
                        val edge = if (past > 0f) (1f - past / EdgeFade.toPx()).let { it * it } else 1f
                        if (past >= EdgeFade.toPx()) return
                        val influence = if (touching) {
                            val d = cy - touchY
                            exp(-(d * d) / (2f * spread * spread)) * wave
                        } else 0f
                        // An app's name ends just past the letters' column and grows inward from there, so a long one
                        // stays on screen.
                        val cx = restX + inward * depth * influence - if (name) inward * NameEdge.toPx() else 0f
                        val left = when {
                            !name -> cx - layout.size.width / 2f
                            rtl -> cx
                            else -> cx - layout.size.width
                        }
                        // An app's name is picked on a pill as tall as its line: swollen or not, that fits the name's slot,
                        // however large the font. Letters and second letters have no limit here.
                        val room = if (name) nameSwell(layout, optionSlot) else Float.POSITIVE_INFINITY
                        val rest = min(fit, room)
                        val scale = rest + (min(swellTo, room) - rest) * influence
                        // At rest (and fully accented) the exact colors, not a lerp's round trip through Oklab.
                        val color = when {
                            accent <= 0f -> style.content
                            accent >= 1f -> accentInk
                            else -> lerp(style.content, accentInk, accent)
                        }
                        withTransform({ scale(scale, scale, pivot = Offset(cx, cy)) }) {
                            if (name && accent > 0f) {
                                val h = layout.size.height.toFloat()
                                val pad = PillPad.toPx()
                                drawRoundRect(
                                    pill,
                                    topLeft = Offset(left - pad, cy - h / 2f),
                                    size = Size(layout.size.width + 2f * pad, h),
                                    cornerRadius = CornerRadius(h / 2f),
                                    alpha = accent * fade * edge,
                                )
                            }
                            drawText(
                                layout,
                                color = color,
                                topLeft = Offset(left, cy - layout.size.height / 2f),
                                // Clamped like the glow's: the rise spring's overshoot takes influence past 1.
                                alpha = (lerp(restInk, lerp(NearInk, 1f, accent), influence) * fade * edge).coerceIn(0f, 1f),
                            )
                        }
                    }
                    val itemSwell = if (names) NameSwell else optionSwell
                    fun drawLetter(i: Int) = drawItem(layouts[i], plan.letterY(i), lerp(accentFrom[i], if (i == accented) swell else 0f, handover), 1f, letterSwell)

                    // Second letters folding back into their letter, fading as they go.
                    val closing = if (glide < 1f && plan.closing >= 0) letters.getOrNull(plan.closing)?.let { prefixLayouts[it] } else null
                    if (closing != null) {
                        for (j in 0 until min(plan.closingCount, closing.size)) drawItem(closing[j], plan.closingY(j), 0f, 1f - glide, itemSwell, names)
                    }
                    // The accented letter last, so if the rise's overshoot makes neighbours touch, it stays in front.
                    for (i in layouts.indices) if (i != accented) drawLetter(i)
                    // The open letter's second letters, coming out of it; the one under the finger takes the accent.
                    val open = if (plan.open >= 0) letters.getOrNull(plan.open)?.let { prefixLayouts[it] } else null
                    if (open != null) {
                        for (j in 0 until min(plan.openCount, open.size)) {
                            if (j != picked) drawItem(open[j], plan.optionY(j), 0f, glide, itemSwell, names)
                        }
                    }
                    if (accented >= 0 && accented < layouts.size) drawLetter(accented)
                    if (open != null && picked in 0 until min(plan.openCount, open.size)) drawItem(open[picked], plan.optionY(picked), swell, glide, itemSwell, names)
                }
            },
    )
}
