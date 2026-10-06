package com.gh00ul.cascade.screenshots

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
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

    /** Scrolled to the list: full scrim, section letters, and the strip at rest on the end edge. */
    @Test fun alphabetWaveIdle() = snap("AlphabetWave_Idle") {
        HomeScreen(settings, apps, favorites, icons, FakeNotifications.byApp(), firstItem = FIRST_APP_ROW)
    }
}
