package com.gh00ul.cascade.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.gh00ul.cascade.data.notificationKey
import com.gh00ul.cascade.util.sendFromLauncher
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
    /** False when the user turned dots off for the app or channel, or Do Not Disturb hides them. */
    val showBadge: Boolean = true,
)

/** Active notifications grouped by app (see [com.gh00ul.cascade.data.AppEntry.notificationKey]), newest first. */
object NotificationStore {
    private val _byApp = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())
    val byApp: StateFlow<Map<String, List<AppNotification>>> = _byApp.asStateFlow()

    /** sbn.key to (app key, notification). Main thread only, like [NotificationListener]'s callbacks. */
    private val entries = LinkedHashMap<String, Pair<String, AppNotification>>()

    /** The full list, read once per connection; after that each callback applies its own change. */
    internal fun reset(active: Array<StatusBarNotification>?, ranking: RankingMap?, ownPackage: String) {
        entries.clear()
        for (sbn in active.orEmpty()) put(sbn, ownPackage)
        emit(ranking)
    }

    internal fun posted(sbn: StatusBarNotification, ranking: RankingMap?, ownPackage: String) {
        // Ongoing updates that were never listed (navigation, downloads) change nothing.
        if (put(sbn, ownPackage)) emit(ranking)
    }

    internal fun removed(sbnKey: String, ranking: RankingMap?) {
        if (entries.remove(sbnKey) != null) emit(ranking)
    }

    internal fun ranked(ranking: RankingMap?) = emit(ranking)

    internal fun clear() {
        entries.clear()
        _byApp.value = emptyMap()
    }

    /** Returns whether the list may have changed. */
    private fun put(sbn: StatusBarNotification, ownPackage: String): Boolean {
        val n = if (sbn.packageName == ownPackage) null else sbn.toAppNotification()
        // An update can turn a notification ongoing or into a group summary; it leaves the list then.
        if (n == null) return entries.remove(sbn.key) != null
        entries[sbn.key] = notificationKey(sbn.packageName, sbn.user) to n
        return true
    }

    private fun emit(ranking: RankingMap?) {
        val r = Ranking()
        _byApp.value = entries.values
            .mapNotNull { (app, n) ->
                if (ranking == null || !ranking.getRanking(n.key, r)) return@mapNotNull app to n
                // The system hides notifications from paused or suspended apps (Focus mode, app timers, Family Link).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && r.isSuspended) return@mapNotNull null
                app to n.copy(showBadge = r.canShowBadge())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedByDescending { it.postTime } }
    }

    /** Opens what the notification points at. Returns false when it has nothing to open. */
    fun open(context: Context, notification: AppNotification): Boolean {
        val opened = notification.contentIntent?.sendFromLauncher(context) ?: false
        if (opened && notification.autoCancel) dismiss(notification)
        return opened
    }

    fun dismiss(notification: AppNotification) {
        runCatching { NotificationListener.instance?.cancelNotification(notification.key) }
    }

    fun dismissAll(notifications: List<AppNotification>) = notifications.filter { it.clearable }.forEach(::dismiss)
}

private fun StatusBarNotification.toAppNotification(): AppNotification? {
    val n = notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || isOngoing) return null
    val extras = n.extras
    // Media notifications become dismissible when paused; the in-row player already shows them.
    if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT) return null
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
