package com.gh00ul.cascade.util

import android.app.Application
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.gh00ul.cascade.MainActivity
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowToast

/**
 * Home's ways into Settings and other apps, on a phone where nothing answers them: each says so in a short message
 * instead of doing nothing, and the home-app setting falls back to Default apps before giving up.
 *
 * Robolectric opens any activity unless told to check that something handles it; checking, as here, an unhandled start
 * throws ActivityNotFoundException, as on a phone. Only the activities a test adds ([answer]) can open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [LauncherActionsFailureTest.FailingLauncherApps::class])
class LauncherActionsFailureTest {
    /** LauncherApps whose App info page won't open: it throws [failure] (Robolectric's own throws UnsupportedOperationException). */
    @Implements(LauncherApps::class)
    class FailingLauncherApps : ShadowLauncherApps() {
        @Implementation
        override fun startAppDetailsActivity(component: ComponentName?, user: UserHandle?, sourceBounds: Rect?, opts: Bundle?) {
            throw failure
        }

        companion object {
            var failure: RuntimeException = ActivityNotFoundException()
        }
    }

    /** A launcher for the default-home role dialog that nothing on the phone shows: launching it throws. */
    private class UnansweredRoleRequest : ActivityResultLauncher<Intent>() {
        val asked = mutableListOf<Intent>()

        override val contract: ActivityResultContract<Intent, *> = ActivityResultContracts.StartActivityForResult()

        override fun launch(input: Intent, options: ActivityOptionsCompat?) {
            asked += input
            // What ComponentActivity's registry throws when no activity takes the request.
            throw ActivityNotFoundException("No activity handles ${input.action}")
        }

        override fun unregister() {}
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val music = FakeApps.music

    @Before fun nothingAnswers() {
        FailingLauncherApps.failure = ActivityNotFoundException()
        shadowOf(context).checkActivities(true)
    }

    /** Lets a Settings page answer [action] (with data of [scheme], if any). */
    private fun answer(action: String, scheme: String? = null) {
        val page = ComponentName("com.android.settings", "com.android.settings.${action.substringAfterLast('.')}")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(page)
            addIntentFilterForActivity(page, IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                if (scheme != null) addDataScheme(scheme)
            })
        }
    }

    private fun opened(): String? = shadowOf(context).nextStartedActivity?.action

    private fun said(): String? = ShadowToast.getTextOfLatestToast()

    /** Before the fix runCatching swallowed the failure: the menu closed and nothing happened. */
    @Test fun appInfoThatWontOpenSaysSo() {
        LauncherActions.openAppInfo(context, music)
        assertEquals("Couldn't open App info for Music", said())
        // A work app whose profile is off or gone is refused with a SecurityException; that says so too.
        FailingLauncherApps.failure = SecurityException("Not a profile of the caller")
        LauncherActions.openAppInfo(context, music)
        assertEquals(2, ShadowToast.shownToastCount())
    }

    /** Before the fix start()'s false was ignored, here and in each test below: nothing happened, and nothing was said. */
    @Test fun uninstallThatWontOpenSaysSo() {
        LauncherActions.uninstall(context, music)
        assertEquals("Couldn't uninstall Music", said())
    }

    @Test fun ownAppInfoThatWontOpenSaysSoAndOpensQuietlyWhenItCan() {
        LauncherActions.openOwnAppInfo(context)
        assertEquals("Couldn't open App info", said())
        assertNull(opened())

        answer(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, scheme = "package")
        LauncherActions.openOwnAppInfo(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    /** Neither the calendar at today nor any calendar app opens. Before the fix the second failure went unchecked. */
    @Test fun aCalendarThatWontOpenSaysSo() {
        // Robolectric resolves a selector intent by its outer intent (any launcher activity), where Android uses the
        // selector (any calendar app): Cascade's own home activity would take "open a calendar app" here.
        shadowOf(context.packageManager).clearIntentFilterForActivity(ComponentName(context, MainActivity::class.java))
        LauncherActions.openCalendar(context)
        assertEquals("Couldn't open the calendar", said())
        assertNull(opened())
    }

    @Test fun accessibilitySettingsThatWontOpenSaySo() {
        LauncherActions.openAccessibilitySettings(context)
        assertEquals("Couldn't open Accessibility settings", said())
    }

    /** Neither Cascade's own notification access page nor the list opens. */
    @Test fun notificationAccessThatWontOpenSaysSo() {
        LauncherActions.openNotificationAccess(context)
        assertEquals("Couldn't open notification access settings", said())
    }

    /** Some phones have no Home settings page. Before the fix only it was tried, and nothing happened. */
    @Test fun theHomeAppSettingFallsBackToDefaultApps() {
        answer(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        LauncherActions.openHomeSettings(context)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, opened())
        // Opened as asked, it needs no explanation.
        assertEquals(0, ShadowToast.shownToastCount())
    }

    /** Opened in place of the dialog the user asked for, the setting says what to do there. */
    @Test fun theHomeAppSettingInPlaceOfTheDialogSaysWhatToDoThere() {
        answer(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        LauncherActions.openHomeSettings(context, hint = true)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, opened())
        assertEquals("Choose Cascade as your home app here", said())
    }

    @Test fun aHomeAppSettingThatWontOpenSaysWhereToLook() {
        LauncherActions.openHomeSettings(context, hint = true)
        assertEquals("Couldn't open the home app setting. Look for Default apps in Settings.", said())
        // Not the hint as well: there's no page to choose on.
        assertEquals(1, ShadowToast.shownToastCount())
    }

    /**
     * Nothing shows the "Set as default home app" dialog. Before the fix ActivityNotFoundException went up through the
     * setup card's tap and crashed Settings; now the home-app setting opens instead, saying what to do there.
     */
    @Test fun aRoleDialogNothingShowsOpensTheHomeSettingInstead() {
        shadowOf(context.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_HOME)
        answer(Settings.ACTION_HOME_SETTINGS)
        val request = UnansweredRoleRequest()
        LauncherActions.requestDefaultLauncher(context, request)
        assertEquals("The role dialog was asked for first", 1, request.asked.size)
        assertEquals(Settings.ACTION_HOME_SETTINGS, opened())
        assertEquals("Choose Cascade as your home app here", said())
    }
}
