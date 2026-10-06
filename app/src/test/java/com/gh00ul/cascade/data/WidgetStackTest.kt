package com.gh00ul.cascade.data

import com.gh00ul.cascade.ui.home.widgets.cells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget stack's list: what can go in it, adding, moving, and dropping app widgets the system lost. */
class WidgetStackTest {
    private val calendar = WIDGET_CALENDAR
    private val weather = WIDGET_WEATHER

    @Test fun appWidgetEntriesCarryTheirId() {
        assertEquals(42, appWidgetId("app:42"))
        assertEquals(0, appWidgetId("app:0"))
        assertNull(appWidgetId(calendar))
        assertNull(appWidgetId(weather))
        assertNull(appWidgetId("app:"))
        assertNull(appWidgetId("app:x"))
        assertNull(appWidgetId("42"))
        assertNull(appWidgetId("apps:42"))
    }

    @Test fun onlyKnownEntriesAreWidgets() {
        for (entry in listOf(calendar, weather, "app:7")) assertTrue(entry, isStackWidget(entry))
        for (entry in listOf("", "clock", "app:", "app:seven", "Calendar")) assertFalse(entry, isStackWidget(entry))
    }

    @Test fun addingGoesAtTheEndOnce() {
        assertEquals(listOf(calendar), addWidget(emptyList(), calendar))
        assertEquals(listOf(calendar, "app:3"), addWidget(listOf(calendar), "app:3"))
        // Each built-in at most once, and an app widget id too.
        assertEquals(listOf(calendar, weather), addWidget(listOf(calendar, weather), calendar))
        assertEquals(listOf("app:3"), addWidget(listOf("app:3"), "app:3"))
        // Not a widget: nothing changes.
        assertEquals(listOf(calendar), addWidget(listOf(calendar), "clock"))
    }

    @Test fun aFullStackTakesNoMore() {
        val full = listOf(calendar, weather, "app:1", "app:2")
        assertEquals(MAX_STACK_WIDGETS, full.size)
        assertEquals(full, addWidget(full, "app:3"))
        assertEquals(listOf(calendar, "app:1", "app:2", weather), addWidget(listOf(calendar, "app:1", "app:2"), weather))
    }

    @Test fun movingReorders() {
        val stack = listOf(calendar, weather, "app:1")
        assertEquals(listOf(weather, calendar, "app:1"), moveWidget(stack, 0, 1))
        assertEquals(listOf(calendar, "app:1", weather), moveWidget(stack, 2, 1))
        assertEquals(listOf("app:1", calendar, weather), moveWidget(stack, 2, 0))
        // Out of range or nowhere: unchanged.
        assertEquals(stack, moveWidget(stack, 0, -1))
        assertEquals(stack, moveWidget(stack, 2, 3))
        assertEquals(stack, moveWidget(stack, 5, 0))
        assertEquals(stack, moveWidget(stack, 1, 1))
    }

    @Test fun deadAppWidgetsLeaveBuiltInsStay() {
        val stack = listOf("app:1", calendar, "app:2", weather, "app:3")
        val alive = setOf(2)
        assertEquals(listOf(calendar, "app:2", weather), dropDeadAppWidgets(stack) { it in alive })
        assertEquals(stack, dropDeadAppWidgets(stack) { true })
        assertEquals(listOf(calendar, weather), dropDeadAppWidgets(stack) { false })
    }

    @Test fun deadCheckAsksOnlyAboutAppWidgets() {
        val asked = mutableListOf<Int>()
        dropDeadAppWidgets(listOf(calendar, "app:9", weather)) { asked += it; true }
        assertEquals(listOf(9), asked)
    }

    /** n cells take 70n − 30 dp, and Android 12's larger cells (73n − 16 dp wide) still count as n. */
    @Test fun cellsFromMinimumSize() {
        assertEquals(1, cells(40f))
        assertEquals(2, cells(110f))
        assertEquals(3, cells(180f))
        assertEquals(4, cells(250f))
        assertEquals(2, cells(130f))
        assertEquals(4, cells(276f))
        assertEquals(1, cells(0f))
    }
}
