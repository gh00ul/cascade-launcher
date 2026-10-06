package com.gh00ul.cascade.data

import android.app.Application
import android.os.Process
import android.os.UserManager
import com.gh00ul.cascade.testing.FakeLauncherApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app list load: the sorted list, labels included, goes out before any icon, the favorites' icons come first,
 * icon rendering stays within its threads and stops when a newer load takes over, and an uninstalled app's icon
 * doesn't outlive the next publish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    /** The repository's loads run on the test thread, a step at a time, only inside [scheduler]'s advance calls. */
    private val scheduler = TestCoroutineScheduler()
    private val loads = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + loads)

    /** Real threads, more than any test renders on, for [renderInParallel] alone. */
    private val pool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

    @After fun tearDown() {
        scope.cancel()
        pool.close()
    }

    private val serial get() = context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle())

    /** The repository's key for the app [FakeLauncherApps] installs under [label]. */
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }

    private fun repository(favorites: List<String> = emptyList()): AppRepository {
        // Seeded already, so the first load keeps these favorites rather than picking the default apps.
        val prefs = Prefs(context).apply { update { it.copy(favorites = favorites, favoritesSeeded = true) } }
        return AppRepository(context, prefs, scope, loads)
    }

    @Test fun theSortedListGoesOutBeforeTheFirstIcon() {
        val installed = FakeLauncherApps.install(context, "Charlie", "Alpha", "Delta", "Bravo")
        val repo = repository(favorites = listOf(key("Delta")))
        var firstIcons: Set<String>? = null
        var appsAtFirstIcons: List<AppEntry>? = null
        // Unconfined, so it runs inside the publish itself and sees the list exactly as it stood then.
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            repo.icons.collect { icons ->
                if (icons.isNotEmpty() && firstIcons == null) {
                    firstIcons = icons.keys.toSet()
                    appsAtFirstIcons = repo.apps.value
                }
            }
        }
        scheduler.advanceUntilIdle()
        watcher.cancel()

        val order = listOf("Alpha", "Bravo", "Charlie", "Delta")
        assertEquals(order.map(::key), appsAtFirstIcons?.map { it.key })
        // Whatever Robolectric resolves a label to (the package name where it can't measure glyphs), it's the one
        // LauncherApps gives.
        val labels = installed.associate { it.componentName.packageName to it.label.toString() }
        assertEquals(order.map { labels[FakeLauncherApps.pkg(it)] }, appsAtFirstIcons?.map { it.label })
        // The home screen's favorite is in the first icon publish; in the end every app has its icon.
        assertTrue(key("Delta") in firstIcons.orEmpty())
        assertEquals(order.map(::key).toSet(), repo.icons.value.keys)
    }

    @Test fun anUninstalledAppsIconIsGoneFromTheNextPublish() {
        FakeLauncherApps.install(context, "Alpha", "Bravo", "Charlie")
        val repo = repository()
        scheduler.advanceUntilIdle()
        assertEquals(setOf(key("Alpha"), key("Bravo"), key("Charlie")), repo.icons.value.keys)

        FakeLauncherApps.uninstallAll()
        FakeLauncherApps.install(context, "Alpha", "Charlie")
        val published = Collections.synchronizedList(mutableListOf<Set<String>>())
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            repo.icons.drop(1).collect { published.add(it.keys.toSet()) }
        }
        // A plain reload, as a profile event starts: no package named, so no icon was dropped up front.
        repo.refresh()
        scheduler.advanceUntilIdle()
        watcher.cancel()

        assertEquals(listOf(key("Alpha"), key("Charlie")), repo.apps.value.map { it.key })
        assertEquals(setOf(key("Alpha"), key("Charlie")), repo.icons.value.keys)
        assertTrue(published.isNotEmpty())
        assertTrue("Bravo's icon published: $published", published.none { key("Bravo") in it })
    }

    @Test fun everyIconPublishHasAllTheFavorites() = runBlocking<Unit> {
        val favorites = (0 until 6).toList()
        val rendered = ConcurrentHashMap.newKeySet<Int>()
        val publishes = Collections.synchronizedList(mutableListOf<Set<Int>>())
        val complete = renderInParallel(
            first = favorites,
            rest = { (6 until 100).toList() },
            threads = 3,
            dispatcher = pool,
            publishEvery = 4,
            render = {
                rendered.add(it)
                Thread.sleep(1)
                true
            },
            publish = {
                publishes.add(rendered.toSet())
                true
            },
        )
        assertTrue(complete)
        assertEquals((0 until 100).toSet(), rendered.toSet())
        assertTrue(publishes.isNotEmpty())
        assertTrue("a publish without every favorite: $publishes", publishes.all { it.containsAll(favorites) })
    }

    @Test fun neverMoreRendersAtOnceThanThreads() = runBlocking<Unit> {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        // The first three renders wait for each other, so all three threads are provably busy at once.
        val allBusy = CountDownLatch(3)
        val complete = renderInParallel(
            first = (0 until 5).toList(),
            rest = { (5 until 60).toList() },
            threads = 3,
            dispatcher = pool,
            render = {
                peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                allBusy.countDown()
                allBusy.await(5, TimeUnit.SECONDS)
                Thread.sleep(1)
                running.decrementAndGet()
                true
            },
            publish = { true },
        )
        assertTrue(complete)
        assertEquals(3, peak.get())
    }

    @Test fun aSupersededLoadStopsRenderingAndPublishing() = runBlocking<Unit> {
        // Like the repository's generation check: once a newer load starts, a render's write and a publish both fail.
        val current = AtomicBoolean(true)
        val rendered = AtomicInteger()
        val lateRenders = AtomicInteger()
        val latePublishes = AtomicInteger()
        val complete = renderInParallel(
            first = listOf(0, 1),
            rest = { (2 until 200).toList() },
            threads = 3,
            dispatcher = pool,
            publishEvery = 2,
            render = { i ->
                if (!current.get()) lateRenders.incrementAndGet()
                if (i == 20) current.set(false)
                rendered.incrementAndGet()
                Thread.sleep(1)
                current.get()
            },
            publish = {
                if (!current.get()) latePublishes.incrementAndGet()
                current.get()
            },
        )
        assertFalse(complete)
        assertTrue("rendered ${rendered.get()} of 200", rendered.get() < 200)
        // The thread that saw it stops; each of the other two finds out on at most one more item or publish.
        assertTrue("${lateRenders.get()} renders after", lateRenders.get() <= 2)
        assertTrue("${latePublishes.get()} publishes after", latePublishes.get() <= 2)
    }

    @Test fun cancellingALoadStopsItsRendersAndPublishes() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val rendered = AtomicInteger()
        val published = AtomicInteger()
        val load = launch(pool) {
            renderInParallel(
                first = emptyList<Int>(),
                rest = { (0 until 1000).toList() },
                threads = 3,
                dispatcher = pool,
                publishEvery = 1,
                render = {
                    rendered.incrementAndGet()
                    started.countDown()
                    Thread.sleep(2)
                    true
                },
                publish = {
                    published.incrementAndGet()
                    true
                },
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        load.cancelAndJoin()
        val renders = rendered.get()
        val publishes = published.get()
        Thread.sleep(50)
        assertTrue("rendered $renders of 1000", renders < 1000)
        assertEquals(renders, rendered.get())
        assertEquals(publishes, published.get())
    }
}
