package com.gh00ul.cascade.testing

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.gh00ul.cascade.notifications.AppNotification

/** Notifications for tests. Times are relative to [FIXED_NOW], so rows read "3 min. ago" and the like. */
object FakeNotifications {
    private const val MINUTE = 60_000L

    fun notification(key: String, title: String, text: String, ageMs: Long, clearable: Boolean = true, now: Long = FIXED_NOW) =
        AppNotification(key, title, text, now - ageMs, contentIntent = null, autoCancel = true, clearable = clearable)

    /** Three conversations in Messages, newest first. */
    fun messages() = listOf(
        notification("0|messages|1", "Maya Chen", "Are we still on for 7? I can grab the table if you're running late.", 3 * MINUTE),
        notification("0|messages|2", "Sam Ortiz", "Sent a photo", 40 * MINUTE),
        notification("0|messages|3", "Mom", "Call me when you land, no rush", 2 * 60 * MINUTE),
    )

    fun mail() = listOf(
        notification("0|mail|1", "Delta Air Lines", "Your trip confirmation: SEA to JFK, Friday 7:05 AM", 18 * MINUTE),
    )

    /** What NotificationStore.byApp would hold for these: keyed by the app's notificationKey. */
    fun byApp(): Map<String, List<AppNotification>> = mapOf(
        FakeApps.messages.notificationKey to messages(),
        FakeApps.mail.notificationKey to mail(),
    )

    /** The public StatusBarNotification constructor; the uid matches [user]. */
    @Suppress("DEPRECATION")
    fun sbn(pkg: String, notification: Notification, id: Int = 1, user: UserHandle = Process.myUserHandle(), postTime: Long = FIXED_NOW) =
        StatusBarNotification(pkg, pkg, id, null, user.hashCode() * 100_000 + 10_123, 0, 0, notification, user, postTime)

    fun message(context: Context, title: String, text: String) = Notification.Builder(context, "messages")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setContentTitle(title)
        .setContentText(text)
        .build()

    /** A running countdown, like the clock app's timer notification. */
    fun timer(context: Context, title: String, endsAt: Long) = Notification.Builder(context, "timers")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(endsAt)
        .setShowWhen(true)
        .setOngoing(true)
        .setContentTitle(title)
        .build()

    /** A running stopwatch counting up from [startedAt]; a null [title] gives it no title. */
    fun stopwatch(context: Context, title: String?, startedAt: Long) = Notification.Builder(context, "timers")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setUsesChronometer(true)
        .setWhen(startedAt)
        .setShowWhen(true)
        .setOngoing(true)
        .setContentTitle(title)
        .build()

    /** A chat named only by EXTRA_CONVERSATION_TITLE, with no EXTRA_TITLE. */
    fun conversation(context: Context, conversationTitle: String, text: String) = Notification.Builder(context, "messages")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .addExtras(Bundle().apply { putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, conversationTitle) })
        .setContentText(text)
        .build()

    /** An expanded-text notification whose body is only in EXTRA_BIG_TEXT, with no EXTRA_TEXT. */
    fun bigText(context: Context, title: String, body: String) = Notification.Builder(context, "messages")
        .setSmallIcon(android.R.drawable.sym_def_app_icon)
        .setContentTitle(title)
        .setStyle(Notification.BigTextStyle().bigText(body))
        .build()
}
