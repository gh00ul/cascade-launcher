package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A–Z rows ([ListAppRow]) read their own entries out of the icon map and the notifications by app, so a new map only
 * recomposes the rows whose entry changed. Each row's AppRow is counted by a composed modifier, whose factory runs every
 * time AppRow's layout is composed; a reader of the whole map shows that each new map did reach composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RowRecompositionTest {
    /** Registers the bare activity the compose rule hosts content in (the app's manifest doesn't declare it). */
    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val a = FakeApps.mail
    private val b = FakeApps.messages
    private val other = FakeApps.phone

    // Referential, so a new map that is equal to the old one is still a write, as a regroup that rebuilds every list is.
    private val icons = mutableStateOf(mapOf(a.key to icon(), b.key to icon()), referentialEqualityPolicy())
    private val notifications = mutableStateOf(FakeNotifications.byApp(), referentialEqualityPolicy())

    /** AppRow runs per app key. */
    private val rowRuns = HashMap<String, Int>()
    /** Runs of a composable that reads the whole icon map, and of one that reads the whole notifications map. */
    private var iconMapRuns = 0
    private var notificationMapRuns = 0
    // Made once, so the rows' modifier stays equal across recompositions. composed is the point: lint's "unnecessary"
    // is about factories that call no composables, but this one counts how often the factory runs.
    @Suppress("UnnecessaryComposedModifier")
    private val probes = listOf(a, b).associate { app -> app.key to Modifier.composed { rowRuns.merge(app.key, 1, Int::plus); this } }

    @Test fun anIconPublishRecomposesOnlyTheRowWhoseIconChanged() {
        show()
        val before = runs()
        val iconMapBefore = iconMapRuns

        compose.runOnIdle { icons.value = icons.value + (a.key to icon()) }
        compose.waitForIdle()

        assertEquals(iconMapBefore + 1, iconMapRuns)
        assertEquals(before[a.key]!! + 1, rowRuns[a.key])
        assertEquals(before[b.key], rowRuns[b.key])
    }

    @Test fun aNewIconMapWithTheSameIconsRecomposesNoRow() {
        show()
        val before = runs()
        val iconMapBefore = iconMapRuns

        compose.runOnIdle { icons.value = HashMap(icons.value) }
        compose.waitForIdle()

        assertEquals(iconMapBefore + 1, iconMapRuns)
        assertEquals(before, runs())
    }

    @Test fun aRegroupThatLeavesEachRowsListEqualRecomposesNoRow() {
        show()
        val before = runs()
        val notificationMapBefore = notificationMapRuns

        // Another app gets a notification: the store rebuilds every app's list, equal but new for A and B.
        compose.runOnIdle {
            notifications.value = FakeNotifications.byApp() +
                (other.notificationKey to listOf(FakeNotifications.notification("0|phone|1", "Missed call", "Maya Chen", 60_000)))
        }
        compose.waitForIdle()

        assertEquals(notificationMapBefore + 1, notificationMapRuns)
        assertEquals(before, runs())

        // A's own list changing still reaches A's row, and only A's.
        compose.runOnIdle { notifications.value = notifications.value + (a.notificationKey to FakeNotifications.messages()) }
        compose.waitForIdle()
        assertEquals(before[a.key]!! + 1, rowRuns[a.key])
        assertEquals(before[b.key], rowRuns[b.key])
    }

    private fun show() {
        compose.setContent {
            Column {
                for (app in listOf(a, b)) ProbedRow(app)
                MapReader(icons) { iconMapRuns++ }
                MapReader(notifications) { notificationMapRuns++ }
            }
        }
        compose.waitForIdle()
        assertEquals("Both rows are counted", setOf(a.key, b.key), rowRuns.keys)
    }

    @Composable
    private fun ProbedRow(app: AppEntry) = ListAppRow(
        app = app,
        expandKey = "all:${app.key}",
        icons = icons,
        notifications = notifications,
        showIcon = true,
        iconSize = 40.dp,
        expanded = false,
        onLaunch = { _, _ -> },
        onLongPress = {},
        onOpenNotification = { _, _ -> },
        onToggleExpand = {},
        modifier = probes.getValue(app.key),
    )

    private fun runs(): Map<String, Int> = HashMap(rowRuns)

    private fun icon() = IconImage(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap(), isGlyph = false)
}

/** Reads the whole map, as the A–Z list items and the home page did before, so it runs on every new map. */
@Composable
private fun MapReader(map: State<Map<String, *>>, onRun: (size: Int) -> Unit) {
    onRun(map.value.size)
}
