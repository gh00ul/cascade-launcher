package com.gh00ul.cascade.data

import android.app.Application
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchTest {
    private fun app(label: String, originalLabel: String = label) =
        FakeApps.app(label, "com.example." + label.lowercase().filter { it.isLetter() }, originalLabel = originalLabel)

    private fun labels(apps: List<AppEntry>, query: String) = searchApps(apps, query).map { it.label }

    @Test fun normalizesAccentsAndStrokedLetters() {
        assertEquals("cafe", "Café".normalizedForSearch())
        assertEquals("cai dat", "Cài đặt".normalizedForSearch())
        assertEquals("strasse", "Straße".normalizedForSearch())
    }

    @Test fun accentedLabelMatchesPlainQuery() {
        assertEquals(listOf("Cài đặt"), labels(listOf(app("Cài đặt"), app("Phone")), "cai d"))
    }

    @Test fun ranksPrefixThenWordStartThenInitialsThenSubstringThenFuzzy() {
        val apps = listOf(
            app("Rogue Maps"), // fuzzy: g...m
            app("Phone"), // no match
            app("Google Maps"), // initials
            app("Gmail"), // prefix
            app("Pro Gmail Client"), // word start
            app("Dogma"), // substring
        )
        assertEquals(listOf("Gmail", "Pro Gmail Client", "Google Maps", "Dogma", "Rogue Maps"), labels(apps, "gm"))
    }

    @Test fun shorterLabelWinsATie() {
        assertEquals(listOf("Camera", "Calendar", "Calculator"), labels(listOf(app("Calculator"), app("Calendar"), app("Camera")), "ca"))
    }

    @Test fun blankQueryFindsNothing() {
        assertTrue(searchApps(FakeApps.all, "  ").isEmpty())
        assertTrue(searchApps(FakeApps.all, "").isEmpty())
    }

    @Test fun renamedAppStillMatchesItsOriginalLabel() {
        val renamed = app("Chat", originalLabel = "Messages")
        assertEquals(listOf("Chat"), labels(listOf(renamed, app("Phone")), "mess"))
        assertEquals(listOf("Chat"), labels(listOf(renamed, app("Phone")), "chat"))
    }

    @Test fun singleLetterNeedsMoreThanAFuzzyMatch() {
        // One letter is a prefix, word start or substring match, never a fuzzy one.
        assertEquals(listOf("Maps", "Camera"), labels(listOf(app("Maps"), app("Camera"), app("Phone")), "m"))
    }

    @Test fun typingIntoTheSameListFindsWhatAFreshSearchFinds() {
        val apps = listOf(
            app("Rogue Maps"), app("Phone"), app("Google Maps"), app("Gmail"), app("Pro Gmail Client"), app("Dogma"),
            app("Cài đặt"), app("Chat", originalLabel = "Messages"), app("Calculator"), app("Camera"),
        )
        val queries = listOf("g", "gm", "gma", "gmai", "m", "ma", "map", "mess", "c", "ca", "cai d", "  CA ")
        // A copy is a new list, so each of these normalizes the labels anew.
        val fresh = queries.map { labels(apps.toList(), it) }
        // Typing: every keystroke searches the same list, reusing the labels normalized for the first one.
        assertEquals(fresh, queries.map { labels(apps, it) })
        assertEquals(listOf("Gmail", "Pro Gmail Client", "Google Maps", "Dogma", "Rogue Maps"), fresh[1])
        assertEquals(listOf("Chat"), fresh[7])
    }

    @Test fun newListIsNotServedTheLastListsLabels() {
        val before = listOf(app("Phone"), app("Maps"))
        assertEquals(emptyList<String>(), labels(before, "ch"))
        // Renaming Phone publishes a new list of the same size; its labels must be read, not the last list's.
        val after = listOf(app("Chat", originalLabel = "Phone"), app("Maps"))
        assertEquals(listOf("Chat"), labels(after, "ch"))
        assertEquals(listOf("Chat"), labels(after, "ph"))
        assertEquals(listOf("Phone"), labels(before, "ph"))
        assertEquals(emptyList<String>(), labels(before, "ch"))
    }
}
