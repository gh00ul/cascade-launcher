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

/** An ongoing notification showing a running clock: a timer, a stopwatch, a call, a recording. */
data class LiveTimer(
    val key: String,
    val packageName: String,
    val title: String,
    /** Wall-clock time the chronometer counts from (or down to, for [countDown]). */
    val base: Long,
    val countDown: Boolean,
    val contentIntent: PendingIntent?,
)

/** Active notifications grouped by app (see [com.gh00ul.cascade.data.AppEntry.notificationKey]), newest first. */
object NotificationStore {
    private val _byApp = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())
    val byApp: StateFlow<Map<String, List<AppNotification>>> = _byApp.asStateFlow()

    /** sbn.key to (app key, notification). Main thread only, like [NotificationListener]'s callbacks. */
    private val entries = LinkedHashMap<String, Pair<String, AppNotification>>()

    private val _timers = MutableStateFlow<List<LiveTimer>>(emptyList())
    /** Running timers, stopwatches and calls, for the clock header. */
    val timers: StateFlow<List<LiveTimer>> = _timers.asStateFlow()
    private val timerEntries = LinkedHashMap<String, LiveTimer>()

    /**
     * False while home is stopped: nothing collects [byApp] then, so callbacks only record their change in [entries]
     * and [resume] regroups once. Starts true, so a listener that connects before home first starts still fills it.
     */
    private var live = true
    /**
     * The ranking that came with the last change while paused. Each RankingMap the listener gets is complete, so the
     * latest one is all [resume] needs.
     */
    private var pendingRanking: RankingMap? = null
    /** [entries] or the ranking changed while paused. */
    private var stale = false

    /**
     * Home stopped (another app in front, screen off). Regrouping allocates a map and a list per app for every post,
     * removal and ranking update; until [resume], callbacks keep only [entries] current, which is O(1). Chatty ongoing
     * updates (navigation, downloads, media) already return before regrouping. Timers still emit: they change rarely,
     * and the clock header collects them lifecycle-aware. Main thread only.
     */
    fun pause() {
        live = false
    }

    /** Home started: regroup once if anything changed while paused, before the collectors resubscribe. */
    fun resume() {
        live = true
        if (stale) group(pendingRanking)
    }

    /** The full list, read once per connection; after that each callback applies its own change. */
    internal fun reset(active: Array<StatusBarNotification>?, ranking: RankingMap?, ownPackage: String) {
        entries.clear()
        timerEntries.clear()
        for (sbn in active.orEmpty()) {
            put(sbn, ownPackage)
            putTimer(sbn, ownPackage)
        }
        emit(ranking)
        emitTimers()
    }

    internal fun posted(sbn: StatusBarNotification, ranking: RankingMap?, ownPackage: String) {
        // Ongoing updates that were never listed (navigation, downloads) change nothing.
        if (put(sbn, ownPackage)) emit(ranking)
        if (putTimer(sbn, ownPackage)) emitTimers()
    }

    internal fun removed(sbnKey: String, ranking: RankingMap?) {
        if (entries.remove(sbnKey) != null) emit(ranking)
        if (timerEntries.remove(sbnKey) != null) emitTimers()
    }

    internal fun ranked(ranking: RankingMap?) = emit(ranking)

    internal fun clear() {
        entries.clear()
        _byApp.value = emptyMap()
        stale = false
        pendingRanking = null
        timerEntries.clear()
        _timers.value = emptyList()
    }

    /** Returns whether the timer list changed. */
    private fun putTimer(sbn: StatusBarNotification, ownPackage: String): Boolean {
        val timer = if (sbn.packageName == ownPackage) null else sbn.toLiveTimer()
        if (timer == null) return timerEntries.remove(sbn.key) != null
        return timerEntries.put(sbn.key, timer) != timer
    }

    private fun emitTimers() {
        _timers.value = timerEntries.values.toList()
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
        if (!live) {
            pendingRanking = ranking
            stale = true
            return
        }
        group(ranking)
    }

    private fun group(ranking: RankingMap?) {
        stale = false
        pendingRanking = null
        val r = Ranking()
        val previous = _byApp.value
        _byApp.value = entries.values
            .mapNotNull { (app, n) ->
                if (ranking == null || !ranking.getRanking(n.key, r)) return@mapNotNull app to n
                // The system hides notifications from paused or suspended apps (Focus mode, app timers, Family Link).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && r.isSuspended) return@mapNotNull null
                app to n.copy(showBadge = r.canShowBadge())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (app, list) ->
                val sorted = list.sortedByDescending { it.postTime }
                // An app whose notifications didn't change keeps its list, so its row (which Compose compares by
                // identity) skips recomposing when another app posts.
                previous[app]?.takeIf { it == sorted } ?: sorted
            }
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

private fun StatusBarNotification.toLiveTimer(): LiveTimer? {
    val n = notification
    val extras = n.extras
    if (!extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || n.`when` <= 0) return null
    if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
    return LiveTimer(
        key = key,
        packageName = packageName,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty(),
        base = n.`when`,
        countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        contentIntent = n.contentIntent,
    )
}
