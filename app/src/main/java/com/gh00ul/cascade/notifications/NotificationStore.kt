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
    /** The login code it carries ("482913 is your verification code"), digits only; see [findLoginCode]. */
    val code: String? = null,
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

/**
 * Something under way, from an ongoing notification: an Android 16 Live Update (a ride, a delivery), a route being
 * navigated, or anything else with a progress bar (a download, an install). [status] is the short part a chip never
 * cuts ("4 min", "42%"), maybe empty; [percent] its progress when it has a determinate one.
 */
data class LiveUpdate(
    val key: String,
    val packageName: String,
    val kind: Kind,
    val title: String,
    val status: String,
    val percent: Int?,
    val contentIntent: PendingIntent?,
) {
    enum class Kind { NAVIGATION, PROGRESS, OTHER }
}

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

    private val _liveUpdates = MutableStateFlow<List<LiveUpdate>>(emptyList())
    /** Things under way (rides, deliveries, a route, downloads), oldest first, for the clock header. */
    val liveUpdates: StateFlow<List<LiveUpdate>> = _liveUpdates.asStateFlow()
    private val liveEntries = LinkedHashMap<String, LiveUpdate>()
    /** [liveEntries] changed while paused. */
    private var liveStale = false

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
     * updates (navigation, downloads) only update [liveEntries], and emit on [resume] too. Timers still emit: they change
     * rarely, and the clock header collects them lifecycle-aware. Main thread only.
     */
    fun pause() {
        live = false
    }

    /** Home started: regroup once if anything changed while paused, before the collectors resubscribe. */
    fun resume() {
        live = true
        if (stale) group(pendingRanking)
        if (liveStale) emitLive()
    }

    /** The full list, read once per connection; after that each callback applies its own change. */
    internal fun reset(active: Array<StatusBarNotification>?, ranking: RankingMap?, ownPackage: String) {
        entries.clear()
        timerEntries.clear()
        liveEntries.clear()
        for (sbn in active.orEmpty()) {
            put(sbn, ownPackage)
            putTimer(sbn, ownPackage)
            putLive(sbn, ranking, ownPackage)
        }
        emit(ranking)
        emitTimers()
        signalLive()
    }

    /** Applies a post; returns the notification as the list now shows it, or null when it isn't one the list shows. */
    internal fun posted(sbn: StatusBarNotification, ranking: RankingMap?, ownPackage: String): AppNotification? {
        val n = if (sbn.packageName == ownPackage) null else sbn.toAppNotification()
        if (n != null) {
            entries[sbn.key] = notificationKey(sbn.packageName, sbn.user) to n
            emit(ranking)
        } else if (entries.remove(sbn.key) != null) {
            // An update can turn a notification ongoing or into a group summary; it leaves the list then. Ongoing
            // updates that were never listed (navigation, downloads) change nothing here.
            emit(ranking)
        }
        if (putTimer(sbn, ownPackage)) emitTimers()
        if (putLive(sbn, ranking, ownPackage)) signalLive()
        return n
    }

    internal fun removed(sbnKey: String, ranking: RankingMap?) {
        if (entries.remove(sbnKey) != null) emit(ranking)
        if (timerEntries.remove(sbnKey) != null) emitTimers()
        if (liveEntries.remove(sbnKey) != null) signalLive()
    }

    internal fun ranked(ranking: RankingMap?) = emit(ranking)

    internal fun clear() {
        entries.clear()
        _byApp.value = emptyMap()
        stale = false
        pendingRanking = null
        timerEntries.clear()
        _timers.value = emptyList()
        liveEntries.clear()
        liveStale = false
        _liveUpdates.value = emptyList()
    }

    /**
     * Returns whether the live list changed. Silent background work the system tucks away (minimum importance) isn't
     * something under way the user follows, so it never gets a chip.
     */
    private fun putLive(sbn: StatusBarNotification, ranking: RankingMap?, ownPackage: String): Boolean {
        var update = if (sbn.packageName == ownPackage) null else sbn.toLiveUpdate()
        if (update != null && ranking != null) {
            val r = Ranking()
            if (ranking.getRanking(sbn.key, r) && r.importance < android.app.NotificationManager.IMPORTANCE_LOW) update = null
        }
        if (update == null) return liveEntries.remove(sbn.key) != null
        return liveEntries.put(sbn.key, update) != update
    }

    /** Emits the live list now while home is visible; while it's stopped, on [resume]. */
    private fun signalLive() {
        if (live) emitLive() else liveStale = true
    }

    private fun emitLive() {
        liveStale = false
        _liveUpdates.value = liveEntries.values.toList()
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

    private fun put(sbn: StatusBarNotification, ownPackage: String) {
        val n = if (sbn.packageName == ownPackage) null else sbn.toAppNotification()
        if (n == null) entries.remove(sbn.key) else entries[sbn.key] = notificationKey(sbn.packageName, sbn.user) to n
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
        code = findLoginCode(title, text),
    )
}

/** Android 16's request for a Live Update, and its chip text: public extras keys only from the 36.1 SDK on. */
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

private fun StatusBarNotification.toLiveUpdate(): LiveUpdate? {
    val n = notification
    if (!isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    val extras = n.extras
    // The player shows media, and running clocks are timer chips already.
    if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || n.category == Notification.CATEGORY_TRANSPORT) return null
    if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) return null
    val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
    val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
    val promoted = Build.VERSION.SDK_INT >= 36 &&
        (n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 || extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING))
    val kind = when {
        n.category == Notification.CATEGORY_NAVIGATION -> LiveUpdate.Kind.NAVIGATION
        max > 0 || indeterminate || n.category == Notification.CATEGORY_PROGRESS -> LiveUpdate.Kind.PROGRESS
        promoted -> LiveUpdate.Kind.OTHER
        // Anything else ongoing ("Tasker is running", a VPN, USB debugging) isn't under way: no chip.
        else -> return null
    }
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val label = title.ifEmpty { text }
    if (label.isEmpty()) return null
    val percent = if (max > 0 && !indeterminate) (extras.getInt(Notification.EXTRA_PROGRESS).toLong() * 100 / max).toInt().coerceIn(0, 100) else null
    val critical = extras.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.trim().orEmpty()
    return LiveUpdate(
        key = key,
        packageName = packageName,
        kind = kind,
        title = label,
        status = critical.ifEmpty { percent?.let { "$it%" }.orEmpty() },
        percent = percent,
        contentIntent = n.contentIntent,
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
