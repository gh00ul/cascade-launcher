package com.gh00ul.cascade.notifications

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.util.ReflectionHelpers

/**
 * Home's start binds the notification listener again when access is granted but nothing is connected (after an update,
 * a force stop or a crash): 5 s after the last start, Cascade's listener is unbound and then rebound, on Android 14 and
 * later. Not when it connects meanwhile, nor without access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [ListenerRebindTest.RecordingListenerService::class])
class ListenerRebindTest {
    /** Records requestUnbind(ComponentName), which Robolectric's shadow doesn't; requestRebind it counts itself. */
    @Implements(NotificationListenerService::class)
    class RecordingListenerService : ShadowNotificationListenerService() {
        companion object {
            /** Each listener asked to unbind, with how many rebinds had been asked for by then. */
            val unbinds = CopyOnWriteArrayList<Pair<ComponentName, Int>>()

            @JvmStatic
            @Implementation(minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            fun requestUnbind(component: ComponentName) {
                unbinds += component to ShadowNotificationListenerService.getRebindRequestCount()
            }
        }
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val listener get() = ComponentName(context, NotificationListener::class.java)
    private val looper get() = shadowOf(Looper.getMainLooper())
    private var rebindsBefore = 0

    @Before fun setUp() {
        RecordingListenerService.unbinds.clear()
        rebindsBefore = ShadowNotificationListenerService.getRebindRequestCount()
    }

    private fun rebinds() = ShadowNotificationListenerService.getRebindRequestCount() - rebindsBefore

    /** Notification access as Settings keeps it, and NotificationManagerCompat reads it: the enabled listeners. */
    private fun allowListeners(vararg components: ComponentName) {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", components.joinToString(":") { it.flattenToString() })
    }

    /** The system binds the listener and it connects. */
    private fun connect(): NotificationListener =
        Robolectric.buildService(NotificationListener::class.java).create().get().apply { onListenerConnected() }

    /** Runs [block] with Build.VERSION.SDK_INT reading [level]: only the version check is under test. */
    private fun withSdkInt(level: Int, block: () -> Unit) {
        val actual = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", level)
        try {
            block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", actual)
        }
    }

    private fun assertNothingRequested() {
        assertEquals(emptyList<Pair<ComponentName, Int>>(), RecordingListenerService.unbinds)
        assertEquals(0, rebinds())
    }

    /** requestRebind alone only undoes a requestUnbind, so the listener is set aside first, then brought back. */
    @Test fun aListenerLeftUnboundIsUnboundAndReboundFiveSecondsAfterHomeStarts() {
        allowListeners(listener)
        NotificationListener.rebindIfDisconnected(context)
        looper.idleFor(Duration.ofMillis(4_999))
        assertNothingRequested()

        looper.idleFor(Duration.ofMillis(1))
        assertEquals(listOf(listener to rebindsBefore), RecordingListenerService.unbinds)
        assertEquals(1, rebinds())
    }

    /** Home started again within the 5 s (a quick trip to another app): one check, 5 s after the last start. */
    @Test fun startsInQuickSuccessionRebindOnce() {
        allowListeners(listener)
        NotificationListener.rebindIfDisconnected(context)
        looper.idleFor(Duration.ofSeconds(3))
        NotificationListener.rebindIfDisconnected(context)
        looper.idleFor(Duration.ofMillis(4_999))
        assertNothingRequested()

        looper.idleFor(Duration.ofSeconds(10))
        assertEquals(1, RecordingListenerService.unbinds.size)
        assertEquals(1, rebinds())
    }

    /** A cold start: the system is binding the listener as home starts, and it connects before the check. */
    @Test fun aListenerThatConnectsMeanwhileIsLeftAlone() {
        allowListeners(listener)
        NotificationListener.rebindIfDisconnected(context)
        looper.idleFor(Duration.ofSeconds(2))
        val connected = connect()
        try {
            looper.idleFor(Duration.ofSeconds(10))
            assertNothingRequested()
        } finally {
            connected.onListenerDisconnected()
        }
    }

    @Test fun withoutAccessNothingIsRebound() {
        allowListeners(ComponentName("com.example.reader", "com.example.reader.Listener"))
        NotificationListener.rebindIfDisconnected(context)
        looper.idleFor(Duration.ofSeconds(10))
        assertNothingRequested()
    }

    /** Unbinding a listener by name is Android 14+, and rebinding alone does nothing for one the system dropped. */
    @Test fun onlyAndroid14AndLaterRebind() {
        allowListeners(listener)
        withSdkInt(33) {
            NotificationListener.rebindIfDisconnected(context)
            looper.idleFor(Duration.ofSeconds(10))
        }
        assertNothingRequested()

        withSdkInt(34) {
            NotificationListener.rebindIfDisconnected(context)
            looper.idleFor(Duration.ofSeconds(5))
        }
        assertEquals(listOf(listener to rebindsBefore), RecordingListenerService.unbinds)
        assertEquals(1, rebinds())
    }
}
