package com.gh00ul.cascade.data

import android.content.Intent
import android.provider.Settings

/**
 * A page of the phone's Settings search can open: "hotspot" finds Hotspot & tethering. [actions] are tried in order,
 * since some pages have no public action and phones differ; the last is always one every phone has.
 */
class SettingsPage(val title: String, val keywords: String, val actions: List<String>) {
    fun intents(): List<Intent> = actions.map { Intent(it) }
}

/** Pages people open often enough to search for, with what they might type instead of the title. */
val SettingsPages = listOf(
    SettingsPage("Wi-Fi", "wifi wireless network internet", listOf(Settings.ACTION_WIFI_SETTINGS)),
    SettingsPage("Bluetooth", "pair headphones earbuds speaker", listOf(Settings.ACTION_BLUETOOTH_SETTINGS)),
    SettingsPage("Hotspot & tethering", "hotspot tether share internet", listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)),
    SettingsPage("Mobile network", "cellular data sim roaming carrier", listOf(Settings.ACTION_NETWORK_OPERATOR_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
    SettingsPage("Airplane mode", "flight offline", listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS)),
    SettingsPage("Data usage", "mobile data limit", listOf(Settings.ACTION_DATA_USAGE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)),
    SettingsPage("NFC", "tap to pay contactless", listOf(Settings.ACTION_NFC_SETTINGS)),
    SettingsPage("VPN", "private network", listOf(Settings.ACTION_VPN_SETTINGS)),
    SettingsPage("Display", "brightness screen timeout dark theme font size", listOf(Settings.ACTION_DISPLAY_SETTINGS)),
    SettingsPage("Night Light", "blue light eye comfort", listOf("android.settings.NIGHT_DISPLAY_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS)),
    SettingsPage("Sound & vibration", "volume ringtone vibrate", listOf(Settings.ACTION_SOUND_SETTINGS)),
    SettingsPage("Do Not Disturb", "dnd silence quiet focus", listOf("android.settings.ZEN_MODE_SETTINGS", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)),
    SettingsPage("Notifications", "alerts badges", listOf(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, Settings.ACTION_SETTINGS)),
    SettingsPage("Battery", "power usage charge", listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)),
    SettingsPage("Battery Saver", "power saving low", listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
    SettingsPage("Storage", "space free up files", listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
    SettingsPage("Apps", "applications app info uninstall permissions", listOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)),
    SettingsPage("Default apps", "browser home launcher sms phone", listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
    SettingsPage("Location", "gps", listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
    SettingsPage("Security", "lock screen fingerprint face unlock pin password", listOf(Settings.ACTION_SECURITY_SETTINGS)),
    SettingsPage("Privacy", "permissions", listOf(Settings.ACTION_PRIVACY_SETTINGS)),
    SettingsPage("Accessibility", "talkback magnification", listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
    SettingsPage("Accounts", "sync google", listOf(Settings.ACTION_SYNC_SETTINGS)),
    SettingsPage("Date & time", "clock time zone", listOf(Settings.ACTION_DATE_SETTINGS)),
    SettingsPage("Languages", "language locale", listOf(Settings.ACTION_LOCALE_SETTINGS)),
    SettingsPage("Keyboard", "input gboard typing", listOf(Settings.ACTION_INPUT_METHOD_SETTINGS)),
    SettingsPage("Cast", "chromecast screen mirror", listOf(Settings.ACTION_CAST_SETTINGS)),
    SettingsPage("Developer options", "developer adb usb debugging", listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
    SettingsPage("System update", "software update android version", listOf("android.settings.SYSTEM_UPDATE_SETTINGS", Settings.ACTION_DEVICE_INFO_SETTINGS)),
    SettingsPage("About phone", "device info build number model", listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
)

/** A match only counts from a title's or keyword's start ("hot" finds Hotspot), not somewhere inside a word. */
private const val PAGE_MIN_SCORE = 60

/**
 * Settings pages matching [query], best first, at most [limit]: the title scored like an app's name, or (from three
 * letters, so two don't drag in every keyword) a keyword starting with what was typed. Nothing for a single letter.
 */
fun findSettingsPages(query: String, limit: Int = 2, pages: List<SettingsPage> = SettingsPages): List<SettingsPage> {
    val q = query.trim().normalizedForSearch()
    if (q.length < 2) return emptyList()
    return pages
        .mapNotNull { page ->
            val title = matchScore(page.title, q)
            val keyword = if (q.length >= 3 && page.keywords.split(' ').any { it.startsWith(q) }) PAGE_MIN_SCORE else 0
            maxOf(title, keyword).takeIf { it >= PAGE_MIN_SCORE }?.let { page to it }
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}
