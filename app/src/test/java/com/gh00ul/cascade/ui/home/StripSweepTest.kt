package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.screenshots.ScreenshotTest
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

/**
 * The strip on its own, with App names on and a phone's worth of apps, swept end to end in small steps: everything it
 * jumps to, in order. Down from its first letter it should pass every letter and every app once, in list order; back up
 * from its last, every letter once, in reverse (names open below their letter, behind a finger going up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
class StripSweepTest {
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

    private val apps = FakeApps.crowded
    private val names = letterApps(apps.mapIndexed { i, app -> PrefixSource(app.section, app.label, i) })
    private val letters = names.keys.toList()
    /** Every item in list order: each letter, then its apps. */
    private val order = letters.flatMap { letter -> listOf("letter:$letter") + names.getValue(letter).map { "app:${it.text}" } }
    private val seen = mutableListOf<String>()

    private fun show(height: Dp) {
        compose.setContent {
            LauncherTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    AlphabetWave(
                        letters = letters,
                        onLetter = { seen += "letter:$it" },
                        modifier = Modifier.height(height).fillMaxHeight(),
                        prefixes = names,
                        onPrefix = { seen += "app:${it.text}" },
                        names = true,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun strip(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithContentDescription("Alphabet index").performTouchInput(block)
        compose.waitForIdle()
    }

    /** Down from the first letter to past the strip's end, then back up to past its top, half a dp at a time. */
    private fun sweep(height: Dp): Pair<List<String>, List<String>> {
        show(height)
        val step = 0.5.dp
        var steps = 0
        strip {
            down(Offset(centerX, 1f))
            steps = ((this.height + 40.dp.toPx()) / step.toPx()).toInt()
        }
        repeat(steps) { strip { moveBy(Offset(0f, step.toPx())) } }
        val downward = seen.toList()
        seen.clear()
        repeat(steps + 2 * (40.dp / step).toInt()) { strip { moveBy(Offset(0f, -step.toPx())) } }
        val upward = seen.toList()
        strip { up() }
        return downward to upward
    }

    private fun check(height: Dp) {
        val (downward, upward) = sweep(height)
        val upLetters = upward.filter { it.startsWith("letter:") }
        val upBackwards = upward.zipWithNext().filter { (a, b) -> order.indexOf(b) >= order.indexOf(a) }
        assertEquals("down: every item once, in order", order, downward)
        assertEquals("up: every letter once, in reverse", letters.reversed().map { "letter:$it" }, upLetters)
        assertEquals("up: nothing visited going the wrong way", emptyList<Pair<String, String>>(), upBackwards)
    }

    @Test fun sweep520() = check(520.dp)

    @Test fun sweep650() = check(650.dp)

    @Test fun sweep732() = check(732.dp)
}
