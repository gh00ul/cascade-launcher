package com.gh00ul.cascade.data

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Process
import com.gh00ul.cascade.LauncherApplication
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AppWidgetProviderInfoBuilder
import org.robolectric.shadows.ShadowAppWidgetHost
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/**
 * WidgetHost against a stand-in for the system's side of it: a widget whose setup screen outlives the process keeps its
 * id through home's prune for a day, and the setup's answer places or frees that id (a record of the wrong type reads
 * as none); the start right after the first prune doesn't look the widgets up again; and a prune never frees an id an
 * add is being handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    application = LauncherApplication::class,
    shadows = [WidgetHostTest.SystemWidgetHost::class, WidgetHostTest.CountingWidgetManager::class],
)
class WidgetHostTest {
    /**
     * The system's side of Cascade's widget host: the ids it holds, in the order it lists them, and the ones freed
     * (Robolectric's host hands out one fixed id and keeps no list). Kept statically, as the system's state outlives
     * the process and WidgetHost's Host with it; cleared before each test.
     */
    @Implements(AppWidgetHost::class)
    class SystemWidgetHost : ShadowAppWidgetHost() {
        @Implementation
        override fun allocateAppWidgetId(): Int {
            val id = nextId++
            ids += id
            onAllocate(id)
            return id
        }

        @Implementation
        fun getAppWidgetIds(): IntArray {
            lister = Thread.currentThread()
            listing.await(5, TimeUnit.SECONDS)
            return ids.toIntArray()
        }

        @Implementation
        fun deleteAppWidgetId(appWidgetId: Int) {
            ids.remove(appWidgetId)
            deleted += appWidgetId
        }

        companion object {
            /** The ids the system holds for the host, in the order it lists them. */
            val ids: MutableList<Int> = CopyOnWriteArrayList()
            /** The ids freed, in order; pruning frees them from the IO pool. */
            val deleted: MutableList<Int> = CopyOnWriteArrayList()
            /** The next id handed out. Only the main thread allocates. */
            var nextId = 41
            /** Runs as an id is handed out, once it's listed: whatever else happens meanwhile. */
            @Volatile var onAllocate: (Int) -> Unit = {}
            /** Listing waits for this (up to 5 s): closed, it lines a prune up with something on the main thread. */
            @Volatile var listing = CountDownLatch(0)
            /** The thread that last listed the ids: a prune's, on the IO pool. */
            @Volatile var lister: Thread? = null

            fun clear() {
                ids.clear()
                deleted.clear()
                nextId = 41
                onAllocate = {}
                listing = CountDownLatch(0)
                lister = null
            }
        }
    }

    /** Counts the lookups of what a widget is, from any thread: each prune makes one per app widget in the stack. */
    @Implements(AppWidgetManager::class)
    class CountingWidgetManager : ShadowAppWidgetManager() {
        @Implementation
        override fun getAppWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? {
            lookups.incrementAndGet()
            return super.getAppWidgetInfo(appWidgetId)
        }

        companion object {
            val lookups = AtomicInteger()
        }
    }

    private val app get() = RuntimeEnvironment.getApplication() as LauncherApplication
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).create().get() }

    /**
     * A clock widget with a setup screen of its own, as the widget picker hands it to begin(). getProfile() reads the
     * provider's uid from the hidden providerInfo, which the system fills in.
     */
    private val clock = AppWidgetProviderInfoBuilder.newBuilder()
        .setProviderInfo(ActivityInfo().apply { applicationInfo = ApplicationInfo().apply { uid = Process.myUid() } })
        .build()
        .apply {
            provider = ComponentName("com.example.clock", "com.example.clock.ClockWidget")
            configure = ComponentName("com.example.clock", "com.example.clock.ClockSetup")
        }

    @Before fun setUp() {
        SystemWidgetHost.clear()
        CountingWidgetManager.lookups.set(0)
        freshProcess()
    }

    /**
     * Starts WidgetHost over as a new process would: it's a process-wide object, so each test, and each process death a
     * test acts out, resets what it keeps in memory. What the system holds (the host's ids, the setup record on disk)
     * stays. justPruned needs no reset: every pruneOnce sets it, and only the onStart after it reads it.
     */
    private fun freshProcess() {
        mapOf(
            "host" to null, "started" to false, "listening" to false, "pruned" to false, "pending" to null,
            "pendingInfo" to null, "configuring" to null, "setupShown" to false, "setupOwner" to null,
        ).forEach { (field, value) -> ReflectionHelpers.setStaticField(WidgetHost::class.java, field, value) }
    }

    /**
     * Runs [block], then waits for the prunes it started: they run on the IO pool, as children of the application's
     * scope. One that has finished already isn't among them, and needs no wait.
     */
    private fun awaitPrunes(block: () -> Unit) {
        val scope = app.scope.coroutineContext.job
        val before = scope.children.toSet()
        block()
        val started = scope.children.filterNot { it in before }.toList()
        runBlocking { withTimeout(5_000) { started.joinAll() } }
    }

    /** Adds [clock] as the widget picker does, up to its setup screen being open; returns the id it's set up under. */
    private fun openSetup(): Int {
        // Robolectric binds nothing without asking, so the system's dialog comes first; the user allows it.
        WidgetHost.begin(activity, clock, 200, 100)
        assertTrue(WidgetHost.onBindResult(activity, Activity.RESULT_OK))
        return SystemWidgetHost.ids.last()
    }

    private val stack get() = app.prefs.settings.value.widgetStack

    /**
     * The process dies while the setup screen is up, and home's create prunes before the answer comes: the setup's id
     * is kept, and only an id nothing uses is freed. A day on, the record no longer counts and the id is freed too.
     * Before the fix the setup's id lived only in memory, so the first prune freed it along with the leftover.
     */
    @Test fun aPruneAfterAProcessDeathLeavesAnOpenSetupAloneForADay() {
        // An add that never finished, before the setup now open.
        SystemWidgetHost.ids += 99
        val setup = openSetup()

        freshProcess()
        awaitPrunes { WidgetHost.pruneOnce(app) }
        assertEquals("Only the leftover is freed", listOf(99), SystemWidgetHost.deleted)

        // No answer a day later (Settings' task was cleared under the setup screen): the next process frees it.
        ShadowSystemClock.advanceBy(Duration.ofDays(1).plusMillis(1))
        freshProcess()
        awaitPrunes { WidgetHost.pruneOnce(app) }
        assertEquals(listOf(99, setup), SystemWidgetHost.deleted)
    }

    /**
     * After a process death, the setup screen answers "done" with no id in its data: the recorded id is placed, and the
     * record is cleared, so a stray second answer can't free the placed widget. Before the fix there was no record, and
     * the answer placed nothing.
     */
    @Test fun aSetupThatAnswersAfterAProcessDeathPlacesTheRecordedId() {
        val setup = openSetup()
        freshProcess()

        WidgetHost.onActivityResult(app, WidgetHost.REQUEST_CONFIGURE, Activity.RESULT_OK, null)
        assertEquals(listOf("app:$setup"), stack)

        WidgetHost.onActivityResult(app, WidgetHost.REQUEST_CONFIGURE, Activity.RESULT_CANCELED, null)
        assertEquals(listOf("app:$setup"), stack)
        assertEquals(emptyList<Int>(), SystemWidgetHost.deleted)
    }

    /**
     * After a process death, the setup screen is cancelled and returns no data: the recorded id is freed and the record
     * cleared, so a later answer places nothing. Before the fix the id stayed allocated until some later prune.
     */
    @Test fun aSetupCancelledAfterAProcessDeathFreesTheRecordedId() {
        val setup = openSetup()
        freshProcess()

        WidgetHost.onActivityResult(app, WidgetHost.REQUEST_CONFIGURE, Activity.RESULT_CANCELED, null)
        assertEquals(listOf(setup), SystemWidgetHost.deleted)

        WidgetHost.onActivityResult(app, WidgetHost.REQUEST_CONFIGURE, Activity.RESULT_OK, null)
        assertEquals(emptyList<String>(), stack)
    }

    /**
     * A setup record whose id isn't an Int (a hand-edited or restored file) reads as no record: a setup screen's answer
     * after a process death finds nothing to place or free, and a prune frees an id nothing uses. Before the fix getInt
     * threw ClassCastException out of both: out of MainActivity.onActivityResult, and out of home's create-time prune.
     */
    @Test fun aSetupRecordOfTheWrongTypeReadsAsNone() {
        app.getSharedPreferences("widget_setup", Context.MODE_PRIVATE).edit().putString("id", "41").commit()
        SystemWidgetHost.ids += 41

        assertTrue(WidgetHost.onActivityResult(app, WidgetHost.REQUEST_CONFIGURE, Activity.RESULT_CANCELED, null))
        assertEquals(emptyList<Int>(), SystemWidgetHost.deleted)
        assertEquals(emptyList<String>(), stack)

        awaitPrunes { WidgetHost.pruneOnce(app) }
        assertEquals("The id nothing uses is freed", listOf(41), SystemWidgetHost.deleted)
    }

    /**
     * Home's create prunes once and its start follows at once: that start doesn't look the widgets up again, since the
     * prune just did. Later starts still do, catching an app uninstalled while home was away. Before the fix the first
     * start pruned too, so each widget was looked up twice on every launch.
     */
    @Test fun theStartRightAfterTheFirstPruneDoesNotLookTheWidgetsUpAgain() {
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(7, clock)
        SystemWidgetHost.ids += 7
        app.prefs.update { it.copy(widgetStack = listOf("app:7")) }

        awaitPrunes {
            WidgetHost.pruneOnce(app)
            WidgetHost.onStart(app)
        }
        assertEquals(1, CountingWidgetManager.lookups.get())

        WidgetHost.onStop()
        awaitPrunes { WidgetHost.onStart(app) }
        assertEquals(2, CountingWidgetManager.lookups.get())
        WidgetHost.onStop()
    }

    /**
     * A prune lists the host's ids just as an add is handed a new one, before begin() has marked it pending. Deciding
     * whether an id is being added waits for begin() to finish that step, so the prune leaves the new id alone. Before
     * the fix the prune could find it neither pending nor in the stack, and freed it under the add.
     */
    @Test fun aPruneListingIdsAsAnAddIsHandedOneLeavesTheNewIdAlone() {
        SystemWidgetHost.ids += 99
        SystemWidgetHost.listing = CountDownLatch(1)
        SystemWidgetHost.onAllocate = { id ->
            // The prune lists the ids now that this one is among them, while begin() is still allocating. Wait until it
            // has freed the id or is held back from deciding about it, bounded so a prune that does neither can't hang.
            SystemWidgetHost.listing.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (id !in SystemWidgetHost.deleted && SystemWidgetHost.lister?.state != Thread.State.BLOCKED &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(1)
            }
        }

        awaitPrunes {
            WidgetHost.pruneOnce(app)
            WidgetHost.begin(activity, clock, 200, 100)
        }
        assertEquals("Only the leftover is freed", listOf(99), SystemWidgetHost.deleted)
    }
}
