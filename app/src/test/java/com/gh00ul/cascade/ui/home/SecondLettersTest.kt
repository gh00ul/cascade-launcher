package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.screenshots.FIRST_APP_ROW
import com.gh00ul.cascade.screenshots.HomeScreen
import com.gh00ul.cascade.screenshots.LauncherSurface
import com.gh00ul.cascade.screenshots.ScreenshotTest
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which second letters each strip letter offers, and where in the A–Z list each one jumps. */
class LetterPrefixesTest {
    private fun prefixes(vararg apps: Pair<String, String>) =
        letterPrefixes(apps.mapIndexed { i, (section, label) -> PrefixSource(section, label, row = 10 + i) })

    @Test fun eachLettersSecondLettersInListOrder() {
        val found = prefixes(
            "M" to "Mail", "M" to "Maps", "M" to "Messages", "M" to "Music",
            "P" to "Phone", "P" to "Photos", "P" to "Podcasts",
        )
        assertEquals(listOf(LetterPrefix("Ma", 10), LetterPrefix("Me", 12), LetterPrefix("Mu", 13)), found["M"])
        assertEquals(listOf(LetterPrefix("Ph", 14), LetterPrefix("Po", 16)), found["P"])
    }

    @Test fun caseAndAccentsCountAsThePlainLetter() {
        assertEquals(listOf("Eb", "Ec", "Em"), prefixes("E" to "eBay", "E" to "Écoute", "E" to "Email")["E"]?.map { it.text })
    }

    /** "#" (digits, symbols), a one-letter name and a space after the first letter offer nothing to pick. */
    @Test fun onlyNamesThatSpellTheirLetterCount() {
        val found = prefixes("#" to "1Password", "#" to "7-Eleven", "M" to "M Bank", "M" to "Mail", "X" to "X")
        assertNull(found["#"])
        assertEquals(listOf("Ma"), found["M"]?.map { it.text })
        assertNull(found["X"])
    }
}

/**
 * The strip with Second letters on, in the harness's A–Z list: hold C, slide toward the list, and moving up and down
 * picks among Ca, Cl and Co, the list jumping to each; slide back and the letters are picked again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
class SecondLettersTest {
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

    private val list = LazyListState(firstVisibleItemIndex = FIRST_APP_ROW)

    private fun show(secondLetters: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(
                            settings = LauncherSettings(favorites = FakeApps.favorites.map { it.key }, secondLetters = secondLetters),
                            apps = FakeApps.all,
                            favorites = FakeApps.favorites,
                            icons = emptyMap(),
                            listState = list,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The row the list jumped to, as its key: "app:…" or "section:…". */
    private fun top() = list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }.key
    private fun app(label: String): AppEntry = FakeApps.byLabel(label)

    private fun strip(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithContentDescription("Alphabet index").performTouchInput(block)
        compose.waitForIdle()
    }

    /** Where [letter] sits on the strip, laid out as the strip lays its letters out: centered, slots of 22dp at most. */
    private fun TouchInjectionScope.letter(letter: String): Offset {
        val letters = FakeApps.all.map { it.section }.distinct()
        val slot = minOf(height.toFloat() / letters.size, 22.dp.toPx())
        val top = (height - slot * letters.size) / 2f
        return Offset(centerX, top + slot * (letters.indexOf(letter) + 0.5f))
    }

    /** Small steps, as a finger moves: the strip follows every event. */
    private fun TouchInjectionScope.slide(dx: Float, dy: Float, steps: Int = 12) = repeat(steps) { moveBy(Offset(dx / steps, dy / steps)) }

    @Test fun slidingTowardTheListPicksSecondLetters() {
        show(secondLetters = true)
        // C, early in the list, so each jump has room to bring its row to the top.
        strip { down(letter("C")) }
        assertEquals("section:C", top())
        // Toward the list: the column (Ca, Cl, Co) is centered on the finger, so it lands on Cl.
        strip { slide(-110.dp.toPx(), 0f) }
        assertEquals("app:${app("Clock").key}", top())
        strip { slide(0f, 44.dp.toPx()) }
        assertEquals("app:${app("Contacts").key}", top())
        strip { slide(0f, -88.dp.toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        // Back on the strip, level with C again: the letters are picked as before, from C's top.
        strip { slide(110.dp.toPx(), 44.dp.toPx()) }
        assertEquals("section:C", top())
        strip { up() }
    }

    /** Off (the default), sliding toward the list changes nothing: the letter under the finger stays picked. */
    @Test fun offTheStripOnlyPicksLetters() {
        show(secondLetters = false)
        strip { down(letter("C")) }
        strip { slide(-110.dp.toPx(), 0f) }
        assertEquals("section:C", top())
        strip { up() }
    }
}
