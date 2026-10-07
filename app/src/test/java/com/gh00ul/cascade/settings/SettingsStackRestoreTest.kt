package com.gh00ul.cascade.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.ui.theme.LauncherTheme
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
 * Settings' page stack, saved by page name, read back by a version that has none of those pages any more (renamed or
 * removed by an update since): Settings starts over on the page it was opened on instead of failing on an empty stack.
 *
 * The save and restore are StateRestorationTester's, by hand: SettingsApp is composed under a registry, saved, taken
 * down, and composed again in the same place under a registry holding what was saved, with the page names changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class)
class SettingsStackRestoreTest {
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

    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var showing by mutableStateOf(true)
    /** Page names changed by [rename]. */
    private var renamed = 0

    @Test fun aStackOfPagesThatNoLongerExistStartsOver() {
        compose.setContent {
            LauncherTheme(dark = false) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (showing) SettingsApp(SettingsScreen.MAIN, onExit = {})
                }
            }
        }
        compose.onNodeWithText("Search settings").assertExists()

        val stale = compose.runOnIdle { registry.performSave() }.mapValues { (_, values) -> values.map { rename(it) } }
        assertTrue("The stack is saved by page name", renamed > 0)
        compose.runOnIdle { showing = false }
        compose.waitForIdle()
        compose.runOnIdle {
            registry = SaveableStateRegistry(stale) { true }
            showing = true
        }
        compose.waitForIdle()
        compose.onNodeWithText("Search settings").assertExists()
    }

    /** [value] as an update without any of today's pages would read it back: every page name is one it doesn't know. */
    private fun rename(value: Any?): Any? = when (value) {
        is String -> if (SettingsScreen.entries.any { it.name == value }) "Old$value".also { renamed++ } else value
        is State<*> -> mutableStateOf(rename(value.value))
        is List<*> -> value.map { rename(it) }
        else -> value
    }
}
