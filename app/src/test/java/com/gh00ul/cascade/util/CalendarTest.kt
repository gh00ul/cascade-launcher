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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Which event the clock header shows. The rule is tested through [chooseCalendarEvent] with rows sorted by begin, as
 * the Instances query returns them; the query itself (declined events, hidden calendars) through
 * [nextCalendarEvent] against a recording provider.
 *
 * Times are October 2026 in [FIXED_ZONE]; [FIXED_NOW] is Monday the 5th at 9:41 AM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CalendarTest {
    private val zone: TimeZone = TimeZone.getTimeZone(FIXED_ZONE)
    private var nextId = 1L

    @Test fun upcomingWithinThreeHoursBeatsOngoing() {
        val focus = timed("Focus", at(5, 9, 0), at(5, 12, 0))
        val standup = timed("Standup", at(5, 10, 0), at(5, 10, 15))
        assertEquals(standup.event, choose(focus, standup))
    }

    @Test fun threeHoursAheadIsInclusive() {
        val work = timed("Work", at(5, 9, 0), at(5, 17, 0))
        val atThree = timed("Call", FIXED_NOW + 3 * HOUR, FIXED_NOW + 4 * HOUR)
        assertEquals(atThree.event, choose(work, atThree))
        val justAfter = timed("Call", FIXED_NOW + 3 * HOUR + 1, FIXED_NOW + 4 * HOUR)
        assertEquals(work.event, choose(work, justAfter))
    }

    @Test fun ongoingBeatsUpcomingBeyondThreeHours() {
        val meeting = timed("Meeting", at(5, 9, 0), at(5, 10, 0))
        val lunch = timed("Lunch", at(5, 14, 0), at(5, 15, 0))
        assertEquals(meeting.event, choose(meeting, lunch))
    }

    @Test fun nextWithinADayBeatsAllDay() {
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 5))
        val dinner = timed("Dinner", at(5, 19, 41), at(5, 21, 0))
        assertEquals(dinner.event, choose(holiday, dinner))
        val breakfast = timed("Breakfast", at(6, 9, 0), at(6, 10, 0))
        assertEquals(breakfast.event, choose(holiday, breakfast))
    }

    @Test fun ongoingBeatsAllDay() {
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 5))
        val meeting = timed("Meeting", at(5, 9, 0), at(5, 10, 0))
        assertEquals(meeting.event, choose(holiday, meeting))
    }

    @Test fun theMostRecentlyBegunOngoingEventWins() {
        val day = timed("Workday", at(5, 8, 0), at(5, 17, 0))
        val block = timed("Focus", at(5, 9, 0), at(5, 11, 0))
        val call = timed("Call", at(5, 9, 30), at(5, 10, 0))
        assertEquals(call.event, choose(call, day, block))
    }

    @Test fun beginningNowIsOngoing() {
        val starting = timed("Starting", FIXED_NOW, FIXED_NOW + 30 * MINUTE)
        assertEquals(starting.event, choose(starting))
        // Were it upcoming, it would be the first upcoming event and win; as the ongoing one, the next event does.
        val next = timed("Next", FIXED_NOW + HOUR, FIXED_NOW + 2 * HOUR)
        assertEquals(next.event, choose(starting, next))
        val later = timed("Later", FIXED_NOW + 4 * HOUR, FIXED_NOW + 5 * HOUR)
        assertEquals(starting.event, choose(starting, later))
    }

    @Test fun endingNowIsOver() {
        val over = timed("Over", at(5, 9, 0), FIXED_NOW)
        assertNull(choose(over))
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 5))
        assertEquals(holiday.event, choose(over, holiday))
    }

    @Test fun timedEventsOfADayOrMoreAreSkipped() {
        val lunch = timed("Lunch", at(5, 14, 0), at(5, 15, 0))
        // Ongoing and kept, either would beat lunch, which is more than three hours away.
        val fullDay = timed("Shift", at(5, 9, 0), at(6, 9, 0))
        assertEquals(lunch.event, choose(fullDay, lunch))
        val trip = timed("Trip", at(4, 8, 0), at(7, 8, 0))
        assertEquals(lunch.event, choose(trip, lunch))
        val almostADay = timed("Shift", at(5, 9, 0), at(6, 8, 59))
        assertEquals(almostADay.event, choose(almostADay, lunch))
        // With nothing else timed, the choice falls through to the all-day event.
        val holiday = allDay("Holiday", LocalDate.of(2026, 10, 5))
        assertNull(choose(trip))
        assertEquals(holiday.event, choose(trip, holiday))
    }

    @Test fun eventsADayAwayAreIgnored() {
        val tomorrow = timed("Tomorrow", FIXED_NOW + DAY, FIXED_NOW + DAY + HOUR)
        assertNull(choose(tomorrow))
        val justInside = timed("Tomorrow", FIXED_NOW + DAY - 1, FIXED_NOW + DAY + HOUR)
        assertEquals(justInside.event, choose(justInside))
    }

    @Test fun allDayFallbackMustCoverToday() {
        val today = allDay("Today", LocalDate.of(2026, 10, 5))
        assertEquals(today.event, choose(today))
        val spanning = allDay("Conference", LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 6))
        assertEquals(spanning.event, choose(spanning))
        assertNull(choose(allDay("Yesterday", LocalDate.of(2026, 10, 4))))
        assertNull(choose(allDay("Tomorrow", LocalDate.of(2026, 10, 6))))
        // The first one that covers today.
        val second = allDay("Second", LocalDate.of(2026, 10, 5))
        assertEquals(today.event, choose(today, second))
    }

    /** All-day days are compared with the local day: at 11:30 PM in Los Angeles it is already the next day in UTC. */
    @Test fun allDayUsesTheLocalDay() {
        assertEquals(2461319, julianDay(LocalDate.of(2026, 10, 5)))
        val monday = allDay("Monday", LocalDate.of(2026, 10, 5))
        val tuesday = allDay("Tuesday", LocalDate.of(2026, 10, 6))
        val lateMonday = at(5, 23, 30)
        assertEquals(monday.event, choose(monday, now = lateMonday))
        assertNull(choose(tuesday, now = lateMonday))
        val earlyTuesday = at(6, 0, 10)
        assertNull(choose(monday, now = earlyTuesday))
        assertEquals(tuesday.event, choose(tuesday, now = earlyTuesday))
    }

    @Test fun nothingToShow() {
        assertNull(chooseCalendarEvent(emptyList(), FIXED_NOW, zone))
    }

    @Test fun queriesVisibleNotDeclinedInstancesAroundNow() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CalendarContract.AUTHORITY)
        withFixedZone { nextCalendarEvent(app, FIXED_NOW) }

        val query = provider.queries.single()
        assertEquals(CalendarContract.AUTHORITY, query.uri.authority)
        assertEquals(listOf("instances", "when", "${FIXED_NOW - DAY}", "${FIXED_NOW + DAY}"), query.uri.pathSegments)
        // Declined instances (ATTENDEE_STATUS_DECLINED is 2) and hidden calendars are left out by the provider.
        assertEquals(2, CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
        assertEquals("visible = 1 AND selfAttendeeStatus != 2", query.selection)
        assertEquals("begin ASC", query.sortOrder)
    }

    @Test fun providerRowsFlowThroughToTheChoice() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CalendarContract.AUTHORITY)
        val monday = julianDay(LocalDate.of(2026, 10, 5))
        val holidayBegin = utcMidnight(LocalDate.of(2026, 10, 5))
        val holidayEnd = utcMidnight(LocalDate.of(2026, 10, 6))
        val holiday = row(1, "Holiday", holidayBegin, holidayEnd, true, monday, monday)
        fun next() = withFixedZone { nextCalendarEvent(app, FIXED_NOW) }

        provider.rows = listOf(
            holiday,
            row(2, "Focus", at(5, 9, 0), at(5, 12, 0), false, monday, monday),
            row(3, "  Standup ", at(5, 10, 0), at(5, 10, 15), false, monday, monday),
        )
        assertEquals(CalendarEvent(3, "Standup", at(5, 10, 0), at(5, 10, 15), allDay = false), next())

        provider.rows = listOf(holiday)
        assertEquals(CalendarEvent(1, "Holiday", holidayBegin, holidayEnd, allDay = true), next())

        // An untitled event.
        provider.rows = listOf(row(4, null, at(5, 10, 0), at(5, 11, 0), false, monday, monday))
        assertEquals("Event", next()?.title)
    }

    @Test fun withoutCalendarPermissionNothingIsQueried() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val provider = Robolectric.setupContentProvider(RecordingProvider::class.java, CalendarContract.AUTHORITY)
        provider.rows = listOf(row(2, "Focus", at(5, 9, 0), at(5, 12, 0), false, 0, 0))
        assertNull(withFixedZone { nextCalendarEvent(app, FIXED_NOW) })
        assertTrue(provider.queries.isEmpty())
    }

    /** Sorts [rows] by begin, as the query does, and chooses at [now]. */
    private fun choose(vararg rows: CalendarInstance, now: Long = FIXED_NOW) =
        chooseCalendarEvent(rows.sortedBy { it.event.begin }, now, zone)

    private fun timed(title: String, begin: Long, end: Long) =
        CalendarInstance(CalendarEvent(nextId++, title, begin, end, allDay = false), julianDay(begin), julianDay(end))

    /** An all-day event from [first] through [last]: dated at UTC midnights, with START_DAY and END_DAY inclusive. */
    private fun allDay(title: String, first: LocalDate, last: LocalDate = first) = CalendarInstance(
        CalendarEvent(nextId++, title, utcMidnight(first), utcMidnight(last.plusDays(1)), allDay = true),
        julianDay(first),
        julianDay(last),
    )

    private fun julianDay(date: LocalDate) = (date.toEpochDay() + 2440588).toInt()

    private fun julianDay(time: Long) = julianDay(ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), FIXED_ZONE).toLocalDate())

    private fun utcMidnight(date: LocalDate) = date.toEpochDay() * DAY

    /** October [day] 2026 at [hour]:[minute] in [FIXED_ZONE]. */
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, FIXED_ZONE).toInstant().toEpochMilli()

    private fun row(id: Long, title: String?, begin: Long, end: Long, allDay: Boolean, startDay: Int, endDay: Int) = mapOf(
        Instances.EVENT_ID to id,
        Instances.TITLE to title,
        Instances.BEGIN to begin,
        Instances.END to end,
        Instances.ALL_DAY to if (allDay) 1 else 0,
        Instances.START_DAY to startDay,
        Instances.END_DAY to endDay,
    )

    /** Records each query and answers it with [rows], in order, ignoring the selection. */
    class RecordingProvider : ContentProvider() {
        class Query(val uri: Uri, val selection: String?, val sortOrder: String?)

        val queries = mutableListOf<Query>()
        var rows: List<Map<String, Any?>> = emptyList()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            queries += Query(uri, selection, sortOrder)
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply { for (row in rows) addRow(columns.map { row[it] }) }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
