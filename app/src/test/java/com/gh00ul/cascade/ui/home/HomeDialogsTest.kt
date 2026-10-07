package com.gh00ul.cascade.ui.home

import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.Folder
import com.gh00ul.cascade.data.WIDGET_WEATHER
import com.gh00ul.cascade.data.folderKey
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeLauncherApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowWindowManagerGlobal

/**
 * Home's dialogs through an activity recreation (a dark mode or font size change): the rename, new-folder and
 * rename-folder dialogs and the widget sheet are still open in the recreated activity, with what was typed; and the
 * folder name's field has focus once its dialog opens, so the keyboard comes up.
 *
 * The activity is [Host], which composes its content from onCreate as MainActivity does, so the activity a recreation
 * makes composes it again and restores its saved state.
 *
 * With one of these dialogs open, Compose never goes idle under Robolectric, and every compose.onNode, perform and
 * assert call first waits for it to. So, as in LongPressMenuTest's renameOpensItsDialog, once a dialog may be open the
 * clock is stepped by hand and only the looper is idled ([frames]), the dialog's semantics are read straight from its
 * window ([dialogNodes]), and the field's own semantics actions do the typing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class, qualifiers = ScreenshotTest.PHONE)
class HomeDialogsTest {
    /** Shows [content] from onCreate, as MainActivity shows LauncherScreen, so a recreated Host shows it again. */
    class Host : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                val shown = content
                shown()
            }
        }

        companion object {
            /** What every Host shows. State, so a test sets it once its apps and settings are in place. */
            var content by mutableStateOf<@Composable () -> Unit>({})
        }
    }

    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, Host::class.java))
                // Kotlin objects outlive a test inside the same Robolectric sandbox.
                Host.content = {}
                try {
                    base.evaluate()
                } finally {
                    Host.content = {}
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<Host>()

    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication
    private val serial get() = app.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }
    /** Robolectric labels each fake app with its package name ("com.example.camera"). */
    private fun label(label: String) = FakeLauncherApps.pkg(label)

    // --- The dialogs alone: what's typed is saved with them ------------------------------------------------------------

    @Test fun renameKeepsWhatsTypedThroughRecreation() {
        compose.mainClock.autoAdvance = false
        show { RenameDialog(FakeApps.camera) {} }
        type("Shutter")
        recreate()
        assertEquals("Shutter", field().editableText())
    }

    /** The folder name is saved selection and all: typed over, then the cursor moved, both come back. */
    @Test fun aFolderNameKeepsWhatsTypedAndTheCursorThroughRecreation() {
        compose.mainClock.autoAdvance = false
        show { FolderNameDialog("New folder", initial = "Games", confirmLabel = "Create", onConfirm = {}, onDismiss = {}) }
        type("Road trip")
        moveCursorTo(4)
        recreate()
        val field = field()
        assertEquals("Road trip", field.editableText())
        assertEquals(TextRange(4), field.config.getOrNull(SemanticsProperties.TextSelectionRange))
    }

    /**
     * The name field asks for focus from inside the dialog's window, once it's there to take it. A phone is in touch
     * mode, where a new window focuses nothing on its own; Robolectric's windows aren't unless told, and would hand the
     * field focus themselves, whatever the dialog asked for.
     */
    @Test fun theFolderNameFieldIsFocusedWhenItsDialogOpens() {
        val touchMode = ShadowWindowManagerGlobal.getInTouchMode()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        try {
            compose.mainClock.autoAdvance = false
            show { FolderNameDialog("New folder", initial = "Games", confirmLabel = "Create", onConfirm = {}, onDismiss = {}) }
            assertEquals("The name field has focus", true, field().config.getOrNull(SemanticsProperties.Focused))
        } finally {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(touchMode)
        }
    }

    // --- Home: the dialog open (which app or folder) is saved too ------------------------------------------------------

    @Test fun renamingAnAppStaysOpenThroughRecreation() {
        showHome()
        hold(row(label("Camera")))
        // From here a dialog may be open: nothing may wait for Compose to idle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Rename").performClick()
        frames()
        type("Shutter")
        recreate()
        assertTrue("The Rename dialog is open again", dialogShows("Rename"))
        assertEquals("Shutter", field().editableText())
    }

    @Test fun namingANewFolderStaysOpenThroughRecreation() {
        showHome()
        hold(row(label("Camera")))
        // With a folder already, Add to folder lists it, then New folder.
        compose.onNodeWithText("Add to folder").performClick()
        compose.waitForIdle()
        // From here a dialog may be open: nothing may wait for Compose to idle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("New folder").performClick()
        frames()
        type("Road trip")
        recreate()
        assertTrue("The New folder dialog is open again", dialogShows("New folder"))
        assertEquals("Road trip", field().editableText())
    }

    @Test fun renamingAFolderStaysOpenThroughRecreation() {
        showHome()
        hold(row("Work"))
        // From here a dialog may be open: nothing may wait for Compose to idle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Rename").performClick()
        frames()
        type("Office")
        recreate()
        assertTrue("The Rename folder dialog is open again", dialogShows("Rename folder"))
        assertEquals("Office", field().editableText())
    }

    /** The sheet has to be there after a recreation to receive a widget's "Allow" answer. It has no text field. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun theWidgetSheetStaysOpenThroughRecreation() {
        // The weather widget without a place: it offers to pick one, and nothing goes to the network.
        showHome(widgetStack = listOf(WIDGET_WEATHER))
        compose.onNode(offersEditWidgets).performCustomAccessibilityActionWithLabel("Edit widgets")
        compose.waitForIdle()
        compose.onNodeWithText("Widget stack").assertExists()
        recreate()
        compose.onNodeWithText("Widget stack").assertExists()
    }

    /** Shows [content] in the host, on the theme home uses, and lets it open. */
    private fun show(content: @Composable () -> Unit) {
        Host.content = { LauncherTheme { content() } }
        frames()
    }

    /** LauncherScreen as MainActivity shows it, with a few fake apps loaded by the real repository and a folder. */
    private fun showHome(widgetStack: List<String> = emptyList()) {
        FakeLauncherApps.install(app, *LABELS.toTypedArray())
        app.repository.refresh()
        awaitApps(LABELS.size)
        app.prefs.update {
            it.copy(
                favorites = listOf(key("Phone"), key("Camera"), folderKey("1"), key("Music")),
                folders = mapOf("1" to Folder("Work", listOf(key("Calendar"), key("Notes")))),
                widgetStack = widgetStack,
                favoritesSeeded = true,
                autoUpdateCheck = false,
                showBattery = false,
                notificationPromptDismissed = true,
            )
        }
        Host.content = { LauncherTheme { LauncherScreen(emptyFlow()) } }
        compose.waitForIdle()
    }

    /** Recreates the activity, as a dark mode or font size change does, and lets the new one open its windows. */
    private fun recreate() {
        // Syncs on the looper only (Instrumentation.waitForIdleSync), never on Compose.
        compose.activityRule.scenario.recreate()
        frames()
    }

    /**
     * Runs the clock by hand and idles the looper, which lays out the windows (a dialog's own, attaching and composing
     * it). Never waits for Compose to idle: with a dialog's field up, it doesn't.
     */
    private fun frames() {
        repeat(3) {
            // A change made outside composition (a click, a semantics action, a new Host.content) reaches the
            // recomposer now, rather than on the looper's next pass.
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(FRAME_STEP_MS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Replaces the dialog field's text, as an accessibility service would, and lets it show. */
    private fun type(text: String) {
        val setText = checkNotNull(field().config[SemanticsActions.SetText].action)
        assertTrue("The field took the text", setText(AnnotatedString(text)))
        frames()
    }

    /** Puts the dialog field's cursor at [index] (in the text as it shows after the last [frames]). */
    private fun moveCursorTo(index: Int) {
        val setSelection = checkNotNull(field().config[SemanticsActions.SetSelection].action)
        assertTrue("The cursor moved", setSelection(index, index, true))
        frames()
    }

    /**
     * The open dialog's semantics, merged as compose.onNode sees them, read straight from its window's Compose root;
     * empty when no dialog is open (the last one shown was dismissed, or none was).
     */
    private fun dialogNodes(): List<SemanticsNode> {
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } ?: return emptyList()
        val window = checkNotNull(dialog.window) { "The open dialog has no window" }
        val root = checkNotNull(composeRoot(window.decorView)) { "The open dialog has no Compose content" }
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true, skipDeactivatedNodes = true)
    }

    private fun composeRoot(view: View): ViewRootForTest? = view as? ViewRootForTest
        ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { composeRoot(group.getChildAt(it)) } }

    /** Whether an open dialog shows [text] (its title, say). */
    private fun dialogShows(text: String) =
        dialogNodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }

    /** The open dialog's text field. */
    private fun field(): SemanticsNode {
        val fields = dialogNodes().filter { SemanticsActions.SetText in it.config }
        assertEquals("Text fields in an open dialog", 1, fields.size)
        return fields.single()
    }

    private fun SemanticsNode.editableText(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun row(text: String): SemanticsNodeInteraction = compose.onAllNodesWithText(text).onFirst()

    /** A finger held on [row] past the long press, then lifted: its menu opens. */
    private fun hold(row: SemanticsNodeInteraction) {
        row.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        row.performTouchInput { up() }
        compose.waitForIdle()
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
        /** How far each of [frames]' steps runs the clock: a few frames. */
        const val FRAME_STEP_MS = 100L

        /** The widget stack, which offers TalkBack its edit sheet. */
        val offersEditWidgets = SemanticsMatcher("offers Edit widgets") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == "Edit widgets" }
        }
    }
}
