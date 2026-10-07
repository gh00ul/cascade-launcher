package com.gh00ul.cascade.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.util.TimeZone

data class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

fun hasCalendarAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * The event worth showing under the clock: one starting within three hours, else the one in progress, else the
 * next one within a day, else an all-day event today. Timed events lasting a day or more are skipped. Declined
 * events and hidden calendars are skipped. Blocking: call off the main thread.
 */
fun nextCalendarEvent(context: Context, now: Long): CalendarEvent? {
    if (!hasCalendarAccess(context)) return null
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now - DAY)
        ContentUris.appendId(it, now + DAY)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.START_DAY,
        CalendarContract.Instances.END_DAY,
    )
    val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
        "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
    return runCatching {
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val rows = mutableListOf<CalendarInstance>()
            while (c.moveToNext()) {
                val event = CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Event" },
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) != 0,
                )
                rows += CalendarInstance(event, startDay = c.getInt(5), endDay = c.getInt(6))
            }
            chooseCalendarEvent(rows, now)
        }
    }.getOrNull()
}

/**
 * One row of the Instances query: the event, its START_DAY and END_DAY (Julian days, both inclusive), and its
 * DISPLAY_COLOR (ARGB; 0 when not asked for, or when the provider has none).
 */
internal data class CalendarInstance(val event: CalendarEvent, val startDay: Int, val endDay: Int, val color: Int = 0)

/** [nextCalendarEvent]'s choice among [rows], sorted by begin as the query returns them. Today is [now]'s day in [zone]. */
internal fun chooseCalendarEvent(rows: List<CalendarInstance>, now: Long, zone: TimeZone = TimeZone.getDefault()): CalendarEvent? {
    // All-day instances are dated in UTC Julian days; compare them with today's local Julian day.
    val today = ((now + zone.getOffset(now)) / DAY + 2440588).toInt()
    var allDay: CalendarEvent? = null
    var ongoing: CalendarEvent? = null
    var upcoming: CalendarEvent? = null
    for ((event, startDay, endDay) in rows) {
        if (event.allDay) {
            if (allDay == null && startDay <= today && today <= endDay) allDay = event
        } else if (event.end > now && event.begin < now + DAY && event.end - event.begin < DAY) {
            // Sorted by start: the last one in progress is the one that began most recently.
            if (event.begin <= now) ongoing = event else if (upcoming == null) upcoming = event
        }
    }
    // Something starting within three hours beats the meeting (or work block) already under way.
    val next = upcoming
    return when {
        next != null && (ongoing == null || next.begin - now <= 3 * 60 * 60_000L) -> next
        ongoing != null -> ongoing
        else -> next ?: allDay
    }
}

/**
 * An event in the calendar widget's agenda: listed under [day] (a local epoch day: the day it starts, or today for
 * one already under way) in its calendar's [color] (ARGB, 0 for none).
 */
data class AgendaEvent(val event: CalendarEvent, val day: Long, val color: Int)

/**
 * The calendar widget's agenda: what's left of today and the next [days] - 1 days, at most [limit] events, by day,
 * each day's all-day events first and then the rest by start. Events that are over are left out, and one under way
 * (or an all-day event that began before today) is listed under today. Declined events and hidden calendars are
 * skipped, as for [nextCalendarEvent]. Null when the calendar couldn't be read, so that isn't taken for an empty one;
 * empty without calendar access, which the caller asks for. Blocking: call off the main thread.
 */
fun upcomingEvents(context: Context, now: Long, days: Int, limit: Int): List<AgendaEvent>? {
    if (!hasCalendarAccess(context) || days <= 0 || limit <= 0) return emptyList()
    val zone = TimeZone.getDefault()
    val today = localDay(now, zone)
    // A day wider on both sides: all-day instances are dated at UTC midnights, which can fall outside the local days.
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, startOfDay(today, zone) - DAY)
        ContentUris.appendId(it, startOfDay(today + days, zone) + DAY)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.START_DAY,
        CalendarContract.Instances.END_DAY,
        CalendarContract.Instances.DISPLAY_COLOR,
    )
    val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
        "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
    // The provider runs in another process and can fail in any way there (access revoked a moment ago, the provider
    // busy or gone); no cursor at all is a failed read too. Each is reported as null, for the widget to say so.
    return runCatching {
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val rows = mutableListOf<CalendarInstance>()
            while (c.moveToNext()) {
                val event = CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Event" },
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) != 0,
                )
                rows += CalendarInstance(event, startDay = c.getInt(5), endDay = c.getInt(6), color = if (c.isNull(7)) 0 else c.getInt(7))
            }
            agenda(rows, now, days, limit, zone)
        }
    }.getOrNull()
}

/** [upcomingEvents]' shaping of the query's [rows], in any order. Today is [now]'s day in [zone]. */
internal fun agenda(rows: List<CalendarInstance>, now: Long, days: Int, limit: Int, zone: TimeZone = TimeZone.getDefault()): List<AgendaEvent> {
    val today = localDay(now, zone)
    val last = today + days - 1
    val windowEnd = startOfDay(today + days, zone)
    return rows.mapNotNull { (event, startDay, endDay, color) ->
        val day = if (event.allDay) {
            // All-day instances are dated in UTC Julian days, like the dates they stand for; END_DAY is inclusive.
            val first = (startDay - JULIAN_EPOCH_DAY).toLong()
            val final = (endDay - JULIAN_EPOCH_DAY).toLong()
            if (final < today || first > last) return@mapNotNull null
            maxOf(first, today)
        } else {
            if (event.end <= now || event.begin >= windowEnd) return@mapNotNull null
            maxOf(localDay(event.begin, zone), today)
        }
        AgendaEvent(event, day, color)
    }
        .distinctBy { it.event.id to it.event.begin }
        .sortedWith(compareBy<AgendaEvent>({ it.day }, { !it.event.allDay }, { it.event.begin }, { it.event.end }, { it.event.title }))
        .take(limit)
}

/** [time]'s day in [zone], as an epoch day. */
internal fun localDay(time: Long, zone: TimeZone): Long = Math.floorDiv(time + zone.getOffset(time), DAY)

/** When the local epoch [day] begins in [zone]. */
private fun startOfDay(day: Long, zone: TimeZone): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone.toZoneId()).toInstant().toEpochMilli()

/** The Julian day of the epoch day 0 (January 1 1970). */
private const val JULIAN_EPOCH_DAY = 2440588

private const val DAY = 24 * 60 * 60 * 1000L
