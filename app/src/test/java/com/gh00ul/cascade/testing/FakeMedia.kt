package com.gh00ul.cascade.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import com.gh00ul.cascade.notifications.NowPlayingState
import com.gh00ul.cascade.notifications.PlaybackStatus
import com.gh00ul.cascade.notifications.seedColor

/**
 * Player states for tests. NowPlayingState needs real TransportControls: Robolectric shadows MediaSession, so a
 * session made here hands out a working controller. Call [release] when done.
 */
class MediaFixtures(context: Context) {
    private val session = MediaSession(context, "cascade-tests")
    val controls: MediaController.TransportControls = session.controller.transportControls

    /** 192 px album art: a warm red-to-amber gradient with two circles. */
    val art: Bitmap by lazy {
        val size = 192
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFFC62828.toInt(), 0xFFFFB300.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null
            paint.color = 0x66FFF3E0
            canvas.drawCircle(size * 0.68f, size * 0.36f, size * 0.22f, paint)
            paint.color = 0xCC3E0A0A.toInt()
            canvas.drawCircle(size * 0.3f, size * 0.74f, size * 0.12f, paint)
        }
    }

    fun release() = session.release()

    fun state(
        status: PlaybackStatus = PlaybackStatus.PLAYING,
        title: String = "Midnight City Lights",
        subtitle: String = "The Neon Coast",
        withArt: Boolean = true,
        durationMs: Long = 225_000,
        positionMs: Long = 72_000,
        canSeek: Boolean = durationMs > 0,
        canSkip: Boolean = true,
        packageName: String = FakeApps.music.packageName,
        sessionId: Int = 1,
    ) = NowPlayingState(
        sessionId = sessionId,
        packageName = packageName,
        trackKey = "$packageName|$title|$subtitle",
        title = title,
        subtitle = subtitle,
        art = if (withArt) art.asImageBitmap() else null,
        artSeed = if (withArt) seedColor(art) else null,
        status = status,
        durationMs = durationMs,
        positionMs = positionMs,
        positionUpdatedAt = SystemClock.elapsedRealtime(),
        speed = 1f,
        canPlayPause = true,
        canSkipPrevious = canSkip,
        canSkipNext = canSkip,
        canSeek = canSeek,
        sessionActivity = null,
        controls = controls,
    )

    /** Playing, 1:12 into a 3:45 track, with art. */
    fun playing() = state()

    /** Paused at 2:30; shown with `resting = true` (a player that was already paused when home opened). */
    fun pausedResting() = state(PlaybackStatus.PAUSED, positionMs = 150_000)

    /** No art and no artist: the row falls back to the app label and the music-note placeholder. */
    fun noArt() = state(title = "Morning Mix", subtitle = "", withArt = false)

    /** A live stream: no duration, no seeking, no skips. */
    fun live() = state(title = "KEXP 90.3 FM", subtitle = "Live from Seattle", durationMs = 0, positionMs = 0, canSeek = false, canSkip = false)
}
