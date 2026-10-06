package com.gh00ul.cascade.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/**
 * Cascade's one motion language: every animation on the home screen takes its durations, easings and springs from
 * here, so rows, cards, chips and screens move alike.
 *
 * Every spec is finite, and motion only ever starts from an event (a press, a state change, a return home, a back
 * gesture), never loops while idle (see BATTERY.md). Compose scales each spec by the system animator duration scale,
 * so "Remove animations" makes them all instant. That includes a tween's `delayMillis`, but not a coroutine `delay()`:
 * time motion with specs, never with `delay()`.
 *
 * The specs are shared values, so starting an animation allocates no spec and running one allocates nothing per frame.
 */
object Motion {
    /** In-place flips: glyphs, readouts, the seek thumb, the selected letter. */
    const val QUICK = 120
    /** Content leaving. Short, so what replaces it isn't kept waiting. */
    const val EXIT = 90
    /** Content arriving, after [EXIT] has cleared the way. */
    const val ENTER = 220
    /** Screen-level motion: home settling in on a return, search opening. */
    const val SCREEN = 360
    /** Album-art colors blending into the next track's. */
    const val BLEND = 600
    /** Delay between consecutive rows of a staggered list, for at most [STAGGER_ROWS] rows. */
    const val STAGGER = 20
    const val STAGGER_ROWS = 8
    /** How far a pressed row shrinks. */
    const val PRESSED_SCALE = 0.97f

    /** Emphasized decelerate: arrivals that start fast and land softly. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Emphasized accelerate: departures that ease away and leave quickly. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Height changes: critically damped, so a card or a list opening never bounces. */
    val Size: FiniteAnimationSpec<IntSize> = spring(1f, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold)
    /** Content fades in once the outgoing content has faded out. */
    val FadeIn: EnterTransition = fadeIn(tween(ENTER, delayMillis = EXIT))
    val FadeOut: ExitTransition = fadeOut(tween(EXIT))

    /** Content below a row (notifications, a player's extras) opening downward and closing back up. */
    val ExpandDown: EnterTransition = expandVertically(Size, Alignment.Top) + FadeIn
    val CollapseUp: ExitTransition = shrinkVertically(Size, Alignment.Top) + FadeOut
    /** Content above the favorites (the floating player, cards) growing up from them and shrinking back down. */
    val ExpandUp: EnterTransition = expandVertically(Size, Alignment.Bottom) + FadeIn
    val CollapseDown: ExitTransition = shrinkVertically(Size, Alignment.Bottom) + FadeOut

    /**
     * Whole-screen layers that trade places (search, and the list and alphabet strip under it) don't wait their turn:
     * the arriving one fades in gently while the leaving one gets out of the way fast.
     */
    val LayerFadeIn: FiniteAnimationSpec<Float> = tween(ENTER, easing = Decelerate)
    val LayerFadeOut: FiniteAnimationSpec<Float> = tween(QUICK)
    val LayerIn: EnterTransition = fadeIn(LayerFadeIn)
    val LayerOut: ExitTransition = fadeOut(LayerFadeOut)

    /**
     * AnimatedContent's swap: the old content fades out, the new one fades in, and the size follows with [Size].
     * Clipped while the size animates unless [clip] is false. A function, because ContentTransform is mutable.
     */
    fun swap(clip: Boolean = true): ContentTransform = ContentTransform(FadeIn, FadeOut, sizeTransform = SizeTransform(clip) { _, _ -> Size })

    /** A press lands at once, without overshoot. */
    val PressIn: AnimationSpec<Float> = spring(1f, Spring.StiffnessHigh)
    /** A release springs back with a little give. */
    val PressOut: AnimationSpec<Float> = spring(0.6f, Spring.StiffnessMedium)
    /** Something dragged and let go (a swiped row, a cancelled back gesture) returning to rest. */
    val SwipeBack: AnimationSpec<Float> = spring(0.6f, Spring.StiffnessMediumLow)
    /** The alphabet wave swelling under the finger: quick, with a little overshoot. */
    val WaveRise: AnimationSpec<Float> = spring(0.6f, Spring.StiffnessMedium)
    /** The wave falling back once the finger lifts: no overshoot, so it never dips below rest. */
    val WaveFall: AnimationSpec<Float> = spring(1f, Spring.StiffnessMediumLow)
    /** Home settling in on a return, and a back gesture easing out once it commits. */
    val Settle: AnimationSpec<Float> = tween(SCREEN, easing = Decelerate)
}
