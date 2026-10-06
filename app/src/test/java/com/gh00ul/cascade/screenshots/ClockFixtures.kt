package com.gh00ul.cascade.screenshots

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.util.CalendarEvent
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Everything the clock header can show as a chip: an alarm, a running timer, the next calendar event and charging.
 * Call [install] before `snap`, set `showCalendar = true`, and pass [awaitEventChip] as `afterContent`.
 */
internal object ClockFixtures {
    private const val MINUTE = 60_000L

    fun install(activity: Activity, now: Long = FIXED_NOW) {
        val app = activity.application

        // Alarm. ShadowAlarmManager keeps alarms per AlarmManager instance, and each context has its own: use the
        // activity's, which is the one ClockHeader asks (LocalContext).
        val show = PendingIntent.getActivity(app, 1, Intent(AlarmClock.ACTION_SHOW_ALARMS), PendingIntent.FLAG_IMMUTABLE)
        val operation = PendingIntent.getBroadcast(app, 2, Intent("com.example.clock.ALARM"), PendingIntent.FLAG_IMMUTABLE)
        activity.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(now + 7 * 60 * MINUTE + 25 * MINUTE, show), operation)

        // Timer: an ongoing countdown notification from the clock app.
        NotificationStore.reset(
            arrayOf(FakeNotifications.sbn("com.example.clock", FakeNotifications.timer(app, "Pasta", now + 4 * MINUTE + 32_000))),
            null,
            app.packageName,
        )

        // Calendar: within the hour, so the chip says "in 25 min".
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        FakeCalendarProvider.events = listOf(CalendarEvent(7, "Design review", now + 25 * MINUTE, now + 55 * MINUTE, allDay = false))

        // Battery: charging at 62%, full in 48 minutes.
        @Suppress("DEPRECATION") // The only way to fake the battery broadcast.
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 62)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC),
        )
        shadowOf(activity.getSystemService(BatteryManager::class.java)).setChargeTimeRemaining(48 * MINUTE)
    }

    /** The event query runs on Dispatchers.IO; wait for its chip. */
    val awaitEventChip: ComposeTestRule.() -> Unit = {
        waitUntil(5_000) { onAllNodes(hasContentDescription("Next event", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
