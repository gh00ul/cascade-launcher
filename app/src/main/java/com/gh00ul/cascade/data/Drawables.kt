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
import kotlin.math.max
import kotlin.math.roundToInt

/** Draws the drawable centered in a square bitmap, keeping its aspect ratio. */
fun Drawable.renderTo(size: Int, filter: ColorFilter? = null): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
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
 * A white glyph: the app's themed-icon layer when it ships one (Android 13+),
 * otherwise a grayscale copy of its regular icon.
 */
fun Drawable.renderMonochrome(size: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= 33 && this is AdaptiveIconDrawable) {
        monochrome?.mutate()?.let { glyph ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            // Adaptive layers are 108dp with the visible icon in the middle 72dp.
            val bleed = size / 4
            glyph.setBounds(-bleed, -bleed, size + bleed, size + bleed)
            glyph.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            glyph.draw(Canvas(bitmap))
            return bitmap
        }
    }
    return renderTo(size, ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }))
}
