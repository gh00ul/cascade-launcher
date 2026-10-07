package com.gh00ul.cascade.notifications

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale

/**
 * Representative colour of album art, or null when it's essentially grey. Buckets hues in 10° steps, picks the
 * strongest 30° window, and averages it. Reads 576 pixels, well under a millisecond.
 */
internal fun seedColor(art: Bitmap): Int? {
    val source = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) ?: return null else art
    val small = source.scale(24, 24)
    val pixels = IntArray(576)
    try {
        small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    } finally {
        // Free the copies now rather than at some later GC, but never [art], which the player draws. createScaledBitmap
        // hands back its source when that is already 24 px.
        if (small !== source) small.recycle()
        if (source !== art) source.recycle()
    }
    val count = IntArray(36)
    val saturation = FloatArray(36)
    val red = IntArray(36)
    val green = IntArray(36)
    val blue = IntArray(36)
    val hsv = FloatArray(3)
    var opaque = 0
    for (c in pixels) {
        if (Color.alpha(c) < 128) continue
        opaque++
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.18f || hsv[2] < 0.16f) continue // greys and near-black carry no hue
        val i = (hsv[0] / 10f).toInt().coerceIn(0, 35)
        count[i]++
        saturation[i] += hsv[1]
        red[i] += Color.red(c)
        green[i] += Color.green(c)
        blue[i] += Color.blue(c)
    }
    if (opaque == 0) return null
    var best = -1
    var bestScore = 0f
    for (i in 0 until 36) {
        var n = 0
        var s = 0f
        for (j in -1..1) {
            val k = (i + j).mod(36)
            n += count[k]
            s += saturation[k]
        }
        if (n > 0 && n * (0.6f + s / n) > bestScore) {
            bestScore = n * (0.6f + s / n)
            best = i
        }
    }
    if (best < 0) return null
    var n = 0
    var r = 0
    var g = 0
    var b = 0
    for (j in -1..1) {
        val k = (best + j).mod(36)
        n += count[k]
        r += red[k]
        g += green[k]
        b += blue[k]
    }
    if (n < opaque * 0.06f) return null // a speck of colour on grey art stays neutral
    return Color.rgb(r / n, g / n, b / n)
}
