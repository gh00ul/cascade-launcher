package com.gh00ul.cascade.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Swiping down on the home page while it's at the top. */
enum class SwipeDownAction { NOTIFICATIONS, QUICK_SETTINGS, SEARCH, OPEN_APP, NOTHING }

/**
 * Double-tapping empty space on the home page. LOCK_SCREEN needs Cascade's accessibility service (Android 9+);
 * OPEN_APP opens [LauncherSettings.doubleTapApp].
 */
enum class DoubleTapAction { NOTHING, LOCK_SCREEN, NOTIFICATIONS, SEARCH, OPEN_APP }

/** The clock and the times on its chips: follow the system setting, or force 12- or 24-hour. */
enum class TimeFormat { SYSTEM, H12, H24 }

/** A dark (or, with dark text, light) tint over the wallpaper on the home page, for text over busy wallpapers. */
enum class WallpaperDim(val alpha: Float) { OFF(0f), LOW(0.15f), MEDIUM(0.3f), HIGH(0.45f) }

/** Text over the wallpaper: follow the wallpaper's brightness, or force white / dark. */
enum class TextColor { AUTO, LIGHT, DARK }

/** Temperatures: by the region's convention (Fahrenheit in the US and a few others), or forced. */
enum class TempUnit { AUTO, CELSIUS, FAHRENHEIT }

/** Where the weather is for: a place picked in Settings, by name and coordinates. */
data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double)

/** A folder on the home screen: its name, and its apps' keys in order. */
data class Folder(val name: String, val apps: List<String>)

/**
 * A favorites entry that is a folder rather than an app: this prefix, then the folder's id, a key into
 * [LauncherSettings.folders].
 */
const val FOLDER_PREFIX = "folder:"

/** Widget stack entries besides Android app widgets ("app:<appWidgetId>"). */
const val WIDGET_CALENDAR = "calendar"
const val WIDGET_WEATHER = "weather"
const val WIDGET_APP_PREFIX = "app:"
const val MAX_STACK_WIDGETS = 4

/** Icon sizes in dp for the home screen favorites and the A–Z list. */
enum class IconSize(val homeDp: Int, val listDp: Int) {
    SMALL(32, 28),
    MEDIUM(40, 34),
    LARGE(48, 40),
    XL(56, 46),
}

enum class ClockStyle { CLASSIC, BOLD, STACKED }

data class LauncherSettings(
    /** App keys in the order they appear on the home screen. */
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val renames: Map<String, String> = emptyMap(),
    val showIcons: Boolean = true,
    val monochromeIcons: Boolean = false,
    val showNotificationPreviews: Boolean = true,
    val showMediaControls: Boolean = true,
    val textColor: TextColor = TextColor.AUTO,
    val iconSize: IconSize = IconSize.MEDIUM,
    val clockStyle: ClockStyle = ClockStyle.CLASSIC,
    /** Next calendar event under the clock; needs READ_CALENDAR. */
    val showCalendar: Boolean = false,
    /** Charging progress and low battery under the clock. */
    val showBattery: Boolean = true,
    /** Look for a newer GitHub release when home opens (at most every 6 hours). */
    val autoUpdateCheck: Boolean = true,
    val swipeDownAction: SwipeDownAction = SwipeDownAction.NOTIFICATIONS,
    val favoritesSeeded: Boolean = false,
    val notificationPromptDismissed: Boolean = false,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    /** The date line under the clock. */
    val showDate: Boolean = true,
    /** The next-alarm chip under the clock. */
    val showAlarm: Boolean = true,
    /** Running timers, stopwatches and calls (from notifications) as chips under the clock. */
    val showTimers: Boolean = true,
    val wallpaperDim: WallpaperDim = WallpaperDim.OFF,
    /** Hide the status bar on the home screen; a swipe from the top edge still shows it for a moment. */
    val hideStatusBar: Boolean = false,
    /** Vibration on long-press, swipes, the alphabet wave and the player's buttons. */
    val haptics: Boolean = true,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.NOTHING,
    /** The "Search the web" row in search (and Go searching the web when no app matches). */
    val searchWeb: Boolean = true,
    /** Hidden apps still show up in search results. */
    val hiddenInSearch: Boolean = true,
    /** Search opens the app by itself as soon as exactly one app matches what you typed. */
    val autoLaunchSingleMatch: Boolean = false,
    /** The battery chip shows all the time, not only while charging or low. */
    val batteryAlways: Boolean = false,
    /** Current weather beside the date, from Open-Meteo for [weatherPlace]. Off until a place is picked. */
    val showWeather: Boolean = false,
    val weatherPlace: WeatherPlace? = null,
    val tempUnit: TempUnit = TempUnit.AUTO,
    /** Listen mode: with headphones connected and nothing playing, offer to resume the app that played last. */
    val resumePrompt: Boolean = true,
    /** Folders by id; each sits in [favorites] as [FOLDER_PREFIX] + id. */
    val folders: Map<String, Folder> = emptyMap(),
    /** The widget stack on home, in swipe order, at most [MAX_STACK_WIDGETS]: [WIDGET_CALENDAR], [WIDGET_WEATHER] or [WIDGET_APP_PREFIX] + an app widget id. Empty: no stack. */
    val widgetStack: List<String> = emptyList(),
    /** Search answers arithmetic ("24*7", "20% of 85") inline. */
    val searchCalculator: Boolean = true,
    /** Search finds contacts too, to call or text; needs READ_CONTACTS. */
    val searchContacts: Boolean = false,
    /** The app (by key) swiping down opens, with [SwipeDownAction.OPEN_APP]. */
    val swipeDownApp: String? = null,
    /** The app (by key) a double-tap opens, with [DoubleTapAction.OPEN_APP]. */
    val doubleTapApp: String? = null,
    /** Things under way (rides, deliveries, a route, downloads) as chips under the clock, from their notifications. */
    val showLiveUpdates: Boolean = true,
    /** Search runs commands: a timer ("10m"), an alarm ("7:30"), directions ("nav home"), quick searches ("yt lofi"). */
    val searchCommands: Boolean = true,
    /** Search finds app shortcuts ("incognito") and Settings pages ("hotspot"). */
    val searchShortcuts: Boolean = true,
    /** While music plays, the wash behind the favorites takes on the album art's color. */
    val musicGlow: Boolean = true,
    /** A login code arriving in a notification goes straight to the clipboard. */
    val copyLoginCodes: Boolean = true,
    /** The letter under the finger on the strip opens up to its apps' second letters (Ma, Me, Mu) to scroll through. */
    val secondLetters: Boolean = false,
    /** The letter under the finger on the strip opens up to its apps by name instead (one or the other, never both). */
    val stripApps: Boolean = false,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = state.asStateFlow()

    /**
     * Changes the settings and stores them. One at a time: the app list's worker and the widget prune update from other
     * threads, and two updates writing out of order would store the older state last, losing the newer on a restart.
     * Stored before it's published: publishing can run a collector on this thread that updates again, and its newer
     * state must be the one stored last.
     */
    fun update(transform: (LauncherSettings) -> LauncherSettings) = synchronized(this) {
        val next = transform(state.value)
        write(next)
        state.value = next
    }

    fun toggleFavorite(key: String) = update {
        it.copy(favorites = if (key in it.favorites) it.favorites - key else it.favorites + key)
    }

    fun setFavorites(keys: List<String>) = update { it.copy(favorites = keys) }

    fun setHidden(key: String, hidden: Boolean) = update {
        it.copy(hidden = if (hidden) it.hidden + key else it.hidden - key)
    }

    fun rename(key: String, label: String?) = update {
        it.copy(renames = if (label.isNullOrBlank()) it.renames - key else it.renames + (key to label.trim()))
    }

    /**
     * The stored settings, each value checked for its type: getBoolean and getString throw on a value of another type,
     * and this runs in Application.onCreate, so one bad value would crash home on every launch. A value of the wrong
     * type reads as its default instead; the next [update] writes every key back with its proper type.
     */
    private fun read(): LauncherSettings {
        val stored = sp.all
        fun bool(key: String, default: Boolean) = stored[key] as? Boolean ?: default
        fun string(key: String) = stored[key] as? String
        return LauncherSettings(
            favorites = string(FAVORITES)?.let(::parseList) ?: emptyList(),
            hidden = string(HIDDEN)?.let(::parseList)?.toSet() ?: emptySet(),
            renames = string(RENAMES)?.let(::parseMap) ?: emptyMap(),
            showIcons = bool(SHOW_ICONS, true),
            monochromeIcons = bool(MONOCHROME, false),
            showNotificationPreviews = bool(PREVIEWS, true),
            showMediaControls = bool(MEDIA, true),
            textColor = string(TEXT_COLOR)?.let { name -> TextColor.entries.firstOrNull { it.name == name } } ?: TextColor.AUTO,
            iconSize = string(ICON_SIZE)?.let { name -> IconSize.entries.firstOrNull { it.name == name } } ?: IconSize.MEDIUM,
            clockStyle = string(CLOCK_STYLE)?.let { name -> ClockStyle.entries.firstOrNull { it.name == name } } ?: ClockStyle.CLASSIC,
            showCalendar = bool(SHOW_CALENDAR, false),
            showBattery = bool(SHOW_BATTERY, true),
            autoUpdateCheck = bool(AUTO_UPDATE, true),
            swipeDownAction = string(SWIPE_DOWN)
                ?.let { name -> SwipeDownAction.entries.firstOrNull { it.name == name } }
                ?: SwipeDownAction.NOTIFICATIONS,
            favoritesSeeded = bool(SEEDED, false),
            notificationPromptDismissed = bool(NOTIFICATION_PROMPT, false),
            timeFormat = string(TIME_FORMAT)?.let { name -> TimeFormat.entries.firstOrNull { it.name == name } } ?: TimeFormat.SYSTEM,
            showDate = bool(SHOW_DATE, true),
            showAlarm = bool(SHOW_ALARM, true),
            showTimers = bool(SHOW_TIMERS, true),
            wallpaperDim = string(WALLPAPER_DIM)?.let { name -> WallpaperDim.entries.firstOrNull { it.name == name } } ?: WallpaperDim.OFF,
            hideStatusBar = bool(HIDE_STATUS_BAR, false),
            haptics = bool(HAPTICS, true),
            doubleTapAction = string(DOUBLE_TAP)
                ?.let { name -> DoubleTapAction.entries.firstOrNull { it.name == name } }
                ?: DoubleTapAction.NOTHING,
            searchWeb = bool(SEARCH_WEB, true),
            hiddenInSearch = bool(HIDDEN_IN_SEARCH, true),
            autoLaunchSingleMatch = bool(AUTO_LAUNCH, false),
            batteryAlways = bool(BATTERY_ALWAYS, false),
            showWeather = bool(SHOW_WEATHER, false),
            weatherPlace = string(WEATHER_PLACE)?.let(::parsePlace),
            tempUnit = string(TEMP_UNIT)?.let { name -> TempUnit.entries.firstOrNull { it.name == name } } ?: TempUnit.AUTO,
            resumePrompt = bool(RESUME_PROMPT, true),
            folders = string(FOLDERS)?.let(::parseFolders) ?: emptyMap(),
            // Only entries the stack can show: its editors move widgets by their place in that list, which must be this one.
            widgetStack = string(WIDGET_STACK)?.let(::parseList)?.filter(::isStackWidget)?.take(MAX_STACK_WIDGETS) ?: emptyList(),
            searchCalculator = bool(SEARCH_CALCULATOR, true),
            searchContacts = bool(SEARCH_CONTACTS, false),
            swipeDownApp = string(SWIPE_DOWN_APP),
            doubleTapApp = string(DOUBLE_TAP_APP),
            showLiveUpdates = bool(SHOW_LIVE_UPDATES, true),
            searchCommands = bool(SEARCH_COMMANDS, true),
            searchShortcuts = bool(SEARCH_SHORTCUTS, true),
            musicGlow = bool(MUSIC_GLOW, true),
            copyLoginCodes = bool(COPY_LOGIN_CODES, true),
            secondLetters = bool(SECOND_LETTERS, false),
            stripApps = bool(STRIP_APPS, false),
        )
    }

    private fun write(s: LauncherSettings) {
        sp.edit {
            putString(FAVORITES, JSONArray(s.favorites).toString())
            putString(HIDDEN, JSONArray(s.hidden.toList()).toString())
            putString(RENAMES, JSONObject(s.renames).toString())
            putBoolean(SHOW_ICONS, s.showIcons)
            putBoolean(MONOCHROME, s.monochromeIcons)
            putBoolean(PREVIEWS, s.showNotificationPreviews)
            putBoolean(MEDIA, s.showMediaControls)
            putString(TEXT_COLOR, s.textColor.name)
            putString(ICON_SIZE, s.iconSize.name)
            putString(CLOCK_STYLE, s.clockStyle.name)
            putBoolean(SHOW_CALENDAR, s.showCalendar)
            putBoolean(SHOW_BATTERY, s.showBattery)
            putBoolean(AUTO_UPDATE, s.autoUpdateCheck)
            putString(SWIPE_DOWN, s.swipeDownAction.name)
            putBoolean(SEEDED, s.favoritesSeeded)
            putBoolean(NOTIFICATION_PROMPT, s.notificationPromptDismissed)
            putString(TIME_FORMAT, s.timeFormat.name)
            putBoolean(SHOW_DATE, s.showDate)
            putBoolean(SHOW_ALARM, s.showAlarm)
            putBoolean(SHOW_TIMERS, s.showTimers)
            putString(WALLPAPER_DIM, s.wallpaperDim.name)
            putBoolean(HIDE_STATUS_BAR, s.hideStatusBar)
            putBoolean(HAPTICS, s.haptics)
            putString(DOUBLE_TAP, s.doubleTapAction.name)
            putBoolean(SEARCH_WEB, s.searchWeb)
            putBoolean(HIDDEN_IN_SEARCH, s.hiddenInSearch)
            putBoolean(AUTO_LAUNCH, s.autoLaunchSingleMatch)
            putBoolean(BATTERY_ALWAYS, s.batteryAlways)
            putBoolean(SHOW_WEATHER, s.showWeather)
            putString(WEATHER_PLACE, s.weatherPlace?.let(::placeJson))
            putString(TEMP_UNIT, s.tempUnit.name)
            putBoolean(RESUME_PROMPT, s.resumePrompt)
            putString(FOLDERS, foldersJson(s.folders))
            putString(WIDGET_STACK, JSONArray(s.widgetStack).toString())
            putBoolean(SEARCH_CALCULATOR, s.searchCalculator)
            putBoolean(SEARCH_CONTACTS, s.searchContacts)
            putString(SWIPE_DOWN_APP, s.swipeDownApp)
            putString(DOUBLE_TAP_APP, s.doubleTapApp)
            putBoolean(SHOW_LIVE_UPDATES, s.showLiveUpdates)
            putBoolean(SEARCH_COMMANDS, s.searchCommands)
            putBoolean(SEARCH_SHORTCUTS, s.searchShortcuts)
            putBoolean(MUSIC_GLOW, s.musicGlow)
            putBoolean(COPY_LOGIN_CODES, s.copyLoginCodes)
            putBoolean(SECOND_LETTERS, s.secondLetters)
            putBoolean(STRIP_APPS, s.stripApps)
        }
    }

    private companion object {
        const val FAVORITES = "favorites"
        const val HIDDEN = "hidden"
        const val RENAMES = "renames"
        const val SHOW_ICONS = "show_icons"
        const val MONOCHROME = "monochrome_icons"
        const val PREVIEWS = "notification_previews"
        const val MEDIA = "media_controls"
        const val TEXT_COLOR = "text_color"
        const val ICON_SIZE = "icon_size"
        const val CLOCK_STYLE = "clock_style"
        const val SHOW_CALENDAR = "show_calendar"
        const val SHOW_BATTERY = "show_battery"
        const val AUTO_UPDATE = "auto_update_check"
        const val SWIPE_DOWN = "swipe_down"
        const val SEEDED = "favorites_seeded"
        const val NOTIFICATION_PROMPT = "notification_prompt_dismissed"
        const val TIME_FORMAT = "time_format"
        const val SHOW_DATE = "show_date"
        const val SHOW_ALARM = "show_alarm"
        const val SHOW_TIMERS = "show_timers"
        const val WALLPAPER_DIM = "wallpaper_dim"
        const val HIDE_STATUS_BAR = "hide_status_bar"
        const val HAPTICS = "haptics"
        const val DOUBLE_TAP = "double_tap"
        const val SEARCH_WEB = "search_web"
        const val HIDDEN_IN_SEARCH = "hidden_in_search"
        const val AUTO_LAUNCH = "auto_launch_single_match"
        const val BATTERY_ALWAYS = "battery_always"
        const val SHOW_WEATHER = "show_weather"
        const val WEATHER_PLACE = "weather_place"
        const val TEMP_UNIT = "temp_unit"
        const val RESUME_PROMPT = "resume_prompt"
        const val FOLDERS = "folders"
        const val WIDGET_STACK = "widget_stack"
        const val SEARCH_CALCULATOR = "search_calculator"
        const val SEARCH_CONTACTS = "search_contacts"
        const val SWIPE_DOWN_APP = "swipe_down_app"
        const val DOUBLE_TAP_APP = "double_tap_app"
        const val SHOW_LIVE_UPDATES = "show_live_updates"
        const val SEARCH_COMMANDS = "search_commands"
        const val SEARCH_SHORTCUTS = "search_shortcuts"
        const val MUSIC_GLOW = "music_glow"
        const val COPY_LOGIN_CODES = "copy_login_codes"
        const val SECOND_LETTERS = "second_letters"
        const val STRIP_APPS = "strip_apps"
    }
}

/** Folders as a JSON object: id to {name, apps: [...]}. SettingsBackup stores them the same way. */
internal fun foldersJson(folders: Map<String, Folder>): String = JSONObject().apply {
    for ((id, folder) in folders) put(id, JSONObject().put("name", folder.name).put("apps", JSONArray(folder.apps)))
}.toString()

/** The folders in [json]; a malformed one is skipped, and an unreadable file reads as none. */
internal fun parseFolders(json: String): Map<String, Folder> = runCatching {
    val obj = JSONObject(json)
    buildMap {
        for (id in obj.keys()) {
            val f = obj.optJSONObject(id) ?: continue
            val apps = f.optJSONArray("apps") ?: continue
            put(id, Folder(f.optString("name"), List(apps.length()) { apps.optString(it) }.filter { it.isNotEmpty() }.distinct()))
        }
    }
}.getOrDefault(emptyMap())

/** A [WeatherPlace] as a JSON object (name, lat, lon); SettingsBackup stores it the same way. */
internal fun placeJson(place: WeatherPlace): String =
    JSONObject().put("name", place.name).put("lat", place.latitude).put("lon", place.longitude).toString()

/** The place in [json], or null when it's malformed or its coordinates are out of range. */
internal fun parsePlace(json: String): WeatherPlace? = runCatching {
    val obj = JSONObject(json)
    WeatherPlace(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lon"))
        .takeIf { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
}.getOrNull()

private fun parseList(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    List(array.length()) { array.getString(it) }
}.getOrDefault(emptyList())

private fun parseMap(json: String): Map<String, String> = runCatching {
    val obj = JSONObject(json)
    obj.keys().asSequence().associateWith { obj.getString(it) }
}.getOrDefault(emptyMap())
