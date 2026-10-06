package com.gh00ul.cascade.ui.home

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FakeLauncherApps
import com.gh00ul.cascade.testing.ServingLauncherApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps

/**
 * An app menu's shortcuts: a press held on the app's row loads them before the long press lands, so the menu's first
 * frame has them and the menu doesn't ask again; a menu opened without such a press (TalkBack) loads them as it opens.
 * The real LauncherScreen on LauncherApplication, with LauncherApps serving two shortcuts for Messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class, qualifiers = ScreenshotTest.PHONE, shadows = [ServingLauncherApps::class])
class MenuShortcutsTest {
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
    private val serial get() = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }

    @Before
    fun seed() {
        resetMenuShortcuts()
        FakeLauncherApps.install(app, *LABELS.toTypedArray())
        Shadow.extract<ShadowLauncherApps>(app.getSystemService(LauncherApps::class.java)).setHasShortcutHostPermission(true)
        val pkg = FakeLauncherApps.pkg("Messages")
        val asMessages = object : ContextWrapper(app) {
            override fun getPackageName() = pkg
        }
        ServingLauncherApps.served = listOf("Maya Chen", "Sam Ortiz").mapIndexed { rank, label ->
            ShortcutInfo.Builder(asMessages, label).setActivity(ComponentName(pkg, "$pkg.Main")).setShortLabel(label)
                .setIntent(Intent(Intent.ACTION_VIEW)).setRank(rank).build()
        }
        ServingLauncherApps.asked.set(0)
        app.repository.refresh()
        val deadline = System.currentTimeMillis() + 10_000
        while (app.repository.apps.value.size < LABELS.size || app.repository.icons.value.size < LABELS.size) {
            check(System.currentTimeMillis() < deadline) { "The repository didn't load the fake apps" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        app.prefs.update {
            it.copy(
                favorites = listOf(key("Phone"), key("Messages"), key("Camera")),
                favoritesSeeded = true,
                autoUpdateCheck = false,
                showBattery = false,
                notificationPromptDismissed = true,
            )
        }
    }

    @After
    fun unserve() {
        ServingLauncherApps.served = emptyList()
    }

    private fun show() {
        compose.setContent { LauncherTheme { LauncherScreen(emptyFlow()) } }
        compose.waitForIdle()
        // Frame by frame from here, to see what the menu's first frame holds.
        compose.mainClock.autoAdvance = false
    }

    // Robolectric's LauncherApps lists the fake apps under their package names.
    private fun row(label: String): SemanticsNodeInteraction = compose.onAllNodesWithText(FakeLauncherApps.pkg(label)).onFirst()

    private fun menuOpen() = compose.onAllNodes(hasContentDescription("App info")).fetchSemanticsNodes().isNotEmpty()
    private fun shortcutShown() = compose.onAllNodesWithText("Maya Chen").fetchSemanticsNodes().isNotEmpty()

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Lets the shortcut loads under way finish: they run on the IO dispatcher, in real time. */
    private fun settleLoads() {
        Thread.sleep(ServingLauncherApps.DELAY_MILLIS * 5)
        shadowOf(Looper.getMainLooper()).idle()
        frame()
    }

    @Test fun aHeldPressLoadsTheShortcutsBeforeTheMenuOpens() {
        show()
        row("Messages").performTouchInput { down(center) }
        // Held past a tap, short of the long press: the load starts.
        compose.mainClock.advanceTimeBy(250)
        settleLoads()
        assertEquals("Loaded while held", 1, ServingLauncherApps.asked.get())
        var frames = 0
        while (!menuOpen()) {
            check(frames++ < 120) { "The menu didn't open" }
            frame()
        }
        assertEquals("The menu's first frame has the shortcuts", true, shortcutShown())
        row("Messages").performTouchInput { up() }
        settleLoads()
        assertEquals("The menu didn't ask again", 1, ServingLauncherApps.asked.get())
    }

    /** No press to start it early (TalkBack's long click): the menu opens at once and the shortcuts follow. */
    @Test fun withoutAHeldPressTheMenuLoadsThemAsItOpens() {
        show()
        row("Messages").performSemanticsAction(SemanticsActions.OnLongClick)
        var frames = 0
        while (!menuOpen()) {
            check(frames++ < 10) { "The menu didn't open" }
            frame()
        }
        assertEquals("Not there on its first frame", false, shortcutShown())
        settleLoads()
        repeat(30) { frame() }
        compose.onNodeWithText("Maya Chen").assertExists()
        assertEquals(1, ServingLauncherApps.asked.get())
    }

    private companion object {
        val LABELS = listOf("Phone", "Messages", "Camera", "Maps", "Music")
    }
}
