package com.gh00ul.cascade.settings

import androidx.compose.runtime.Composable
import com.gh00ul.cascade.data.LauncherSettings

/** The widget stack: what's in it, in what order, and adding more. (Being built.) */
@Composable
internal fun WidgetsPage(settings: LauncherSettings, nav: SettingsNav) {
    SettingsPage(title = SettingsScreen.WIDGETS.title, onBack = nav.back) {
        PageText("${settings.widgetStack.size} widgets")
    }
}
