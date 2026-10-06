package com.gh00ul.cascade.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
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

/**
 * The letter strip on the end edge. Dragging along it makes the letters near your finger swell and bulge
 * out in a wave, and jumps the app list to whichever letter you're on.
 */
@Composable
fun AlphabetWave(letters: List<String>, onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    // Read through a state: the gesture below outlives recompositions, and Settings can turn vibration off meanwhile.
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val style = LocalLauncherStyle.current
    val measurer = rememberTextMeasurer()
    val layouts = remember(letters, measurer, style) { letters.map { measurer.measure(it, style.letter) } }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnLetter by rememberUpdatedState(onLetter)
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

    Spacer(
        modifier
            .width(36.dp)
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
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    select(down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        select(change.position.y)
                    }
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
                }
            },
    )
}
