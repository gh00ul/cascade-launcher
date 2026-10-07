package com.gh00ul.cascade.util

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import androidx.compose.ui.geometry.Rect as ComposeRect

/**
 * Opening an app from a row: LauncherApps starts it from the row's bounds, rounded to pixels. Bounds that aren't numbers
 * (a layout that hasn't settled) still open the app, just without starting from them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [LaunchAppTest.RecordingLauncherApps::class])
class LaunchAppTest {
    /** LauncherApps that records each app it's asked to open and the bounds it's opened from (Robolectric's opens nothing). */
    @Implements(LauncherApps::class)
    class RecordingLauncherApps : ShadowLauncherApps() {
        @Implementation
        fun startMainActivity(component: ComponentName, user: UserHandle, sourceBounds: Rect?, opts: Bundle?) {
            started += component to sourceBounds
        }

        companion object {
            val started = ArrayList<Pair<ComponentName, Rect?>>()
        }
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val music = FakeApps.music

    @Before fun clear() {
        RecordingLauncherApps.started.clear()
    }

    /** Before the fix, rounding NaN threw IllegalArgumentException before the app was asked for: the tap crashed home. */
    @Test fun boundsThatArentNumbersStillOpenTheApp() {
        LauncherActions.launch(context, music, bounds = ComposeRect(Float.NaN, Float.NaN, Float.NaN, Float.NaN))
        // With the row's view too, as home passes it: no reveal animation, as there are no bounds to reveal from.
        LauncherActions.launch(context, music, sourceView = View(context), bounds = ComposeRect(0f, Float.NaN, 120f, 48f))
        assertEquals(listOf(music.component to null, music.component to null), RecordingLauncherApps.started)
    }

    @Test fun realBoundsAreRoundedToPixels() {
        LauncherActions.launch(context, music, bounds = ComposeRect(10.4f, 20.6f, 110.5f, 220f))
        assertEquals(listOf(music.component to Rect(10, 21, 111, 220)), RecordingLauncherApps.started)
    }
}
