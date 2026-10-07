package com.gh00ul.cascade.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * Settings backup as a JSON file: every [LauncherSettings] field under its property name, plus a version marker that
 * also tells a Cascade backup apart from other JSON. Restoring merges onto the current settings, so a key that is
 * missing, unknown or of the wrong type is skipped and backups from older or newer builds still load.
 */
object SettingsBackup {
    const val VERSION = 1
    private const val VERSION_KEY = "cascadeSettingsVersion"

    /** "cascade-settings-2026-10-06.json" (ISO date). */
    fun fileName(date: LocalDate): String = "cascade-settings-$date.json"

    /** Pretty-printed JSON (JSONObject.toString(2)) with every LauncherSettings field plus the version marker. */
    fun encode(settings: LauncherSettings): String = JSONObject()
        .put(VERSION_KEY, VERSION)
        .put("favorites", JSONArray(settings.favorites))
        .put("hidden", JSONArray(settings.hidden.toList()))
        .put("renames", JSONObject(settings.renames))
        .put("showIcons", settings.showIcons)
        .put("monochromeIcons", settings.monochromeIcons)
        .put("showNotificationPreviews", settings.showNotificationPreviews)
        .put("showMediaControls", settings.showMediaControls)
        .put("textColor", settings.textColor.name)
        .put("iconSize", settings.iconSize.name)
        .put("clockStyle", settings.clockStyle.name)
        .put("showCalendar", settings.showCalendar)
        .put("showBattery", settings.showBattery)
        .put("autoUpdateCheck", settings.autoUpdateCheck)
        .put("swipeDownAction", settings.swipeDownAction.name)
        .put("favoritesSeeded", settings.favoritesSeeded)
        .put("notificationPromptDismissed", settings.notificationPromptDismissed)
        .put("timeFormat", settings.timeFormat.name)
        .put("showDate", settings.showDate)
        .put("showAlarm", settings.showAlarm)
        .put("showTimers", settings.showTimers)
        .put("wallpaperDim", settings.wallpaperDim.name)
        .put("hideStatusBar", settings.hideStatusBar)
        .put("haptics", settings.haptics)
        .put("doubleTapAction", settings.doubleTapAction.name)
        .put("searchWeb", settings.searchWeb)
        .put("hiddenInSearch", settings.hiddenInSearch)
        .put("autoLaunchSingleMatch", settings.autoLaunchSingleMatch)
        .put("batteryAlways", settings.batteryAlways)
        .put("showWeather", settings.showWeather)
        // {name, lat, lon} as Prefs stores it, or null for no place; put(key, null) would drop the key instead.
        .put("weatherPlace", settings.weatherPlace?.let { JSONObject(placeJson(it)) } ?: JSONObject.NULL)
        .put("tempUnit", settings.tempUnit.name)
        .put("resumePrompt", settings.resumePrompt)
        .put("folders", JSONObject(foldersJson(settings.folders)))
        // Android app widgets are bound to this install's widget ids, which mean nothing elsewhere: only Cascade's own.
        .put("widgetStack", JSONArray(settings.widgetStack.filterNot { it.startsWith(WIDGET_APP_PREFIX) }))
        .put("searchCalculator", settings.searchCalculator)
        .put("searchContacts", settings.searchContacts)
        .put("swipeDownApp", settings.swipeDownApp ?: JSONObject.NULL)
        .put("doubleTapApp", settings.doubleTapApp ?: JSONObject.NULL)
        .put("showLiveUpdates", settings.showLiveUpdates)
        .put("searchCommands", settings.searchCommands)
        .put("searchShortcuts", settings.searchShortcuts)
        .put("musicGlow", settings.musicGlow)
        .put("copyLoginCodes", settings.copyLoginCodes)
        .put("secondLetters", settings.secondLetters)
        .put("stripApps", settings.stripApps)
        .toString(2)

    /** [current] with every field the backup carries replaced; null when [json] isn't a Cascade settings backup. */
    fun decode(json: String, current: LauncherSettings): LauncherSettings? = parse(json)?.invoke(current)

    /**
     * The slow half of [decode]: parses [json] and returns the merge, which puts the backup onto the settings it's given
     * as [decode] does, without parsing anything again. A restore parses off the main thread, then merges onto the
     * latest settings inside the Prefs update. Null when [json] isn't a Cascade settings backup.
     */
    fun parse(json: String): ((LauncherSettings) -> LauncherSettings)? {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return null
        }
        // Any version from 1 up loads; a newer build's extra keys are ignored.
        val version = root.opt(VERSION_KEY) as? Number ?: return null
        if (version.toDouble() < 1) return null
        // The lists, maps and nested objects now; the merge only looks up single values.
        val favorites = root.strings("favorites")?.distinct()
        val hidden = root.strings("hidden")?.toCollection(LinkedHashSet())
        // Same rule as Prefs.rename: blank labels mean no rename.
        val renames = (root.opt("renames") as? JSONObject)?.let { obj ->
            buildMap<String, String> {
                for (key in obj.keys()) (obj.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }
        val place = root.opt("weatherPlace")
        val restoredPlace = (place as? JSONObject)?.let { parsePlace(it.toString()) }
        val folders = (root.opt("folders") as? JSONObject)?.let { parseFolders(it.toString()) }
        val builtIns = root.strings("widgetStack")?.filter { it == WIDGET_CALENDAR || it == WIDGET_WEATHER }?.distinct()
        return { current -> LauncherSettings(
            favorites = favorites ?: current.favorites,
            hidden = hidden ?: current.hidden,
            renames = renames ?: current.renames,
            showIcons = root.bool("showIcons") ?: current.showIcons,
            monochromeIcons = root.bool("monochromeIcons") ?: current.monochromeIcons,
            showNotificationPreviews = root.bool("showNotificationPreviews") ?: current.showNotificationPreviews,
            showMediaControls = root.bool("showMediaControls") ?: current.showMediaControls,
            textColor = root.enum("textColor", TextColor.entries) ?: current.textColor,
            iconSize = root.enum("iconSize", IconSize.entries) ?: current.iconSize,
            clockStyle = root.enum("clockStyle", ClockStyle.entries) ?: current.clockStyle,
            showCalendar = root.bool("showCalendar") ?: current.showCalendar,
            showBattery = root.bool("showBattery") ?: current.showBattery,
            autoUpdateCheck = root.bool("autoUpdateCheck") ?: current.autoUpdateCheck,
            swipeDownAction = root.enum("swipeDownAction", SwipeDownAction.entries) ?: current.swipeDownAction,
            // Never cleared: AppRepository.seedFavorites would otherwise put the defaults over the restored favorites.
            favoritesSeeded = current.favoritesSeeded || root.bool("favoritesSeeded") == true || favorites != null,
            notificationPromptDismissed = root.bool("notificationPromptDismissed") ?: current.notificationPromptDismissed,
            timeFormat = root.enum("timeFormat", TimeFormat.entries) ?: current.timeFormat,
            showDate = root.bool("showDate") ?: current.showDate,
            showAlarm = root.bool("showAlarm") ?: current.showAlarm,
            showTimers = root.bool("showTimers") ?: current.showTimers,
            wallpaperDim = root.enum("wallpaperDim", WallpaperDim.entries) ?: current.wallpaperDim,
            hideStatusBar = root.bool("hideStatusBar") ?: current.hideStatusBar,
            haptics = root.bool("haptics") ?: current.haptics,
            doubleTapAction = root.enum("doubleTapAction", DoubleTapAction.entries) ?: current.doubleTapAction,
            searchWeb = root.bool("searchWeb") ?: current.searchWeb,
            hiddenInSearch = root.bool("hiddenInSearch") ?: current.hiddenInSearch,
            autoLaunchSingleMatch = root.bool("autoLaunchSingleMatch") ?: current.autoLaunchSingleMatch,
            batteryAlways = root.bool("batteryAlways") ?: current.batteryAlways,
            showWeather = root.bool("showWeather") ?: current.showWeather,
            // Null is a value here (no place picked), not a missing key; a malformed or out-of-range place is skipped.
            weatherPlace = when (place) {
                JSONObject.NULL -> null
                is JSONObject -> restoredPlace ?: current.weatherPlace
                else -> current.weatherPlace
            },
            tempUnit = root.enum("tempUnit", TempUnit.entries) ?: current.tempUnit,
            resumePrompt = root.bool("resumePrompt") ?: current.resumePrompt,
            folders = folders ?: current.folders,
            // The current app widgets stay (they're this install's, bound and set up), and the backup's own widgets
            // replace the rest, as many as fit: a configured widget is never pushed off the stack.
            widgetStack = builtIns?.let { restored ->
                val apps = current.widgetStack.filter { it.startsWith(WIDGET_APP_PREFIX) }
                restored.take((MAX_STACK_WIDGETS - apps.size).coerceAtLeast(0)) + apps
            } ?: current.widgetStack,
            searchCalculator = root.bool("searchCalculator") ?: current.searchCalculator,
            // Only with the permission still to ask for: restoring can't grant it.
            searchContacts = root.bool("searchContacts") ?: current.searchContacts,
            // A JSON null is "no app", as for the weather place.
            swipeDownApp = if (root.has("swipeDownApp")) root.opt("swipeDownApp") as? String else current.swipeDownApp,
            doubleTapApp = if (root.has("doubleTapApp")) root.opt("doubleTapApp") as? String else current.doubleTapApp,
            showLiveUpdates = root.bool("showLiveUpdates") ?: current.showLiveUpdates,
            searchCommands = root.bool("searchCommands") ?: current.searchCommands,
            searchShortcuts = root.bool("searchShortcuts") ?: current.searchShortcuts,
            musicGlow = root.bool("musicGlow") ?: current.musicGlow,
            copyLoginCodes = root.bool("copyLoginCodes") ?: current.copyLoginCodes,
            // The strip opens one way or the other: a mode the backup doesn't have (App names, in one from before it)
            // keeps its current value only if the backup's other mode is off, rather than leave both on.
            secondLetters = root.bool("secondLetters") ?: (current.secondLetters && root.bool("stripApps") != true),
            stripApps = root.bool("stripApps") ?: (current.stripApps && root.bool("secondLetters") != true),
        ) }
    }

    /** Short text for the restore confirmation: what applying [restored] replaces in [current]. */
    fun summary(current: LauncherSettings, restored: LauncherSettings): String {
        // The two internal flags aren't settings the user sees, so a difference only there still counts as a match.
        val userFacing = { s: LauncherSettings -> s.copy(favoritesSeeded = false, notificationPromptDismissed = false) }
        if (userFacing(restored) == userFacing(current)) return "This backup matches your current settings."
        val changed = SCALARS.count { it(current) != it(restored) }
        return listOfNotNull(
            count(restored.favorites, current.favorites, "favorite", "favorites"),
            count(restored.hidden, current.hidden, "hidden app", "hidden apps"),
            count(restored.renames.entries, current.renames.entries, "renamed app", "renamed apps"),
            when (changed) {
                0 -> null
                1 -> "1 other setting changes"
                else -> "$changed other settings change"
            },
        ).joinToString("\n")
    }

    /** The user-facing settings besides the three app lists. */
    private val SCALARS = listOf<(LauncherSettings) -> Any?>(
        LauncherSettings::showIcons,
        LauncherSettings::monochromeIcons,
        LauncherSettings::showNotificationPreviews,
        LauncherSettings::showMediaControls,
        LauncherSettings::textColor,
        LauncherSettings::iconSize,
        LauncherSettings::clockStyle,
        LauncherSettings::showCalendar,
        LauncherSettings::showBattery,
        LauncherSettings::autoUpdateCheck,
        LauncherSettings::swipeDownAction,
        LauncherSettings::timeFormat,
        LauncherSettings::showDate,
        LauncherSettings::showAlarm,
        LauncherSettings::showTimers,
        LauncherSettings::wallpaperDim,
        LauncherSettings::hideStatusBar,
        LauncherSettings::haptics,
        LauncherSettings::doubleTapAction,
        LauncherSettings::searchWeb,
        LauncherSettings::hiddenInSearch,
        LauncherSettings::autoLaunchSingleMatch,
        LauncherSettings::batteryAlways,
        LauncherSettings::showWeather,
        LauncherSettings::weatherPlace,
        LauncherSettings::tempUnit,
        LauncherSettings::resumePrompt,
        LauncherSettings::folders,
        LauncherSettings::widgetStack,
        LauncherSettings::searchCalculator,
        LauncherSettings::searchContacts,
        LauncherSettings::swipeDownApp,
        LauncherSettings::doubleTapApp,
        LauncherSettings::showLiveUpdates,
        LauncherSettings::searchCommands,
        LauncherSettings::searchShortcuts,
        LauncherSettings::musicGlow,
        LauncherSettings::copyLoginCodes,
        LauncherSettings::secondLetters,
        LauncherSettings::stripApps,
    )

    /** "6 favorites (now 5)"; null when both are empty. The same count with other contents still reads "now". */
    private fun count(restored: Collection<*>, current: Collection<*>, one: String, many: String): String? {
        if (restored.isEmpty() && current.isEmpty()) return null
        val now = if (restored == current) "unchanged" else "now ${current.size}"
        return "${restored.size} ${if (restored.size == 1) one else many} ($now)"
    }

    private fun JSONObject.bool(key: String) = opt(key) as? Boolean

    /** The String entries of an array; null when [key] is missing or not an array. */
    private fun JSONObject.strings(key: String): List<String>? = (opt(key) as? JSONArray)?.let { array ->
        List(array.length()) { array.opt(it) }.filterIsInstance<String>()
    }

    private fun <E : Enum<E>> JSONObject.enum(key: String, entries: List<E>): E? =
        (opt(key) as? String)?.let { name -> entries.firstOrNull { it.name == name } }
}
