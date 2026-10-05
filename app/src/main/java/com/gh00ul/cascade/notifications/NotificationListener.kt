package com.gh00ul.cascade.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Runs once the user grants notification access; mirrors active notifications into [NotificationStore]. */
class NotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        sync()
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationStore.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = sync()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = sync()

    private fun sync() = NotificationStore.publish(runCatching { activeNotifications }.getOrNull(), packageName)

    companion object {
        @Volatile
        var instance: NotificationListener? = null
            private set
    }
}
