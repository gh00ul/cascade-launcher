package com.gh00ul.cascade.data

import android.app.Application
import android.content.pm.ApplicationInfo
import com.gh00ul.cascade.settings.favoritesSummary
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.folderNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Folders on the home screen: how the stored keys resolve into rows, and every edit keeping an app in one place at
 * most (a favorite, or one folder) and leaving no empty or dangling folder behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FoldersTest {
    private val f1 = folderKey("1")
    private val f2 = folderKey("2")

    private fun settings(favorites: List<String>, vararg folders: Pair<String, Folder>) =
        LauncherSettings(favorites = favorites, folders = folders.toMap())

    // Resolving into rows

    @Test fun favoritesResolveIntoAppsAndFoldersInHomeOrder() {
        val apps = listOf(FakeApps.phone, FakeApps.mail, FakeApps.camera, FakeApps.photos)
        val s = settings(
            listOf(FakeApps.phone.key, f1, "gone/gone.Main#0", FakeApps.camera.key),
            "1" to Folder("Work", listOf(FakeApps.mail.key, "missing/missing.Main#0", FakeApps.photos.key)),
        )
        val rows = homeItems(s, apps.associateBy { it.key })
        assertEquals(listOf(FakeApps.phone.key, f1, FakeApps.camera.key), rows.map { it.key })
        // The folder holds the apps that are here, in order; the missing one keeps its place in storage.
        val folder = rows[1] as HomeFolder
        assertEquals("Work", folder.label)
        assertEquals(listOf(FakeApps.mail, FakeApps.photos), folder.apps)
    }

    @Test fun aFolderWithNoAppsHereIsHiddenAndAFolderKeyWithNoFolderIsSkipped() {
        val apps = listOf(FakeApps.phone).associateBy { it.key }
        val s = settings(
            listOf(f1, FakeApps.phone.key, f2, folderKey("3"), FakeApps.phone.key),
            "1" to Folder("Paused", listOf("work/work.Main#10")),
            "2" to Folder("Empty", emptyList()),
        )
        // A key stored twice shows once.
        assertEquals(listOf(FakeApps.phone.key), homeItems(s, apps).map { it.key })
    }

    @Test fun aFolderWithoutANameShowsAsFolder() {
        assertEquals("Folder", HomeFolder("1", "  ", listOf(FakeApps.mail)).label)
        assertEquals("Games", folderLabel(" Games "))
    }

    @Test fun homeAppKeysAreTheFavoritesWithEachFoldersAppsInItsPlace() {
        val s = settings(listOf("a", f1, "d"), "1" to Folder("F", listOf("b", "c")))
        assertEquals(listOf("a", "b", "c", "d"), s.homeAppKeys())
        assertEquals("1", s.folderOf("c"))
        assertNull(s.folderOf("a"))
    }

    // Creating

    @Test fun aNewFolderTakesThePlaceOfTheFavoriteItStartedFrom() {
        val s = settings(listOf("a", "b", "c")).newFolder("1", " Tools ", listOf("b"))
        assertEquals(listOf("a", f1, "c"), s.favorites)
        assertEquals(Folder("Tools", listOf("b")), s.folders["1"])
    }

    @Test fun aNewFolderForAnAppThatIsNoFavoriteGoesAtTheEnd() {
        val s = settings(listOf("a", "b")).newFolder("1", "Tools", listOf("x"))
        assertEquals(listOf("a", "b", f1), s.favorites)
        assertEquals(listOf("x"), s.folders["1"]?.apps)
    }

    @Test fun aNewFolderTakesItsAppFromAnotherFolderAndDropsThatOneIfLeftEmpty() {
        val s = settings(listOf("a", f1), "1" to Folder("Old", listOf("x"))).newFolder("2", "New", listOf("x"))
        assertEquals(listOf("a", f2), s.favorites)
        assertEquals(mapOf("2" to Folder("New", listOf("x"))), s.folders)
    }

    @Test fun anEmptyNewFolderStaysUntilItsFirstAppsArrive() {
        val s = settings(listOf("a")).newFolder("1", "Later", emptyList())
        assertEquals(listOf("a", f1), s.favorites)
        assertEquals(Folder("Later", emptyList()), s.folders["1"])
        assertEquals(listOf("a"), s.addToFolder("a", "1").folders["1"]?.apps)
    }

    @Test fun folderIdsAreTheSmallestFreeNumber() {
        assertEquals("1", nextFolderId(emptyMap()))
        assertEquals("2", nextFolderId(mapOf("1" to Folder("", emptyList()), "3" to Folder("", emptyList()))))
    }

    @Test fun aNewFoldersNameComesFromThePlayCategoryNumberedIfTaken() {
        assertEquals("Games", defaultFolderName(ApplicationInfo.CATEGORY_GAME, emptyList()))
        assertEquals("Games 2", defaultFolderName(ApplicationInfo.CATEGORY_GAME, listOf("games")))
        assertEquals("Folder", defaultFolderName(ApplicationInfo.CATEGORY_UNDEFINED, listOf("Games")))
        // A folder saved without a name is called "Folder" too.
        assertEquals("Folder 3", defaultFolderName(-1, listOf("", "Folder 2")))
    }

    // Moving apps in and out

    @Test fun addingAFavoriteMovesItIntoTheFolderAtTheEnd() {
        val s = settings(listOf("a", f1, "c"), "1" to Folder("F", listOf("b"))).addToFolder("c", "1")
        assertEquals(listOf("a", f1), s.favorites)
        assertEquals(listOf("b", "c"), s.folders["1"]?.apps)
    }

    @Test fun anAppIsInOneFolderAtMost() {
        val start = settings(listOf(f1, f2), "1" to Folder("One", listOf("a", "b")), "2" to Folder("Two", listOf("c")))
        val s = start.addToFolder("a", "2")
        assertEquals(listOf("b"), s.folders["1"]?.apps)
        assertEquals(listOf("c", "a"), s.folders["2"]?.apps)
        // Moving the last app out drops the folder it leaves, and its place on home.
        val emptied = s.addToFolder("b", "2")
        assertEquals(listOf(f2), emptied.favorites)
        assertEquals(setOf("2"), emptied.folders.keys)
    }

    @Test fun addingToAMissingFolderOrTheSameFolderChangesNothing() {
        val s = settings(listOf("a", f1), "1" to Folder("F", listOf("b")))
        assertSame(s, s.addToFolder("a", "9"))
        assertSame(s, s.addToFolder("b", "1"))
    }

    @Test fun anAppTakenOutOfAFolderGoesBackRightAfterIt() {
        val s = settings(listOf("a", f1, "d"), "1" to Folder("F", listOf("b", "c"))).removeFromFolder("b")
        assertEquals(listOf("a", f1, "b", "d"), s.favorites)
        assertEquals(listOf("c"), s.folders["1"]?.apps)
    }

    @Test fun takingOutTheLastAppDropsTheFolderUnlessItsEditorKeepsIt() {
        val s = settings(listOf("a", f1), "1" to Folder("F", listOf("b")))
        val dropped = s.removeFromFolder("b")
        assertEquals(listOf("a", "b"), dropped.favorites)
        assertEquals(emptyMap<String, Folder>(), dropped.folders)

        val kept = s.removeFromFolder("b", keepEmptyFolder = true)
        assertEquals(listOf("a", f1, "b"), kept.favorites)
        assertEquals(Folder("F", emptyList()), kept.folders["1"])
        // Hidden from home meanwhile, and gone with the next edit elsewhere.
        assertEquals(listOf("a"), homeItems(kept, mapOf("a" to FakeApps.phone.copy(key = "a"))).map { it.key })
        assertEquals(listOf("b", "a"), kept.reorderFavorites(listOf("b", "a")).favorites)
    }

    // Removing a folder

    @Test fun removingAFolderPutsItsAppsBackInItsPlaceAndOrder() {
        val s = settings(listOf("a", f1, "d"), "1" to Folder("F", listOf("b", "c"))).dissolveFolder("1")
        assertEquals(listOf("a", "b", "c", "d"), s.favorites)
        assertEquals(emptyMap<String, Folder>(), s.folders)
    }

    @Test fun removingAFolderFavoriteKeepsItsApps() {
        val s = settings(listOf(f1, "a"), "1" to Folder("F", listOf("b", "a"))).removeFavorite(f1)
        // "a" was somehow both: it stays once.
        assertEquals(listOf("b", "a"), s.favorites)
        assertEquals(listOf("b"), settings(listOf("a", "b")).removeFavorite("a").favorites)
    }

    @Test fun renamingKeepsTheNameTrimmed() {
        val s = settings(listOf(f1), "1" to Folder("F", listOf("a"))).renameFolder("1", "  Work ")
        assertEquals("Work", s.folders["1"]?.name)
    }

    // Reordering

    @Test fun reorderingAFolderKeepsMissingAppsInTheirPlaces() {
        val s = settings(listOf(f1), "1" to Folder("F", listOf("a", "gone", "b", "c")))
        assertEquals(listOf("c", "gone", "a", "b"), s.reorderFolder("1", listOf("c", "a", "b")).folders["1"]?.apps)
        // An app that isn't in it (the list was stale): nothing changes.
        assertSame(s, s.reorderFolder("1", listOf("c", "a", "x")))
    }

    @Test fun reorderingFavoritesMovesFoldersLikeApps() {
        val s = settings(listOf("a", "gone", f1, "b"), "1" to Folder("F", listOf("x")))
        assertEquals(listOf(f1, "gone", "b", "a"), s.reorderFavorites(listOf(f1, "b", "a")).favorites)
    }

    // Uninstalls and moved apps

    @Test fun anUninstalledAppLeavesFavoritesHiddenAndFoldersButFoldersStay() {
        val s = LauncherSettings(
            favorites = listOf("p/a#0", f1),
            hidden = setOf("p/b#0", "q/c#0"),
            folders = mapOf("1" to Folder("F", listOf("p/c#0"))),
        ).withoutApps { it.startsWith("p/") }
        assertEquals(listOf(f1), s.favorites)
        assertEquals(setOf("q/c#0"), s.hidden)
        // Emptied, it's hidden from home but kept until the next edit.
        assertEquals(Folder("F", emptyList()), s.folders["1"])
        assertEquals(emptyMap<String, Folder>(), s.tidyFolders().folders)
        assertEquals(emptyList<String>(), s.tidyFolders().favorites)
    }

    @Test fun folderKeysAreNeverTakenForGoneApps() {
        val s = settings(listOf(f1), "1" to Folder("F", listOf("a"))).withoutApps { true }
        assertEquals(listOf(f1), s.favorites)
    }

    @Test fun aMovedAppIsFollowedIntoItsFolder() {
        val s = LauncherSettings(
            favorites = listOf("old", f1),
            renames = mapOf("old2" to "Mine"),
            folders = mapOf("1" to Folder("F", listOf("x", "old2"))),
        ).withMovedApps(mapOf("old" to "new", "old2" to "new2"))
        assertEquals(listOf("new", f1), s.favorites)
        assertEquals(listOf("x", "new2"), s.folders["1"]?.apps)
        assertEquals(mapOf("new2" to "Mine"), s.renames)
    }

    @Test fun tidyingDropsOrphanFoldersAndDanglingKeys() {
        val s = settings(listOf("a", f1, folderKey("9")), "1" to Folder("F", listOf("b")), "2" to Folder("Orphan", listOf("c")))
        val tidy = s.tidyFolders()
        assertEquals(listOf("a", f1), tidy.favorites)
        assertEquals(setOf("1"), tidy.folders.keys)
        assertSame(tidy, tidy.tidyFolders())
    }

    // What the rows say

    @Test fun aFolderRowShowsItsNewestNotificationAndTheirCount() {
        val messages = FakeNotifications.messages()
        val mail = FakeNotifications.mail()
        val news = folderNotifications(listOf(FakeApps.mail, FakeApps.phone, FakeApps.messages), FakeNotifications.byApp())
        // Messages' newest is 3 minutes old, Mail's 18.
        assertEquals(FakeApps.messages, news?.app)
        assertEquals(messages.first(), news?.latest)
        assertEquals(messages.size + mail.size, news?.count)
        assertEquals(true, news?.badge)
        assertNull(folderNotifications(listOf(FakeApps.phone, FakeApps.camera), FakeNotifications.byApp()))
        // Dots off for all of them: counted, not badged.
        val quiet = mapOf(FakeApps.mail.notificationKey to mail.map { it.copy(showBadge = false) })
        assertEquals(false, folderNotifications(listOf(FakeApps.mail), quiet)?.badge)
    }

    @Test fun theFavoritesSummaryCountsFoldersAsRows() {
        assertEquals("4 apps, 2 folders · add, remove or reorder", favoritesSummary(4, 2))
        assertEquals("1 app · add, remove or reorder", favoritesSummary(1, 0))
        assertEquals("1 folder · add, remove or reorder", favoritesSummary(0, 1))
    }
}
