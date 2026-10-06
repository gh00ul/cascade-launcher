package com.gh00ul.cascade.notifications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class PlaybackStatus { PLAYING, BUFFERING, PAUSED }

data class NowPlayingState(
    /** Identifies the session, so switching between players can animate. */
    val sessionId: Int,
    val packageName: String,
    /** Changes with the track; drives the track-change animation. */
    val trackKey: String,
    val title: String,
    val subtitle: String,
    val art: ImageBitmap?,
    /** Representative colour of the art, or null for no art / grey art. */
    val artSeed: Int?,
    val status: PlaybackStatus,
    val durationMs: Long,
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] when [positionMs] was measured. */
    val positionUpdatedAt: Long,
    val speed: Float,
    val canPlayPause: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val canSeek: Boolean,
    val sessionActivity: PendingIntent?,
    private val controls: MediaController.TransportControls,
) {
    val isPlaying get() = status == PlaybackStatus.PLAYING
    val isBuffering get() = status == PlaybackStatus.BUFFERING
    val isPaused get() = status == PlaybackStatus.PAUSED
    val hasDuration get() = durationMs > 0
    /** Streams have no duration and can't seek; a podcast that is still loading never counts. */
    val isLive get() = !hasDuration && !canSeek && isPlaying

    fun positionAt(now: Long): Long {
        val position = if (isPlaying) positionMs + ((now - positionUpdatedAt) * speed).toLong() else positionMs
        return if (hasDuration) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }

    // A dead or misbehaving session throws from the system server; never let that crash the launcher.
    fun playPause() = command { if (isPaused) controls.play() else controls.pause() }
    fun next() = command { controls.skipToNext() }
    fun previous() = command { controls.skipToPrevious() }
    fun seekTo(ms: Long) = command { controls.seekTo(if (hasDuration) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)) }

    private inline fun command(block: () -> Unit) {
        runCatching(block)
    }

    internal fun sameExceptPosition(other: NowPlayingState) =
        copy(positionMs = 0, positionUpdatedAt = 0) == other.copy(positionMs = 0, positionUpdatedAt = 0)
}

/**
 * The one media session worth showing on the home screen: playing first, then buffering, then the most recent paused
 * one. Paused sessions retire after 30 minutes or when hidden. Reading media sessions rides on notification access,
 * so [NotificationListener] starts and stops this.
 */
object NowPlaying {
    private const val STALE_PAUSE_MS = 30 * 60 * 1000L
    /** Apps briefly drop their metadata between tracks; wait this long before removing the player. */
    private const val REMOVE_GRACE_MS = 800L

    private val _state = MutableStateFlow<NowPlayingState?>(null)
    val state: StateFlow<NowPlayingState?> = _state.asStateFlow()

    /** Debug builds only: report Cascade's own fake session as if it came from this package. */
    internal var debugAlias: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var ownPackage: String? = null
    private val callbacks = LinkedHashMap<MediaController, MediaController.Callback>()
    private val pausedSince = HashMap<MediaSession.Token, Long>()
    private val hidden = HashSet<MediaSession.Token>()
    private var shown: MediaSession.Token? = null
    private var artKey: String? = null
    private var art: ImageBitmap? = null
    private var artSeed: Int? = null

    private val recheck = Runnable { publish() }
    private var clearPending = false
    private val clear = Runnable {
        clearPending = false
        _state.value = null
    }

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

    internal fun start(context: Context) {
        val sessions = context.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(context, NotificationListener::class.java)
        ownPackage = context.packageName
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
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
        pausedSince.clear()
        hidden.clear()
        shown = null
    }

    /** Re-check the 30-minute rule; Handler delays don't advance in deep sleep. Call on the main thread. */
    fun refresh() = publish()

    /** Hide the shown (paused) session until it plays again. */
    fun hide() {
        shown?.let { hidden += it }
        publish()
    }

    private fun track(list: List<MediaController>) {
        val keep = list.toSet()
        callbacks.keys.filter { it !in keep }.forEach { controller -> callbacks.remove(controller)?.let { controller.unregisterCallback(it) } }
        for (controller in list) {
            if (controller in callbacks) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    callbacks.remove(controller)?.let { controller.unregisterCallback(it) }
                    publish()
                }
            }
            controller.registerCallback(callback, handler)
            callbacks[controller] = callback
        }
        // Keep MediaSessionManager's priority order.
        val ordered = list.filter { it in callbacks }.associateWith { callbacks.getValue(it) }
        callbacks.clear()
        callbacks.putAll(ordered)
        publish()
    }

    private fun publish() {
        handler.removeCallbacks(recheck)
        val now = SystemClock.elapsedRealtime()
        val tokens = callbacks.keys.mapTo(HashSet()) { it.sessionToken }
        pausedSince.keys.retainAll(tokens)
        hidden.retainAll(tokens)
        var wake = Long.MAX_VALUE
        val eligible = callbacks.keys.filter { c ->
            val s = c.playbackState?.state ?: return@filter false
            if (c.metadata == null || s !in ACTIVE_STATES) return@filter false
            val token = c.sessionToken
            if (s != PlaybackState.STATE_PAUSED) {
                pausedSince -= token
                hidden -= token
                return@filter true
            }
            if (token in hidden) return@filter false
            val left = pausedSince.getOrPut(token) { now } + STALE_PAUSE_MS - now
            if (left > 0) wake = minOf(wake, left)
            left > 0
        }
        if (wake != Long.MAX_VALUE) handler.postDelayed(recheck, wake + 1_000)
        val chosen = eligible.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: eligible.firstOrNull { it.playbackState?.state != PlaybackState.STATE_PAUSED }
            ?: eligible.firstOrNull()
        shown = chosen?.sessionToken
        val next = chosen?.let(::toState)
        val prev = _state.value
        if (next == null) {
            if (prev != null && !clearPending) {
                clearPending = true
                handler.postDelayed(clear, REMOVE_GRACE_MS)
            }
            return
        }
        handler.removeCallbacks(clear)
        clearPending = false
        // Chatty apps re-post their state every second; emit only what a viewer could notice.
        if (prev != null && prev.sameExceptPosition(next) && abs(prev.positionAt(now) - next.positionAt(now)) < 750) return
        _state.value = next
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
        val pkg = controller.packageName.let { if (it == ownPackage) debugAlias ?: it else it }
        val trackKey = "$pkg|$title|$subtitle|${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)}"
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        // Include the bitmap's size: many apps post the title first and the cover a moment later.
        val key = "$trackKey|${bitmap?.width}x${bitmap?.height}"
        if (key != artKey) {
            artKey = key
            val thumb = bitmap?.let { runCatching { thumbnail(it) }.getOrNull() }
            art = thumb?.asImageBitmap()
            artSeed = thumb?.let { runCatching { seedColor(it) }.getOrNull() }
        }
        val playback = controller.playbackState
        val actions = playback?.actions ?: 0L
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val status = when (playback?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> PlaybackStatus.PLAYING
            PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
            else -> PlaybackStatus.BUFFERING
        }
        val now = SystemClock.elapsedRealtime()
        return NowPlayingState(
            sessionId = controller.sessionToken.hashCode(),
            packageName = pkg,
            trackKey = trackKey,
            title = title,
            subtitle = subtitle,
            art = art,
            artSeed = artSeed,
            status = status,
            durationMs = duration,
            positionMs = playback?.position ?: 0L,
            positionUpdatedAt = playback?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
            speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canPlayPause = actions == 0L ||
                actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSeek = duration > 0 && actions and PlaybackState.ACTION_SEEK_TO != 0L,
            sessionActivity = controller.sessionActivity,
            controls = controller.transportControls,
        )
    }

    private fun thumbnail(bitmap: Bitmap): Bitmap {
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap else bitmap
        val scale = 192f / max(source.width, source.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else source
    }
}
