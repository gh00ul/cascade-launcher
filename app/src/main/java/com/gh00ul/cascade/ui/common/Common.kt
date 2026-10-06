package com.gh00ul.cascade.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gh00ul.cascade.data.IconImage

/**
 * [key]'s entry in a map that is only ever replaced whole (the icon map, notifications by app), as a state of its own.
 * Whoever reads it recomposes only when that entry changes under [policy], not on every new map; and while it stays
 * the same, the first instance is kept. A null [key] reads null.
 */
@Composable
fun <K : Any, V : Any> rememberEntry(
    map: State<Map<K, V>>,
    key: K?,
    policy: SnapshotMutationPolicy<V?> = structuralEqualityPolicy(),
): State<V?> = remember(map, key, policy) { derivedStateOf(policy) { key?.let { map.value[it] } } }

/**
 * For a value read during composition, so the first frame is right, and read again by a lifecycle effect on every
 * start or resume. An effect that registers while the lifecycle is already at that state runs at once, replaying the
 * event in the same frame as that composition: [consume] is true for that one run, so the read isn't made twice.
 */
class ReplaySkip internal constructor(private var pending: Boolean) {
    fun consume(): Boolean = pending.also { pending = false }
}

/** A [ReplaySkip] that skips only if the lifecycle was at least [state] when this was first composed. */
@Composable
fun rememberReplaySkip(state: Lifecycle.State): ReplaySkip {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember { ReplaySkip(lifecycle.currentState.isAtLeast(state)) }
}

/** An app icon; themed glyphs take the surrounding content color so they read on any background. */
@Composable
fun AppIcon(icon: IconImage?, size: Dp, modifier: Modifier = Modifier) {
    val tint = if (icon?.isGlyph == true) LocalContentColor.current else null
    val badge = icon?.badge
    if (icon == null || badge == null) {
        AppIcon(icon?.bitmap, size, modifier, tint = tint)
    } else {
        // A work badge stays in its own colors over the tinted glyph.
        Box(modifier.size(size)) {
            AppIcon(icon.bitmap, size, tint = tint)
            AppIcon(badge, size)
        }
    }
}

@Composable
fun AppIcon(icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    if (icon != null) {
        Image(
            icon,
            contentDescription = null,
            modifier = modifier.size(size),
            colorFilter = tint?.let { ColorFilter.tint(it) },
            filterQuality = FilterQuality.Medium,
        )
    } else {
        Box(modifier.size(size).background(LocalContentColor.current.copy(alpha = 0.12f), CircleShape))
    }
}

/** The few Material icons that aren't in material-icons-core (paths from Material Icons, Apache 2.0). */
object ExtraIcons {
    val VisibilityOff = icon(
        "VisibilityOff",
        "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 " +
            "-1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28," +
            "2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73," +
            "22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 " +
            "0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 " +
            "0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
    )
    val Visibility = icon(
        "Visibility",
        "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12," +
            "17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3," +
            "-1.34 3,-3 -1.34,-3 -3,-3z",
    )
    val Wallpaper = icon(
        "Wallpaper",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5," +
            "3.01L14.5,12l4.5,6H5l3.5,-4.5z",
    )

    val Play = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Alarm = icon(
        "Alarm",
        "M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8L11,8v6l4.75," +
            "2.85 0.75,-1.23 -4,-2.37L12.5,8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87," +
            "0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
    val Timer = icon(
        "Timer",
        "M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12," +
            "4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7," +
            "-7 7,3.13 7,7 -3.13,7 -7,7z",
    )
    val Event = icon(
        "Event",
        "M17,12h-5v5h5v-5zM16,1v2H8V1H6v2H5c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9," +
            "-2 -2,-2h-1V1h-2zM19,19H5V8h14v11z",
    )
    val Bolt = icon(
        "Bolt",
        "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97," +
            "17.55 11,21 11,21z",
    )
    val BatteryAlert = icon(
        "BatteryAlert",
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4," +
            "4 15.67,4zM13,18h-2v-2h2v2zM13,14h-2V9h2v5z",
    )
    val MusicNote = icon("MusicNote", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
