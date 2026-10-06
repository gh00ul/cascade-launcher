package com.gh00ul.cascade.screenshots

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Instances
import com.gh00ul.cascade.util.CalendarEvent
import java.util.TimeZone

/**
 * Answers nextCalendarEvent's Instances query with [events], ignoring the selection. Registered for
 * CalendarContract.AUTHORITY by [ClockFixtures.install].
 */
class FakeCalendarProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.START_DAY, Instances.END_DAY)
        val cursor = MatrixCursor(columns)
        for (e in events.sortedBy { it.begin }) {
            cursor.addRow(columns.map { column ->
                when (column) {
                    Instances.EVENT_ID -> e.id
                    Instances.TITLE -> e.title
                    Instances.BEGIN -> e.begin
                    Instances.END -> e.end
                    Instances.ALL_DAY -> if (e.allDay) 1 else 0
                    Instances.START_DAY -> julianDay(e.begin)
                    Instances.END_DAY -> julianDay(e.end)
                    else -> null
                }
            })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        @Volatile var events: List<CalendarEvent> = emptyList()

        private fun julianDay(time: Long): Int {
            val day = 24 * 60 * 60 * 1000L
            return ((time + TimeZone.getDefault().getOffset(time)) / day + 2440588).toInt()
        }
    }
}
