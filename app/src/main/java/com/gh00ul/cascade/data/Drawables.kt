package com.gh00ul.cascade.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Draws the drawable centered in a square bitmap, keeping its aspect ratio. */
fun Drawable.renderTo(size: Int, filter: ColorFilter? = null): Bitmap {
    val bitmap = createBitmap(size, size)
    val w = intrinsicWidth
    val h = intrinsicHeight
    val (dw, dh) = if (w > 0 && h > 0 && w != h) {
        val scale = size.toFloat() / max(w, h)
        (w * scale).roundToInt() to (h * scale).roundToInt()
    } else size to size
    val left = (size - dw) / 2
    val top = (size - dh) / 2
    val drawable = mutate()
    drawable.setBounds(left, top, left + dw, top + dh)
    drawable.colorFilter = filter
    drawable.draw(Canvas(bitmap))
    return bitmap
}

/**
 * A finished bitmap moved to graphics memory (Android 9+, as Launcher3 does): it's drawn without a texture upload
 * the first time it scrolls into view, and no second copy stays in the app's memory. A hardware bitmap can't be drawn
 * into or read back, so call this only after all software drawing; the original is recycled. Where the copy fails
 * (or isn't supported, as in tests), the original stays.
 */
fun Bitmap.toHardware(): Bitmap {
    if (Build.VERSION.SDK_INT < 28 || config == Bitmap.Config.HARDWARE) return this
    val copy = runCatching { copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return this
    recycle()
    return copy
}

/** [Bitmap.toHardware] for a bitmap already wrapped for Compose. */
fun ImageBitmap.toHardware(): ImageBitmap {
    val bitmap = asAndroidBitmap()
    val copy = bitmap.toHardware()
    return if (copy === bitmap) this else copy.asImageBitmap()
}

/**
 * An app icon ready to draw. A [isGlyph] icon is a white silhouette (Android 13 themed icon) that the UI
 * tints with its current text color; its profile [badge], if any, is drawn over it untinted.
 */
@Immutable
class IconImage(val bitmap: ImageBitmap, val isGlyph: Boolean, val badge: ImageBitmap? = null)

/**
 * The app's themed-icon layer when it ships one (Android 13+) as a white glyph,
 * otherwise a grayscale copy of its regular icon.
 */
fun Drawable.renderMonochrome(size: Int): IconImage {
    if (Build.VERSION.SDK_INT >= 33 && this is AdaptiveIconDrawable) {
        monochrome?.mutate()?.let { glyph ->
            val bitmap = createBitmap(size, size)
            // Adaptive layers are 108dp with the visible icon in the middle 72dp.
            val bleed = size / 4
            glyph.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            glyph.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            glyph.draw(Canvas(bitmap))
            return IconImage(bitmap.asImageBitmap(), isGlyph = true)
        }
    }
    val gray = renderTo(size, ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }))
    return IconImage(gray.asImageBitmap(), isGlyph = false)
}
