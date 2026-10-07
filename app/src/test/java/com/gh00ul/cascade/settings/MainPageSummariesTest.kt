package com.gh00ul.cascade.settings

import android.Manifest
import android.content.ComponentName
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.gh00ul.cascade.update.Updater
import com.gh00ul.cascade.util.LockService
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The top settings page's summaries say what will actually happen: the calendar isn't listed under the clock without
 * calendar access, double-tap to lock says when the lock service is off, and a failed update check isn't reported as an
 * update that didn't finish. The page reads access, the service and the updater's state itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class)
class MainPageSummariesTest {
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

    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication

    /** Kotlin objects outlive a test inside the same Robolectric sandbox. */
    @After fun idleUpdater() = Updater.finished(Updater.State.Idle)

    private fun show() {
        app.prefs.update { it.copy(showCalendar = true, doubleTapAction = DoubleTapAction.LOCK_SCREEN) }
        compose.setContent { LauncherTheme(dark = false) { SettingsApp(SettingsScreen.MAIN, onExit = {}) } }
        compose.waitForIdle()
    }

    /**
     * Without calendar access (Robolectric grants nothing) and with the lock service off, neither works yet. Before the
     * fix the summaries promised both: "Classic · alarm, timers, calendar, battery" and "double-tap: lock".
     */
    @Test fun summariesDontPromiseWhatCantWorkYet() {
        show()
        compose.onNodeWithText("Classic · alarm, timers, battery").assertExists()
        compose.onNodeWithText("Swipe down: notifications · double-tap: lock (lock service off)").assertExists()
    }

    @Test fun withAccessAndTheLockServiceOnTheyReadAsSet() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(app, LockService::class.java).flattenToString())
        show()
        compose.onNodeWithText("Classic · alarm, timers, calendar, battery").assertExists()
        compose.onNodeWithText("Swipe down: notifications · double-tap: lock").assertExists()
    }

    /** A check that failed has no release. Before the fix About read "update didn't finish", as if one had begun. */
    @Test fun aFailedCheckSaysItCouldntCheck() {
        Updater.finished(Updater.State.Failed("Couldn't reach GitHub. Check your connection.", release = null))
        show()
        compose.onNodeWithText("couldn't check for updates", substring = true).assertExists()
        compose.onNodeWithText("update didn't finish", substring = true).assertDoesNotExist()
    }
}
