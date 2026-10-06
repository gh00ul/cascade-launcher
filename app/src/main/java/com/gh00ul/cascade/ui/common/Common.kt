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
    val BatteryFull = icon(
        "BatteryFull",
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z",
    )
    val MusicNote = icon("MusicNote", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")

    // Weather, from Material Symbols (filled, Apache 2.0), moved from their 960-unit grid onto this 24-unit one.
    val ClearDay = icon(
        "ClearDay",
        "M11.25,4.75v-3.75h1.5v3.75h-1.5Zm6.4,2.65l-1.05,-1.05 2.65,-2.675 1.05,1.075 -2.65,2.65Zm1.6,5.35v-1.5h3.75v1.5" +
            "H19.25ZM11.25,23v-3.75h1.5v3.75h-1.5ZM6.325,7.375L3.7,4.75l1.05,-1.05 2.65,2.65 -1.075,1.025Zm12.95,12.925" +
            "L16.6,17.65l1.025,-1.025 2.7,2.6 -1.05,1.075ZM1,12.75v-1.5h3.75v1.5H1Zm3.775,7.55l-1.075,-1.05 2.625,-2.625 " +
            "0.55,0.5 0.55,0.525 -2.65,2.65Zm2.975,-4.05q-1.75,-1.75 -1.75,-4.25t1.75,-4.25q1.75,-1.75 4.25,-1.75t4.25," +
            "1.75q1.75,1.75 1.75,4.25t-1.75,4.25q-1.75,1.75 -4.25,1.75t-4.25,-1.75Z",
    )
    val Bedtime = icon(
        "Bedtime",
        "M12.1,22q-2.1,0 -3.938,-0.8t-3.2,-2.163Q3.6,17.675 2.8,15.838T2,11.9q0,-3.65 2.325,-6.438T10.25,2q-0.45,2.45 " +
            "0.275,4.812T13.025,10.95q1.775,1.775 4.138,2.513T22,13.75q-0.65,3.6 -3.45,5.925T12.1,22Z",
    )
    val PartlyCloudyDay = icon(
        "PartlyCloudyDay",
        "M6,19.5q-1.45,0 -2.475,-1.025t-1.025,-2.475q0,-1.45 1.025,-2.475t2.475,-1.025q0.975,0 1.788,0.525t1.188,1.425" +
            "l0.45,1.025h1.1q0.825,0 1.4,0.6t0.575,1.425q0,0.825 -0.588,1.413T10.5,19.5H6Zm7.95,-1.825q0,-1.525 -1.025," +
            "-2.613T10.4,13.975q-0.5,-1.4 -1.7,-2.225t-2.7,-0.75q0.35,-2.2 2.062,-3.6T12,6q2.5,0 4.25,1.75t1.75,4.25q0," +
            "1.95 -1.113,3.5T13.95,17.675ZM11.25,4.75v-3.75h1.5v3.75h-1.5Zm6.4,2.65l-1.05,-1.075 2.65,-2.65 1.05,1.075 " +
            "-2.65,2.65Zm1.6,5.35v-1.5h3.75v1.5H19.25Zm0.025,7.55L16.625,17.65l1.075,-1.075 2.65,2.65 -1.075,1.075ZM6.35," +
            "7.4L3.7,4.75l1.05,-1.05 2.65,2.65 -1.05,1.05Z",
    )
    val PartlyCloudyNight = icon(
        "PartlyCloudyNight",
        "M6,12.75q1.275,0 2.337,0.688T9.9,15.325l0.175,0.425h0.475q1.125,0 1.913,0.8t0.788,1.95q0,1.15 -0.812,1.95T10.5," +
            "21.25H6q-1.75,0 -3,-1.25T1.75,17q0,-1.775 1.25,-3.013T6,12.75Zm5.25,-10.75q-0.45,2.45 0.275,4.812T14.025," +
            "10.95q1.775,1.775 4.138,2.513T23,13.75q-0.65,3.6 -3.45,5.925T13.1,22h-0.2q0.875,-0.6 1.363,-1.525t0.488," +
            "-1.975q0,-1.65 -1.05,-2.825t-2.625,-1.375q-0.75,-1.4 -2.1,-2.225t-2.975,-0.825q-0.8,0 -1.562,0.213T3,12.1" +
            "v-0.2q0,-3.65 2.325,-6.438T11.25,2Z",
    )
    val Cloud = icon(
        "Cloud",
        "M6.275,20q-2.2,0 -3.738,-1.538T1,14.725q0,-1.95 1.25,-3.425t3.175,-1.775q0.5,-2.425 2.35,-3.963T12.05,4.025q2.8," +
            "0 4.725,2.038T18.7,10.95v0.6q1.8,-0.05 3.05,1.163T23,15.775q0,1.725 -1.25,2.975t-2.975,1.25H6.275Z",
    )
    val Foggy = icon(
        "Foggy",
        "M18,18.875q-0.35,0 -0.613,-0.263T17.125,18q0,-0.35 0.263,-0.613T18,17.125q0.35,0 0.613,0.263T18.875,18q0,0.35 " +
            "-0.263,0.613T18,18.875ZM7,22q-0.35,0 -0.613,-0.263T6.125,21.125q0,-0.35 0.263,-0.613T7,20.25q0.35,0 0.613," +
            "0.263T7.875,21.125q0,0.35 -0.263,0.613T7,22Zm-1,-3.125q-0.35,0 -0.613,-0.263T5.125,18q0,-0.35 0.263,-0.613" +
            "T6,17.125h9q0.35,0 0.613,0.263T15.875,18q0,0.35 -0.263,0.613T15,18.875H6ZM10,22q-0.35,0 -0.613,-0.263T9.125," +
            "21.125q0,-0.35 0.263,-0.613T10,20.25h7q0.35,0 0.613,0.263T17.875,21.125q0,0.35 -0.263,0.613T17,22H10ZM7.25," +
            "15.5q-2.175,0 -3.713,-1.538T2,10.25q0,-1.975 1.413,-3.525T6.925,5.025q0.8,-1.4 2.113,-2.212T12,2q2.275,0 " +
            "3.812,1.438T17.7,7q1.975,0.1 3.138,1.338T22,11.25q0,1.75 -1.238,3T17.75,15.5H7.25Z",
    )
    val Rainy = icon(
        "Rainy",
        "M13.95,21.925q-0.275,0.125 -0.588,0.025T12.925,21.575l-1.725,-3.45q-0.125,-0.275 -0.038,-0.588T11.525,17.1" +
            "q0.275,-0.125 0.588,-0.025t0.438,0.375l1.725,3.45q0.125,0.275 0.038,0.588T13.95,21.925Zm6,-0.025q-0.275," +
            "0.125 -0.588,0.025T18.925,21.55l-1.725,-3.45q-0.125,-0.275 -0.038,-0.588T17.525,17.075q0.275,-0.125 0.588," +
            "-0.025t0.438,0.375l1.725,3.45q0.125,0.275 0.038,0.588T19.95,21.9Zm-12,0q-0.275,0.125 -0.588,0.038T6.925," +
            "21.575l-1.725,-3.45q-0.125,-0.275 -0.025,-0.588t0.375,-0.438q0.275,-0.125 0.588,-0.038T6.575,17.425l1.725," +
            "3.475q0.125,0.275 0.025,0.575t-0.375,0.425Zm-0.7,-6.4q-2.175,0 -3.713,-1.538T2,10.25q0,-1.975 1.413,-3.525" +
            "T6.925,5.025q0.8,-1.4 2.113,-2.212T12,2q2.275,0 3.812,1.438T17.7,7q1.975,0.1 3.138,1.338T22,11.25q0,1.75 " +
            "-1.238,3T17.75,15.5H7.25Z",
    )
    val WeatherSnowy = icon(
        "WeatherSnowy",
        "M5.788,18.459q-0.288,-0.291 -0.288,-0.713t0.291,-0.709q0.291,-0.288 0.713,-0.288t0.709,0.291q0.288,0.291 0.288," +
            "0.713t-0.291,0.709q-0.291,0.288 -0.713,0.288t-0.709,-0.291Zm3,3.25q-0.288,-0.291 -0.288,-0.713t0.291,-0.709" +
            "q0.291,-0.288 0.713,-0.288t0.709,0.291q0.288,0.291 0.288,0.713T10.209,21.713Q9.918,22 9.497,22T8.787,21.709Z" +
            "m3,-3.25q-0.288,-0.291 -0.288,-0.713t0.291,-0.709q0.291,-0.288 0.713,-0.288t0.709,0.291q0.288,0.291 0.288," +
            "0.713t-0.291,0.709q-0.291,0.288 -0.713,0.288t-0.709,-0.291Zm6,0q-0.288,-0.291 -0.288,-0.713t0.291,-0.709" +
            "q0.291,-0.288 0.713,-0.288t0.709,0.291q0.288,0.291 0.288,0.713t-0.291,0.709q-0.291,0.288 -0.713,0.288" +
            "t-0.709,-0.291Zm-3,3.25q-0.288,-0.291 -0.288,-0.713t0.291,-0.709q0.291,-0.288 0.713,-0.288t0.709,0.291" +
            "q0.288,0.291 0.288,0.713T16.209,21.713Q15.918,22 15.497,22T14.788,21.709ZM7.25,14.5q-2.171,0 -3.711,-1.538" +
            "Q2,11.424 2,9.254 2,7.275 3.413,5.725 4.825,4.175 6.925,4.025q0.8,-1.4 2.113,-2.212T12.011,1q2.264,0 3.802," +
            "1.438Q17.35,3.875 17.7,6q1.975,0.1 3.138,1.338T22,10.241Q22,12 20.761,13.25 19.521,14.5 17.75,14.5H7.25Z",
    )
    val Thunderstorm = icon(
        "Thunderstorm",
        "M7.5,23l0.9,-2.5h-1.9l1.25,-3.5h2.5l-1.075,2.5h2.075L8.5,23h-1Zm6.75,-1l0.7,-2h-1.95l1.075,-3h2.5l-0.875,2h2.05" +
            "L15.25,22h-1ZM7.25,15.5q-2.175,0 -3.713,-1.538T2,10.25q0,-1.975 1.413,-3.525T6.925,5.025q0.8,-1.4 2.113," +
            "-2.212T12,2q2.275,0 3.812,1.438T17.7,7q1.975,0.1 3.138,1.338T22,11.25q0,1.75 -1.238,3T17.75,15.5H7.25Z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
