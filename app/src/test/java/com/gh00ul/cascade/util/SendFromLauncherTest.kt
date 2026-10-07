package com.gh00ul.cascade.util

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Opening another app's screen from home (a notification, a player): home vouches for the activity start, in the way
 * each Android version takes. A dead intent is OpenNotifiedTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SendFromLauncherTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** A notification's tap action. */
    private val route = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), PendingIntent.FLAG_IMMUTABLE)

    /** The options the activity was started with, as Robolectric records the send. */
    private fun sentOptions(): Bundle = requireNotNull(shadowOf(context).nextStartedActivityForResult?.options) { "Nothing was started" }

    /**
     * Runs [block] with Build.VERSION.SDK_INT reading [level], on Android 16's framework: only the version check is under
     * test, and that framework takes every mode, so these run on the one SDK every other test uses.
     */
    private fun <T> withSdkInt(level: Int, block: () -> T): T {
        val actual = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", level)
        try {
            return block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", actual)
        }
    }

    /**
     * Android 16 asks for the start to be allowed only while home is visible, which it is whenever it sends these.
     * Before the fix it asked with the mode Android 16 deprecated.
     */
    @Test fun android16AllowsTheStartWhileHomeIsVisible() {
        assertTrue(route.sendFromLauncher(context))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE, sentOptions().getInt(BAL_MODE))
    }

    @Test fun android14And15AllowTheStart() {
        for (level in listOf(34, 35)) {
            assertTrue(withSdkInt(level) { route.sendFromLauncher(context) })
            assertEquals("SDK $level", ALLOWED, sentOptions().getInt(BAL_MODE))
        }
    }

    @Test fun olderVersionsLeaveItToTheSystem() {
        for (level in listOf(26, 33)) {
            assertTrue(withSdkInt(level) { route.sendFromLauncher(context) })
            assertFalse("SDK $level", sentOptions().containsKey(BAL_MODE))
        }
    }

    private companion object {
        /** Where ActivityOptions.toBundle puts the mode: ComponentOptions.KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, hidden. */
        const val BAL_MODE = "android.pendingIntent.backgroundActivityAllowed"

        /** The mode Android 14 and 15 take. Deprecated from 16 on, where the newer mode is checked instead. */
        @Suppress("DEPRECATION")
        const val ALLOWED = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }
}
