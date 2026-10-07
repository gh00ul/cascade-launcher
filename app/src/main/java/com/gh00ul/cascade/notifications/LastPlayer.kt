package com.gh00ul.cascade.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The app that last played, and the track it was on. */
data class LastPlayed(val packageName: String, val title: String, val subtitle: String)

/**
 * Which app last played, for listen mode's "Resume" row: kept on disk, so it's known after a restart and when the
 * app's session is long gone. [NowPlaying] reports every track that plays, home visible or not; a write only happens
 * when the app or track changes.
 */
object LastPlayer {
    private val _state = MutableStateFlow<LastPlayed?>(null)
    val state: StateFlow<LastPlayed?> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Reads the stored player. Call once, from Application.onCreate: it's one small file. */
    fun init(context: Context) {
        val sp = context.getSharedPreferences("last_player", Context.MODE_PRIVATE)
        prefs = sp
        _state.value = sp.getString(KEY, null)?.let(::parse)
    }

    /** [packageName] is playing [title]. Called on the main thread by [NowPlaying]. */
    internal fun played(packageName: String, title: String, subtitle: String) {
        val now = LastPlayed(packageName, title, subtitle)
        if (_state.value == now) return
        _state.value = now
        prefs?.edit { putString(KEY, json(now)) }
    }

    /**
     * Starts [packageName] playing again, the way Android's own resume controls do: connect to the app's media browser
     * asking for its recent media, and press play on the session it hands back. Apps without a browser get a play key
     * instead, which Android sends to the app that played last. Call on the main thread.
     */
    fun resume(context: Context, packageName: String) {
        val app = context.applicationContext
        val service = runCatching {
            app.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0)
                .firstOrNull()?.serviceInfo
        }.getOrNull()
        if (service == null) {
            pressPlay(app)
            return
        }
        var browser: MediaBrowser? = null
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val connected = browser ?: return
                val played = runCatching {
                    MediaController(app, connected.sessionToken).transportControls.apply {
                        prepare()
                        play()
                    }
                }.isSuccess
                if (!played) pressPlay(app)
                // Long enough for the app to start its own playback service; staying bound would keep it alive.
                handler.postDelayed({ connected.disconnect() }, DISCONNECT_MS)
            }

            override fun onConnectionFailed() = pressPlay(app)
        }
        val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
        val created = runCatching { MediaBrowser(app, ComponentName(service.packageName, service.name), callback, hints) }.getOrNull()
        browser = created
        if (created == null || runCatching { created.connect() }.isFailure) {
            pressPlay(app)
            return
        }
        // A browser that neither connects nor fails would stay bound, keeping the app's service alive, for as long as
        // Cascade runs. Once connected, onConnected's own disconnect applies; disconnecting twice is safe. No play key
        // here: home opens the app when nothing plays within a few seconds.
        handler.postDelayed({ if (!created.isConnected) created.disconnect() }, CONNECT_TIMEOUT_MS)
    }

    /** A play key, as headphones' button would send it: Android routes it to the app that played last. */
    private fun pressPlay(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }

    private const val KEY = "last"
    private const val DISCONNECT_MS = 5_000L
    /** Far longer than an app takes to start its browser service cold. */
    private const val CONNECT_TIMEOUT_MS = 10_000L

    internal fun json(p: LastPlayed): String =
        JSONObject().put("package", p.packageName).put("title", p.title).put("subtitle", p.subtitle).toString()

    internal fun parse(json: String): LastPlayed? = runCatching {
        val obj = JSONObject(json)
        LastPlayed(obj.getString("package"), obj.optString("title"), obj.optString("subtitle"))
            .takeIf { it.packageName.isNotBlank() }
    }.getOrNull()
}
