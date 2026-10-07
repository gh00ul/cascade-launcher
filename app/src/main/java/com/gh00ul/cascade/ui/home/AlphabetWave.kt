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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** The second letters' column: how far inward of the swollen letter it stands, and each one's height at most. */
private val PrefixOffset = 72.dp
private val PrefixSlot = 44.dp
/**
 * How far past the strip's inner edge the finger slides to pick second letters, and how far back it comes to return to
 * the letters: apart, so a finger resting near the line doesn't flicker between the two.
 */
private val EnterPrefixes = 64.dp
private val LeavePrefixes = 40.dp

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
 * The letter strip on the end edge. Dragging along it makes the letters near your finger swell and bulge
 * out in a wave, and jumps the app list to whichever letter you're on. [restAlpha] fades the strip while it rests
 * (read while drawing its layer, so it can follow a scroll); under the finger it's always at full strength.
 *
 * With [prefixes] (the Second letters setting), a held letter with more than one shows them in a column beside it (Ma,
 * Me, Mu); sliding the finger toward the list picks among them by height, jumping the list to that one with [onPrefix],
 * and sliding back returns to the letters.
 */
@Composable
fun AlphabetWave(
    letters: List<String>,
    onLetter: (String) -> Unit,
    modifier: Modifier = Modifier,
    restAlpha: () -> Float = { 1f },
    prefixes: Map<String, List<LetterPrefix>> = emptyMap(),
    onPrefix: (LetterPrefix) -> Unit = {},
) {
    // Read through a state: the gesture below outlives recompositions, and Settings can turn vibration off meanwhile.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val style = LocalLauncherStyle.current
    val measurer = rememberTextMeasurer()
    val layouts = remember(letters, measurer, style) { letters.map { measurer.measure(it, style.letter) } }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnLetter by rememberUpdatedState(onLetter)
    val currentPrefixes by rememberUpdatedState(prefixes)
    val currentOnPrefix by rememberUpdatedState(onPrefix)
    val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
    val prefixStyle = remember(style) { style.letter.copy(fontSize = 20.sp) }
    val prefixLayouts = remember(prefixes, measurer, prefixStyle) {
        prefixes.mapValues { (_, list) -> list.map { measurer.measure(it.text, prefixStyle) } }
    }
    val scope = rememberCoroutineScope()

    var touchY by remember { mutableFloatStateOf(Float.NaN) }
    var selected by remember { mutableIntStateOf(-1) }
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

    // Second letters: whether the finger is picking among them, the one it's on (kept as the column fades out after
    // release), and the column's top, which follows the finger until picking starts and then holds still under it.
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableIntStateOf(-1) }
    var columnTop by remember { mutableFloatStateOf(0f) }
    val columnShown = selected >= 0 && (letters.getOrNull(selected)?.let { prefixes[it] }?.size ?: 0) >= 2
    val column by animateFloatAsState(if (columnShown) 1f else 0f, tween(Motion.QUICK), label = "secondLetters")

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
            .pointerInput(Unit) {
                var handoverJob: Job? = null
                fun select(y: Float) {
                    val count = currentLetters.size
                    if (count == 0) return
                    val slot = min(size.height.toFloat() / count, MaxSlot.toPx())
                    val top = (size.height - slot * count) / 2f
                    val index = ((y - top) / slot).toInt().coerceIn(0, count - 1)
                    touchY = y
                    if (index != selected) {
                        selected = index
                        a11yIndex = index
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnLetter(currentLetters[index])
                        if (index != accented) {
                            val from = currentAccentFrom
                            val swellNow = wave.coerceIn(0f, 1f)
                            for (i in from.indices) from[i] = lerp(from[i], if (i == accented) swellNow else 0f, handover)
                            accented = index
                            // Reset here rather than in the coroutine, so no frame draws the new letter at the old progress.
                            handover = 0f
                            handoverJob?.cancel()
                            handoverJob = scope.launch { animate(0f, 1f, animationSpec = Handover) { value, _ -> handover = value } }
                        }
                    }
                }
                fun heldOptions(): List<LetterPrefix> = currentLetters.getOrNull(selected)?.let { currentPrefixes[it] }.orEmpty()
                fun prefixSlot(count: Int) = min(PrefixSlot.toPx(), size.height.toFloat() / count)
                fun placeColumn(y: Float, count: Int) {
                    val height = prefixSlot(count) * count
                    columnTop = (y - height / 2f).coerceIn(0f, (size.height - height).coerceAtLeast(0f))
                }
                fun pick(y: Float, options: List<LetterPrefix>) {
                    if (options.isEmpty()) return
                    val index = ((y - columnTop) / prefixSlot(options.size)).toInt().coerceIn(0, options.size - 1)
                    if (index != picked) {
                        picked = index
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnPrefix(options[index])
                    }
                }
                fun move(x: Float, y: Float) {
                    // How far past the strip's inner edge, toward the list, the finger is.
                    val inward = if (rtl) x - size.width else -x
                    if (!picking) {
                        select(y)
                        val options = heldOptions()
                        if (options.size >= 2) placeColumn(y, options.size)
                        if (options.size >= 2 && inward > EnterPrefixes.toPx()) {
                            picking = true
                            pick(y, options)
                        }
                    } else if (inward < LeavePrefixes.toPx()) {
                        // Back on the letters: the list returns to the letter's top.
                        picking = false
                        picked = -1
                        select(y)
                        currentLetters.getOrNull(selected)?.let(currentOnLetter)
                    } else {
                        pick(y, heldOptions())
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    picking = false
                    picked = -1
                    move(down.position.x, down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        move(change.position.x, change.position.y)
                    }
                    picking = false
                    selected = -1
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
                    val slot = min(size.height / count, MaxSlot.toPx())
                    val top = (size.height - slot * count) / 2f
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

                    // A local function, called rather than passed around, so it allocates nothing.
                    fun drawLetter(i: Int) {
                        val layout = layouts[i]
                        val cy = top + slot * (i + 0.5f)
                        val influence = if (touching) {
                            val d = cy - touchY
                            exp(-(d * d) / (2f * spread * spread)) * wave
                        } else 0f
                        val cx = restX + inward * depth * influence
                        val accent = lerp(accentFrom[i], if (i == accented) swell else 0f, handover)
                        // The letter under the finger still swells to the full 2.6x, and a little more with the accent.
                        val scale = (fit + (2.6f - fit) * influence) * (1f + AccentGrowth * accent)
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
                                topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                                alpha = 0.55f + 0.45f * influence,
                            )
                        }
                    }
                    // The accented letter last, so if the rise's overshoot makes neighbours touch, it stays in front.
                    for (i in layouts.indices) if (i != accented) drawLetter(i)
                    if (accented >= 0 && accented < layouts.size) drawLetter(accented)

                    // The held letter's second letters, in a column inward of it on a pill of the scrim.
                    val options = if (column > 0f) letters.getOrNull(accented)?.let { prefixLayouts[it] } else null
                    if (options != null && options.size >= 2) {
                        val slotP = min(PrefixSlot.toPx(), size.height / options.size)
                        var widest = 0
                        for (o in options) if (o.size.width > widest) widest = o.size.width
                        val padX = 16.dp.toPx()
                        val padY = 6.dp.toPx()
                        // Slides in a little from the letter as it appears.
                        val cx = restX + inward * (depth + PrefixOffset.toPx() - 12.dp.toPx() * (1f - column))
                        val pillWidth = widest + 2 * padX
                        drawRoundRect(
                            style.scrim,
                            topLeft = Offset(cx - pillWidth / 2f, columnTop - padY),
                            size = Size(pillWidth, slotP * options.size + 2 * padY),
                            cornerRadius = CornerRadius(22.dp.toPx()),
                            alpha = 0.72f * column,
                        )
                        for (i in options.indices) {
                            val layout = options[i]
                            val on = i == picked
                            val cy = columnTop + slotP * (i + 0.5f)
                            val scale = if (on) 1.2f else 1f
                            withTransform({ scale(scale, scale, pivot = Offset(cx, cy)) }) {
                                drawText(
                                    layout,
                                    color = if (on) style.accent else style.content,
                                    topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                                    alpha = column * if (on || !picking) 1f else 0.7f,
                                )
                            }
                        }
                    }
                }
            },
    )
}
