package com.gh00ul.cascade.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

data class CalendarEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean)

fun hasCalendarAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * The event worth showing under the clock: one starting within three hours, else the one in progress, else the
 * next one within a day, else an all-day event today. Timed events lasting a day or more are skipped. Declined events and hidden calendars are skipped. Blocking: call off the main thread.
 */
fun nextCalendarEvent(context: Context, now: Long): CalendarEvent? {
    if (!hasCalendarAccess(context)) return null
    val day = 24 * 60 * 60 * 1000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, now - day)
        ContentUris.appendId(it, now + day)
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
    // All-day instances are dated in UTC Julian days; compare them with today's local Julian day.
    val today = ((now + TimeZone.getDefault().getOffset(now)) / day + 2440588).toInt()
    var allDay: CalendarEvent? = null
    var ongoing: CalendarEvent? = null
    var upcoming: CalendarEvent? = null
    return runCatching {
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val event = CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Event" },
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) != 0,
                )
                if (event.allDay) {
                    if (allDay == null && c.getInt(5) <= today && today <= c.getInt(6)) allDay = event
                } else if (event.end > now && event.begin < now + day && event.end - event.begin < day) {
                    // Sorted by start: the last one in progress is the one that began most recently.
                    if (event.begin <= now) ongoing = event else if (upcoming == null) upcoming = event
                }
            }
            // Something starting within three hours beats the meeting (or work block) already under way.
            val next = upcoming
            when {
                next != null && (ongoing == null || next.begin - now <= 3 * 60 * 60_000L) -> next
                ongoing != null -> ongoing
                else -> next ?: allDay
            }
        }
    }.getOrNull()
}
