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
import androidx.compose.ui.unit.Dp
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

    /** A finger resting a moment, then lifting: a deliberate lift, as opposed to one still on the move. */
    private fun TouchInjectionScope.rest() = advanceEventTime(150)

    /**
     * Moves the finger half a dp at a time, [down] the strip or up it, until the list has jumped to [target] (or [max]
     * has been covered): every row the list jumped to on the way, in order, starting with where it was.
     */
    private fun walkTo(target: String, down: Boolean = true, max: Dp = 300.dp): List<String> {
        val seen = mutableListOf(top().toString())
        var moved = 0.dp
        while (seen.last() != target && moved < max) {
            strip { moveBy(Offset(0f, (if (down) 0.5.dp else (-0.5).dp).toPx())) }
            moved += 0.5.dp
            if (top().toString() != seen.last()) seen += top().toString()
        }
        return seen
    }

    private fun key(label: String) = "app:${app(label).key}"

    /** Small steps, as a finger moves: the strip follows every event. */
    private fun TouchInjectionScope.slide(dx: Float, dy: Float, steps: Int = 12) = repeat(steps) { moveBy(Offset(dx / steps, dy / steps)) }

    @Test fun draggingThroughALetterScrollsItsSecondLetters() {
        show(secondLetters = true)
        // C, early in the list, so each jump has room to bring its row to the top. It opens where it is, under the finger.
        strip { down(letter("C")) }
        assertEquals("section:C", top())
        // On down the strip: Ca, Cl and Co, each a slot of its own, then D (only Docs, so nothing to open).
        assertEquals(listOf("section:C", key("Calculator"), key("Clock"), key("Contacts"), "section:D"), walkTo("section:D"))
        // Back up, having overshot onto D: C opens again with its last, Co, under the finger, then its others, then C.
        assertEquals(listOf("section:D", key("Contacts"), key("Clock"), key("Calculator"), "section:C"), walkTo("section:C", down = false))
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
     * just below, and the strip follows the finger one to one. Held past the strip's end, the strip scrolls in the rest,
     * on through T to Z, picking as it goes, and stops at the last app, which letting go opens.
     */
    @Test fun aCrowdedLetterOpensUnderTheFinger() {
        show(stripApps = true, apps = FakeApps.crowded)
        val letters = apps.map { it.section }.distinct()
        val sApps = apps.count { it.section == "S" }
        var slot = 0f
        strip {
            down(letter("S"))
            // S's layout once open: slots shrunk to fit it all, S kept where it was.
            slot = minOf(height / (letters.size + sApps * 1.3f), 22.dp.toPx())
        }
        assertEquals("section:S", top())
        // A finger settling doesn't leave S (it used to land halfway down S's apps, which had moved under it).
        strip { slide(0f, slot / 4) }
        assertEquals("section:S", top())
        // From S's middle: to its first app, then its second, each where it's drawn.
        strip { slide(0f, slot / 2 + slot * 1.3f / 2 - slot / 4) }
        assertEquals("app:${app("Samsung Free").key}", top())
        strip { slide(0f, slot * 1.3f) }
        assertEquals("app:${app("Samsung Health").key}", top())
        // Past the strip's end and held: it scrolls to the very last app, Zoom. Lifting out there opens nothing;
        // back on the strip, on Zoom at its end, a lift opens it.
        strip { slide(0f, height.toFloat()) }
        strip { rest(); up() }
        assertEquals(emptyList<AppEntry>(), opened)
        strip { down(letter("S")) }
        strip { slide(0f, height.toFloat()) }
        strip { moveTo(Offset(centerX, height - 4.dp.toPx())) }
        strip { rest(); up() }
        assertEquals(listOf(app("Zoom")), opened)
    }

    /**
     * Slowly down off a letter's last app onto the next letter, whose apps outnumber it (F's two, then G's twelve): G's
     * apps shrink every slot as they open, and the finger stays on G, then goes on to its first app. (The finger used to
     * land just above G's shrunk slot, back on F, which opened again, so it went round F's apps and never got past.)
     */
    @Test fun slowlyOffALettersLastAppReachesTheNext() {
        show(stripApps = true, apps = FakeApps.crowded)
        strip { down(letter("F")) }
        strip { slide(0f, ((22.dp + 22.dp * 1.3f) / 2).toPx()) }
        // Half a dp at a time, noting each row the list jumps to.
        val seen = mutableListOf(top())
        repeat(160) {
            strip { moveBy(Offset(0f, 0.5.dp.toPx())) }
            if (top() != seen.last()) seen += top()
        }
        val expected = listOf("app:${app("Facebook").key}", "app:${app("Files").key}", "section:G", "app:${app("Galaxy Store").key}")
        assertEquals(expected, seen.take(4))
        strip { up() }
    }

    /**
     * Past the last letter's last app (W's Weather) and off the strip's end: there's nothing after it, so the finger stays
     * on Weather (past the last letter's apps used to throw, taking the launcher down). Letting go out there, well past
     * the end, opens nothing: sliding off the end is a way to call it off.
     */
    @Test fun pastTheLastLettersAppsStaysOnTheLast() {
        show(stripApps = true)
        strip { down(letter("W")) }
        strip { slide(0f, 300.dp.toPx(), steps = 30) }
        strip { rest(); up() }
        assertEquals(emptyList<AppEntry>(), opened)
    }

    /** App names: letting go on an app's name opens that app; letting go on a letter opens nothing. */
    @Test fun withAppNamesLettingGoOnAnAppOpensIt() {
        show(stripApps = true)
        strip { down(letter("C")) }
        assertEquals(listOf("section:C", key("Calculator"), key("Calendar")), walkTo(key("Calendar")))
        assertEquals(emptyList<AppEntry>(), opened)
        strip { rest(); up() }
        assertEquals(listOf(app("Calendar")), opened)
        // C again, down past its apps onto D, and let go there: the list stays at D and nothing opens.
        strip { down(letter("C")) }
        walkTo("section:D")
        strip { rest(); up() }
        assertEquals(listOf(app("Calendar")), opened)
    }

    /**
     * App names: the lifts that open nothing. A swipe that only passes over the strip (a flick, lifting on the move), a tap
     * (even low on a letter, a short roll from its first app), and a finger slid off sideways onto the list.
     */
    @Test fun withAppNamesStrayLiftsOpenNothing() {
        show(stripApps = true)
        // A flick down from C, lifting as it goes.
        strip {
            down(letter("C"))
            slide(0f, 100.dp.toPx(), steps = 5)
            up()
        }
        // A tap low on C, rolling a few dp as it lifts.
        strip { down(letter("C") + Offset(0f, 8.dp.toPx())) }
        strip { slide(0f, 4.dp.toPx(), steps = 2) }
        strip { rest(); up() }
        // Onto Calendar, then off sideways over the list.
        strip { down(letter("C")) }
        walkTo(key("Calendar"))
        strip { slide(-200.dp.toPx(), 0f) }
        strip { rest(); up() }
        assertEquals(emptyList<AppEntry>(), opened)
    }

    /** App names: overshooting C's last app onto D and coming back lands on that last app, Contacts, not on C. */
    @Test fun withAppNamesAnOvershootComesBackToTheLastApp() {
        show(stripApps = true)
        strip { down(letter("C")) }
        walkTo("section:D")
        assertEquals(listOf("section:D", key("Contacts")), walkTo(key("Contacts"), down = false))
        strip { rest(); up() }
        assertEquals(listOf(app("Contacts")), opened)
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
