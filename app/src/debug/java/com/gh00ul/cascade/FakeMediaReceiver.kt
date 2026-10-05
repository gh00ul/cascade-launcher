package com.gh00ul.cascade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Debug builds only. Fakes a playing media session so the home-screen music card can be tried without a
 * music app:
 *   adb shell am broadcast -n com.gh00ul.cascade/.FakeMediaReceiver --es cmd start
 *   adb shell am broadcast -n com.gh00ul.cascade/.FakeMediaReceiver --es cmd stop
 */
class FakeMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "start" -> start(context.applicationContext)
            "stop" -> stop()
        }
    }

    private companion object {
        var session: MediaSession? = null
        var playing = true
        var position = 0L
        var track = 0
        val tracks = listOf("Midnight Drive" to "Neon Coast", "Glass Rivers" to "Low Tide", "Paper Moons" to "Neon Coast")

        fun start(context: Context) {
            val s = session ?: MediaSession(context, "fake").also { session = it }
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play(true)
                override fun onPause() = play(false)
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            s.isActive = true
            publish()
        }

        fun stop() {
            session?.release()
            session = null
        }

        fun play(on: Boolean) {
            playing = on
            publish()
        }

        fun skip(delta: Int) {
            track = (track + delta).mod(tracks.size)
            position = 0
            publish()
        }

        fun publish() {
            val s = session ?: return
            val (title, artist) = tracks[track]
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 214_000)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art(track))
                    .build(),
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        position + 71_000,
                        1f,
                        SystemClock.elapsedRealtime(),
                    )
                    .build(),
            )
        }

        fun art(seed: Int): Bitmap {
            val colors = listOf(0xFF7B5CFF.toInt() to 0xFFFF6B9A.toInt(), 0xFF00B4D8.toInt() to 0xFF0077B6.toInt(), 0xFFFFB703.toInt() to 0xFFFB8500.toInt())
            val (a, b) = colors[seed % colors.size]
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 256f, 256f, Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f, a, b, Shader.TileMode.CLAMP) })
            return bitmap
        }
    }
}
