package com.gh00ul.cascade.util

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A live or timer chip's tap: what the notification opens, or the app that posted it when that's missing or dead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenNotifiedTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val maps = "com.example.maps"

    @Before
    fun installMaps() {
        val main = ComponentName(maps, "$maps.Main")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(main)
            addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }

    private fun opened() = shadowOf(context).nextStartedActivity

    @Test fun noTapActionOpensTheApp() {
        LauncherActions.openNotified(context, null, maps)
        assertEquals(maps, opened()?.component?.packageName)
    }

    @Test fun aCancelledTapActionOpensTheApp() {
        val dead = PendingIntent.getActivity(context, 0, Intent("com.example.UPLOADS"), PendingIntent.FLAG_IMMUTABLE)
        dead.cancel()
        LauncherActions.openNotified(context, dead, maps)
        assertEquals(maps, opened()?.component?.packageName)
    }

    @Test fun aWorkingTapActionIsWhatOpens() {
        val route = PendingIntent.getActivity(context, 1, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), PendingIntent.FLAG_IMMUTABLE)
        LauncherActions.openNotified(context, route, maps)
        assertEquals(Intent.ACTION_VIEW, opened()?.action)
    }
}
