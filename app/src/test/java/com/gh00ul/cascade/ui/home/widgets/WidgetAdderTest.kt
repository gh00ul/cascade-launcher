package com.gh00ul.cascade.ui.home.widgets

import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gh00ul.cascade.data.WidgetHost
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
import org.robolectric.shadows.AppWidgetProviderInfoBuilder
import org.robolectric.shadows.ShadowAppWidgetHost
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/**
 * Adding an app widget from the picker when it can't be added: the system won't hand out an id, or nothing on the phone
 * shows its "Allow Cascade to create widgets" dialog. Either way the picker says "Couldn't add the widget" rather than
 * doing nothing, and an id already handed out is freed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [WidgetAdderTest.RefusingWidgetHost::class])
class WidgetAdderTest {
    /** The system's side of the widget host: it refuses ids while [refuse] is set, and records the ids freed. */
    @Implements(AppWidgetHost::class)
    class RefusingWidgetHost : ShadowAppWidgetHost() {
        @Implementation
        override fun allocateAppWidgetId(): Int {
            // What AppWidgetHost throws when the system's widget service can't be reached.
            if (refuse) throw RuntimeException("system server dead?")
            return super.allocateAppWidgetId()
        }

        @Implementation
        fun deleteAppWidgetId(appWidgetId: Int) {
            deleted += appWidgetId
        }

        companion object {
            var refuse = false
            val deleted = mutableListOf<Int>()
        }
    }

    /** Registers the bare activity the compose rule hosts content in (the app's manifest doesn't declare it). */
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

    private lateinit var adder: WidgetAdder

    /**
     * A clock widget, as the picker hands it over. getProfile() reads the provider's uid from the hidden providerInfo,
     * which the system fills in.
     */
    private val clock = AppWidgetProviderInfoBuilder.newBuilder()
        .setProviderInfo(ActivityInfo().apply { applicationInfo = ApplicationInfo().apply { uid = Process.myUid() } })
        .build()
        .apply { provider = ComponentName("com.example.clock", "com.example.clock.ClockWidget") }

    @Before fun freshProcess() {
        RefusingWidgetHost.refuse = false
        RefusingWidgetHost.deleted.clear()
        // WidgetHost is process-wide, and Kotlin objects outlive a test inside the same Robolectric sandbox.
        mapOf(
            "host" to null, "started" to false, "listening" to false, "pruned" to false, "pending" to null,
            "pendingInfo" to null, "configuring" to null, "setupShown" to false, "setupOwner" to null,
        ).forEach { (field, value) -> ReflectionHelpers.setStaticField(WidgetHost::class.java, field, value) }
    }

    private fun show() {
        compose.setContent { adder = rememberWidgetAdder() }
        compose.waitForIdle()
    }

    /** Before the fix begin() returned null for this, which also meant "bound": nothing was set up, and nothing said. */
    @Test fun anIdTheSystemWontHandOutSaysTheWidgetCouldntBeAdded() {
        RefusingWidgetHost.refuse = true
        show()
        compose.runOnIdle { adder.app(clock) }
        assertEquals("Couldn't add the widget", ShadowToast.getTextOfLatestToast())
        assertEquals(emptyList<Int>(), RefusingWidgetHost.deleted)
    }

    /**
     * Robolectric binds nothing without asking, so the picker launches the system's dialog; with activities checked,
     * nothing handles it. Before the fix the id was freed without a word.
     */
    @Test fun aBindDialogThatWontOpenSaysTheWidgetCouldntBeAddedAndFreesTheId() {
        show()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        compose.runOnIdle { adder.app(clock) }
        assertEquals("Couldn't add the widget", ShadowToast.getTextOfLatestToast())
        assertEquals("The id handed out is freed", 1, RefusingWidgetHost.deleted.size)
    }
}
