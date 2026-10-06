package com.gh00ul.cascade.ui.home

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.notifications.LastPlayer
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FakeLauncherApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowLauncherApps

/**
 * Listen mode's Resume row in the real LauncherScreen: headphones on, nothing playing, the app that played last offered.
 * An app that ignores the request is opened after a few seconds, so play is one tap away there; never once home is left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    application = LauncherApplication::class,
    qualifiers = ScreenshotTest.PHONE,
    shadows = [ListenModeTest.RecordingLauncherApps::class],
)
class ListenModeTest {
    /** LauncherApps that records the apps it's asked to open (Robolectric's opens nothing). */
    @Implements(LauncherApps::class)
    class RecordingLauncherApps : ShadowLauncherApps() {
        @Implementation
        fun startMainActivity(component: ComponentName, user: UserHandle, sourceBounds: Rect?, opts: Bundle?) {
            opened += component
        }

        companion object {
            val opened = ArrayList<ComponentName>()
        }
    }

    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication
    private val music get() = FakeLauncherApps.pkg("Music")

    @Before
    fun seed() {
        FakeLauncherApps.install(app, "Phone", "Music")
        app.repository.refresh()
        val deadline = System.currentTimeMillis() + 10_000
        while (app.repository.apps.value.size < 2) {
            check(System.currentTimeMillis() < deadline) { "The repository didn't load the fake apps" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        app.prefs.update { it.copy(favoritesSeeded = true, autoUpdateCheck = false, showBattery = false, notificationPromptDismissed = true) }
        LastPlayer.played(music, "Midnight City", "M83")
        val headphones = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
        shadowOf(app.getSystemService(AudioManager::class.java)).setOutputDevices(listOf(headphones))
        RecordingLauncherApps.opened.clear()
        compose.setContent { LauncherTheme { LauncherScreen(emptyFlow()) } }
        compose.waitForIdle()
    }

    private fun tapResume() {
        compose.onNodeWithContentDescription("Resume $music", substring = true).performClick()
        compose.waitForIdle()
    }

    /** Nothing plays within a few seconds of Resume, with home still in front: the app opens. */
    @Test fun anAppThatIgnoresResumeIsOpened() {
        tapResume()
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(listOf(music), RecordingLauncherApps.opened.map { it.packageName })
    }

    /** Home left within those seconds (another app opened): the music app never opens over it. */
    @Test fun leavingHomeCancelsTheFallback() {
        tapResume()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), RecordingLauncherApps.opened.map { it.packageName })
    }
}
