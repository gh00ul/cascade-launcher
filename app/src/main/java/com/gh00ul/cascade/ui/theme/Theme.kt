package com.gh00ul.cascade.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material You colors from the wallpaper on Android 12+. The home screen is always dark. */
@Composable
fun LauncherTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Text drawn straight over the wallpaper, with a soft shadow so it stays readable on light images. */
object LauncherText {
    private val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 8f)

    val clock = TextStyle(color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, shadow = shadow)
    val date = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val favorite = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium, shadow = shadow)
    val app = TextStyle(color = Color.White, fontSize = 20.sp, shadow = shadow)
    val small = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, shadow = shadow)
    val section = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, shadow = shadow)
    val letter = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)
}
