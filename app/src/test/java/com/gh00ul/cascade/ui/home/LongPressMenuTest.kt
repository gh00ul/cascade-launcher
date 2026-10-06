package com.gh00ul.cascade.ui.home

import android.content.ComponentName
import android.os.Looper
import android.os.Process
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.Folder
import com.gh00ul.cascade.data.folderKey
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FakeLauncherApps
import com.gh00ul.cascade.testing.PressRecorder
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/**
 * The long-press menus in the real LauncherScreen, on LauncherApplication with fake apps loaded by the real repository,
 * so the menus' real actions (AppMenu, FolderMenu) run against the real prefs. Robolectric labels each fake app with
 * its package name ("com.example.camera").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class, qualifiers = ScreenshotTest.PHONE)
class LongPressMenuTest {
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
    private val settings get() = app.prefs.settings.value
    private val serial get() = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }
    private fun label(label: String) = FakeLauncherApps.pkg(label)

    @Before
    fun seed() {
        loadApps()
        app.prefs.update {
            it.copy(
                favorites = listOf(key("Phone"), key("Messages"), folderKey("1"), key("Camera"), key("Music")),
                folders = mapOf("1" to Folder("Work", listOf(key("Calendar"), key("Notes")))),
                favoritesSeeded = true,
                autoUpdateCheck = false,
                showBattery = false,
                notificationPromptDismissed = true,
            )
        }
    }

    /**
     * The screen as MainActivity shows it; with [presses], every row's press goes to it instead of the ripple, and with
     * [keyboard], the keyboard's shows and hides.
     */
    private fun show(presses: PressRecorder? = null, keyboard: KeyboardRecorder? = null) {
        compose.setContent {
            LauncherTheme {
                CompositionLocalProvider(
                    LocalIndication provides (presses ?: LocalIndication.current),
                    LocalSoftwareKeyboardController provides (keyboard ?: LocalSoftwareKeyboardController.current),
                ) { LauncherScreen(emptyFlow()) }
            }
        }
        compose.waitForIdle()
    }

    /** Records what's asked of the keyboard, which Robolectric has none of. */
    private class KeyboardRecorder : SoftwareKeyboardController {
        val calls = ArrayList<String>()
        override fun show() { calls += "show" }
        override fun hide() { calls += "hide" }
    }

    private fun row(text: String): SemanticsNodeInteraction = compose.onAllNodesWithText(text).onFirst()

    /** A finger held on [row] past the long press, then (unless [release] is false) lifted without moving. */
    private fun hold(row: SemanticsNodeInteraction, release: Boolean = true) {
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        if (release) {
            row.performTouchInput { up() }
            compose.waitForIdle()
        }
    }

    private fun count(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
    private fun appMenuOpen() = count("App info") > 0
    private fun folderMenuOpen() = count("Edit apps: add, remove or reorder what's in it") > 0

    // --- A folder's menu outlives its folder: Remove dissolves the folder while the menu is still closing --------------

    /** Folder menu from a long press on the folder's row, then Remove: the folder dissolves and the menu closes. */
    @Test fun removeFromAFolderMenuDissolvesTheFolder() {
        show()
        hold(row("Work"))
        assertTrue("The folder menu opened", folderMenuOpen())
        compose.onNodeWithContentDescription("Remove folder: its apps go back to your favorites").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("Folder gone", folderKey("1") in settings.favorites)
        assertFalse("Menu closed", folderMenuOpen())
    }

    /** The same from the open folder's "⋮". */
    @Test fun removeFromTheFolderPopupsOptionsDissolvesTheFolder() {
        show()
        row("Work").performTouchInput { click(Offset(width * 0.08f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Folder options").performClick()
        compose.waitForIdle()
        assertTrue("The folder menu opened", folderMenuOpen())
        compose.onNodeWithContentDescription("Remove folder: its apps go back to your favorites").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("Folder gone", folderKey("1") in settings.favorites)
    }

    // --- Holding, dragging, and the menus' actions ---------------------------------------------------------------------

    @Test fun holdOpensTheMenuAndADragStillReorders() {
        show()
        val camera = row(label("Camera"))
        val pitch = with(compose.density) { (row(label("Music")).getBoundsInRoot().top - camera.getBoundsInRoot().top).toPx() }
        hold(camera, release = false)
        assertTrue("Menu open while held", appMenuOpen())
        camera.performTouchInput {
            repeat(24) { moveBy(Offset(0f, -pitch * 3.3f / 24)) }
            up()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("Menu closed by the drag", appMenuOpen())
        assertTrue("Camera moved up: ${settings.favorites}", settings.favorites.indexOf(key("Camera")) < 3)
    }

    /** Hold a folder's row and let go: its menu, not the folder itself. */
    @Test fun holdingAFolderOpensItsMenuNotTheFolder() {
        show()
        hold(row("Work"))
        assertTrue(folderMenuOpen())
        compose.onNodeWithContentDescription("Folder options").assertDoesNotExist()
    }

    @Test fun favoriteButtonTogglesAndCloses() {
        show()
        hold(row(label("Camera")))
        compose.onNodeWithContentDescription("Remove from favorites").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(key("Camera") in settings.favorites)
        assertFalse(appMenuOpen())
    }

    @Test fun renameOpensItsDialog() {
        show()
        hold(row(label("Camera")))
        // The dialog's text field never lets Robolectric idle (RenameDialog alone doesn't either): step frames by hand.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.mainClock.advanceTimeBy(500)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("A dialog shows", org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test fun addToFolderMovesTheAppIntoIt() {
        show()
        hold(row(label("Camera")))
        compose.onNodeWithText("Add to folder").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Work").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(key("Camera") in settings.folders.getValue("1").apps)
        assertFalse(appMenuOpen())
    }

    @Test fun backClosesTheMenu() {
        show()
        hold(row(label("Camera")))
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(appMenuOpen())
    }

    @Test fun tapOutsideClosesTheMenu() {
        show()
        hold(row(label("Camera")))
        compose.onRoot().performTouchInput { click(Offset(width / 2f, 300f)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(appMenuOpen())
    }

    @Test fun leavingHomeClosesTheMenu() {
        show()
        hold(row(label("Camera")))
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse(appMenuOpen())
    }

    @Test fun talkBacksLongClickOpensTheMenu() {
        show()
        row(label("Camera")).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertTrue(appMenuOpen())
    }

    @Test fun aLongPressInsideAnOpenFolderSwapsItForTheAppsMenu() {
        show()
        row("Work").performTouchInput { click(Offset(width * 0.08f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithText(label("Calendar")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove from Work").assertExists()
        compose.onNodeWithContentDescription("Folder options").assertDoesNotExist()
    }

    @Test fun aListRowsLongPressOpensItsMenu() {
        show()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText(label("Maps")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(appMenuOpen())
    }

    /**
     * Home's long press takes [LongPressScale] of the system's timeout, on a favorite (the lift watches for it) and on
     * an A–Z row (clickable does): each menu opens within a couple of frames of that, well before the system's.
     */
    @Test fun aLongPressLandsBeforeTheSystemTimeout() {
        show()
        val system = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val quick = (system * LongPressScale).toLong()
        // From the finger landing to the menu's first frame, frame by frame; then the menu is closed again.
        fun millisToMenu(row: SemanticsNodeInteraction): Long {
            compose.mainClock.autoAdvance = false
            row.performTouchInput { down(center) }
            val start = compose.mainClock.currentTime
            while (!appMenuOpen()) {
                check(compose.mainClock.currentTime - start < system * 2) { "The menu didn't open" }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            val millis = compose.mainClock.currentTime - start
            row.performTouchInput { cancel() }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.autoAdvance = true
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            return millis
        }
        val favorite = millisToMenu(row(label("Messages")))
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val listRow = millisToMenu(compose.onNodeWithText(label("Maps")))
        for ((what, millis) in listOf("favorite" to favorite, "list row" to listRow)) {
            assertTrue("A $what's menu waits for the long press ($millis ms)", millis >= quick)
            assertTrue("A $what's menu opens at $quick ms, not the system's $system ($millis ms)", millis < quick + 50)
        }
    }

    /** Whether an app came with the system is known from the app list: it can be hidden, not uninstalled. */
    @Test fun aSystemAppsMenuOffersHideNotUninstall() {
        FakeLauncherApps.install(app, "Clock", system = true)
        app.repository.refresh()
        awaitApps(LABELS.size + 1)
        show()
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText(label("Clock")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, count("Hide from app list"))
        assertEquals(0, count("Uninstall"))
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText(label("Maps")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("An app the user installed", 1, count("Uninstall"))
    }

    // --- The row's press ends as its menu opens, though the finger is still down ---------------------------------------

    @Test fun holdingAFavoriteEndsItsPressAsTheMenuOpens() {
        val presses = PressRecorder()
        show(presses)
        val camera = row(label("Camera"))
        hold(camera, release = false)
        assertTrue(appMenuOpen())
        assertTrue("It was pressed", presses.presses > 0)
        assertEquals("No press left under the menu", emptyList<Any>(), presses.held)
        camera.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue("Still open once let go", appMenuOpen())
    }

    @Test fun holdingAFolderEndsItsPressAsTheMenuOpens() {
        val presses = PressRecorder()
        show(presses)
        hold(row("Work"), release = false)
        assertTrue(folderMenuOpen())
        assertTrue(presses.presses > 0)
        assertEquals(emptyList<Any>(), presses.held)
    }

    @Test fun holdingAListRowEndsItsPressAsTheMenuOpens() {
        val presses = PressRecorder()
        show(presses)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val maps = compose.onNodeWithText(label("Maps"))
        hold(maps, release = false)
        assertTrue(appMenuOpen())
        assertTrue(presses.presses > 0)
        assertEquals(emptyList<Any>(), presses.held)
        maps.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(emptyList<Any>(), presses.held)
    }

    // --- TalkBack: what's under an open menu is out of reach -----------------------------------------------------------

    /** The list under the menu is hidden from TalkBack; the alphabet strip beside it is too. */
    @Test fun theAlphabetStripIsHiddenFromTalkBackUnderTheMenu() {
        show()
        compose.onNodeWithContentDescription("Alphabet index").assertExists()
        hold(row(label("Camera")))
        assertTrue(appMenuOpen())
        compose.onNodeWithContentDescription("Alphabet index").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Alphabet index").assertExists()
    }

    private fun openSearch(query: String) {
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.6f) }
        compose.waitForIdle()
        compose.onNodeWithText("Search apps").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
    }

    /** The keyboard goes as a menu opens over search, and comes back when it's closed without a pick, not with one. */
    @Test fun aMenuOverSearchSendsTheKeyboardAway() {
        val keyboard = KeyboardRecorder()
        show(keyboard = keyboard)
        openSearch("maps")
        keyboard.calls.clear()
        compose.onAllNodesWithText(label("Maps")).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(appMenuOpen())
        assertEquals("Gone as the menu opens", listOf("hide"), keyboard.calls)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("Back to typing", listOf("hide", "show"), keyboard.calls)

        keyboard.calls.clear()
        compose.onAllNodesWithText(label("Maps")).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to favorites").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(appMenuOpen())
        assertEquals("A pick doesn't bring it back", listOf("hide"), keyboard.calls)
    }

    /** A search result's menu opens over search, and Back closes the menu, not search. */
    @Test fun backFromAMenuOverSearchClosesOnlyTheMenu() {
        show()
        openSearch("maps")
        compose.onAllNodesWithText(label("Maps")).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("Menu open over search", appMenuOpen())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("Menu closed", appMenuOpen())
        compose.onNode(hasSetTextAction()).assertExists()
    }

    /** Under a menu opened from search, search's results are hidden from TalkBack like the list is. */
    @Test fun searchIsHiddenFromTalkBackUnderTheMenu() {
        show()
        openSearch("maps")
        compose.onAllNodesWithText(label("Maps")).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(appMenuOpen())
        // Only the menu's header names Maps; the result row under it isn't reachable.
        assertEquals(1, compose.onAllNodesWithText(label("Maps")).fetchSemanticsNodes().size)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun loadApps() {
        FakeLauncherApps.install(app, *LABELS.toTypedArray())
        app.repository.refresh()
        awaitApps(LABELS.size)
    }

    private fun awaitApps(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (app.repository.apps.value.size == count && app.repository.icons.value.size == count) return
            Thread.sleep(20)
        }
        error("The repository didn't load the fake apps")
    }

    private companion object {
        val LABELS = listOf("Phone", "Messages", "Camera", "Maps", "Music", "Photos", "Calendar", "Notes")
    }
}
