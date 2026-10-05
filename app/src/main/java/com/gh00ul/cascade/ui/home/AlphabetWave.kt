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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlin.math.exp
import kotlin.math.min

private val MaxSlot = 22.dp
private val WaveSpread = 52.dp
private val WaveDepth = 64.dp

/**
 * The letter strip on the right edge. Dragging along it makes the letters near your finger swell and bulge
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
    val wave by animateFloatAsState(
        targetValue = if (selected >= 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "wave",
    )

    Canvas(
        modifier
            .width(36.dp)
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
        val restX = size.width - 14.dp.toPx()
        val spread = WaveSpread.toPx()
        val depth = WaveDepth.toPx()
        val touching = !touchY.isNaN() && wave > 0f

        if (touching) {
            val center = Offset(restX - depth * 0.6f, touchY)
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
            val cx = restX - depth * influence
            val scale = 1f + 1.6f * influence
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
