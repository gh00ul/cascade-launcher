package com.gh00ul.cascade.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

private val MaxSlot = 22.dp
private val WaveSpread = 52.dp
private val WaveDepth = 64.dp

/**
 * The letter strip on the end edge. Dragging along it makes the letters near your finger swell and bulge
 * out in a wave, and jumps the app list to whichever letter you're on.
 */
@Composable
fun AlphabetWave(letters: List<String>, onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val style = LocalLauncherStyle.current
    val measurer = rememberTextMeasurer()
    val layouts = remember(letters, measurer, style) { letters.map { measurer.measure(it, style.letter) } }
    val currentLetters by rememberUpdatedState(letters)
    val currentOnLetter by rememberUpdatedState(onLetter)

    var touchY by remember { mutableFloatStateOf(Float.NaN) }
    var selected by remember { mutableIntStateOf(-1) }
    // For TalkBack the strip is a slider, one letter per step; this is the letter it's on.
    var a11yIndex by remember { mutableIntStateOf(0) }
    val wave by animateFloatAsState(
        targetValue = if (selected >= 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "wave",
    )

    Canvas(
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
            },
    ) {
        val count = letters.size
        if (count == 0) return@Canvas
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

        if (touching) {
            val center = Offset(restX + inward * depth * 0.6f, touchY)
            val radius = 170.dp.toPx()
            drawCircle(
                Brush.radialGradient(listOf(style.scrim.copy(alpha = 0.6f * wave), Color.Transparent), center, radius),
                radius,
                center,
            )
        }

        layouts.forEachIndexed { i, layout ->
            val cy = top + slot * (i + 0.5f)
            val influence = if (touching) {
                val d = cy - touchY
                exp(-(d * d) / (2f * spread * spread)) * wave
            } else 0f
            val cx = restX + inward * depth * influence
            // The letter under the finger still swells to the full 2.6x.
            val scale = fit + (2.6f - fit) * influence
            val color = if (i == selected) style.accent else style.content
            withTransform({ scale(scale, scale, pivot = Offset(cx, cy)) }) {
                drawText(
                    layout,
                    color = color,
                    topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                    alpha = 0.55f + 0.45f * influence,
                )
            }
        }
    }
}
