package com.gh00ul.cascade.ui.home

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.ui.theme.Motion
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * Home and the A–Z list have two rests: home, with its last screen in view, and the list, with its search pill at the
 * top. Between them is the seam, home's last screen, which the list's top slides up over. A fling never leaves the list
 * on the seam: it settles on one rest or the other, so home is never left half covered.
 */

/** Slower than this, a finger let go on the seam goes to the nearer rest; faster, the way it was heading. */
private val FlickVelocity = 400.dp

/**
 * How far the list is from resting on the A–Z list's top, in px: 0 there, negative while home shows (minus home's
 * height at the very top), positive a little way past it. Null deeper in, once the header (row 1) has scrolled away.
 */
internal fun LazyListState.fromListTop(): Float? = when (firstVisibleItemIndex) {
    0 -> layoutInfo.visibleItemsInfo.firstOrNull()?.let { home -> firstVisibleItemScrollOffset - home.size.toFloat() }
    1 -> firstVisibleItemScrollOffset.toFloat()
    else -> null
}

/**
 * How far the A–Z list has come up over home: 0 while home rests, 1 once the list's top reaches the top. A home taller
 * than [homeHeight] (lots of favorites) scrolls its overflow first, at 0; only its last screen counts. Read it while
 * drawing: it changes on every scroll frame.
 */
internal fun LazyListState.listCover(homeHeight: Float): Float {
    val at = fromListTop() ?: return if (firstVisibleItemIndex > 0) 1f else 0f
    return (1f + at / homeHeight).coerceIn(0f, 1f)
}

/** Flings between home and the A–Z list that settle on a rest; [homeHeight] is home's last screen, the seam. */
@Composable
internal fun rememberHomeSnapFling(list: LazyListState, homeHeight: Float): FlingBehavior {
    val decay = rememberSplineBasedDecay<Float>()
    val flick = with(LocalDensity.current) { FlickVelocity.toPx() }
    return remember(list, decay, homeHeight, flick) { HomeSnapFling(list, decay, homeHeight, flick) }
}

/**
 * The list's fling, as the platform's own (the same spline decay), until it would end on the seam: then it eases onto
 * the nearer rest instead, carrying the fling's speed in. A fling from home lands on the list's top rather than running
 * on into the letters; one heading home from inside the list lands on the list's top or on home, whichever its stop
 * was nearer. Flings clear of the seam, deeper in the list or in a tall home's overflow, are left alone.
 */
private class HomeSnapFling(
    private val list: LazyListState,
    private val decay: DecayAnimationSpec<Float>,
    private val seam: Float,
    private val flickVelocity: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = withContext(FlingMotion) {
        val from = list.fromListTop()
        val rest = from?.let { restFor(it, initialVelocity) }
        if (from != null && rest != null) {
            settle(from, rest, initialVelocity)
            return@withContext 0f
        }
        if (abs(initialVelocity) <= 1f) return@withContext initialVelocity
        // From deep in the list, where it is isn't known until the header comes back: look again once it does. Known
        // here and clear of the seam, it stays clear, since a decay's stop doesn't change along the way.
        var watching = from == null
        var velocityLeft = initialVelocity
        var settleFrom = 0f
        var settleTo = Float.NaN
        var last = 0f
        AnimationState(0f, initialVelocity).animateDecay(decay) {
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            velocityLeft = velocity
            if (abs(delta - consumed) > 0.5f) {
                cancelAnimation()
                return@animateDecay
            }
            if (!watching) return@animateDecay
            val at = list.fromListTop() ?: return@animateDecay
            watching = false
            val stop = restFor(at, velocity) ?: return@animateDecay
            // A fast frame can jump the header and land past the list's top: put it back before this frame draws.
            if ((stop - at) * velocity <= 0f) {
                land(stop)
            } else {
                settleFrom = at
                settleTo = stop
            }
            cancelAnimation()
        }
        if (settleTo.isNaN()) return@withContext velocityLeft
        settle(settleFrom, settleTo, velocityLeft)
        0f
    }

    /**
     * Where a fling at [velocity] from [at] (a [fromListTop]) rests: home (-[seam]), the list's top (0), or null when it
     * ends clear of the seam and has nowhere to settle.
     */
    private fun restFor(at: Float, velocity: Float): Float? {
        val home = -seam
        if (at > home && at < 0f) {
            // Let go on the seam: a flick goes the way it was heading, anything slower to the nearer rest.
            return when {
                abs(velocity) >= flickVelocity -> if (velocity > 0f) 0f else home
                at > home / 2 -> 0f
                else -> home
            }
        }
        // Off it, a fling that would stop on the seam or cross it settles on the rest nearer its stop. So does one heading
        // up the list that would stop with the header part way off the top, rather than leave the search pill cut off.
        val end = at + decay.calculateTargetValue(0f, velocity)
        val reaches = if (at >= 0f) velocity < 0f && end < headerHeight() else end > home
        if (!reaches) return null
        return if (end > home / 2) 0f else home
    }

    /** The A–Z header's height while it is the first row on screen, else 0. */
    private fun headerHeight(): Float =
        list.layoutInfo.visibleItemsInfo.firstOrNull()?.takeIf { it.index == 1 }?.size?.toFloat() ?: 0f

    /**
     * Scrolls from [from] to [rest] on a critically damped spring that starts at the fling's [velocity]. Heading there
     * faster than the spring would carry it, it stiffens to ease straight in. Only a fling that meets the rest close by
     * and fast (one from deep in the list, stopping on its top) is past what that allows: it gives a few dp past the
     * rest and springs back, as a list does at its edge, rather than stopping dead.
     */
    private suspend fun ScrollScope.settle(from: Float, rest: Float, velocity: Float) {
        val distance = rest - from
        if (abs(distance) >= 0.5f) {
            val omega = (velocity * sign(distance) / abs(distance)).coerceIn(SnapOmega, MaxSnapOmega)
            var last = 0f
            AnimationState(0f, velocity).animateTo(distance, spring(1f, omega * omega, 0.5f)) {
                val delta = value - last
                val consumed = scrollBy(delta)
                last = value
                if (abs(delta - consumed) > 0.5f) cancelAnimation()
            }
        }
        land(rest)
    }

    /**
     * Puts the list exactly on [rest]: the list keeps fractions of a pixel between scrolls, which can leave it a pixel
     * short. A home that fits the screen rests at the list's very start, so it aims a pixel past, where the edge stops it.
     */
    private fun ScrollScope.land(rest: Float) {
        val at = list.fromListTop() ?: return
        val home = list.layoutInfo.visibleItemsInfo.firstOrNull()?.takeIf { it.index == 0 }
        val target = if (rest < 0f && home != null && home.size <= seam + 0.5f) -home.size - 1f else rest
        if (abs(target - at) >= 0.5f) scrollBy(target - at)
    }
}

/** The settle spring's natural frequency, from its stiffness (a unit mass), and the most a fast fling stiffens it to. */
private val SnapOmega = sqrt(Motion.SNAP_STIFFNESS)
private val MaxSnapOmega = SnapOmega * 2

/**
 * Flings keep their speed with Remove animations on, as the platform's own do, and the settle is part of the fling.
 * Compose's default fling runs at this scale for the same reason.
 */
private object FlingMotion : MotionDurationScale {
    override val scaleFactor = 1f
}
