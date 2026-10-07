package com.gh00ul.cascade.data

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.time.LocalDate

// Robolectric for the real org.json: on the plain JVM it is a stub that returns defaults.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsBackupTest {
    private val full = LauncherSettings(
        favorites = listOf("b/b.Main#0", "folder:1", "a/a.Main#0", "folder:2", "c/c.Main#10"),
        hidden = setOf("h/h.Main#0"),
        renames = mapOf("a/a.Main#0" to "Alpha", "b/b.Main#0" to "Café ☕"),
        showIcons = false,
        monochromeIcons = true,
        showNotificationPreviews = false,
        showMediaControls = false,
        textColor = TextColor.DARK,
        iconSize = IconSize.XL,
        clockStyle = ClockStyle.STACKED,
        showCalendar = true,
        showBattery = false,
        autoUpdateCheck = false,
        swipeDownAction = SwipeDownAction.QUICK_SETTINGS,
        favoritesSeeded = true,
        notificationPromptDismissed = true,
        timeFormat = TimeFormat.H24,
        showDate = false,
        showAlarm = false,
        showTimers = false,
        wallpaperDim = WallpaperDim.MEDIUM,
        hideStatusBar = true,
        haptics = false,
        doubleTapAction = DoubleTapAction.LOCK_SCREEN,
        searchWeb = false,
        hiddenInSearch = false,
        autoLaunchSingleMatch = true,
        batteryAlways = true,
        showWeather = true,
        weatherPlace = WeatherPlace("Zürich", 47.3769, 8.5417),
        tempUnit = TempUnit.FAHRENHEIT,
        resumePrompt = false,
        folders = mapOf("1" to Folder("Jeux ☕", listOf("g/g.Main#0", "k/k.Main#10")), "2" to Folder("", listOf("m/m.Main#0"))),
        // Only Cascade's own widgets: app widgets aren't backed up.
        widgetStack = listOf(WIDGET_WEATHER, WIDGET_CALENDAR),
        searchCalculator = false,
        searchContacts = true,
        swipeDownApp = "s/s.Main#0",
        doubleTapApp = "d/d.Main#10",
        showLiveUpdates = false,
        searchCommands = false,
        searchShortcuts = false,
        musicGlow = false,
        copyLoginCodes = false,
        secondLetters = true,
        stripApps = true,
    )

    private fun decode(json: String, current: LauncherSettings = LauncherSettings()) = SettingsBackup.decode(json, current)

    @Test fun everySettingSurvivesARoundTrip() {
        assertEveryFieldChanged(full)
        assertEquals(full, decode(SettingsBackup.encode(full)))
        // Defaults restore over a customized setup too; only favoritesSeeded stays set.
        assertEquals(LauncherSettings(favoritesSeeded = true), decode(SettingsBackup.encode(LauncherSettings()), full))
    }

    @Test fun everyLauncherSettingsFieldIsBackedUp() {
        // A field added to LauncherSettings must be added to the backup too.
        val fields = LauncherSettings::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
        val root = JSONObject(SettingsBackup.encode(full))
        assertEquals(SettingsBackup.VERSION, root.getInt("cascadeSettingsVersion"))
        assertEquals(fields.toSet() + "cascadeSettingsVersion", root.keys().asSequence().toSet())
    }

    @Test fun partialFileChangesOnlyWhatItCarries() {
        val current = full.copy(showIcons = true, favoritesSeeded = false)
        val restored = decode("""{"cascadeSettingsVersion":1,"showIcons":false,"favorites":["x"]}""", current)
        // Favorites without folders keep the folders on home (see aBackupFromBeforeFoldersKeepsTheFolders).
        assertEquals(current.copy(showIcons = false, favorites = listOf("x", "folder:1", "folder:2"), favoritesSeeded = true), restored)
    }

    /**
     * A backup from before folders (v0.6.0 to v0.8.x), or one whose folders can't be read, has favorites but no folders:
     * the folders on home stay, after the restored favorites, and an app in one isn't a favorite too. Restored without
     * their keys, they'd vanish from home and the next folder edit would delete them.
     */
    @Test fun aBackupFromBeforeFoldersKeepsTheFolders() {
        val current = LauncherSettings(
            favorites = listOf("a", "folder:1", "b", "folder:2", "folder:3"),
            folders = mapOf(
                "1" to Folder("Games", listOf("g", "k")),
                "2" to Folder("Work", listOf("w")),
                // Its apps were uninstalled: hidden on home, and the next edit drops it.
                "3" to Folder("Old", emptyList()),
                // Not on home at all.
                "4" to Folder("Stray", listOf("z")),
            ),
        )
        val backup = SettingsBackup.encode(LauncherSettings(favorites = listOf("x", "g", "a")))
        for (folders in listOf(null, "none", JSONArray(), JSONObject().put("1", 5))) {
            val json = JSONObject(backup).apply { if (folders == null) remove("folders") else put("folders", folders) }.toString()
            val restored = decode(json, current)!!
            assertEquals("$folders", listOf("x", "a", "folder:1", "folder:2"), restored.favorites)
            assertEquals("$folders", current.folders - "3" - "4", restored.folders)
            assertEquals("$folders", restored, restored.tidyFolders())
            // And the confirmation says the folders home shows stay.
            assertTrue("$folders", "2 folders (unchanged)" in SettingsBackup.summary(current, restored))
        }
        // A folder the backup's favorites have keeps its place; one that isn't on home now is left out.
        val placed = decode("""{"cascadeSettingsVersion":1,"favorites":["folder:2","x","folder:9"]}""", current)!!
        assertEquals(listOf("folder:2", "x", "folder:1"), placed.favorites)
        assertEquals(placed, placed.tidyFolders())
    }

    /** The backup's folders only come with its favorites, which hold their places on home. */
    @Test fun foldersAreSkippedWithTheFavorites() {
        for (favorites in listOf("", """"favorites":"a,b",""", """"favorites":[1,2],""")) {
            val json = """{"cascadeSettingsVersion":1,$favorites"folders":{"9":{"name":"Other","apps":["z"]}}}"""
            assertEquals(favorites, full, decode(json, full))
        }
    }

    /** Restore parses the file before the confirmation and merges it when tapped: a change made in between stays. */
    @Test fun aParsedBackupMergesOntoTheSettingsAsTheyAreThen() {
        val merge = SettingsBackup.parse("""{"cascadeSettingsVersion":1,"showIcons":false,"widgetStack":["weather","clock"]}""")!!
        // Changed after the file was read: another setting, and an app widget added.
        val later = full.copy(showIcons = true, haptics = true, widgetStack = listOf("app:3", WIDGET_CALENDAR))
        assertEquals(later.copy(showIcons = false, widgetStack = listOf(WIDGET_WEATHER, "app:3")), merge(later))
        assertNull(SettingsBackup.parse("not json"))
        assertNull(SettingsBackup.parse("""{"showIcons":false}"""))
    }

    @Test fun aBackupFromBeforeAppNamesDoesntLeaveBothStripModesOn() {
        // Second letters on, and no App names at all: restored over App names, App names goes off.
        val old = JSONObject(SettingsBackup.encode(LauncherSettings(secondLetters = true))).apply { remove("stripApps") }.toString()
        val restored = decode(old, LauncherSettings(stripApps = true))!!
        assertEquals(true to false, restored.secondLetters to restored.stripApps)
        // With Second letters off in it, App names stays as it was.
        val off = JSONObject(SettingsBackup.encode(LauncherSettings())).apply { remove("stripApps") }.toString()
        assertEquals(true, decode(off, LauncherSettings(stripApps = true))!!.stripApps)
    }

    @Test fun anythingButABackupIsRejected() {
        val encoded = SettingsBackup.encode(full)
        for (json in listOf(
            "not json",
            "",
            "[]",
            "{}",
            "null",
            """{"showIcons":false}""",
            """{"cascadeSettingsVersion":"1"}""",
            """{"cascadeSettingsVersion":null}""",
            """{"cascadeSettingsVersion":0}""",
            encoded.substring(0, encoded.length / 2),
        )) {
            assertNull(json, decode(json, full))
        }
    }

    @Test fun unknownEnumNamesKeepTheCurrentValue() {
        val current = LauncherSettings(textColor = TextColor.LIGHT, iconSize = IconSize.LARGE, clockStyle = ClockStyle.BOLD)
        val restored = decode(
            """{"cascadeSettingsVersion":1,"iconSize":"HUGE","clockStyle":"FLIP","textColor":"PURPLE","swipeDownAction":"SEARCH",""" +
                """"timeFormat":"H36","wallpaperDim":"MAX","doubleTapAction":"WAVE","tempUnit":"KELVIN"}""",
            current,
        )
        assertEquals(current.copy(swipeDownAction = SwipeDownAction.SEARCH), restored)
    }

    @Test fun enumsAreStoredByName() {
        val root = JSONObject(SettingsBackup.encode(full))
        assertEquals("QUICK_SETTINGS", root.getString("swipeDownAction"))
        assertEquals("LOCK_SCREEN", root.getString("doubleTapAction"))
        assertEquals("MEDIUM", root.getString("wallpaperDim"))
        // A backup from before QUICK_SETTINGS and NOTHING existed still restores its choice.
        assertEquals(SwipeDownAction.SEARCH, decode("""{"cascadeSettingsVersion":1,"swipeDownAction":"SEARCH"}""")?.swipeDownAction)
        assertEquals(SwipeDownAction.NOTHING, decode("""{"cascadeSettingsVersion":1,"swipeDownAction":"NOTHING"}""")?.swipeDownAction)
    }

    @Test fun weatherPlaceIsANestedObject() {
        val place = JSONObject(SettingsBackup.encode(full)).getJSONObject("weatherPlace")
        assertEquals(setOf("name", "lat", "lon"), place.keys().asSequence().toSet())
        assertEquals("Zürich", place.getString("name"))
        assertEquals(47.3769, place.getDouble("lat"), 0.0)
        assertEquals(8.5417, place.getDouble("lon"), 0.0)
        // No place is stored as null, which restores as no place.
        assertTrue(JSONObject(SettingsBackup.encode(LauncherSettings())).isNull("weatherPlace"))
        assertNull(decode("""{"cascadeSettingsVersion":1,"weatherPlace":null}""", full)?.weatherPlace)
    }

    @Test fun malformedWeatherPlaceKeepsTheCurrentOne() {
        for (place in listOf(
            """{"name":"Nowhere","lat":95,"lon":0}""",
            """{"name":"Nowhere","lat":0,"lon":-181}""",
            """{"lat":1,"lon":2}""",
            """{"name":"Nowhere","lat":"north","lon":2}""",
            """"Zürich"""",
            "[]",
            "3",
        )) {
            assertEquals(place, full.weatherPlace, decode("""{"cascadeSettingsVersion":1,"weatherPlace":$place}""", full)?.weatherPlace)
        }
        assertEquals(
            WeatherPlace("Oslo", 59.91, 10.75),
            decode("""{"cascadeSettingsVersion":1,"weatherPlace":{"name":"Oslo","lat":59.91,"lon":10.75,"extra":1}}""", full)?.weatherPlace,
        )
    }

    @Test fun unknownKeysAndNewerVersionsStillLoad() {
        val restored = decode(
            """{"cascadeSettingsVersion":7,"dockStyle":0.4,"gestures":{"up":"drawer"},"monochromeIcons":true}""",
            full.copy(monochromeIcons = false),
        )
        assertEquals(full, restored)
    }

    @Test fun wrongTypesKeepTheCurrentValue() {
        val current = full.copy(favoritesSeeded = false)
        val restored = decode(
            """{"cascadeSettingsVersion":1,"favorites":"a,b","showIcons":"yes","iconSize":2,"renames":["a"],"hidden":""" +
                """["h",3,null,true,"i"],"showBattery":null,"wallpaperDim":0.4,"haptics":"off","timeFormat":24}""",
            current,
        )
        // Nothing replaced the favorites, so seeding is left alone too.
        assertEquals(current.copy(hidden = setOf("h", "i")), restored)
    }

    @Test fun nonStringEntriesAndDuplicatesAreSkipped() {
        val restored = decode("""{"cascadeSettingsVersion":1,"favorites":["x",1,"y",{},"x"],"renames":{"a":"Alpha","b":5}}""")
        assertEquals(listOf("x", "y"), restored?.favorites)
        assertEquals(mapOf("a" to "Alpha"), restored?.renames)
    }

    /** A list or map with entries but none that can be restored is skipped whole, rather than emptying the current one. */
    @Test fun aFieldWithNothingUsableKeepsTheCurrentValue() {
        val current = full.copy(favoritesSeeded = false, widgetStack = listOf(WIDGET_CALENDAR, "app:3"))
        val restored = decode(
            """{"cascadeSettingsVersion":1,"favorites":[1,2],"hidden":[true,null],"renames":{"a":5,"b":"  "},""" +
                """"folders":{"1":5},"widgetStack":["notes"]}""",
            current,
        )
        // Nothing replaced the favorites, so seeding is left alone too.
        assertEquals(current, restored)
        // Empty ones are a value, though: no favorites, no hidden apps, and so on. The app widgets stay, as always.
        val emptied = decode("""{"cascadeSettingsVersion":1,"favorites":[],"hidden":[],"renames":{},"folders":{},"widgetStack":[]}""", current)
        assertEquals(
            current.copy(
                favorites = emptyList(),
                hidden = emptySet(),
                renames = emptyMap(),
                folders = emptyMap(),
                widgetStack = listOf("app:3"),
                favoritesSeeded = true,
            ),
            emptied,
        )
    }

    /** A gesture's app is a string, or null for none; anything else keeps the current one. */
    @Test fun gestureAppsOfTheWrongTypeKeepTheCurrentOne() {
        for (value in listOf("5", "true", "{}", """["x"]""")) {
            val restored = decode("""{"cascadeSettingsVersion":1,"swipeDownApp":$value,"doubleTapApp":$value}""", full)
            assertEquals(value, full.swipeDownApp to full.doubleTapApp, restored?.let { it.swipeDownApp to it.doubleTapApp })
        }
        val restored = decode("""{"cascadeSettingsVersion":1,"swipeDownApp":null,"doubleTapApp":"x/x.Main#0"}""", full)
        assertEquals(null to "x/x.Main#0", restored?.let { it.swipeDownApp to it.doubleTapApp })
    }

    /** Some editors save UTF-8 with a byte order mark in front. */
    @Test fun aFileWithAByteOrderMarkRestores() {
        assertEquals(full, decode("\uFEFF" + SettingsBackup.encode(full)))
    }

    @Test fun hiddenAppsKeepTheirOrder() {
        val restored = decode("""{"cascadeSettingsVersion":1,"hidden":["z","a","m"]}""")
        assertEquals(listOf("z", "a", "m"), restored?.hidden?.toList())
    }

    @Test fun restoreNeverClearsFavoritesSeeded() {
        val current = LauncherSettings(favoritesSeeded = true)
        assertEquals(true, decode("""{"cascadeSettingsVersion":1,"favoritesSeeded":false}""", current)?.favoritesSeeded)
        assertEquals(true, decode(SettingsBackup.encode(LauncherSettings()), current)?.favoritesSeeded)
        // Restored favorites must not be replaced by the first-run defaults.
        assertEquals(true, decode("""{"cascadeSettingsVersion":1,"favorites":[],"favoritesSeeded":false}""")?.favoritesSeeded)
    }

    @Test fun blankRenamesAreDropped() {
        val restored = decode("""{"cascadeSettingsVersion":1,"renames":{"a":"  Alpha ","b":"   ","c":""}}""", full)
        assertEquals(mapOf("a" to "Alpha"), restored?.renames)
    }

    @Test fun summaryOfAMatchingBackup() {
        val matches = "This backup matches your current settings."
        assertEquals(matches, SettingsBackup.summary(full, full))
        assertEquals(matches, SettingsBackup.summary(LauncherSettings(), LauncherSettings()))
        // The internal flags aren't settings the user changes.
        assertEquals(matches, SettingsBackup.summary(full.copy(notificationPromptDismissed = false), full))
    }

    @Test fun summaryCountsWhatChanges() {
        val current = LauncherSettings(
            favorites = listOf("a", "b", "c", "d", "e"),
            hidden = setOf("h", "i"),
            renames = mapOf("a" to "Alpha"),
        )
        val restored = current.copy(
            favorites = listOf("a", "b", "c", "d", "e", "f"),
            renames = mapOf("a" to "A", "b" to "B", "c" to "C"),
            showIcons = false,
            textColor = TextColor.DARK,
            iconSize = IconSize.SMALL,
            swipeDownAction = SwipeDownAction.SEARCH,
            favoritesSeeded = true,
        )
        assertEquals(
            "6 favorites (now 5)\n2 hidden apps (unchanged)\n3 renamed apps (now 1)\n4 other settings change",
            SettingsBackup.summary(current, restored),
        )
    }

    @Test fun summaryUsesTheSingular() {
        val current = LauncherSettings(favorites = listOf("a", "b"), renames = mapOf("a" to "Alpha"))
        val restored = LauncherSettings(favorites = listOf("a"), hidden = setOf("h"), renames = mapOf("a" to "Beta"), showBattery = false)
        assertEquals(
            "1 favorite (now 2)\n1 hidden app (now 0)\n1 renamed app (now 1)\n1 other setting changes",
            SettingsBackup.summary(current, restored),
        )
    }

    /** The folders on home get a line of their own, not a place among the other settings. */
    @Test fun summaryNamesTheFolders() {
        val current = LauncherSettings(
            favorites = listOf("a", "folder:1", "folder:2", "folder:3"),
            folders = mapOf("1" to Folder("A", listOf("x")), "2" to Folder("B", listOf("y")), "3" to Folder("C", listOf("z"))),
        )
        val restored = current.copy(favorites = listOf("a", "folder:1", "folder:2"), folders = current.folders - "3", showIcons = false)
        assertEquals("3 favorites (now 4)\n2 folders (now 3)\n1 other setting changes", SettingsBackup.summary(current, restored))
        // A renamed folder is the same count, but still a change.
        val renamed = current.copy(folders = current.folders + ("1" to Folder("Games", listOf("x"))))
        assertEquals("4 favorites (unchanged)\n3 folders (now 3)", SettingsBackup.summary(current, renamed))
        // Folders home can't show aren't ones the user has: one that isn't on it, or one with no apps.
        val offHome = current.copy(folders = current.folders + ("9" to Folder("Stray", listOf("q"))))
        assertEquals("This backup matches your current settings.", SettingsBackup.summary(current, offHome))
        val emptied = current.copy(folders = current.folders + ("1" to Folder("A", emptyList())))
        assertEquals("4 favorites (unchanged)\n2 folders (now 3)", SettingsBackup.summary(current, emptied))
    }

    @Test fun summaryLeavesOutEmptyListsAndUnchangedSettings() {
        assertEquals("1 other setting changes", SettingsBackup.summary(LauncherSettings(), LauncherSettings(showCalendar = true)))
        val place = WeatherPlace("Oslo", 59.91, 10.75)
        assertEquals(
            "3 other settings change",
            SettingsBackup.summary(LauncherSettings(), LauncherSettings(haptics = false, weatherPlace = place, tempUnit = TempUnit.CELSIUS)),
        )
        // A reordered list has the same count but still changes.
        val current = LauncherSettings(favorites = listOf("a", "b"))
        assertEquals("2 favorites (now 2)", SettingsBackup.summary(current, current.copy(favorites = listOf("b", "a"))))
    }

    @Test fun fileNameUsesTheIsoDate() {
        assertEquals("cascade-settings-2026-10-06.json", SettingsBackup.fileName(LocalDate.of(2026, 10, 6)))
    }
}
