package com.gh00ul.cascade.ui.home

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gh00ul.cascade.data.normalizedForSearch
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

private val MaxSlot = 22.dp
private val WaveSpread = 52.dp
private val WaveDepth = 64.dp
/** The glow is baked this much stronger and faded back by alpha, so the rise spring's overshoot past 1 still fits. */
private const val GlowHeadroom = 1.25f
/**
 * How much bigger the accented letter draws, on top of the wave's swell. Small enough that, fully swollen, it still
 * fits between its neighbours (slots are 22dp apart).
 */
private const val AccentGrowth = 0.03f
/** The accent moving from one letter to the next. */
private val Handover = tween<Float>(Motion.QUICK)
/** Second letters opening out of their letter (and folding back), the strip making room as they do. */
private val Unfold = tween<Float>(Motion.QUICK)
/**
 * A second letter's slot against a letter's: taller, as two letters swell wider and taller than one, and a little
 * easier to land on. Under the finger it swells less than a letter's 2.6x, to fit.
 */
private const val OptionSlot = 1.3f
private const val OptionSwell = 2f
/** An app's name under the finger swells less again: it's a word, not two letters. */
private const val NameSwell = 1.5f
/** How far past the letters' column, toward the screen edge, app names end (they grow inward from there). */
private val NameEdge = 5.dp

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

    /** The second letters' slot height. */
    val optionSlot get() = slot * OptionSlot

    /** Letter [i]'s center below the strip's top: in its slot, below the second letters out above it. */
    fun offset(i: Int) = slot * (i + 0.5f) + if (open in 0 until i) openCount * optionSlot else 0f

    fun letterY(i: Int) = if (progress >= 1f) toY[i] else lerp(fromY[i], toY[i], progress)

    private fun optionTarget(j: Int) = top + slot * (open + 1) + optionSlot * (j + 0.5f)

    fun optionY(j: Int) = if (progress >= 1f) optionTarget(j) else lerp(openFrom, optionTarget(j), progress)

    fun closingY(j: Int) = lerp(closingFirst + closingStep * j, toY[closing], progress)

    /** What's at [y] in the layout: a letter's index, or -2 - j for the open letter's j-th second letter. */
    fun hit(y: Float): Int {
        val down = y - top
        if (open < 0) return (down / slot).toInt().coerceIn(0, count - 1)
        val options = slot * (open + 1)
        val after = options + openCount * optionSlot
        return when {
            down < options -> (down / slot).toInt().coerceIn(0, open)
            down < after -> -2 - ((down - options) / optionSlot).toInt().coerceIn(0, openCount - 1)
            else -> (open + 1 + ((down - after) / slot).toInt()).coerceIn(open + 1, count - 1)
        }
    }

    /**
     * Lays the strip out with [newOpen]'s [newCount] second letters out (-1 and 0 for none), keeping letter [anchor] at
     * [anchorY] so what's under the finger stays there, or centering it all with no anchor (-1). Slots shrink from
     * [maxSlot] only as far as needed to fit [height]. With [glide], everything moves from where it is now.
     */
    fun layOut(height: Float, maxSlot: Float, newOpen: Int, newCount: Int, anchor: Int, anchorY: Float, glide: Boolean) {
        if (glide) {
            for (i in 0 until count) fromY[i] = letterY(i)
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
        val total = slot * count + openCount * optionSlot
        top = if (anchor < 0) (height - total) / 2f
        else (anchorY - offset(anchor)).coerceIn(0f, (height - total).coerceAtLeast(0f))
        for (i in 0 until count) toY[i] = if (openCount == 0) top + slot * (i + 0.5f) else top + offset(i)
        progress = if (glide) 0f else 1f
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
 * setting), [prefixes] are the letter's apps by name instead: they end at the strip's edge and grow inward, and a letter
 * with even one app opens.
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
) {
    // Read through a state: the gesture below outlives recompositions, and Settings can turn vibration off meanwhile.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val style = LocalLauncherStyle.current
    val measurer = rememberTextMeasurer()
    val layouts = remember(letters, measurer, style) { letters.map { measurer.measure(it, style.letter) } }
    val prefixLayouts: Map<String, List<TextLayoutResult>> = remember(prefixes, measurer, style) {
        prefixes.mapValues { (_, list) -> list.map { measurer.measure(it.text, style.letter) } }
    }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnLetter by rememberUpdatedState(onLetter)
    val currentPrefixes by rememberUpdatedState(prefixes)
    val currentOnPrefix by rememberUpdatedState(onPrefix)
    val currentNames by rememberUpdatedState(names)
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
                fun layOut(open: Int, count: Int, anchor: Int, anchorY: Float) {
                    plan.layOut(size.height.toFloat(), MaxSlot.toPx(), open, count, anchor, anchorY, glide = true)
                    unfoldJob?.cancel()
                    unfoldJob = scope.launch { animate(0f, 1f, animationSpec = Unfold) { value, _ -> plan.progress = value } }
                }
                fun move(y: Float) {
                    val count = currentLetters.size
                    if (count == 0) return
                    if (plan.height != size.height.toFloat()) {
                        plan.layOut(size.height.toFloat(), MaxSlot.toPx(), -1, 0, -1, 0f, glide = false)
                    }
                    touchY = y
                    val hit = plan.hit(y)
                    if (hit <= -2) {
                        // On one of the open letter's second letters.
                        val options = currentLetters.getOrNull(plan.open)?.let { currentPrefixes[it] }.orEmpty()
                        val j = -2 - hit
                        if (j != picked && j in options.indices) {
                            picked = j
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentOnPrefix(options[j])
                            accent(-1)
                        }
                        return
                    }
                    val index = hit.coerceIn(0, count - 1)
                    if (index == selected && picked < 0) return
                    selected = index
                    a11yIndex = index
                    picked = -1
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnLetter(currentLetters[index])
                    accent(index)
                    if (index != plan.open) {
                        // Open this letter's second letters (folding any others back), keeping it under the finger.
                        val options = currentPrefixes[currentLetters[index]].orEmpty()
                        val opening = if (options.size >= (if (currentNames) 1 else 2)) options.size else 0
                        if (opening > 0 || plan.open >= 0) layOut(if (opening > 0) index else -1, opening, index, plan.toY[index])
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    picked = -1
                    move(down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        move(change.position.y)
                    }
                    selected = -1
                    // Lifted: whatever opened folds back, and the strip settles back to its resting layout.
                    if (currentPrefixes.isNotEmpty() && plan.height >= 0f) layOut(-1, 0, -1, 0f)
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
                onDrawBehind {
                    val count = letters.size
                    if (count == 0) return@onDrawBehind
                    if (plan.height != size.height) plan.layOut(size.height, MaxSlot.toPx(), -1, 0, -1, 0f, glide = false)
                    val slot = plan.slot
                    val glide = plan.progress
                    // Letters rest 14dp in from the screen edge and bulge inward, toward the list (mirrored in RTL).
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val restX = if (rtl) 14.dp.toPx() else size.width - 14.dp.toPx()
                    val inward = if (rtl) 1f else -1f
                    val spread = WaveSpread.toPx()
                    val depth = WaveDepth.toPx()
                    // Large fonts in a short strip: shrink resting letters to their slot (capitals are ~0.71em tall).
                    val fit = (slot / (style.letter.fontSize.toPx() * 0.8f)).coerceAtMost(1f)
                    val touching = !touchY.isNaN() && wave > 0f
                    val swell = wave.coerceIn(0f, 1f)

                    if (touching) {
                        translate(restX + inward * depth * 0.6f, touchY) {
                            drawCircle(glow, glowRadius, Offset.Zero, alpha = (wave / GlowHeadroom).coerceAtMost(1f))
                        }
                    }

                    // A local function, called rather than passed around, so it allocates nothing. [accent] is how far
                    // toward the accent color it is; [fade] scales its alpha (second letters coming and going).
                    fun drawItem(layout: TextLayoutResult, cy: Float, accent: Float, fade: Float, swellTo: Float = 2.6f, name: Boolean = false) {
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
                        // The letter under the finger still swells to the full 2.6x, and a little more with the accent.
                        val scale = (fit + (swellTo - fit) * influence) * (1f + AccentGrowth * accent)
                        // At rest (and fully accented) the exact colors, not a lerp's round trip through Oklab.
                        val color = when {
                            accent <= 0f -> style.content
                            accent >= 1f -> style.accent
                            else -> lerp(style.content, style.accent, accent)
                        }
                        withTransform({ scale(scale, scale, pivot = Offset(cx, cy)) }) {
                            drawText(
                                layout,
                                color = color,
                                topLeft = Offset(left, cy - layout.size.height / 2f),
                                alpha = (0.55f + 0.45f * influence) * fade,
                            )
                        }
                    }
                    val itemSwell = if (names) NameSwell else OptionSwell
                    fun drawLetter(i: Int) = drawItem(layouts[i], plan.letterY(i), lerp(accentFrom[i], if (i == accented) swell else 0f, handover), 1f)

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
