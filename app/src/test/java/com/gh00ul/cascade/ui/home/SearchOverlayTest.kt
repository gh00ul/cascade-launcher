package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Search's settings: opening a lone match as you type, the web row, and apps left out of the results. */
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
    private val field get() = compose.onNode(hasSetTextAction())

    private fun show(searchWeb: Boolean = true, autoLaunch: Boolean = false, excluded: Set<String> = emptySet()) {
        compose.setContent {
            LauncherTheme {
                LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = true) {
                        SearchOverlay(
                            apps = apps,
                            icons = emptyMap(),
                            showIcons = false,
                            iconSize = 34.dp,
                            excluded = excluded,
                            searchWeb = searchWeb,
                            autoLaunchSingleMatch = autoLaunch,
                            onLaunch = { app, _ -> launched += app.label },
                            onLongPress = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
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

    /** The actions of the activities started since the last call, leaving out the test's own host. */
    private fun startedActions(): List<String?> {
        val host = shadowOf(compose.activity)
        return generateSequence { host.nextStartedActivity }.map { it.action }.filter { it != Intent.ACTION_MAIN }.toList()
    }
}
