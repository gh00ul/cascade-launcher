package com.gh00ul.cascade.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import com.gh00ul.cascade.data.notificationKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppNotification(
    val key: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val contentIntent: PendingIntent?,
    val autoCancel: Boolean,
    val clearable: Boolean,
)

/** Active notifications grouped by app (see [com.gh00ul.cascade.data.AppEntry.notificationKey]), newest first. */
object NotificationStore {
    private val _byApp = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())
    val byApp: StateFlow<Map<String, List<AppNotification>>> = _byApp.asStateFlow()

    internal fun publish(active: Array<StatusBarNotification>?, ownPackage: String) {
        _byApp.value = active.orEmpty()
            .filter { it.packageName != ownPackage }
            .mapNotNull { sbn -> sbn.toAppNotification()?.let { notificationKey(sbn.packageName, sbn.user) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedByDescending { it.postTime } }
    }

    internal fun clear() {
        _byApp.value = emptyMap()
    }

    /** Opens what the notification points at. Returns false when it has nothing to open. */
    fun open(context: Context, notification: AppNotification): Boolean {
        val intent = notification.contentIntent ?: return false
        return try {
            intent.send(context, 0, null, null, null, null, sendOptions())
            if (notification.autoCancel) dismiss(notification)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    fun dismiss(notification: AppNotification) {
        runCatching { NotificationListener.instance?.cancelNotification(notification.key) }
    }

    fun dismissAll(notifications: List<AppNotification>) = notifications.filter { it.clearable }.forEach(::dismiss)

    // The launcher is in the foreground, so let the notification's app start its activity.
    @Suppress("DEPRECATION")
    private fun sendOptions() = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 34) {
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }.toBundle()
}

private fun StatusBarNotification.toAppNotification(): AppNotification? {
    val n = notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || isOngoing) return null
    val extras = n.extras
    val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
        ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
        ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
    if (title.isEmpty() && text.isEmpty()) return null
    return AppNotification(
        key = key,
        title = title,
        text = text,
        postTime = postTime,
        contentIntent = n.contentIntent,
        autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
        clearable = isClearable,
    )
}
