package com.gh00ul.cascade.util

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FIXED_ZONE
import com.gh00ul.cascade.testing.withFixedZone
import com.gh00ul.cascade.ui.home.widgets.AgendaRow
import com.gh00ul.cascade.ui.home.widgets.agendaRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * The calendar widget's agenda: which events it lists, under which day, in what order, and the query behind it.
 * Times are October 2026 in [FIXED_ZONE]; [FIXED_NOW] is Monday the 5th at 9:41 AM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgendaTest {
    private val zone: TimeZone = TimeZone.getTimeZone(FIXED_ZONE)
    private val monday = LocalDate.of(2026, 10, 5).toEpochDay()
    private var nextId = 1L

    @Test fun listsWhatsLeftOfTodayAndTheNextTwoDaysByDay() {
        val standup = timed("Standup", at(5, 10, 0), at(5, 10, 15))
        val lunch = timed("Lunch", at(5, 12, 0), at(5, 13, 0))
        val dentist = timed("Dentist", at(6, 9, 0), at(6, 10, 0))
        val flight = timed("Flight", at(7, 18, 0), at(7, 21, 0))
        val thursday = timed("Too far", at(8, 9, 0), at(8, 10, 0))
        val listed = shape(listOf(flight, lunch, thursday, dentist, standup))
        assertEquals(listOf("Standup", "Lunch", "Dentist", "Flight"), listed.map { it.event.title })
        assertEquals(listOf(monday, monday, monday + 1, monday + 2), listed.map { it.day })
    }

    @Test fun overEventsGoAndOngoingOnesStayUnderToday() {
        val breakfast = timed("Breakfast", at(5, 8, 0), at(5, 9, 0))
        val endsNow = timed("Ends now", at(5, 9, 0), FIXED_NOW)
        val focus = timed("Focus", at(5, 9, 0), at(5, 11, 0))
        // Began last night, still going.
        val overnight = timed("Night shift", at(4, 22, 0), at(5, 10, 0))
        val listed = shape(listOf(breakfast, endsNow, focus, overnight))
        assertEquals(listOf("Night shift", "Focus"), listed.map { it.event.title })
        assertTrue(listed.all { it.day == monday })
    }

    @Test fun allDayEventsComeFirstEachDay() {
        val standup = timed("Standup", at(5, 10, 0), at(5, 10, 15))
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 5))
        val dentist = timed("Dentist", at(6, 9, 0), at(6, 10, 0))
        val birthday = allDay("Birthday", LocalDate.of(2026, 10, 6))
        assertEquals(
            listOf("Holiday", "Standup", "Birthday", "Dentist"),
            shape(listOf(standup, dentist, holiday, birthday)).map { it.event.title },
        )
    }

    @Test fun allDayEventsUseTheirDatesAndListOnceUnderTheFirstShownDay() {
        // Began Saturday and runs through Tuesday: listed once, under today.
        val conference = allDay("Conference", LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 6))
        val yesterday = allDay("Yesterday", LocalDate.of(2026, 10, 4))
        val wednesday = allDay("Wednesday", LocalDate.of(2026, 10, 7))
        val thursday = allDay("Thursday", LocalDate.of(2026, 10, 8))
        val listed = shape(listOf(conference, yesterday, wednesday, thursday))
        assertEquals(listOf("Conference" to monday, "Wednesday" to monday + 2), listed.map { it.event.title to it.day })
        // Late Monday night in Los Angeles it is already Tuesday in UTC; the dates still read as local ones.
        val lateMonday = shape(listOf(allDay("Monday", LocalDate.of(2026, 10, 5))), now = at(5, 23, 30))
        assertEquals(listOf(monday), lateMonday.map { it.day })
    }

    @Test fun keepsTheLimitAndDropsRepeats() {
        val events = (0 until 6).map { timed("Call $it", at(5, 10 + it, 0), at(5, 10 + it, 30)) }
        assertEquals(listOf("Call 0", "Call 1", "Call 2"), shape(events, limit = 3).map { it.event.title })
        // The same instance twice (a provider quirk) is listed once; a recurring event's next instance is not a repeat.
        val weekly = timed("Weekly", at(5, 15, 0), at(5, 16, 0))
        val nextWeek = weekly.copy(event = weekly.event.copy(begin = at(6, 15, 0), end = at(6, 16, 0)), startDay = julianDay(LocalDate.of(2026, 10, 6)))
        assertEquals(2, shape(listOf(weekly, weekly, nextWeek)).size)
    }

    @Test fun colorsComeThrough() {
        val red = timed("Red", at(5, 10, 0), at(5, 11, 0)).copy(color = 0xFFD50000.toInt())
        assertEquals(0xFFD50000.toInt(), shape(listOf(red)).single().color)
    }

    @Test fun rowsNameTheDaysAndTheTimes() {
        val events = shape(
            listOf(
                allDay("Holiday", LocalDate.of(2026, 10, 5)),
                timed("Focus", at(5, 9, 0), at(5, 11, 0)),
                timed("Lunch", at(5, 12, 30), at(5, 13, 30)),
                timed("Dentist", at(6, 9, 0), at(6, 10, 0)),
                timed("Flight", at(7, 18, 5), at(7, 21, 0)),
            ),
        )
        val rows = withFixedZone {
            val time = SimpleDateFormat("h:mm a", Locale.US)
            agendaRows(events, FIXED_NOW, monday, time) { day -> "Day $day" }
        }
        assertEquals(
            listOf(
                AgendaRow.Day("Today"),
                AgendaRow.Event(events[0], "All day", now = false),
                AgendaRow.Event(events[1], "Now", now = true),
                AgendaRow.Event(events[2], "12:30 PM", now = false),
                AgendaRow.Day("Tomorrow"),
                AgendaRow.Event(events[3], "9:00 AM", now = false),
                AgendaRow.Day("Day ${monday + 2}"),
                AgendaRow.Event(events[4], "6:05 PM", now = false),
            ),
            rows,
        )
    }

    @Test fun queriesVisibleNotDeclinedInstancesAroundTheDaysWithColors() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CalendarContract.AUTHORITY)
        provider.rows = listOf(
            row(1, "  Standup ", at(5, 10, 0), at(5, 10, 15), color = 0xFF0B8043.toInt()),
            row(2, null, at(6, 9, 0), at(6, 10, 0), color = null),
        )
        val listed = withFixedZone { upcomingEvents(app, FIXED_NOW, days = 3, limit = 8) }

        val query = provider.queries.single()
        // Monday's midnight to Thursday's, a day wider on each side.
        val start = ZonedDateTime.of(2026, 10, 5, 0, 0, 0, 0, FIXED_ZONE).toInstant().toEpochMilli() - DAY
        val end = ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, FIXED_ZONE).toInstant().toEpochMilli() + DAY
        assertEquals(listOf("instances", "when", "$start", "$end"), query.uri.pathSegments)
        assertEquals("visible = 1 AND selfAttendeeStatus != 2", query.selection)
        assertTrue(Instances.DISPLAY_COLOR in query.projection)
        assertEquals(
            listOf(AgendaEvent(CalendarEvent(1, "Standup", at(5, 10, 0), at(5, 10, 15), false), monday, 0xFF0B8043.toInt()),
                AgendaEvent(CalendarEvent(2, "Event", at(6, 9, 0), at(6, 10, 0), false), monday + 1, 0)),
            listed,
        )
    }

    @Test fun withoutCalendarPermissionNothingIsQueried() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CalendarContract.AUTHORITY)
        provider.rows = listOf(row(1, "Standup", at(5, 10, 0), at(5, 10, 15)))
        assertEquals(emptyList<AgendaEvent>(), withFixedZone { upcomingEvents(app, FIXED_NOW, 3, 8) })
        assertTrue(provider.queries.isEmpty())
    }

    /** [agenda] at [now] of [rows] in no particular order: the query's order mustn't matter. */
    private fun shape(rows: List<CalendarInstance>, now: Long = FIXED_NOW, days: Int = 3, limit: Int = 8) =
        agenda(rows.shuffled(java.util.Random(7)), now, days, limit, zone)

    private fun timed(title: String, begin: Long, end: Long) =
        CalendarInstance(CalendarEvent(nextId++, title, begin, end, allDay = false), julianDay(begin), julianDay(end))

    /** An all-day event from [first] through [last]: dated at UTC midnights, with START_DAY and END_DAY inclusive. */
    private fun allDay(title: String, first: LocalDate, last: LocalDate = first) = CalendarInstance(
        CalendarEvent(nextId++, title, first.toEpochDay() * DAY, last.plusDays(1).toEpochDay() * DAY, allDay = true),
        julianDay(first),
        julianDay(last),
    )

    private fun julianDay(date: LocalDate) = (date.toEpochDay() + 2440588).toInt()

    private fun julianDay(time: Long) = julianDay(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), FIXED_ZONE).toLocalDate())

    /** October [day] 2026 at [hour]:[minute] in [FIXED_ZONE]. */
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, FIXED_ZONE).toInstant().toEpochMilli()

    private fun row(id: Long, title: String?, begin: Long, end: Long, color: Int? = null) = mapOf(
        Instances.EVENT_ID to id,
        Instances.TITLE to title,
        Instances.BEGIN to begin,
        Instances.END to end,
        Instances.ALL_DAY to 0,
        Instances.START_DAY to julianDay(begin),
        Instances.END_DAY to julianDay(end),
        Instances.DISPLAY_COLOR to color,
    )

    /** Records each query and answers it with [rows], in order, ignoring the selection. */
    class RecordingProvider : ContentProvider() {
        class Query(val uri: Uri, val projection: List<String>, val selection: String?)

        val queries = mutableListOf<Query>()
        var rows: List<Map<String, Any?>> = emptyList()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            queries += Query(uri, columns.toList(), selection)
            return MatrixCursor(columns).apply { for (row in rows) addRow(columns.map { row[it] }) }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
