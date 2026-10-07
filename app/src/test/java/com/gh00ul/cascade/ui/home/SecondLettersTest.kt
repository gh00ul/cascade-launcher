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

    /** App names: every app of the letter, in list order, whole. */
    @Test fun appNamesAreEveryAppInListOrder() {
        val found = letterApps(listOf(PrefixSource("M", "Mail", 10), PrefixSource("M", "Mail (work)", 11), PrefixSource("M", "Maps", 12), PrefixSource("N", "News", 13)))
        assertEquals(listOf(LetterPrefix("Mail", 10), LetterPrefix("Mail (work)", 11), LetterPrefix("Maps", 12)), found["M"])
        assertEquals(listOf(LetterPrefix("News", 13)), found["N"])
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
 * The strip with Second letters on, in the harness's A–Z list: the letter under the finger opens up in the strip to its
 * second letters, and dragging on through them jumps the list to each; the next letter folds them back.
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

    private var apps = FakeApps.all
    /** Apps the strip opened, in order. */
    private val opened = mutableListOf<AppEntry>()

    private fun show(secondLetters: Boolean = false, stripApps: Boolean = false, apps: List<AppEntry> = FakeApps.all) {
        this.apps = apps
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
                LauncherTheme {
                    LauncherSurface(darkText = false, Modifier.fillMaxSize()) {
                        HomeScreen(
                            settings = LauncherSettings(favorites = FakeApps.favorites.map { it.key }, secondLetters = secondLetters, stripApps = stripApps),
                            apps = apps,
                            favorites = FakeApps.favorites,
                            icons = emptyMap(),
                            listState = list,
                            onLaunch = { opened += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The row the list jumped to, as its key: "app:…" or "section:…". */
    private fun top() = list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }.key
    private fun app(label: String): AppEntry = apps.first { it.label == label && !it.isWork }

    private fun strip(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithContentDescription("Alphabet index").performTouchInput(block)
        compose.waitForIdle()
    }

    /** Where [letter] sits on the strip, laid out as the strip lays its letters out: centered, slots of 22dp at most. */
    private fun TouchInjectionScope.letter(letter: String): Offset {
        val letters = apps.map { it.section }.distinct()
        val slot = minOf(height.toFloat() / letters.size, 22.dp.toPx())
        val top = (height - slot * letters.size) / 2f
        return Offset(centerX, top + slot * (letters.indexOf(letter) + 0.5f))
    }

    /** Small steps, as a finger moves: the strip follows every event. */
    private fun TouchInjectionScope.slide(dx: Float, dy: Float, steps: Int = 12) = repeat(steps) { moveBy(Offset(dx / steps, dy / steps)) }

    @Test fun draggingThroughALetterScrollsItsSecondLetters() {
        show(secondLetters = true)
        // Letters' slots are 22dp here, second letters' 1.3 times that: half of each from one center to the next.
        val letter = 22.dp
        val option = letter * 1.3f
        val toFirst = (letter + option) / 2
        // C, early in the list, so each jump has room to bring its row to the top. It opens where it is, under the finger.
        strip { down(letter("C")) }
        assertEquals("section:C", top())
        // On down the strip: Ca, Cl and Co, each a slot of its own, before D.
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        strip { slide(0f, option.toPx()) }
        assertEquals("app:${app("Clock").key}", top())
        strip { slide(0f, option.toPx()) }
        assertEquals("app:${app("Contacts").key}", top())
        // D (only Docs, so nothing to open): C folds back and D stays under the finger.
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("section:D", top())
        // Back up a letter: C, just above D again, opens once more, with its second letters below it.
        strip { slide(0f, -letter.toPx()) }
        assertEquals("section:C", top())
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        strip { up() }
    }

    /** App names: C opens up to its apps, whole, each a slot of its own; even D, with one app, opens to it. */
    @Test fun withAppNamesALetterOpensToItsApps() {
        show(stripApps = true)
        val letter = 22.dp
        val option = letter * 1.3f
        val toFirst = (letter + option) / 2
        strip { down(letter("C")) }
        assertEquals("section:C", top())
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        strip { slide(0f, option.toPx()) }
        assertEquals("app:${app("Calendar").key}", top())
        strip { slide(0f, option.toPx() * 2) }
        assertEquals("app:${app("Clock").key}", top())
        strip { slide(0f, option.toPx()) }
        assertEquals("app:${app("Contacts").key}", top())
        // On to D, which opens to Docs below it.
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("section:D", top())
        strip { slide(0f, toFirst.toPx()) }
        assertEquals("app:${app("Docs").key}", top())
        strip { up() }
    }

    /**
     * A letter with more apps than fit below it on the strip (S, with 16) still opens under the finger, its first app
     * just below; the strip then scrolls up past the finger as it moves, so the last app and the letters after it come
     * within reach.
     */
    @Test fun aCrowdedLetterOpensUnderTheFinger() {
        show(stripApps = true, apps = FakeApps.crowded)
        val letters = apps.map { it.section }.distinct()
        val opened = apps.count { it.section == "S" }
        var slot = 0f
        var below = 0f
        strip {
            val at = letter("S")
            down(at)
            // S's layout once open: slots shrunk to fit it all, S kept where it was, and how fast the rest scrolls past.
            slot = minOf(height / (letters.size + opened * 1.3f), 22.dp.toPx())
            val total = slot * letters.size + opened * slot * 1.3f
            below = maxOf(1f, (total - slot * (letters.indexOf("S") + 0.5f)) / (height - at.y))
        }
        assertEquals("section:S", top())
        // A finger settling doesn't leave S (it used to land halfway down S's apps, which had moved under it).
        strip { slide(0f, slot / 4) }
        assertEquals("section:S", top())
        // From S's middle: to its first app, its second, then its last, and on to T, each a scrolled slot on.
        val toFirst = (slot / 2 + slot * 1.3f / 2) / below - slot / 4
        strip { slide(0f, toFirst) }
        assertEquals("app:${app("Samsung Free").key}", top())
        strip { slide(0f, slot * 1.3f / below) }
        assertEquals("app:${app("Samsung Health").key}", top())
        strip { slide(0f, slot * 1.3f * (opened - 2) / below) }
        assertEquals("app:${app("Steam").key}", top())
        strip { slide(0f, (slot * 1.3f / 2 + slot / 2) / below) }
        assertEquals("section:T", top())
        strip { up() }
    }

    /** App names: letting go on an app's name opens that app; letting go on a letter opens nothing. */
    @Test fun withAppNamesLettingGoOnAnAppOpensIt() {
        show(stripApps = true)
        val letter = 22.dp
        val option = letter * 1.3f
        val toFirst = (letter + option) / 2
        // C to its second app, Calendar, and let go.
        strip { down(letter("C")) }
        strip { slide(0f, toFirst.toPx() + option.toPx()) }
        assertEquals("app:${app("Calendar").key}", top())
        assertEquals(emptyList<AppEntry>(), opened)
        strip { up() }
        assertEquals(listOf(app("Calendar")), opened)
        // C again, then down past its apps onto D, and let go there: the list stays at D and nothing opens.
        strip { down(letter("C")) }
        strip { slide(0f, toFirst.toPx() + option.toPx() * 4 + toFirst.toPx()) }
        assertEquals("section:D", top())
        strip { up() }
        assertEquals(listOf(app("Calendar")), opened)
    }

    /** A gesture the system takes over (a back swipe from this edge, say) ends in a cancel, not a lift: nothing opens. */
    @Test fun withAppNamesACancelledGestureOpensNothing() {
        show(stripApps = true)
        strip { down(letter("C")) }
        strip { slide(0f, ((22.dp + 22.dp * 1.3f) / 2).toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        strip { cancel() }
        assertEquals(emptyList<AppEntry>(), opened)
    }

    /** Second letters only jump the list: letting go on one opens nothing. */
    @Test fun withSecondLettersLettingGoOpensNothing() {
        show(secondLetters = true)
        strip { down(letter("C")) }
        strip { slide(0f, ((22.dp + 22.dp * 1.3f) / 2).toPx()) }
        assertEquals("app:${app("Calculator").key}", top())
        strip { up() }
        assertEquals(emptyList<AppEntry>(), opened)
    }

    /** Off (the default), the letters are all the strip has: one slot down from C is D. */
    @Test fun offTheStripOnlyHasLetters() {
        show(secondLetters = false)
        strip { down(letter("C")) }
        strip { slide(0f, 22.dp.toPx()) }
        assertEquals("section:D", top())
        strip { up() }
    }
}
