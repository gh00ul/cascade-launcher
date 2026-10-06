package com.gh00ul.cascade.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/** Whether the system says dark text reads better on the current wallpaper. Updates when the wallpaper changes. */
@Composable
fun rememberWallpaperSupportsDarkText(): Boolean {
    if (Build.VERSION.SDK_INT < 27) return false
    val context = LocalContext.current
    var supportsDarkText by remember { mutableStateOf(readSupportsDarkText(context)) }
    // Listens only while home is visible, so no callbacks while away (some live wallpapers report colors often). Each
    // return home registers first and then re-reads the colors (one call, which the system answers from its cache)
    // before the first frame, so a wallpaper set meanwhile is already right and a change that lands during the return
    // isn't missed.
    LifecycleStartEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) supportsDarkText = colors.supportsDarkText()
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        supportsDarkText = readSupportsDarkText(context)
        onStopOrDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return supportsDarkText
}

@RequiresApi(27)
private fun readSupportsDarkText(context: Context): Boolean = runCatching {
    WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM).supportsDarkText()
}.getOrDefault(false)

@RequiresApi(27)
private fun WallpaperColors?.supportsDarkText(): Boolean {
    if (this == null) return false
    if (Build.VERSION.SDK_INT >= 31) return colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    return primaryColor.luminance() > 0.5f
}
