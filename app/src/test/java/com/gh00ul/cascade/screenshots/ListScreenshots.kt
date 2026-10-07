package com.gh00ul.cascade.screenshots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.FakeNotifications
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
}
