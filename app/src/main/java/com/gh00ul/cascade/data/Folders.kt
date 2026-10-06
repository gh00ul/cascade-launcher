package com.gh00ul.cascade.data

import android.content.pm.ApplicationInfo

/*
 * Folders on the home screen. A folder sits in [LauncherSettings.favorites] as [FOLDER_PREFIX] + its id, in home order
 * with the apps, and its apps live in [LauncherSettings.folders]. An app is either a favorite of its own, in one
 * folder, or not on the home screen at all; every edit here keeps it that way.
 *
 * Everything here is a pure function of the settings, so Prefs.update can run it and the tests can check it.
 */

/** A row of the home screen's favorites: an app, or a folder of apps. */
sealed interface HomeItem {
    /** The row's entry in [LauncherSettings.favorites], so also a stable key for lists. */
    val key: String
}

data class HomeApp(val app: AppEntry) : HomeItem {
    override val key: String get() = app.key
}

/** A folder as the home screen shows it: its installed apps in order; never empty (an empty folder isn't shown). */
data class HomeFolder(val id: String, val name: String, val apps: List<AppEntry>) : HomeItem {
    override val key: String = folderKey(id)
    /** What it's called on screen: a folder saved without a name is "Folder". */
    val label: String get() = folderLabel(name)
}

/** The name a folder shows: [name], or "Folder" when it has none. */
fun folderLabel(name: String): String = name.trim().ifEmpty { DEFAULT_FOLDER_NAME }

private const val DEFAULT_FOLDER_NAME = "Folder"

fun isFolderKey(key: String): Boolean = key.startsWith(FOLDER_PREFIX)

fun folderKey(id: String): String = FOLDER_PREFIX + id

/**
 * The home screen's rows, from the stored keys: apps that resolve in [byKey] and folders that hold at least one, in
 * home order. Missing apps (uninstalled, disabled, their profile paused) are skipped and keep their places, and a
 * folder with none of its apps here is hidden until one is back. A key that appears twice shows once.
 */
fun homeItems(settings: LauncherSettings, byKey: Map<String, AppEntry>): List<HomeItem> {
    val seen = HashSet<String>()
    return settings.favorites.mapNotNull { key ->
        if (!seen.add(key)) return@mapNotNull null
        if (isFolderKey(key)) {
            val id = key.removePrefix(FOLDER_PREFIX)
            val folder = settings.folders[id] ?: return@mapNotNull null
            val apps = folder.apps.mapNotNull { byKey[it] }
            if (apps.isEmpty()) null else HomeFolder(id, folder.name, apps)
        } else {
            byKey[key]?.let(::HomeApp)
        }
    }
}

/** The folders on the home screen, in home order, as id to folder. Folders nothing points at are left out. */
fun LauncherSettings.homeFolders(): List<Pair<String, Folder>> = favorites.asSequence()
    .filter(::isFolderKey)
    .map { it.removePrefix(FOLDER_PREFIX) }
    .distinct()
    .mapNotNull { id -> folders[id]?.let { id to it } }
    .toList()

/** The id of the home screen folder [appKey] is in, or null. */
fun LauncherSettings.folderOf(appKey: String): String? = homeFolders().firstOrNull { (_, folder) -> appKey in folder.apps }?.first

/** Every app key on the home screen, in home order: the favorites, with each folder's apps in its place. */
fun LauncherSettings.homeAppKeys(): List<String> = favorites.flatMap { key ->
    if (isFolderKey(key)) folders[key.removePrefix(FOLDER_PREFIX)]?.apps.orEmpty() else listOf(key)
}.distinct()

/** An id no folder has yet: the smallest number from 1 up. */
fun nextFolderId(folders: Map<String, Folder>): String = generateSequence(1) { it + 1 }.map(Int::toString).first { it !in folders }

/**
 * A name for a new folder started from an app of Play's [category] ([ApplicationInfo.category], or -1): the category's
 * name where it has one, "Folder" otherwise, with a number added if a folder in [taken] already has it.
 */
fun defaultFolderName(category: Int, taken: Collection<String>): String {
    val base = when (category) {
        ApplicationInfo.CATEGORY_GAME -> "Games"
        ApplicationInfo.CATEGORY_AUDIO -> "Music & audio"
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Travel"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility"
        else -> DEFAULT_FOLDER_NAME
    }
    val names = taken.mapTo(HashSet()) { folderLabel(it).lowercase() }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in names }
}

/**
 * A new folder [id] called [name] holding [appKeys], which leave their places on the home screen (as favorites or in
 * other folders). It takes the place of the first of them that was a favorite, or goes at the end.
 */
fun LauncherSettings.newFolder(id: String, name: String, appKeys: List<String>): LauncherSettings {
    val moving = appKeys.distinct()
    val movingSet = moving.toHashSet()
    var placed = false
    val keys = buildList {
        for (key in favorites) {
            if (key in movingSet) {
                if (!placed) add(folderKey(id))
                placed = true
            } else {
                add(key)
            }
        }
        if (!placed) add(folderKey(id))
    }
    return copy(
        favorites = keys,
        folders = folders.mapValues { (_, f) -> f.copy(apps = f.apps.filterNot { it in movingSet }) } + (id to Folder(name.trim(), moving)),
    ).tidyFolders(keep = id)
}

/**
 * [appKey] moved into folder [id], at its end: out of the favorites and any other folder. A folder that's missing is
 * left alone; one that somehow isn't on the home screen goes where the app was, or at the end.
 */
fun LauncherSettings.addToFolder(appKey: String, id: String): LauncherSettings {
    val folder = folders[id] ?: return this
    if (appKey in folder.apps) return this
    val key = folderKey(id)
    val keys = if (key in favorites) favorites - appKey else {
        val at = favorites.indexOf(appKey)
        if (at < 0) favorites + key else favorites.toMutableList().apply { set(at, key) }
    }
    return copy(
        favorites = keys,
        folders = folders.mapValues { (fid, f) -> if (fid == id) f.copy(apps = f.apps + appKey) else f.copy(apps = f.apps - appKey) },
    ).tidyFolders(keep = id)
}

/**
 * [appKey] taken out of its folder and put back on the home screen as a favorite, right after the folder. A folder
 * left empty is dropped, unless [keepEmptyFolder] (its editor is open, and you may be about to add others).
 */
fun LauncherSettings.removeFromFolder(appKey: String, keepEmptyFolder: Boolean = false): LauncherSettings {
    val id = folders.entries.firstOrNull { appKey in it.value.apps }?.key ?: return this
    val key = folderKey(id)
    val keys = when {
        appKey in favorites -> favorites
        key in favorites -> favorites.toMutableList().apply { add(indexOf(key) + 1, appKey) }
        else -> favorites + appKey
    }
    return copy(
        favorites = keys,
        folders = folders + (id to folders.getValue(id).let { it.copy(apps = it.apps - appKey) }),
    ).tidyFolders(keep = id.takeIf { keepEmptyFolder })
}

/** Folder [id] gone, its apps back on the home screen as favorites in its place and order. */
fun LauncherSettings.dissolveFolder(id: String): LauncherSettings {
    val folder = folders[id] ?: return copy(favorites = favorites - folderKey(id)).tidyFolders()
    val apps = folder.apps.filterNot { it in favorites }
    val key = folderKey(id)
    val keys = if (key in favorites) favorites.flatMap { if (it == key) apps else listOf(it) }.distinct() else (favorites + apps).distinct()
    return copy(favorites = keys, folders = folders - id).tidyFolders()
}

fun LauncherSettings.renameFolder(id: String, name: String): LauncherSettings {
    val folder = folders[id] ?: return this
    return copy(folders = folders + (id to folder.copy(name = name.trim()))).tidyFolders(keep = id)
}

/**
 * Folder [id]'s apps in [order]: the keys of the apps shown, written over their places in the stored list, so apps
 * that are missing for now keep theirs. Nothing changes unless [order] is exactly the folder's shown apps.
 */
fun LauncherSettings.reorderFolder(id: String, order: List<String>): LauncherSettings {
    val folder = folders[id] ?: return this
    val shown = order.toHashSet()
    if (folder.apps.count { it in shown } != order.size) return this
    val next = order.iterator()
    return copy(folders = folders + (id to folder.copy(apps = folder.apps.map { if (it in shown) next.next() else it })))
        .tidyFolders(keep = id)
}

/**
 * The favorites in [order], the keys of the rows shown (apps and folders), written over their places in the stored
 * list, so favorites that are missing for now keep theirs. Nothing changes unless [order] is exactly the rows shown.
 */
fun LauncherSettings.reorderFavorites(order: List<String>): LauncherSettings {
    val shown = order.toHashSet()
    if (favorites.count { it in shown } != order.size) return this
    val next = order.iterator()
    return copy(favorites = favorites.map { if (it in shown) next.next() else it }).tidyFolders()
}

/** A favorite taken off the home screen; a folder's apps stay, as favorites in its place (see [dissolveFolder]). */
fun LauncherSettings.removeFavorite(key: String): LauncherSettings =
    if (isFolderKey(key)) dissolveFolder(key.removePrefix(FOLDER_PREFIX)) else copy(favorites = favorites - key).tidyFolders()

/**
 * Apps that are gone for good ([gone]: uninstalled) taken out of the favorites, the hidden apps and the folders. A
 * folder left empty stays hidden until the next edit drops it.
 */
fun LauncherSettings.withoutApps(gone: (String) -> Boolean): LauncherSettings {
    fun goneApp(key: String) = !isFolderKey(key) && gone(key)
    return copy(
        favorites = favorites.filterNot(::goneApp),
        hidden = hidden.filterNotTo(LinkedHashSet(), ::goneApp),
        folders = folders.mapValues { (_, f) -> if (f.apps.any(::goneApp)) f.copy(apps = f.apps.filterNot(::goneApp)) else f },
    )
}

/**
 * Each app key in [moves] replaced by its new key wherever it's kept: favorites, hidden apps, renames, folders and the
 * gestures' apps. A rename the new key already has wins over the carried-over one.
 */
fun LauncherSettings.withMovedApps(moves: Map<String, String>): LauncherSettings = copy(
    swipeDownApp = swipeDownApp?.let { moves[it] ?: it },
    doubleTapApp = doubleTapApp?.let { moves[it] ?: it },
    favorites = favorites.map { moves[it] ?: it }.distinct(),
    hidden = hidden.mapTo(LinkedHashSet()) { moves[it] ?: it },
    renames = buildMap {
        renames.forEach { (k, v) -> if (k !in moves) put(k, v) }
        renames.forEach { (k, v) -> moves[k]?.let { if (it !in this) put(it, v) } }
    },
    folders = folders.mapValues { (_, f) -> if (f.apps.any { it in moves }) f.copy(apps = f.apps.map { moves[it] ?: it }.distinct()) else f },
)

/**
 * Drops what an edit can leave behind: folders with no apps (but [keep], the one being edited), folders the
 * favorites don't point at, and favorites pointing at no folder.
 */
fun LauncherSettings.tidyFolders(keep: String? = null): LauncherSettings {
    val onHome = favorites.filter(::isFolderKey).mapTo(HashSet()) { it.removePrefix(FOLDER_PREFIX) }
    val kept = folders.filter { (id, f) -> id == keep || (f.apps.isNotEmpty() && id in onHome) }
    val keys = favorites.filter { !isFolderKey(it) || it.removePrefix(FOLDER_PREFIX) in kept }
    return if (kept.size == folders.size && keys.size == favorites.size) this else copy(favorites = keys, folders = kept)
}
