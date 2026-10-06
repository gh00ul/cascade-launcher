package com.gh00ul.cascade.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification

/**
 * Runs once the user grants notification access; mirrors active notifications into [NotificationStore]
 * and lets [NowPlaying] follow media sessions.
 */
class NotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        NotificationStore.reset(runCatching { activeNotifications }.getOrNull(), currentRanking, packageName)
        NowPlaying.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationStore.clear()
        NowPlaying.stop()
    }

    // Apply what each callback delivers: re-reading every notification is a binder call on the main thread.
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        // A login code goes to the clipboard as it arrives (never for the ones already there when the listener connects).
        NotificationStore.posted(sbn, rankingMap, packageName)?.let { offerLoginCode(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) NotificationStore.removed(sbn.key, rankingMap)
    }

    // Pausing an app (Focus mode, app timers), Do Not Disturb and dot settings arrive only as ranking changes.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = NotificationStore.ranked(rankingMap)

    companion object {
        @Volatile
        var instance: NotificationListener? = null
            private set
    }
}
