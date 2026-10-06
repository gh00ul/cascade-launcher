package com.gh00ul.cascade.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.IconSize

/** Material You colors from the wallpaper on Android 12+. Menus and sheets on the home screen are always dark. */
@Composable
fun LauncherTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme(dark), content = content)
}

@Composable
fun colorScheme(dark: Boolean) = colorScheme(LocalContext.current, dark)

/** The same scheme outside composition, for callers that remember it: the dynamic one reads dozens of system colors. */
fun colorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

/**
 * Colors and text drawn straight over the wallpaper: white with a dark shadow normally,
 * near-black with a light glow when the wallpaper is light ([darkText]).
 */
@Immutable
class LauncherStyle(val darkText: Boolean, val accent: Color) {
    val content = if (darkText) Color(0xFF16181C) else Color.White
    /** Tint laid over the wallpaper behind content, and the backdrop of overlays. */
    val scrim = if (darkText) Color.White else Color.Black

    private val shadow = if (darkText) {
        Shadow(Color.White.copy(alpha = 0.55f), Offset(0f, 1f), 10f)
    } else {
        Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 8f)
    }

    val clock = TextStyle(color = content, fontSize = 76.sp, fontWeight = FontWeight.Light, letterSpacing = (-2.5).sp, shadow = shadow)
    val date = TextStyle(color = content, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.15.sp, shadow = shadow)
    val favorite = TextStyle(color = content, fontSize = 26.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    private val favoriteSmall = favorite.copy(fontSize = 22.sp)
    private val favoriteLarge = favorite.copy(fontSize = 27.sp)
    private val favoriteXL = favorite.copy(fontSize = 28.sp)
    val app = TextStyle(color = content, fontSize = 20.sp, shadow = shadow)
    val small = TextStyle(color = content.copy(alpha = 0.8f), fontSize = 14.sp, shadow = shadow)
    val section = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, shadow = shadow)
    val letter = TextStyle(color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)
    val clockBold = TextStyle(color = content, fontSize = 80.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp, shadow = shadow)
    val clockStacked = TextStyle(color = content, fontSize = 96.sp, lineHeight = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, shadow = shadow)
    val chip = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val mediaTitle = TextStyle(color = content, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val mediaTime = small.copy(fontFeatureSettings = "tnum")

    /**
     * Glass, for every card and pill over the wallpaper (chips, cards, the widget stack, the player): a tint of the
     * scrim, a faint light falling from the top, and a hairline edge that catches it along the top. Built once per style
     * and sized to whatever they fill, so drawing them allocates nothing.
     */
    val glassSheen: Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (darkText) 0.22f else 0.06f), Color.Transparent))
    val glassEdge: Brush = if (darkText) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.4f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.06f)))
    }

    /**
     * The favorite label beside an icon of [iconSize] (an [IconSize.homeDp]): smaller next to small icons, a little
     * larger next to large ones, [favorite] at Medium. Returns one of the styles above, so rows allocate nothing.
     */
    fun favoriteFor(iconSize: Dp): TextStyle = when {
        iconSize < IconSize.MEDIUM.homeDp.dp -> favoriteSmall
        iconSize < IconSize.LARGE.homeDp.dp -> favorite
        iconSize < IconSize.XL.homeDp.dp -> favoriteLarge
        else -> favoriteXL
    }
}

val LocalLauncherStyle = staticCompositionLocalOf { LauncherStyle(darkText = false, accent = Color.White) }

/** The glass edge's width: a hairline that still shows on a busy wallpaper. */
val GlassEdgeWidth = 0.8.dp

/** Glass behind a card or pill of [shape]: the scrim at [fill], [LauncherStyle.glassSheen] and the edge. */
fun Modifier.glass(style: LauncherStyle, shape: Shape, fill: Float): Modifier = this
    .background(style.scrim.copy(alpha = fill), shape)
    .background(style.glassSheen, shape)
    .border(GlassEdgeWidth, style.glassEdge, shape)
