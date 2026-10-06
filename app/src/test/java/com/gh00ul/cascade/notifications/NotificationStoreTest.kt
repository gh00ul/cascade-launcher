package com.gh00ul.cascade.notifications

import android.app.Application
import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import com.gh00ul.cascade.data.notificationKey
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.testing.FakeNotifications.sbn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val own = "com.gh00ul.cascade"
    private val messages = FakeApps.messages.packageName
    private val mail = FakeApps.mail.packageName
    private val phone = FakeApps.phone.packageName
    private val clock = "com.example.clock"

    @After fun clear() {
        NotificationStore.clear()
        NotificationStore.resume()
    }

    private fun message(title: String, text: String = "Hi") = FakeNotifications.message(context, title, text)

    private fun builder() = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)

    private fun titles(appKey: String) = NotificationStore.byApp.value[appKey]?.map { it.title }

    /** A Ranking as the system fills it; RankingMap.getRanking copies these fields with Ranking.populate. */
    private fun ranking(sbnKey: String, showBadge: Boolean = true, suspended: Boolean = false) = Ranking().apply {
        ReflectionHelpers.setField(this, "mKey", sbnKey)
        ReflectionHelpers.setField(this, "mShowBadge", showBadge)
        ReflectionHelpers.setField(this, "mHidden", suspended)
    }

    /** Through the hidden RankingMap(Ranking[]) constructor the system uses. */
    private fun rankingMap(vararg rankings: Ranking): RankingMap =
        ReflectionHelpers.callConstructor(RankingMap::class.java, ClassParameter.from(Array<Ranking>::class.java, arrayOf(*rankings)))

    @Test fun groupsByAppNewestFirst() {
        NotificationStore.reset(
            arrayOf(
                sbn(messages, message("Older"), id = 1, postTime = 1_000),
                sbn(mail, message("Flight"), id = 2, postTime = 2_000),
                sbn(messages, message("Newer"), id = 3, postTime = 3_000),
            ),
            null,
            own,
        )
        val byApp = NotificationStore.byApp.value
        val key = notificationKey(messages, Process.myUserHandle())
        assertEquals(FakeApps.messages.notificationKey, key)
        assertEquals("$messages@${Process.myUserHandle().hashCode()}", key)
        assertEquals(listOf("Newer", "Older"), byApp.getValue(key).map { it.title })
        assertEquals(listOf("Flight"), byApp.getValue(FakeApps.mail.notificationKey).map { it.title })
    }

    @Test fun workProfileNotificationsStaySeparate() {
        NotificationStore.reset(
            arrayOf(
                sbn(mail, message("Personal"), id = 1),
                sbn(mail, message("Work"), id = 2, user = FakeApps.workUser),
            ),
            null,
            own,
        )
        val byApp = NotificationStore.byApp.value
        assertEquals(listOf("Personal"), byApp.getValue(FakeApps.mail.notificationKey).map { it.title })
        assertEquals(listOf("Work"), byApp.getValue(FakeApps.workMail.notificationKey).map { it.title })
    }

    @Test fun dropsOngoingSummariesMediaAndOwnNotifications() {
        val ongoing = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Uploading").setOngoing(true).build()
        val summary = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("3 new messages").setGroup("g").setGroupSummary(true).build()
        val transport = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Song").setCategory(Notification.CATEGORY_TRANSPORT).build()
        val mediaSession = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Podcast").addExtras(Bundle().apply { putParcelable(Notification.EXTRA_MEDIA_SESSION, null) }).build()
        val empty = Notification.Builder(context, "c").setSmallIcon(android.R.drawable.sym_def_app_icon).build()
        NotificationStore.reset(
            arrayOf(
                sbn(messages, ongoing, id = 1),
                sbn(messages, summary, id = 2),
                sbn(messages, transport, id = 3),
                sbn(messages, mediaSession, id = 4),
                sbn(messages, empty, id = 5),
                sbn(own, message("Update ready"), id = 6),
                sbn(messages, message("Kept"), id = 7),
            ),
            null,
            own,
        )
        assertEquals(mapOf(FakeApps.messages.notificationKey to listOf("Kept")), NotificationStore.byApp.value.mapValues { (_, v) -> v.map { it.title } })
    }

    @Test fun chronometerBecomesLiveTimer() {
        val endsAt = FIXED_NOW + 272_000
        NotificationStore.reset(arrayOf(sbn("com.example.clock", FakeNotifications.timer(context, "Pasta", endsAt))), null, own)
        val timer = NotificationStore.timers.value.single()
        assertEquals("Pasta", timer.title)
        assertEquals(endsAt, timer.base)
        assertTrue(timer.countDown)
        assertEquals("com.example.clock", timer.packageName)
        // Ongoing, so it's a chip only, never a row notification.
        assertTrue(NotificationStore.byApp.value.isEmpty())
    }

    @Test fun removedAndPostedUpdateTheLists() {
        val first = sbn(messages, message("First"), id = 1)
        NotificationStore.reset(arrayOf(first), null, own)
        NotificationStore.posted(sbn(messages, message("Second"), id = 2, postTime = FIXED_NOW + 1), null, own)
        assertEquals(listOf("Second", "First"), NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey).map { it.title })
        NotificationStore.removed(first.key, null)
        assertEquals(listOf("Second"), NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey).map { it.title })

        val timer = sbn("com.example.clock", FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000), id = 9)
        NotificationStore.posted(timer, null, own)
        assertEquals(1, NotificationStore.timers.value.size)
        NotificationStore.removed(timer.key, null)
        assertTrue(NotificationStore.timers.value.isEmpty())
    }

    @Test fun clearEmptiesEverything() {
        NotificationStore.reset(
            arrayOf(sbn(messages, message("Hi")), sbn("com.example.clock", FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000), id = 2)),
            null,
            own,
        )
        NotificationStore.clear()
        assertTrue(NotificationStore.byApp.value.isEmpty())
        assertFalse(NotificationStore.timers.value.isNotEmpty())
    }

    @Test fun equalPostTimesKeepPostingOrder() {
        NotificationStore.reset(
            arrayOf(
                sbn(messages, message("Maya"), id = 1, postTime = 1_000),
                sbn(mail, message("Receipt"), id = 2, postTime = 2_000),
                sbn(messages, message("Sam"), id = 3, postTime = 1_000),
                sbn(phone, message("Missed call"), id = 4, postTime = 1_000),
                sbn(messages, message("Mom"), id = 5, postTime = 3_000),
                sbn(mail, message("Newsletter"), id = 6, postTime = 2_000),
            ),
            null,
            own,
        )
        NotificationStore.posted(sbn(messages, message("Lee"), id = 7, postTime = 1_000), null, own)
        assertEquals(listOf("Mom", "Maya", "Sam", "Lee"), titles(FakeApps.messages.notificationKey))
        assertEquals(listOf("Receipt", "Newsletter"), titles(FakeApps.mail.notificationKey))
        assertEquals(listOf("Missed call"), titles(FakeApps.phone.notificationKey))
        assertEquals(3, NotificationStore.byApp.value.size)
    }

    @Test fun repostingAKeyReplacesItsEntryAndResorts() {
        NotificationStore.reset(
            arrayOf(sbn(messages, message("Maya", "On my way"), id = 1, postTime = 1_000), sbn(messages, message("Sam"), id = 2, postTime = 2_000)),
            null,
            own,
        )
        NotificationStore.posted(sbn(messages, message("Maya", "Here!"), id = 1, postTime = 3_000), null, own)
        val list = NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey)
        assertEquals(listOf("Maya" to "Here!", "Sam" to "Hi"), list.map { it.title to it.text })
        assertEquals(3_000L, list.first().postTime)
    }

    @Test fun updateThatTurnsOngoingOrIntoASummaryRemovesTheEntry() {
        NotificationStore.reset(arrayOf(sbn(messages, message("Sending photo"), id = 1), sbn(messages, message("Book club"), id = 2)), null, own)
        NotificationStore.posted(sbn(messages, builder().setContentTitle("Sending photo").setOngoing(true).build(), id = 1), null, own)
        assertEquals(listOf("Book club"), titles(FakeApps.messages.notificationKey))
        val summary = builder().setContentTitle("2 chats").setGroup("g").setGroupSummary(true).build()
        NotificationStore.posted(sbn(messages, summary, id = 2), null, own)
        // Nothing left for the app, so its key goes too.
        assertTrue(NotificationStore.byApp.value.isEmpty())
    }

    @Test fun ongoingUpdateThatWasNeverListedChangesNothing() {
        val chat = sbn(messages, message("Book club"), id = 1)
        NotificationStore.reset(arrayOf(chat), null, own)
        val byApp = NotificationStore.byApp.value
        val timers = NotificationStore.timers.value
        // Navigation re-posts every few seconds. The lists aren't rebuilt for it, so the badge-off ranking it
        // carries isn't applied either; ranking changes arrive through ranked().
        val navigation = builder().setContentTitle("200 ft").setContentText("Turn left onto Pine St").setOngoing(true).build()
        NotificationStore.posted(sbn("com.example.maps", navigation, id = 2), rankingMap(ranking(chat.key, showBadge = false)), own)
        assertSame(byApp, NotificationStore.byApp.value)
        assertSame(timers, NotificationStore.timers.value)
        assertTrue(NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey).single().showBadge)
    }

    @Test fun titleAndTextFallBackAndAreTrimmed() {
        val bothTitles = builder().setContentTitle("Maya")
            .addExtras(Bundle().apply { putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Book club") }).build()
        NotificationStore.reset(
            arrayOf(
                sbn(messages, FakeNotifications.conversation(context, "  Book club ", "\tSee you at 7  "), id = 1, postTime = 3_000),
                // EXTRA_TITLE wins when both are there; a title alone is enough to list it.
                sbn(messages, bothTitles, id = 2, postTime = 1_000),
                sbn(mail, FakeNotifications.bigText(context, " Gate change", "  Now boarding at B12.\n"), id = 3, postTime = 2_000),
                // Only whitespace: nothing to show.
                sbn(phone, message("   ", " \n "), id = 4),
            ),
            null,
            own,
        )
        val byApp = NotificationStore.byApp.value
        assertEquals(listOf("Book club" to "See you at 7", "Maya" to ""), byApp.getValue(FakeApps.messages.notificationKey).map { it.title to it.text })
        assertEquals(listOf("Gate change" to "Now boarding at B12."), byApp.getValue(FakeApps.mail.notificationKey).map { it.title to it.text })
        assertFalse(FakeApps.phone.notificationKey in byApp)
    }

    @Test fun rankingDropsSuspendedAppsAndAppliesBadges() {
        val paused = sbn(messages, message("Maya"), id = 1)
        val noDots = sbn(mail, message("Receipt"), id = 2)
        val unranked = sbn(phone, message("Missed call"), id = 3)
        NotificationStore.reset(
            arrayOf(paused, noDots, unranked),
            rankingMap(ranking(paused.key, suspended = true), ranking(noDots.key, showBadge = false)),
            own,
        )
        val byApp = NotificationStore.byApp.value
        // Focus mode or an app timer paused Messages, so the system hides its notifications.
        assertFalse(FakeApps.messages.notificationKey in byApp)
        assertFalse(byApp.getValue(FakeApps.mail.notificationKey).single().showBadge)
        // A key the ranking doesn't have yet stays, badge and all.
        assertTrue(byApp.getValue(FakeApps.phone.notificationKey).single().showBadge)
    }

    @Test fun rankedAppliesTheNewRanking() {
        val chat = sbn(messages, message("Maya"), id = 1)
        NotificationStore.reset(arrayOf(chat), rankingMap(ranking(chat.key)), own)
        assertTrue(NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey).single().showBadge)
        NotificationStore.ranked(rankingMap(ranking(chat.key, showBadge = false)))
        assertFalse(NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey).single().showBadge)
        NotificationStore.ranked(rankingMap(ranking(chat.key, suspended = true)))
        assertTrue(NotificationStore.byApp.value.isEmpty())
        // Unpausing the app brings the notification back.
        NotificationStore.ranked(rankingMap(ranking(chat.key)))
        assertEquals(listOf("Maya"), titles(FakeApps.messages.notificationKey))
    }

    @Test fun notificationForAllUsersIsFiledUnderOurs() {
        val all = ReflectionHelpers.getStaticField<UserHandle>(UserHandle::class.java, "ALL")
        NotificationStore.reset(arrayOf(sbn(messages, message("Storage almost full"), user = all)), null, own)
        assertEquals(listOf("Storage almost full"), titles(FakeApps.messages.notificationKey))
    }

    @Test fun stopwatchCountsUpFromWhen() {
        val startedAt = FIXED_NOW - 95_000
        NotificationStore.reset(arrayOf(sbn(clock, FakeNotifications.stopwatch(context, "Stopwatch", startedAt))), null, own)
        val timer = NotificationStore.timers.value.single()
        assertFalse(timer.countDown)
        assertEquals(startedAt, timer.base)
        assertEquals("Stopwatch", timer.title)
    }

    @Test fun onlyARunningChronometerFromAnotherAppIsATimer() {
        val endsAt = FIXED_NOW + 60_000
        val noChronometer = builder().setContentTitle("Pasta").setContentText("Paused").setWhen(endsAt).setOngoing(true).build()
        val noTime = builder().setContentTitle("Call").setUsesChronometer(true).setWhen(0).setOngoing(true).build()
        val media = builder().setContentTitle("Song").setUsesChronometer(true).setWhen(FIXED_NOW - 30_000).setOngoing(true)
            .addExtras(Bundle().apply { putParcelable(Notification.EXTRA_MEDIA_SESSION, null) }).build()
        NotificationStore.reset(
            arrayOf(
                sbn(clock, noChronometer, id = 1),
                sbn("com.example.dialer", noTime, id = 2),
                // A player's elapsed time belongs to the player row.
                sbn(FakeApps.music.packageName, media, id = 3),
                sbn(own, FakeNotifications.timer(context, "Own", endsAt), id = 4),
                sbn(clock, FakeNotifications.timer(context, "Tea", endsAt), id = 5),
            ),
            null,
            own,
        )
        assertEquals(listOf("Tea"), NotificationStore.timers.value.map { it.title })
    }

    @Test fun timerTitleIsTrimmedAndMayBeEmpty() {
        NotificationStore.reset(
            arrayOf(
                sbn(clock, FakeNotifications.timer(context, "  Pasta \n", FIXED_NOW + 60_000), id = 1),
                sbn(clock, FakeNotifications.stopwatch(context, null, FIXED_NOW - 5_000), id = 2),
                sbn(clock, FakeNotifications.stopwatch(context, "   ", FIXED_NOW - 5_000), id = 3),
            ),
            null,
            own,
        )
        assertEquals(listOf("Pasta", "", ""), NotificationStore.timers.value.map { it.title })
    }

    @Test fun repostingAnIdenticalTimerDoesNotReEmit() {
        NotificationStore.reset(arrayOf(sbn(clock, FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000))), null, own)
        val timers = NotificationStore.timers.value
        NotificationStore.posted(sbn(clock, FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000)), null, own)
        assertSame(timers, NotificationStore.timers.value)
    }

    @Test fun updatedTimerReplacesItsChipInPlace() {
        val endsAt = FIXED_NOW + 272_000
        NotificationStore.reset(
            arrayOf(
                sbn(clock, FakeNotifications.timer(context, "Pasta", endsAt), id = 1),
                sbn(clock, FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000), id = 2),
            ),
            null,
            own,
        )
        // "+1 min" on the first timer: same key, later end, same place in the list.
        NotificationStore.posted(sbn(clock, FakeNotifications.timer(context, "Pasta", endsAt + 60_000), id = 1), null, own)
        assertEquals(listOf("Pasta" to endsAt + 60_000, "Tea" to FIXED_NOW + 60_000), NotificationStore.timers.value.map { it.title to it.base })
        // Pausing it swaps the chronometer for text, and the chip goes.
        NotificationStore.posted(sbn(clock, builder().setContentTitle("Pasta").setContentText("Paused").setOngoing(true).build(), id = 1), null, own)
        assertEquals(listOf("Tea"), NotificationStore.timers.value.map { it.title })
    }

    @Test fun pausedStoreRegroupsOnceOnResume() {
        val maya = sbn(messages, message("Maya"), id = 1, postTime = 1_000)
        val receipt = sbn(mail, message("Receipt"), id = 2, postTime = 2_000)
        NotificationStore.reset(arrayOf(maya, receipt), rankingMap(ranking(maya.key), ranking(receipt.key)), own)
        val byApp = NotificationStore.byApp.value
        // Another app in front: each callback only records its change.
        NotificationStore.pause()
        val sam = sbn(messages, message("Sam"), id = 3, postTime = 3_000)
        NotificationStore.posted(sam, rankingMap(ranking(maya.key), ranking(receipt.key), ranking(sam.key)), own)
        NotificationStore.removed(receipt.key, rankingMap(ranking(maya.key), ranking(sam.key)))
        NotificationStore.ranked(rankingMap(ranking(maya.key, showBadge = false), ranking(sam.key)))
        assertSame(byApp, NotificationStore.byApp.value)
        // Back home: all three at once, under the latest ranking.
        NotificationStore.resume()
        val list = NotificationStore.byApp.value.getValue(FakeApps.messages.notificationKey)
        assertEquals(listOf("Sam" to true, "Maya" to false), list.map { it.title to it.showBadge })
        assertFalse(FakeApps.mail.notificationKey in NotificationStore.byApp.value)
    }

    @Test fun timersStillEmitWhilePaused() {
        NotificationStore.pause()
        NotificationStore.posted(sbn(clock, FakeNotifications.timer(context, "Tea", FIXED_NOW + 60_000)), null, own)
        assertEquals(listOf("Tea"), NotificationStore.timers.value.map { it.title })
    }

    @Test fun noOpCallbacksDoNotEmit() {
        val chat = sbn(messages, message("Book club"), id = 1, postTime = 1_000)
        val ranks = rankingMap(ranking(chat.key))
        NotificationStore.reset(arrayOf(chat), ranks, own)
        var emissions = 0
        val job = CoroutineScope(Dispatchers.Unconfined).launch { NotificationStore.byApp.collect { emissions++ } }
        try {
            // The current value, on subscribing.
            assertEquals(1, emissions)
            val navigation = builder().setContentTitle("200 ft").setContentText("Turn left onto Pine St").setOngoing(true).build()
            NotificationStore.posted(sbn("com.example.maps", navigation, id = 2), ranks, own)
            NotificationStore.removed("unknown key", ranks)
            NotificationStore.ranked(rankingMap(ranking(chat.key)))
            NotificationStore.posted(sbn(messages, message("Book club"), id = 1, postTime = 1_000), ranks, own)
            NotificationStore.resume()
            assertEquals(1, emissions)
            // A real change still does.
            NotificationStore.posted(sbn(messages, message("Maya"), id = 3, postTime = 2_000), ranks, own)
            assertEquals(2, emissions)
        } finally {
            job.cancel()
        }
    }

    @Test fun clearWhilePausedDropsThePendingRegroup() {
        NotificationStore.reset(arrayOf(sbn(messages, message("Maya"), id = 1)), null, own)
        NotificationStore.pause()
        NotificationStore.posted(sbn(mail, message("Receipt"), id = 2), null, own)
        // Notification access turned off while away.
        NotificationStore.clear()
        NotificationStore.resume()
        assertTrue(NotificationStore.byApp.value.isEmpty())
        // Reconnecting while away fills the list on the way back.
        NotificationStore.pause()
        NotificationStore.reset(arrayOf(sbn(mail, message("Receipt"), id = 2)), null, own)
        assertTrue(NotificationStore.byApp.value.isEmpty())
        NotificationStore.resume()
        assertEquals(listOf("Receipt"), titles(FakeApps.mail.notificationKey))
    }
}
