package com.gh00ul.cascade.notifications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

data class NowPlayingState(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] when [positionMs] was measured. */
    val positionUpdatedAt: Long,
    val speed: Float,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val sessionActivity: PendingIntent?,
    private val controls: MediaController.TransportControls,
) {
    fun positionAt(now: Long): Long {
        val position = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }

    fun playPause() = if (isPlaying) controls.pause() else controls.play()
    fun next() = controls.skipToNext()
    fun previous() = controls.skipToPrevious()
}

/**
 * The media session worth showing on the home screen: whatever is playing, else the most recent paused one.
 * Reading media sessions rides on notification access, so [NotificationListener] starts and stops this.
 */
object NowPlaying {
    private val _state = MutableStateFlow<NowPlayingState?>(null)
    val state: StateFlow<NowPlayingState?> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var artKey: String? = null
    private var art: ImageBitmap? = null

    private val ACTIVE_STATES = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    internal fun start(context: Context) {
        val sessions = context.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(context, NotificationListener::class.java)
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            manager = sessions
            track(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // Notification access was revoked between connecting and now.
        }
    }

    internal fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        track(emptyList())
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    private fun publish() {
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null && (it.playbackState?.state ?: -1) in ACTIVE_STATES }
        _state.value = controller?.let(::toState)
    }

    private fun toState(controller: MediaController): NowPlayingState? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val key = "${controller.packageName}|$title|$subtitle|${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)}"
        if (key != artKey) {
            artKey = key
            art = (metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.let(::thumbnail)
        }
        val playback = controller.playbackState
        val actions = playback?.actions ?: 0L
        return NowPlayingState(
            packageName = controller.packageName,
            title = title,
            subtitle = subtitle,
            art = art,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = playback?.position ?: 0L,
            positionUpdatedAt = playback?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = playback?.playbackSpeed ?: 1f,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            sessionActivity = controller.sessionActivity,
            controls = controller.transportControls,
        )
    }

    private fun thumbnail(bitmap: Bitmap): ImageBitmap {
        val scale = 192f / max(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        return small.asImageBitmap()
    }
}
