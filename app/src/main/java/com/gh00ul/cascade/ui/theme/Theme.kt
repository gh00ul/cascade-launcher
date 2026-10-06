package com.gh00ul.cascade.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material You colors from the wallpaper on Android 12+. Menus and sheets on the home screen are always dark. */
@Composable
fun LauncherTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme(dark), content = content)
}

@Composable
fun colorScheme(dark: Boolean) = when {
    Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
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

    val clock = TextStyle(color = content, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, shadow = shadow)
    val date = TextStyle(color = content, fontSize = 17.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val favorite = TextStyle(color = content, fontSize = 26.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val app = TextStyle(color = content, fontSize = 20.sp, shadow = shadow)
    val small = TextStyle(color = content.copy(alpha = 0.8f), fontSize = 14.sp, shadow = shadow)
    val section = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, shadow = shadow)
    val letter = TextStyle(color = content, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)
    val mediaTitle = TextStyle(color = content, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val mediaTime = small.copy(fontFeatureSettings = "tnum")
}

val LocalLauncherStyle = staticCompositionLocalOf { LauncherStyle(darkText = false, accent = Color.White) }
