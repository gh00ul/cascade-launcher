package com.gh00ul.cascade.data

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        favorites = listOf("b/b.Main#0", "a/a.Main#0", "c/c.Main#10"),
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
        swipeDownAction = SwipeDownAction.SEARCH,
        favoritesSeeded = true,
        notificationPromptDismissed = true,
    )

    private fun decode(json: String, current: LauncherSettings = LauncherSettings()) = SettingsBackup.decode(json, current)

    @Test fun everySettingSurvivesARoundTrip() {
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
        assertEquals(current.copy(showIcons = false, favorites = listOf("x"), favoritesSeeded = true), restored)
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
            """{"cascadeSettingsVersion":1,"iconSize":"HUGE","clockStyle":"FLIP","textColor":"PURPLE","swipeDownAction":"SEARCH"}""",
            current,
        )
        assertEquals(current.copy(swipeDownAction = SwipeDownAction.SEARCH), restored)
    }

    @Test fun unknownKeysAndNewerVersionsStillLoad() {
        val restored = decode(
            """{"cascadeSettingsVersion":7,"wallpaperDim":0.4,"gestures":{"up":"drawer"},"monochromeIcons":true}""",
            full.copy(monochromeIcons = false),
        )
        assertEquals(full, restored)
    }

    @Test fun wrongTypesKeepTheCurrentValue() {
        val current = full.copy(favoritesSeeded = false)
        val restored = decode(
            """{"cascadeSettingsVersion":1,"favorites":"a,b","showIcons":"yes","iconSize":2,"renames":["a"],"hidden":""" +
                """["h",3,null,true,"i"],"showBattery":null}""",
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

    @Test fun summaryLeavesOutEmptyListsAndUnchangedSettings() {
        assertEquals("1 other setting changes", SettingsBackup.summary(LauncherSettings(), LauncherSettings(showCalendar = true)))
        // A reordered list has the same count but still changes.
        val current = LauncherSettings(favorites = listOf("a", "b"))
        assertEquals("2 favorites (now 2)", SettingsBackup.summary(current, current.copy(favorites = listOf("b", "a"))))
    }

    @Test fun fileNameUsesTheIsoDate() {
        assertEquals("cascade-settings-2026-10-06.json", SettingsBackup.fileName(LocalDate.of(2026, 10, 6)))
    }
}
