package com.gh00ul.cascade.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.Contact
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.ContactResult
import com.gh00ul.cascade.ui.home.ContactRow
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import org.junit.Test

/** The A–Z list with the alphabet strip, and search over it. */
class ListScreenshots : ScreenshotTest() {
    private val settings get() = LauncherSettings(favorites = favorites.map { it.key })

    /** "ca": Camera, Calendar, Calculator (prefix, shortest first), then Podcasts and Authenticator (substring), then Contacts (fuzzy), then the web row. */
    @Test fun searchResults() = snap("Search_Results", afterContent = { onNode(hasSetTextAction()).performTextInput("ca") }) {
        HomeScreen(settings, apps, favorites, icons, searchOpen = true)
    }

    /** "10m": a timer to start, above the apps and tinted, since Go starts it. */
    @Test fun searchTimer() = snap("Search_Timer", afterContent = { onNode(hasSetTextAction()).performTextInput("10m") }) {
        HomeScreen(settings, apps, favorites, icons, searchOpen = true)
    }

    /** "nav pike place market": no app matches, so directions are what Go does. */
    @Test fun searchDirections() = snap("Search_Directions", afterContent = { onNode(hasSetTextAction()).performTextInput("nav pike place market") }) {
        HomeScreen(settings, apps, favorites, icons, searchOpen = true)
    }

    /** "hotspot": the Settings page for it. */
    @Test fun searchSettings() = snap("Search_Settings", afterContent = { onNode(hasSetTextAction()).performTextInput("hotspot") }) {
        HomeScreen(settings, apps, favorites, icons, searchOpen = true)
    }

    /** The top of the list: the search pill and the gear, then the first sections, letters on the icon column. */
    @Test fun top() = snap("List_Top") {
        HomeScreen(settings, apps, favorites, icons, FakeNotifications.byApp(), firstItem = 1)
    }

    /** Scrolled to the list: full scrim, section letters, and the strip at rest on the end edge. */
    @Test fun alphabetWaveIdle() = snap("AlphabetWave_Idle") {
        HomeScreen(settings, apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW)
    }

    /**
     * A finger held on M: the letters around it swell toward the list over the glow, M takes the accent, and the list
     * has jumped to M. The capture waits for the wave to settle with the finger still down.
     */
    @Test fun alphabetWaveDragging() = snap(
        "AlphabetWave_Dragging",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                // The finger held for the variant before is still down on this root: lift it, or down() is refused.
                if (currentPosition() != null) up()
                // 16 letters in 22dp slots: the strip's middle falls between K and L, and M's middle is a slot and a half below.
                down(center + Offset(0f, 33.dp.toPx()))
            }
        },
    ) {
        HomeScreen(settings, apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW)
    }

    /**
     * Second letters on: M held and the finger dragged on down two slots. M has opened up in the strip to Ma, Me and Mu
     * before N; Me, under the finger, swells and takes the accent, and the list has jumped to Messages.
     */
    @Test fun alphabetWaveSecondLetters() = snap(
        "AlphabetWave_SecondLetters",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                down(center + Offset(0f, 33.dp.toPx()))
                repeat(12) { moveBy(Offset(0f, 44.dp.toPx() / 12)) }
            }
        },
    ) {
        HomeScreen(settings.copy(secondLetters = true), apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW)
    }

    /**
     * App names on: M held and dragged on four slots. M has opened up in the strip to its apps, whole (Mail, the work
     * Mail, Maps, Messages, Music), ending at the strip's edge; Messages, under the finger, takes the accent.
     */
    @Test fun alphabetWaveAppNames() = snap(
        "AlphabetWave_AppNames",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                down(center + Offset(0f, 33.dp.toPx()))
                // To the first app (half a letter's slot and half an app's), then three apps' slots on.
                val distance = (22.dp.toPx() + 22.dp.toPx() * 1.3f) / 2 + 22.dp.toPx() * 1.3f * 3
                repeat(16) { moveBy(Offset(0f, distance / 16)) }
            }
        },
    ) {
        HomeScreen(settings.copy(stripApps = true), apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW)
    }

    // At One UI's largest font size (see LargeFont): the same screens, nothing cut short or overlapping.

    /** "ca" at 2x: the pill, the rows, the Settings page's two lines and the web row. */
    @Test fun searchResultsLargeFont() = snap("Search_Results_Font2x", afterContent = { onNode(hasSetTextAction()).performTextInput("ca") }) {
        LargeFont { HomeScreen(settings, apps, favorites, icons, searchOpen = true) }
    }

    /** "10m" at 2x: the timer's row, title over detail, above the apps. */
    @Test fun searchTimerLargeFont() = snap("Search_Timer_Font2x", afterContent = { onNode(hasSetTextAction()).performTextInput("10m") }) {
        LargeFont { HomeScreen(settings, apps, favorites, icons, searchOpen = true) }
    }

    /** "24*7" at 2x: the calculator's answer over its question. */
    @Test fun searchCalculationLargeFont() = snap("Search_Calculation_Font2x", afterContent = { onNode(hasSetTextAction()).performTextInput("24*7") }) {
        LargeFont { HomeScreen(settings, apps, favorites, icons, searchOpen = true) }
    }

    /**
     * Contacts as search lists them, drawn alone on search's scrim (search reaches them through the contacts provider):
     * a long name ends before Message and Call, and one without a number has neither. At the default size and at 2x.
     */
    @Test fun searchContacts() = snap("Search_Contacts", Frame.Component) { Contacts() }

    @Test fun searchContactsLargeFont() = snap("Search_Contacts_Font2x", Frame.Component) { LargeFont { Contacts() } }

    @Composable
    private fun Contacts() {
        val style = LocalLauncherStyle.current
        val iconSize = settings.iconSize.listDp.dp
        Column(Modifier.fillMaxWidth().drawBehind { drawRect(style.scrim, alpha = 0.94f) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
            for (contact in listOf(
                Contact(1, null, "Alexandra Montgomery-Whitfield", null, "+1 555 0100"),
                Contact(2, null, "Sam Ortiz", null, null),
            )) {
                ContactRow(ContactResult(contact, null), showIcon = true, iconSize = iconSize, onOpen = {}, onMessage = {}, onCall = {})
            }
        }
    }

    /** The top of the list at 2x: the search pill and the gear, section letters and rows. */
    @Test fun topLargeFont() = snap("List_Top_Font2x") {
        LargeFont { HomeScreen(settings, apps, favorites, icons, FakeNotifications.byApp(), firstItem = 1) }
    }

    /** As AlphabetWave_SecondLetters, at 2x: Me swells no more than its slot leaves room for. */
    @Test fun alphabetWaveSecondLettersLargeFont() = snap(
        "AlphabetWave_SecondLetters_Font2x",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                down(center + Offset(0f, 33.dp.toPx()))
                repeat(12) { moveBy(Offset(0f, 44.dp.toPx() / 12)) }
            }
        },
    ) {
        LargeFont { HomeScreen(settings.copy(secondLetters = true), apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW) }
    }

    /** As AlphabetWave_AppNames, at 2x: Messages' pill fits its slot, and no name reaches the screen's far edge. */
    @Test fun alphabetWaveAppNamesLargeFont() = snap(
        "AlphabetWave_AppNames_Font2x",
        afterContent = {
            onNodeWithContentDescription("Alphabet index").performTouchInput {
                if (currentPosition() != null) up()
                down(center + Offset(0f, 33.dp.toPx()))
                val distance = (22.dp.toPx() + 22.dp.toPx() * 1.3f) / 2 + 22.dp.toPx() * 1.3f * 3
                repeat(16) { moveBy(Offset(0f, distance / 16)) }
            }
        },
    ) {
        LargeFont { HomeScreen(settings.copy(stripApps = true), apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW) }
    }
}

/**
 * [content] at One UI's largest font size, 2x, scaled the way Android 14 and later scale it (nonlinearly: large text
 * grows less than small), as a phone set to it would draw it. Only sp change; the canvas and dp stay as they are, so
 * touches land where they do at the default size. Pop-ups in a window of their own (dialogs) don't see it.
 */
@Composable
internal fun LargeFont(content: @Composable () -> Unit) = FontScale(2f, content)
