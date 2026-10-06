package com.gh00ul.cascade.notifications

import android.app.Application
import android.app.Notification
import android.os.Bundle
import android.os.Process
import com.gh00ul.cascade.data.notificationKey
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.testing.FakeNotifications.sbn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val own = "com.gh00ul.cascade"
    private val messages = FakeApps.messages.packageName
    private val mail = FakeApps.mail.packageName

    @After fun clear() = NotificationStore.clear()

    private fun message(title: String, text: String = "Hi") = FakeNotifications.message(context, title, text)

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
}
