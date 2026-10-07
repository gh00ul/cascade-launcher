package com.gh00ul.cascade.data

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps
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

    private val launcherApps get() = Shadow.extract<ShadowLauncherApps>(context.getSystemService(LauncherApps::class.java))

    /** The repository's key for the app [FakeLauncherApps] installs under [label]. */
    private fun key(label: String) = FakeLauncherApps.pkg(label).let { "$it/$it.Main#$serial" }

    private lateinit var prefs: Prefs

    private fun repository(favorites: List<String> = emptyList(), folders: Map<String, Folder> = emptyMap()): AppRepository {
        // Seeded already, so the first load keeps these favorites rather than picking the default apps.
        prefs = Prefs(context).apply { update { it.copy(favorites = favorites, folders = folders, favoritesSeeded = true) } }
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

    /** Every icon drawn again (monochrome turned on): each keeps showing its old icon until its new one is ready. */
    @Test fun redrawingEveryIconNeverBlanksOne() {
        val labels = (0 until 60).map { "App%02d".format(it) }
        FakeLauncherApps.install(context, *labels.toTypedArray())
        val repo = repository()
        scheduler.advanceUntilIdle()
        val all = labels.map(::key).toSet()
        assertEquals(all, repo.icons.value.keys)

        val published = Collections.synchronizedList(mutableListOf<Set<String>>())
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            repo.icons.drop(1).collect { published.add(it.keys.toSet()) }
        }
        repo.refresh(clearIcons = true)
        scheduler.advanceUntilIdle()
        watcher.cancel()

        assertTrue(published.isNotEmpty())
        assertTrue("A publish missing icons: ${published.map { all.size - it.size }}", published.all { it == all })
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

    /** Folder entries aren't apps: a load neither drops nor moves them, and the apps inside are drawn with the favorites. */
    @Test fun aLoadKeepsFoldersAndDrawsTheirAppsWithTheFavorites() {
        FakeLauncherApps.install(context, "Alpha", "Bravo", "Charlie", "Delta")
        // One app in the folder is missing for now (its profile paused, say): it keeps its place.
        val folders = mapOf("1" to Folder("Work", listOf(key("Delta"), key("Echo"))))
        val repo = repository(favorites = listOf(key("Alpha"), folderKey("1")), folders = folders)
        var firstIcons: Set<String>? = null
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            repo.icons.collect { if (it.isNotEmpty() && firstIcons == null) firstIcons = it.keys.toSet() }
        }
        scheduler.advanceUntilIdle()
        watcher.cancel()

        assertEquals(listOf(key("Alpha"), folderKey("1")), prefs.settings.value.favorites)
        assertEquals(folders, prefs.settings.value.folders)
        assertTrue("first icons: $firstIcons", firstIcons.orEmpty().containsAll(setOf(key("Alpha"), key("Delta"))))
    }

    @Test fun anUninstalledAppLeavesItsFolder() {
        FakeLauncherApps.install(context, "Alpha", "Bravo", "Charlie")
        repository(favorites = listOf(folderKey("1"), key("Alpha")), folders = mapOf("1" to Folder("F", listOf(key("Bravo"), key("Charlie")))))
        scheduler.advanceUntilIdle()

        launcherApps.notifyPackageRemoved(FakeLauncherApps.pkg("Bravo"))
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.advanceUntilIdle()

        assertEquals(listOf(folderKey("1"), key("Alpha")), prefs.settings.value.favorites)
        assertEquals(listOf(key("Charlie")), prefs.settings.value.folders["1"]?.apps)
    }

    /** An icon picker swapped the app's launcher activity: its place in a folder follows, like a favorite's. */
    @Test fun aMovedAppKeepsItsPlaceInItsFolder() {
        FakeLauncherApps.install(context, "Alpha", "Bravo")
        val repo = repository(favorites = listOf(folderKey("1")), folders = mapOf("1" to Folder("F", listOf(key("Bravo"), key("Alpha")))))
        scheduler.advanceUntilIdle()

        FakeLauncherApps.uninstallAll()
        FakeLauncherApps.install(context, "Bravo")
        val alias = FakeLauncherApps.installActivity(context, "Alpha", "${FakeLauncherApps.pkg("Alpha")}.Alias")
        // The reload a package change starts.
        repo.refresh(FakeLauncherApps.pkg("Alpha"))
        scheduler.advanceUntilIdle()

        val moved = "${alias.componentName.flattenToString()}#$serial"
        assertEquals(listOf(key("Bravo"), moved), prefs.settings.value.folders["1"]?.apps)
        assertEquals(listOf(folderKey("1")), prefs.settings.value.favorites)
    }

    /**
     * A moved app's settings are stored without the repository's lock held: a settings change on the main thread holds
     * Prefs' lock while its collectors refresh, which takes the repository's, so the other order could deadlock.
     */
    @Test fun aMoveIsStoredWithoutTheListLockHeld() {
        FakeLauncherApps.install(context, "Alpha", "Bravo")
        val repo = repository(favorites = listOf(key("Alpha")))
        scheduler.advanceUntilIdle()
        // By reflection: it's private, and Thread.holdsLock needs the object itself.
        val lock: Any = AppRepository::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(repo)!!
        val heldWhileStoring = Collections.synchronizedList(mutableListOf<Boolean>())
        // Unconfined, so it runs inside each update, on the thread storing it.
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            prefs.settings.drop(1).collect { heldWhileStoring.add(Thread.holdsLock(lock)) }
        }

        FakeLauncherApps.uninstallAll()
        FakeLauncherApps.install(context, "Bravo")
        val alias = FakeLauncherApps.installActivity(context, "Alpha", "${FakeLauncherApps.pkg("Alpha")}.Alias")
        repo.refresh(FakeLauncherApps.pkg("Alpha"))
        scheduler.advanceUntilIdle()
        watcher.cancel()

        assertEquals(listOf("${alias.componentName.flattenToString()}#$serial"), prefs.settings.value.favorites)
        assertTrue("held while storing: $heldWhileStoring", heldWhileStoring.isNotEmpty() && true !in heldWhileStoring)
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
