package com.gh00ul.cascade.ui.home

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeContactsProvider
import com.gh00ul.cascade.testing.FakeContactsProvider.Companion.contact
import com.gh00ul.cascade.testing.FakeContactsProvider.Companion.phone
import com.gh00ul.cascade.ui.theme.LauncherTheme
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

/**
 * Search's settings: opening a lone match as you type, the web row, apps left out of the results, the calculator's
 * answer, and contacts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchOverlayTest {
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

    private val camera = FakeApps.app("Camera", "com.example.camera")
    private val apps = listOf(FakeApps.app("Maps", "com.example.maps"), FakeApps.app("Mail", "com.example.mail"), camera)
    private val launched = mutableListOf<String>()
    private var dismissed = 0
    private val field get() = compose.onNode(hasSetTextAction())

    private fun show(
        searchWeb: Boolean = true,
        autoLaunch: Boolean = false,
        excluded: Set<String> = emptySet(),
        calculator: Boolean = true,
        contacts: Boolean = false,
        apps: List<AppEntry> = this.apps,
        showIcons: Boolean = false,
        timeFormat: TimeFormat = TimeFormat.SYSTEM,
    ) {
        compose.setContent {
            LauncherTheme {
                LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = true) {
                        SearchOverlay(
                            apps = apps,
                            icons = emptyMap(),
                            showIcons = showIcons,
                            iconSize = 34.dp,
                            excluded = excluded,
                            searchWeb = searchWeb,
                            autoLaunchSingleMatch = autoLaunch,
                            onLaunch = { app, _ -> launched += app.label },
                            onLongPress = { _, _ -> },
                            onDismiss = { dismissed++ },
                            searchCalculator = calculator,
                            searchContacts = contacts,
                            timeFormat = timeFormat,
                        )
                    }
                }
            }
        }
    }

    /** An alarm reads in Cascade's own 12/24-hour setting, as the home clock does, not the phone's. */
    @Test fun anAlarmFollowsCascadesTimeFormat() {
        show(timeFormat = TimeFormat.H24)
        field.performTextInput("7:30")
        compose.waitForIdle()
        val row = compose.onNode(hasText("Set an alarm for", substring = true)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(row, Regex("""^Set an alarm for (7|19):30, """).containsMatchIn(row))
    }

    @Test fun typingDownToOneMatchOpensIt() {
        show(autoLaunch = true)
        // One letter is too little to go on, even with a single match.
        field.performTextInput("c")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
        field.performTextInput("a")
        compose.waitForIdle()
        assertEquals(listOf("Camera"), launched)
    }

    @Test fun twoMatchesWaitForMore() {
        show(autoLaunch = true)
        field.performTextInput("ma")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
    }

    @Test fun deletingDownToOneMatchDoesntOpenIt() {
        show(autoLaunch = true)
        field.performTextInput("cax")
        compose.waitForIdle()
        field.performTextReplacement("ca")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
    }

    @Test fun typingOpensNothingWhenOff() {
        show()
        field.performTextInput("ca")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
    }

    @Test fun excludedAppsArentFound() {
        show(autoLaunch = true, excluded = setOf(camera.key))
        field.performTextInput("ca")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
        compose.onNodeWithText("Camera").assertDoesNotExist()
    }

    @Test fun webRowAndGoFollowTheSetting() {
        show(searchWeb = true)
        field.performTextInput("zzz")
        compose.onNodeWithText("Search the web", substring = true).assertExists()
        field.performImeAction()
        assertEquals(listOf(Intent.ACTION_WEB_SEARCH), startedActions())
    }

    @Test fun withoutWebSearchGoWithNoMatchDoesNothing() {
        show(searchWeb = false)
        field.performTextInput("zzz")
        compose.onNodeWithText("Search the web", substring = true).assertDoesNotExist()
        field.performImeAction()
        assertEquals(emptyList<String>(), startedActions())
        // An app hit still opens with Go.
        field.performTextReplacement("cam")
        field.performImeAction()
        assertEquals(listOf("Camera"), launched)
    }

    @Test fun anAnswerTopsTheResultsAndATapCopiesIt() {
        show()
        field.performTextInput("24*7")
        val answer = compose.onNodeWithContentDescription("24 times 7 equals 168")
        // TalkBack: "24 times 7 equals 168, double tap to copy".
        assertEquals("Copy", answer.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        answer.performClick()
        assertEquals("168", clipboardText())
        // Copying opens nothing and leaves search open.
        assertEquals(emptyList<String>(), launched)
        assertEquals(emptyList<String?>(), startedActions())
        assertEquals(0, dismissed)
    }

    @Test fun goCopiesTheAnswerRatherThanOpeningAMatchingApp() {
        show(apps = listOf(FakeApps.app("1+1 Game", "com.example.game")))
        field.performTextInput("1+1")
        compose.onNodeWithText("1+1 Game").assertExists()
        field.performImeAction()
        assertEquals("2", clipboardText())
        assertEquals(emptyList<String>(), launched)
    }

    @Test fun anAnswerIsntOpenedAsALoneMatch() {
        show(apps = listOf(FakeApps.app("1+1 Game", "com.example.game")), autoLaunch = true)
        field.performTextInput("1+1")
        compose.waitForIdle()
        assertEquals(emptyList<String>(), launched)
    }

    @Test fun theCalculatorFollowsItsSetting() {
        show(calculator = false)
        field.performTextInput("24*7")
        compose.onNodeWithContentDescription("24 times 7 equals 168").assertDoesNotExist()
        // Go searches the web instead, as for any query no app matches.
        field.performImeAction()
        assertEquals(listOf(Intent.ACTION_WEB_SEARCH), startedActions())
    }

    @Test fun contactsFollowTheAppsWithButtonsToTextAndCall() {
        val provider = contactsAllowed()
        provider.contacts = listOf(contact(1, "Jane Doe", hasPhone = true), contact(2, "Jake"))
        provider.phones = listOf(phone(1, "555-0100"))
        show(contacts = true)
        field.performTextInput("ja")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jane Doe").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jake").assertExists()
        compose.onNodeWithContentDescription("Message Jane Doe").assertExists()
        // No number, no buttons.
        compose.onNodeWithContentDescription("Call Jake").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message Jake").assertDoesNotExist()

        compose.onNodeWithContentDescription("Call Jane Doe").performClick()
        val dial = startedIntents().single()
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:555-0100", dial.dataString)
        assertEquals(1, dismissed)

        compose.onNodeWithContentDescription("Message Jane Doe").performClick()
        val text = startedIntents().single()
        assertEquals(Intent.ACTION_SENDTO, text.action)
        assertEquals("smsto:555-0100", text.dataString)
    }

    @Test fun tappingAContactOpensIt() {
        val provider = contactsAllowed()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        show(contacts = true)
        field.performTextInput("ja")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jane Doe").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jane Doe").performClick()
        val view = startedIntents().single()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("content://com.android.contacts/contacts/lookup/lookup1/1", view.dataString)
        assertEquals(1, dismissed)
    }

    /** With icons, the "=" and a contact's initial are drawn on the icon column; TalkBack hears neither. */
    @Test fun withIconsRowsReadAsWords() {
        val provider = contactsAllowed()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        show(contacts = true, showIcons = true)
        field.performTextInput("24*7")
        val answer = compose.onNodeWithContentDescription("24 times 7 equals 168").fetchSemanticsNode()
        assertEquals(null, answer.config.getOrNull(SemanticsProperties.Text))

        field.performTextReplacement("ja")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Jane Doe").fetchSemanticsNodes().isNotEmpty() }
        val row = compose.onNodeWithText("Jane Doe").fetchSemanticsNode()
        assertEquals(listOf("Jane Doe"), row.config[SemanticsProperties.Text].map { it.text })
    }

    @Test fun contactsAreLookedUpOnceTypingPauses() {
        val provider = contactsAllowed()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        show(contacts = true)
        compose.mainClock.autoAdvance = false
        field.performTextInput("j")
        compose.mainClock.advanceTimeBy(100)
        field.performTextInput("a")
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(100) // A lookup, if one started, would run on Dispatchers.IO.
        assertTrue(provider.queries.isEmpty())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { provider.queries.isNotEmpty() }
        // Only the query typing paused on.
        assertEquals(listOf("contacts", "filter", "ja"), provider.queries.single().uri.pathSegments)
    }

    @Test fun contactsSwitchedOffLookNothingUp() {
        val provider = contactsAllowed()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        show(contacts = false)
        assertNothingLookedUp(provider)
    }

    @Test fun withoutContactsAccessNothingIsLookedUp() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CONTACTS)
        val provider = FakeContactsProvider.install()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        show(contacts = true)
        assertNothingLookedUp(provider)
    }

    private fun assertNothingLookedUp(provider: FakeContactsProvider) {
        field.performTextInput("ja")
        compose.mainClock.advanceTimeBy(1_000)
        Thread.sleep(100) // A lookup, if one started, would run on Dispatchers.IO.
        compose.waitForIdle()
        assertTrue(provider.queries.isEmpty())
        compose.onNodeWithText("Jane Doe").assertDoesNotExist()
    }

    private fun contactsAllowed(): FakeContactsProvider {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS)
        return FakeContactsProvider.install()
    }

    private fun clipboardText() =
        compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    /** The actions of the activities started since the last call, leaving out the test's own host. */
    private fun startedActions(): List<String?> = startedIntents().map { it.action }

    /** The activities started since the last call, leaving out the test's own host. */
    private fun startedIntents(): List<Intent> {
        val host = shadowOf(compose.activity)
        return generateSequence { host.nextStartedActivity }.filter { it.action != Intent.ACTION_MAIN }.toList()
    }
}
