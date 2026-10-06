package com.gh00ul.cascade.settings

import java.text.Normalizer
import java.util.Locale

/**
 * A setting as search finds it: its title, the page it's on, the row's highlight [key] (null for a whole page), and
 * other words people might type for it.
 */
internal class SettingEntry(val title: String, val screen: SettingsScreen, val key: String? = null, val keywords: String = "")

/** Every setting, in page order. Keys match the `key` of the row that shows it. */
internal val SettingsIndex = listOf(
    SettingEntry("Favorites", SettingsScreen.FAVORITES, keywords = "home apps reorder order add remove pin drag"),
    SettingEntry("Hidden apps", SettingsScreen.HIDDEN, keywords = "hide unhide show app list"),
    SettingEntry("Renamed apps", SettingsScreen.RENAMED, keywords = "rename name label reset"),
    SettingEntry("Widgets", SettingsScreen.WIDGETS, keywords = "widget stack calendar agenda weather forecast app widgets"),
    SettingEntry("Folders", SettingsScreen.FAVORITES, keywords = "folder group pop-up popup organize"),
    SettingEntry("Notification previews", SettingsScreen.HOME, "previews", "notifications messages dot latest"),
    SettingEntry("Copy login codes", SettingsScreen.HOME, "codes", "verification code otp 2fa one-time password sms clipboard paste"),
    SettingEntry("Music player", SettingsScreen.HOME, "media", "music media controls player song playing"),
    SettingEntry("Glow with the music", SettingsScreen.HOME, "musicGlow", "album art color colour tint playing"),
    SettingEntry("Resume with headphones", SettingsScreen.HOME, "resume", "listen mode bluetooth earbuds spotify music continue"),

    SettingEntry("Text color", SettingsScreen.LOOK, "textColor", "white dark light contrast automatic readable"),
    SettingEntry("Wallpaper dimming", SettingsScreen.LOOK, "dim", "darken dim scrim readability readable background"),
    SettingEntry("App icons", SettingsScreen.LOOK, "showIcons", "icons hide show text only"),
    SettingEntry("Icon size", SettingsScreen.LOOK, "iconSize", "small medium large xl bigger smaller"),
    SettingEntry("Monochrome icons", SettingsScreen.LOOK, "monochrome", "themed grayscale greyscale black white material you"),
    SettingEntry("Hide status bar", SettingsScreen.LOOK, "statusBar", "status bar fullscreen immersive notch"),
    SettingEntry("Wallpaper", SettingsScreen.LOOK, "wallpaper", "background picker image photo"),

    SettingEntry("Clock style", SettingsScreen.CLOCK, "clockStyle", "classic bold stacked font time big"),
    SettingEntry("Time format", SettingsScreen.CLOCK, "timeFormat", "12 24 hour am pm military"),
    SettingEntry("Date", SettingsScreen.CLOCK, "date", "day month weekday"),
    SettingEntry("Weather", SettingsScreen.CLOCK, "weather", "temperature forecast sunny rain"),
    SettingEntry("Weather location", SettingsScreen.CLOCK, "weatherPlace", "city place town location"),
    SettingEntry("Temperature units", SettingsScreen.CLOCK, "units", "celsius fahrenheit degrees"),
    SettingEntry("Next alarm", SettingsScreen.CLOCK, "alarm", "wake up alarms"),
    SettingEntry("Timers and stopwatches", SettingsScreen.CLOCK, "timers", "stopwatch call countdown running"),
    SettingEntry("Live activity", SettingsScreen.CLOCK, "live", "live updates ride delivery uber navigation maps route download progress"),
    SettingEntry("Next calendar event", SettingsScreen.CLOCK, "calendar", "events agenda meeting appointment"),
    SettingEntry("Battery", SettingsScreen.CLOCK, "battery", "charging low power percent"),

    SettingEntry("Swipe down", SettingsScreen.GESTURES, "swipeDown", "pull notifications quick settings shade gesture"),
    SettingEntry("Double-tap", SettingsScreen.GESTURES, "doubleTap", "double tap lock screen sleep turn off gesture"),
    SettingEntry("Vibration", SettingsScreen.GESTURES, "haptics", "haptic haptics feedback vibrate touch"),

    SettingEntry("Search the web", SettingsScreen.SEARCH, "searchWeb", "internet google browser web"),
    SettingEntry("Hidden apps in search", SettingsScreen.SEARCH, "hiddenInSearch", "hidden find"),
    SettingEntry("Open single match", SettingsScreen.SEARCH, "autoLaunch", "launch automatically instant open"),
    SettingEntry("Calculator", SettingsScreen.SEARCH, "calculator", "math calculate sum arithmetic percent"),
    SettingEntry("Contacts in search", SettingsScreen.SEARCH, "contacts", "people call text message phone number"),
    SettingEntry("Commands", SettingsScreen.SEARCH, "commands", "timer alarm directions navigate youtube play song quick actions"),
    SettingEntry("Shortcuts and settings in search", SettingsScreen.SEARCH, "shortcuts", "app shortcuts incognito settings pages wifi hotspot bluetooth"),

    SettingEntry("Back up settings", SettingsScreen.BACKUP, "backup", "export save file copy"),
    SettingEntry("Restore settings", SettingsScreen.BACKUP, "restore", "import load file new phone"),

    SettingEntry("Check for updates", SettingsScreen.ABOUT, "update", "version upgrade new release"),
    SettingEntry("Automatic update checks", SettingsScreen.ABOUT, "autoUpdate", "github updates"),
    SettingEntry("Default home app", SettingsScreen.ABOUT, "defaultHome", "launcher home default set"),
    SettingEntry("Notification access", SettingsScreen.ABOUT, "notificationAccess", "permission listener dots"),
    SettingEntry("Source code", SettingsScreen.ABOUT, "source", "github open source license mit"),
)

/**
 * Settings whose words start with every word of [query] (title, page or keywords; accents and case ignored), best
 * first: a title starting with the query, then a title word, then the rest in page order.
 */
internal fun findSettings(query: String, index: List<SettingEntry> = SettingsIndex): List<SettingEntry> {
    val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    val whole = terms.joinToString(" ")
    return index
        .mapNotNull { entry ->
            val title = normalize(entry.title)
            val words = (title + " " + normalize(entry.screen.title) + " " + normalize(entry.keywords)).split(' ')
            if (!terms.all { term -> words.any { it.startsWith(term) } }) return@mapNotNull null
            val score = when {
                title.startsWith(whole) -> 3
                title.split(' ').any { it.startsWith(terms[0]) } -> 2
                else -> 1
            }
            entry to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

private val Marks = Regex("\\p{Mn}+")
private val NotWord = Regex("[^\\p{L}\\p{N}]+")

private fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").lowercase(Locale.ROOT).replace(NotWord, " ").trim()
