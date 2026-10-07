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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 *
 * Home runs under a lifecycle the test moves by hand, as in ClockHeaderLifecycleTest. On a phone, nothing recomposes
 * while home is stopped; the test's composition never stops, so leaving home is timed to land in the last frame of the
 * wait, where the frame that recomposes for it comes only after the wait has run out. There, as on a phone, only the
 * wait itself can see that home was left.
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
    private val owner = HandLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }

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
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { LauncherTheme { LauncherScreen(emptyFlow()) } }
        }
        compose.waitForIdle()
    }

    /**
     * Taps Resume and returns when the app is due to open. The tap's change composes on the next frame, which starts
     * the wait. Tapped through its semantics action, so no touch moves the clock or starts a press animation.
     */
    private fun tapResume(): Long {
        compose.onNodeWithContentDescription("Resume $music", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        val tapped = compose.mainClock.currentTime
        compose.waitForIdle()
        return tapped + FRAME_MS + WAIT_MS
    }

    /** Runs the clock to [time] exactly (not rounded up to a frame) and checks no frame is on its way there. */
    private fun runTo(time: Long) {
        compose.mainClock.advanceTimeBy(time - compose.mainClock.currentTime, ignoreFrameDuration = true)
        compose.waitForIdle()
        check(compose.mainClock.currentTime == time) { "Home was still busy at $time" }
    }

    private fun opened() = RecordingLauncherApps.opened.map { it.packageName }

    /**
     * Nothing plays within 4 s of Resume, with home still in front: the app opens then, within the half frame either
     * side of when it's due. That places the end of the wait inside the window the next test leaves home in.
     */
    @Test fun anAppThatIgnoresResumeIsOpened() {
        val due = tapResume()
        runTo(due - FRAME_MS / 2)
        assertEquals(emptyList<String>(), opened())
        compose.mainClock.advanceTimeBy(FRAME_MS - 1, ignoreFrameDuration = true)
        assertEquals(listOf(music), opened())
    }

    /**
     * Home left half a frame before the app is due (another app opened): it never opens over what's in front. Leaving
     * asks for a frame, which lands a whole frame later, after the wait has run out; until then nothing recomposes, as
     * on a phone while home is stopped. So recomposing can't be what stops it.
     */
    @Test fun leavingHomeCancelsTheFallback() {
        val due = tapResume()
        runTo(due - FRAME_MS / 2)
        owner.registry.currentState = Lifecycle.State.CREATED
        compose.mainClock.advanceTimeBy(FRAME_MS - 1, ignoreFrameDuration = true)
        assertEquals("Opened after home was left", emptyList<String>(), opened())

        // Nor later, once home has recomposed.
        compose.mainClock.advanceTimeBy(WAIT_MS)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), opened())
    }

    /** A lifecycle the test moves by hand, standing in for the activity's. */
    private class HandLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        /** The compose test clock's frame. */
        const val FRAME_MS = 16L
        /** How long listen mode waits for the app to play before opening it. */
        const val WAIT_MS = 4_000L
    }
}
