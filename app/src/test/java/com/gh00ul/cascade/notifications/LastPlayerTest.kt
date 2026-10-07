package com.gh00ul.cascade.notifications

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Looper
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import com.gh00ul.cascade.ui.home.isListeningDevice
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Listen mode's memory of the last player (a bad stored record included), and which outputs count as headphones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LastPlayerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("last_player", Context.MODE_PRIVATE)

    @Test fun theLastPlayerSurvivesARestart() {
        LastPlayer.init(context)
        LastPlayer.played("com.spotify.music", "Midnight City", "M83")
        // A new process reads it back from disk.
        LastPlayer.init(context)
        assertEquals(LastPlayed("com.spotify.music", "Midnight City", "M83"), LastPlayer.state.value)
    }

    @Test fun theSameTrackAgainWritesNothing() {
        LastPlayer.init(context)
        LastPlayer.played("com.spotify.music", "Intro", "The xx")
        var writes = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Chatty apps re-post the same metadata; only a new track or app is written.
        LastPlayer.played("com.spotify.music", "Intro", "The xx")
        LastPlayer.played("com.spotify.music", "Intro", "The xx")
        LastPlayer.played("com.spotify.music", "Islands", "The xx")
        // apply() reports changes on the main thread.
        shadowOf(Looper.getMainLooper()).idle()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(1, writes)
    }

    /**
     * A stored record that isn't a string (a hand-edited or restored file) reads as none, and the next track writes over
     * it. Before the fix getString threw ClassCastException here, in Application.onCreate: home crashed on every launch.
     */
    @Test fun aRecordOfTheWrongTypeReadsAsNone() {
        LastPlayer.init(context)
        LastPlayer.played("com.spotify.music", "Intro", "The xx")
        prefs.edit().putInt("last", 7).commit()
        // A new process reads it back.
        LastPlayer.init(context)
        assertNull(LastPlayer.state.value)
        LastPlayer.played("com.example.radio", "News", "")
        assertEquals(LastPlayed("com.example.radio", "News", ""), LastPlayer.parse(prefs.getString("last", null).orEmpty()))
    }

    @Test fun aMalformedRecordIsDropped() {
        assertNull(LastPlayer.parse("not json"))
        assertNull(LastPlayer.parse("""{"package":"","title":"x"}"""))
        val p = LastPlayed("com.example.radio", "", "")
        assertEquals(p, LastPlayer.parse(LastPlayer.json(p)))
    }

    private val radio = ComponentName("com.example.radio", "com.example.radio.Browser")

    /** The radio app has a media browser service, as Resume finds it. */
    private fun installBrowser(): Application {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).apply {
            addServiceIfNotPresent(radio)
            addIntentFilterForService(radio, IntentFilter(MediaBrowserService.SERVICE_INTERFACE))
        }
        return app
    }

    private fun playKeys(app: Application) = shadowOf(app.getSystemService(AudioManager::class.java)).dispatchedMediaKeyEvents

    @Test fun aBrowserThatNeverAnswersIsLetGo() {
        val app = installBrowser()
        // Its service binds, takes the connect request and never answers: neither connected nor failed.
        shadowOf(app).setComponentNameAndServiceForBindService(radio, Binder())
        LastPlayer.resume(app, radio.packageName)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        // Bound for good otherwise, keeping the radio app alive while Cascade runs.
        looper.idleFor(Duration.ofSeconds(10))
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        // Home opens the app when nothing plays, so giving up sends no play key of its own.
        assertTrue(playKeys(app).isEmpty())
    }

    @Test fun aBrowserThatFailsGetsOnePlayKey() {
        val app = installBrowser()
        shadowOf(app).declareComponentUnbindable(radio)
        LastPlayer.resume(app, radio.packageName)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertEquals(
            listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PLAY),
            playKeys(app).map { it.action to it.keyCode },
        )
        // The timeout later finds it disconnected already: disconnecting again changes nothing, and plays nothing.
        looper.idleFor(Duration.ofSeconds(10))
        assertEquals(2, playKeys(app).size)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test fun headphonesCountButTheSpeakerAndCallLinksDont() {
        assertTrue(isListeningDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(isListeningDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(isListeningDevice(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(isListeningDevice(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(isListeningDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(isListeningDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertFalse(isListeningDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }
}
