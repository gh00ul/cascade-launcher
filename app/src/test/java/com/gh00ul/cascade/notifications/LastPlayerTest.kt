package com.gh00ul.cascade.notifications

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import com.gh00ul.cascade.ui.home.isListeningDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Listen mode's memory of the last player, and which outputs count as headphones. */
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
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(1, writes)
    }

    @Test fun aMalformedRecordIsDropped() {
        assertNull(LastPlayer.parse("not json"))
        assertNull(LastPlayer.parse("""{"package":"","title":"x"}"""))
        val p = LastPlayed("com.example.radio", "", "")
        assertEquals(p, LastPlayer.parse(LastPlayer.json(p)))
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
