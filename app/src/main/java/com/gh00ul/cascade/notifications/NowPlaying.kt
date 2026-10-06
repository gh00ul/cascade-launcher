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
import androidx.compose.ui.graphics.asAndroidBitmap
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
    fun play() = command { controls.play() }
    fun pause() = command { controls.pause() }
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

    /** Kept current by the callbacks: each getter is a binder call, and metadata unparcels the art. */
    private class Tracked(val controller: MediaController) {
        lateinit var callback: MediaController.Callback
        var playback: PlaybackState? = null
        /** [SystemClock.elapsedRealtime] when [playback] last changed: dates a pause. */
        var playbackSince = 0L
        var metadata: MediaMetadata? = null
        var thumb: Bitmap? = null

        fun updateMetadata(m: MediaMetadata?) {
            val bitmap = m?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            thumb = bitmap?.let { runCatching { thumbnail(it) }.getOrNull() }
            // Keep only the text: the full-size art (and the parcel behind it) would otherwise live as long as the session.
            metadata = m?.let { src ->
                MediaMetadata.Builder().apply {
                    for (k in TEXT_KEYS) src.getString(k)?.let { putString(k, it) }
                    putLong(MediaMetadata.METADATA_KEY_DURATION, src.getLong(MediaMetadata.METADATA_KEY_DURATION))
                }.build()
            }
        }
    }

    private class Snapshot(
        val token: MediaSession.Token,
        val controller: MediaController,
        val playback: PlaybackState?,
        val metadata: MediaMetadata?,
        val thumb: Bitmap?,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var ownPackage: String? = null
    /** Keyed by token: getActiveSessions hands out new MediaController objects for the same session every time. */
    private val sessions = LinkedHashMap<MediaSession.Token, Tracked>()
    // Keyed by package: an app that loses its session with its process and recreates it paused is the same player.
    private val pausedSince = HashMap<String, Long>()
    private val hidden = HashSet<String>()
    private var shown: MediaSession.Token? = null
    private var artKey: String? = null
    private var art: ImageBitmap? = null
    private var artSeed: Int? = null

    private val recheck = Runnable { publish() }
    private var clearPending = false
    private val clear = Runnable {
        clearPending = false
        removePlayer()
        // A shown session held only through the grace gives way to the next one in line.
        publish(force = true)
    }

    private val PLAYING_STATES = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
    )
    private val ACTIVE_STATES = PLAYING_STATES + setOf(
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )
    /** The metadata [toState] reads, besides the duration. */
    private val TEXT_KEYS = listOf(
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    internal fun start(context: Context) {
        val sessionManager = context.getSystemService(MediaSessionManager::class.java) ?: return
        val listener = ComponentName(context, NotificationListener::class.java)
        ownPackage = context.packageName
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            manager = sessionManager
            track(sessionManager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // Notification access was revoked between connecting and now.
        }
    }

    internal fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        track(emptyList())
        // Plays while disconnected can't be seen: start over, and register() dates a still-paused session on reconnect.
        pausedSince.clear()
        hidden.clear()
        shown = null
        publish(force = true, immediate = true)
    }

    /** Re-check everything (Handler delays don't advance in deep sleep). Call on the main thread when home shows. */
    fun refresh() = publish(force = true, immediate = true)

    /** Hide the shown (not playing) player until its app plays again. */
    fun hide() {
        shown?.let { sessions[it] }?.let { hidden += it.controller.packageName }
        publish(force = true, immediate = true)
    }

    private fun track(list: List<MediaController>) {
        val incoming = LinkedHashMap<MediaSession.Token, MediaController>()
        for (controller in list) incoming.putIfAbsent(controller.sessionToken, controller)
        for ((token, tracked) in sessions.entries.toList()) {
            if (token !in incoming) {
                tracked.controller.unregisterCallback(tracked.callback)
                sessions.remove(token)
            }
        }
        // Rebuild in MediaSessionManager's priority order, keeping existing registrations.
        val ordered = LinkedHashMap<MediaSession.Token, Tracked>()
        for ((token, controller) in incoming) {
            ordered[token] = sessions[token] ?: register(token, controller)
        }
        sessions.clear()
        sessions.putAll(ordered)
        publish()
    }

    private fun register(token: MediaSession.Token, controller: MediaController): Tracked {
        val tracked = Tracked(controller)
        tracked.callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val stateChanged = tracked.playback?.state != state?.state
                tracked.playback = state
                tracked.playbackSince = SystemClock.elapsedRealtime()
                // Chatty apps re-post their position every second; with nobody watching, only the state code matters
                // to the pause and hide bookkeeping.
                if (stateChanged || _state.subscriptionCount.value > 0) publish()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                tracked.updateMetadata(metadata)
                // The cover can change with the same text and size (radio, placeholder art): read it again.
                if (token == shown) artKey = null
                publish()
            }

            override fun onSessionDestroyed() {
                sessions.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
                publish()
            }
        }
        controller.registerCallback(tracked.callback, handler)
        // Callbacks only report changes; read the current values once.
        tracked.playback = controller.playbackState
        // Already paused when first seen (start, reconnect): date it from the session's own last update.
        val now = SystemClock.elapsedRealtime()
        tracked.playbackSince = tracked.playback?.lastPositionUpdateTime?.takeIf { it in 1..now } ?: now
        tracked.updateMetadata(controller.metadata)
        return tracked
    }

    /**
     * Recomputes the shown session. The pause and hide bookkeeping runs on every call, so plays and pauses while home
     * isn't showing still count; the rest is skipped while nothing observes the state (launcher stopped or screen off),
     * and so is the 30-minute recheck, which [refresh] on resume reschedules as it catches up. [immediate] removes the
     * player without the between-tracks grace, unless the shown session is only between tracks.
     */
    private fun publish(force: Boolean = false, immediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Reads only cached state, so it's cheap. An app that plays clears its timer and hide; the others start resting.
        val playing = HashSet<String>()
        val resting = HashMap<String, Long>()
        for (tracked in sessions.values) {
            val playback = tracked.playback ?: continue
            val owner = tracked.controller.packageName
            if (playback.state in PLAYING_STATES) {
                playing += owner
            } else if (playback.state in ACTIVE_STATES) {
                resting[owner] = max(tracked.playbackSince, resting[owner] ?: 0L)
            }
        }
        pausedSince -= playing
        hidden -= playing
        for ((owner, since) in resting) if (owner !in playing) pausedSince.getOrPut(owner) { since }
        handler.removeCallbacks(recheck)
        if (!force && _state.subscriptionCount.value == 0) return
        var wake = Long.MAX_VALUE
        val eligible = sessions.mapNotNull { (token, tracked) ->
            val playback = tracked.playback
            val metadata = tracked.metadata
            val s = playback?.state ?: return@mapNotNull null
            // Nothing to show without a title: let the next session in line have the player, but only once the shown
            // one's grace has run out.
            if (s !in ACTIVE_STATES || titleOf(metadata) == null && (token != shown || _state.value == null)) {
                return@mapNotNull null
            }
            if (s in PLAYING_STATES) return@mapNotNull Snapshot(token, tracked.controller, playback, metadata, tracked.thumb)
            // Paused, or stuck buffering: retire after 30 minutes, and allow hiding.
            val owner = tracked.controller.packageName
            if (owner in hidden) return@mapNotNull null
            // Unset only while another session of the same app plays.
            val left = (pausedSince[owner] ?: now) + STALE_PAUSE_MS - now
            if (left <= 0) return@mapNotNull null
            wake = minOf(wake, left)
            Snapshot(token, tracked.controller, playback, metadata, tracked.thumb)
        }
        if (wake != Long.MAX_VALUE) handler.postDelayed(recheck, wake + 1_000)
        val chosen = eligible.firstOrNull { it.playback?.state in PLAYING_STATES }
            ?: eligible.firstOrNull { it.playback?.state != PlaybackState.STATE_PAUSED }
            ?: eligible.firstOrNull()
        val previous = shown
        shown = chosen?.token
        val next = chosen?.let(::toState)
        val prev = _state.value
        if (next == null) {
            if (prev == null) return
            if (immediate && !betweenTracks(previous)) {
                handler.removeCallbacks(clear)
                clearPending = false
                removePlayer()
            } else {
                // The player stays up through the grace, so hide() still acts on it.
                shown = previous
                if (!clearPending) {
                    clearPending = true
                    handler.postDelayed(clear, REMOVE_GRACE_MS)
                }
            }
            return
        }
        handler.removeCallbacks(clear)
        clearPending = false
        // Chatty apps re-post their state every second; emit only what a viewer could notice.
        if (prev != null && prev.sameExceptPosition(next) && abs(prev.positionAt(now) - next.positionAt(now)) < 750) return
        _state.value = next
    }

    /** No player. Its art goes too: the cover of a session that may be gone shouldn't outlive it here. */
    private fun removePlayer() {
        _state.value = null
        artKey = null
        art = null
        artSeed = null
    }

    /** The shown session is still there and active, and has only dropped its title for a moment. */
    private fun betweenTracks(token: MediaSession.Token?): Boolean {
        val tracked = token?.let { sessions[it] } ?: return false
        return tracked.controller.packageName !in hidden &&
            tracked.playback?.state in ACTIVE_STATES &&
            titleOf(tracked.metadata) == null
    }

    private fun titleOf(metadata: MediaMetadata?): String? =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }

    private fun toState(snapshot: Snapshot): NowPlayingState? {
        val controller = snapshot.controller
        val metadata = snapshot.metadata ?: return null
        val title = titleOf(metadata) ?: return null
        val subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val pkg = controller.packageName.let { if (it == ownPackage) debugAlias ?: it else it }
        // Only what is shown: an album arriving a moment after the title must not replay the track change.
        val trackKey = "$pkg|$title|$subtitle"
        val thumb = snapshot.thumb
        // Include the cover's size: many apps post the title first and the cover a moment later.
        val key = "$trackKey|${thumb?.width}x${thumb?.height}"
        if (key != artKey) {
            artKey = key
            // Re-posted metadata usually carries the same cover; keep the old image so it doesn't crossfade to itself.
            val same = thumb != null && runCatching { art?.asAndroidBitmap()?.sameAs(thumb) == true }.getOrDefault(false)
            if (!same) {
                art = thumb?.asImageBitmap()
                artSeed = thumb?.let { runCatching { seedColor(it) }.getOrNull() }
                // Start the GPU upload now rather than in the frame that first draws the new cover.
                thumb?.prepareToDraw()
            }
        }
        val playback = snapshot.playback
        val actions = playback?.actions ?: 0L
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val status = when (playback?.state) {
            in PLAYING_STATES -> PlaybackStatus.PLAYING
            PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED
            else -> PlaybackStatus.BUFFERING
        }
        val now = SystemClock.elapsedRealtime()
        return NowPlayingState(
            sessionId = snapshot.token.hashCode(),
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
            // Negative while rewinding; 0 (or garbage) while playing means normal speed.
            speed = playback?.playbackSpeed?.takeIf { it != 0f && it.isFinite() } ?: 1f,
            canPlayPause = actions == 0L ||
                actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L,
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSeek = duration > 0 && actions and PlaybackState.ACTION_SEEK_TO != 0L,
            sessionActivity = controller.sessionActivity,
            controls = controller.transportControls,
        )
    }

    /** At most 192 px, in software (seedColor and sameAs read its pixels). Never recycles [bitmap]: the metadata owns it. */
    internal fun thumbnail(bitmap: Bitmap): Bitmap {
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap else bitmap
        val scale = 192f / max(source.width, source.height)
        if (scale >= 1f) return source
        val thumb = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        // A full-size software copy of hardware art was only a step to the thumbnail: free it now, not at some later
        // GC. createScaledBitmap can hand back its source, which is then the thumbnail.
        if (source !== bitmap && source !== thumb) source.recycle()
        return thumb
    }
}
