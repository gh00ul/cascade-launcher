package com.gh00ul.cascade.notifications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.os.postDelayed
import com.gh00ul.cascade.util.LauncherActions

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

    // Android's own onDestroy reports the disconnect first, so this finds nothing left to do there. It keeps a destroyed
    // listener from staying reachable through instance (and its notifications on screen) on a build that doesn't.
    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) onListenerDisconnected()
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

        private val handler = Handler(Looper.getMainLooper())
        private val rebindToken = Any()

        /**
         * Binds the listener again when access is granted but it isn't connected. The system rebinds a listener whose
         * binding died only once, so an update, a force stop or a crash can leave it unbound (no dots, previews, player
         * or chips) until access is turned off and on. Checks [REBIND_DELAY_MS] after the last call, so a listener the
         * system is binding right then, as on a cold start, connects first. Call on the main thread when home starts.
         */
        fun rebindIfDisconnected(context: Context) {
            if (instance != null) return
            val app = context.applicationContext
            handler.removeCallbacksAndMessages(rebindToken)
            handler.postDelayed(REBIND_DELAY_MS, rebindToken) { rebindNow(app) }
        }

        private fun rebindNow(context: Context) {
            // requestRebind only brings back a listener that requestUnbind set aside, and is a no-op otherwise, so set
            // it aside first. Unbinding by name is Android 14+.
            if (Build.VERSION.SDK_INT < 34) return
            if (instance != null || !LauncherActions.hasNotificationAccess(context)) return
            val component = ComponentName(context, NotificationListener::class.java)
            // Harmless when a binding is on its way after all: unbinding drops only a connected listener, and the
            // rebind skips one already bound.
            try {
                NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            } catch (e: SecurityException) {
                // Android checks only that the component is ours, and a grant revoked since the check above makes both
                // calls no-ops; the docs still say they "fail" for a listener without access. Not connecting is then
                // right, and the next home start tries again.
            }
        }

        private const val REBIND_DELAY_MS = 5_000L
    }
}
