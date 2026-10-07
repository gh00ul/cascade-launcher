package com.gh00ul.cascade.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

data class AppEntry(
    /** Stable id: component plus user serial, so work-profile twins stay distinct. */
    val key: String,
    val label: String,
    val originalLabel: String,
    val component: ComponentName,
    val user: UserHandle,
    /** In another profile than the launcher's own (work, clone, ...), so never the main copy of an app. */
    val isWork: Boolean,
    /** In a work (managed) profile specifically; an app clone is in another profile but not a work one. */
    val isManagedProfile: Boolean,
    /** Section this app is filed under in the A–Z list ("#" for digits, symbols and letters outside the index). */
    val section: String,
    /** Part of the system image, so it can't be uninstalled: known when the list loads, so a menu needn't ask. */
    val isSystem: Boolean = false,
) {
    val packageName: String get() = component.packageName
    /** Built once rather than on every read: rows look it up on each composition. Not part of equals or copy. */
    val notificationKey: String = notificationKey(packageName, user)
}

fun notificationKey(packageName: String, user: UserHandle): String {
    // hashCode() is the user id. A notification posted to every user (the hidden USER_ALL, -1) belongs to ours.
    val id = user.hashCode().let { if (it == -1) Process.myUserHandle().hashCode() else it }
    return "$packageName@$id"
}

private class InstalledApp(
    val key: String,
    val label: String,
    val info: LauncherActivityInfo,
    val isWork: Boolean,
    val isManagedProfile: Boolean,
)

/**
 * [items] in A–Z list order, each with the section it's filed under: "#" (digits, symbols and letters outside the
 * index) first, then the sections in index order, each one run, and by name within a section.
 */
internal fun <T> sortIntoSections(
    items: List<T>,
    label: (T) -> String,
    locales: LocaleList = LocaleList.getDefault(),
): List<Pair<T, String>> {
    val primary = locales[0] ?: Locale.getDefault()
    // ICU's own, which java.text.Collator wraps on Android anyway: JVM tests then check the same rules.
    val collator = Collator.getInstance(primary).apply { strength = Collator.PRIMARY }
    val index = sectionIndex(locales, primary)
    return items
        .map { item ->
            // Sorted by the trimmed name too, not just bucketed by it: ICU doesn't ignore spaces, so " Zoom" came
            // before "Zebra".
            val name = label(item).trim()
            val bucket = index.getBucketIndex(name)
            // Digits, symbols and letters the index has no section for all go under "#", first.
            val section = index.getBucket(bucket)?.takeIf { it.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL }?.label
            Triple(item to (section ?: "#"), name, if (section == null) -1 else bucket)
        }
        // Index order first, so each section is one run: the collator alone splits some (Czech "ch" sorts
        // after H, katakana ties with hiragana). Then by name within the section.
        .sortedWith(compareBy<Triple<Pair<T, String>, String, Int>> { it.third }.thenBy(collator) { it.second })
        .map { it.first }
}

/** An A–Z index and the languages it was built for. */
private class SectionIndex(val locales: LocaleList, val primary: Locale, val index: AlphabeticIndex.ImmutableIndex<Any>)

/**
 * The last index built. Building one adds about a dozen alphabets, and the languages rarely change, so every sort
 * (each reload, each rename) reuses it. An ImmutableIndex is thread-safe, so they can share it.
 */
@Volatile private var lastSectionIndex: SectionIndex? = null

/** Builds the index for [locales] ahead of the first sort, so the app list load can do it while labels load. */
private fun warmSectionIndex(locales: LocaleList = LocaleList.getDefault()) {
    sectionIndex(locales, locales[0] ?: Locale.getDefault())
}

private fun sectionIndex(locales: LocaleList, primary: Locale): AlphabeticIndex.ImmutableIndex<Any> {
    lastSectionIndex?.let { if (it.locales == locales && it.primary == primary) return it.index }
    return buildSectionIndex(primary, locales).also { lastSectionIndex = SectionIndex(locales, primary, it) }
}

/**
 * The sections of the user's languages, then (as the system's own index does) other common alphabets so
 * their names aren't all lumped under "#": A–Z, Å/Ä/Ö after Z in Swedish, pinyin A–Z for Chinese, kana rows
 * for Japanese, initial consonants for Korean.
 */
private fun buildSectionIndex(primary: Locale, locales: LocaleList): AlphabeticIndex.ImmutableIndex<Any> =
    AlphabeticIndex<Any>(primary).apply {
        // The default cap of 99 labels would thin out every alphabet, A–Z included.
        setMaxLabelCount(300)
        for (i in 0 until locales.size()) addLabels(locales[i])
        addLabels(Locale.ENGLISH, Locale.JAPANESE, Locale.KOREAN)
        // Ukrainian and Serbian together cover Cyrillic, as in the Contacts index.
        for (tag in listOf("th", "ar", "he", "el", "uk", "sr")) addLabels(Locale.forLanguageTag(tag))
    }.buildImmutableIndex()

/**
 * How long a package or profile event waits before reloading. They come in bursts (on Android 15 a work profile
 * toggle sends onPackagesAvailable, MANAGED_PROFILE_AVAILABLE and PROFILE_AVAILABLE), and each one cancels the
 * previous wait, so a burst ends in one reload.
 */
private const val EVENT_COALESCE_MS = 250L

/**
 * Threads a reload uses for labels and icons. Several, so a cold start isn't one app at a time (each label can open
 * the app's resources, each icon decodes and draws); few, so the UI and render threads keep their cores.
 */
private const val LOAD_THREADS = 3

/** Icons rendered between two publishes of the icon map, after the favorites' own publish. */
internal const val ICON_PUBLISH_EVERY = 24

class AppRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** Where reloads run: the list, its labels and the icons, at most [LOAD_THREADS] at once. Tests pass their own. */
    private val workers: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(LOAD_THREADS),
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    /** The list the last reload installed; null until the first. Written under [lock]. */
    @Volatile private var installed: List<InstalledApp>? = null

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Every launchable app, renames applied, sorted for the A–Z list. */
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val iconCache = ConcurrentHashMap<String, IconImage>()
    /** The profile badge layer for glyph icons; the same for every app in a profile, so drawn once per size. */
    private val badgeCache = ConcurrentHashMap<UserHandle, ImageBitmap>()
    private val _icons = MutableStateFlow<Map<String, IconImage>>(emptyMap())
    val icons: StateFlow<Map<String, IconImage>> = _icons.asStateFlow()

    private var loadJob: Job? = null
    private val lock = Any()
    /** Bumped by every refresh; a load job only writes shared state while its own is current. Guarded by [lock]. */
    private var generation = 0
    /** Whether [registerForChanges] has run. Guarded by [callback]. */
    private var registered = false

    private var lastLocales = context.resources.configuration.locales
    private var lastDensityDpi = context.resources.configuration.densityDpi

    private val callback = object : LauncherApps.Callback() {
        // Only a real uninstall: an update calls onPackageChanged instead. Apps that are just missing for now
        // (disabled, on an unmounted SD card, profile paused) keep their favorite and hidden state.
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            val suffix = "#${userManager.getSerialNumberForUser(user)}"
            fun stale(key: String) = key.startsWith("$packageName/") && key.endsWith(suffix)
            // Folders lose the app too; one left empty is hidden from home until the next edit drops it.
            prefs.update { s -> s.withoutApps(::stale) }
            refresh(packageName, coalesce = true)
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(packageName, coalesce = true)
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(packageName, coalesce = true)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            refresh(*packageNames, coalesce = true)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            refresh(*packageNames, coalesce = true)
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh(coalesce = true)
    }

    init {
        scope.launch {
            prefs.settings.map { it.showIcons && it.monochromeIcons }.distinctUntilChanged().drop(1).collect { refresh(clearIcons = true) }
        }
        // Icons are rendered at the home size; loadIcons re-renders any cached icon whose width no longer matches.
        scope.launch {
            prefs.settings.map { it.iconSize }.distinctUntilChanged().drop(1).collect { refresh() }
        }
        // A rename re-sorts the installed list. A reload publishes its own list, with the renames current by then.
        scope.launch(workers) {
            prefs.settings.map { it.renames }.distinctUntilChanged().collect { installed?.let { publishSorted(it) } }
        }
        refresh()
    }

    /** Called by the application: labels, A–Z sections and sort order follow the language, icons the density. */
    fun onConfigurationChanged(config: Configuration) {
        if (config.locales == lastLocales && config.densityDpi == lastDensityDpi) return
        lastLocales = config.locales
        lastDensityDpi = config.densityDpi
        // Labels are read again in the new language; icons drawn for another density are re-rendered by size.
        refresh()
    }

    /**
     * Reloads the app list; icons of [changedPackages] (all, with [clearIcons]) are re-rendered, the rest come from cache.
     * With [coalesce], for bursty system events, the reload waits [EVENT_COALESCE_MS] so the next event can replace it.
     */
    fun refresh(vararg changedPackages: String, clearIcons: Boolean = false, coalesce: Boolean = false) {
        // Invalidate and start a new generation in one step: the previous job may be mid-render and must not
        // put its stale icon back into the cache after this.
        val gen = synchronized(lock) {
            if (clearIcons) {
                iconCache.clear()
                badgeCache.clear()
            }
            for (pkg in changedPackages) iconCache.keys.removeIf { it.startsWith("$pkg/") }
            ++generation
        }
        loadJob?.cancel()
        loadJob = scope.launch(workers) {
            registerForChanges()
            // Invalidation above stays immediate: the job that survives a burst re-renders every icon it dropped.
            if (coalesce) delay(EVENT_COALESCE_MS)
            // The A–Z index for the current languages builds while the list and its labels load.
            val index = launch { warmSectionIndex() }
            val me = Process.myUserHandle()
            val profiles = userManager.userProfiles
            // One profile's activity list at a time; the labels, each of which can open the app's resources, in
            // parallel on the workers and in list order.
            val list = profiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                val other = user != me
                val managed = other && isManagedProfile(user)
                runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                    .filter { it.componentName.packageName != context.packageName }
                    .map { async { InstalledApp("${it.componentName.flattenToString()}#$serial", it.label.toString(), it, other, managed) } }
            }.awaitAll()
            if (!ifCurrent(gen) { retargetMovedKeys(installed, list); installed = list }) return@launch
            seedFavorites(list)
            index.join()
            // The favorites' icons render while the list sorts, and go out after it.
            val published = async { publishSorted(list, gen) }
            loadIcons(list, profiles, gen, published)
        }
    }

    /**
     * Registers for package and profile changes, once: from the first reload, before its first look at the app list,
     * so no change is missed, but off the main thread, as both are binder calls that held up the first frame (the
     * system is busiest at boot). A reload cancelled before it ran leaves it to the next one.
     *
     * Registered for the life of the process, not just while home is started: the repository is app-scoped and also
     * feeds Settings, these events are rare, and catching up on every start would cost a full reload per return home.
     */
    private fun registerForChanges() {
        synchronized(callback) {
            if (registered) return
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
            val profileEvents = IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                // The generic ones also cover profiles that aren't managed, such as app clones.
                if (Build.VERSION.SDK_INT >= 34) {
                    addAction(Intent.ACTION_PROFILE_ADDED)
                    addAction(Intent.ACTION_PROFILE_REMOVED)
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                }
            }
            ContextCompat.registerReceiver(context, profileReceiver, profileEvents, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    /** Runs [write] unless a newer refresh has started since generation [gen]; returns whether it ran. */
    private inline fun ifCurrent(gen: Int, write: () -> Unit): Boolean = synchronized(lock) {
        (gen == generation).also { if (it) write() }
    }

    /**
     * Sorts [list] with the current renames and publishes it, unless a newer list was installed since or (with [gen])
     * a newer refresh started. A rename that lands meanwhile sorts it again, so a reload's list always goes out before
     * its icons. Returns the published entries, or null when superseded.
     */
    private fun publishSorted(list: List<InstalledApp>, gen: Int? = null): List<AppEntry>? {
        while (true) {
            val renames = prefs.settings.value.renames
            val entries = buildEntries(list, renames)
            synchronized(lock) {
                if (installed !== list || (gen != null && gen != generation)) return null
                if (prefs.settings.value.renames == renames) {
                    _apps.value = entries
                    return entries
                }
            }
        }
    }

    /** Only a work profile gets the "work" tag; other kinds (app clones, ...) can only be told apart from Android 15. */
    private fun isManagedProfile(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 35) return true
        val type = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() ?: return true
        return type == UserManager.USER_TYPE_PROFILE_MANAGED
    }

    private fun buildEntries(list: List<InstalledApp>, renames: Map<String, String>): List<AppEntry> =
        sortIntoSections(list, { renames[it.key] ?: it.label }).map { (app, section) ->
            AppEntry(
                app.key, renames[app.key] ?: app.label, app.label, app.info.componentName, app.info.user, app.isWork,
                app.isManagedProfile, section, isSystem = app.info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }

    /**
     * Keeps favorites, folder places, hidden flags and renames when an app's launcher activity is replaced: icon
     * pickers (Telegram, Signal) swap activity-aliases, and updates can rename the activity. [old] is the previous list.
     */
    private fun retargetMovedKeys(old: List<InstalledApp>?, new: List<InstalledApp>) {
        val settings = prefs.settings.value
        val live = new.mapTo(HashSet()) { it.key }
        // Folder entries in the favorites aren't apps: never stale, never moved.
        val gestureApps = listOfNotNull(settings.swipeDownApp, settings.doubleTapApp)
        val stale = (settings.favorites + settings.folders.values.flatMap { it.apps } + settings.hidden + settings.renames.keys + gestureApps)
            .filterTo(HashSet()) { it !in live && !isFolderKey(it) }
        if (stale.isEmpty()) return
        // The same app in the same profile: "pkg/cls#serial" -> "pkg#serial".
        fun owner(key: String) = key.substringBefore('/') + "#" + key.substringAfterLast('#')
        val newByOwner = new.groupBy { owner(it.key) }
        val oldByOwner = old?.groupBy { owner(it.key) }
        val moves = HashMap<String, String>()
        for (key in stale) {
            // App gone, mid-update or its profile off: keep the key for when it's back.
            val now = newByOwner[owner(key)] ?: continue
            val before = oldByOwner?.get(owner(key))?.mapTo(HashSet()) { it.key }
            val target = when {
                // No earlier look at this app (first load, or it was unavailable), so no proof the entry moved: a key
                // kept on purpose looks the same. Only an icon picker's swap shows, by the disabled alias it left.
                before == null -> now.singleOrNull()?.takeIf { !it.isWork && isDisabledAliasOf(key, it) }
                // It went away in this reload: follow the one entry that appeared with it. If none did, it was
                // removed (say, a second launcher entry disabled) and its settings must not land on the app.
                key in before -> now.filter { it.key !in before }.singleOrNull()
                else -> null
            } ?: continue
            moves[key] = target.key
        }
        if (moves.isEmpty()) return
        // Apps in folders follow too. A rename the new entry already has wins over the carried-over one.
        prefs.update { s -> s.withMovedApps(moves) }
    }

    /** First run: start the home screen with the default phone, messages, browser, camera and gallery apps. */
    private fun seedFavorites(list: List<InstalledApp>) {
        if (prefs.settings.value.favoritesSeeded || list.isEmpty()) return
        val pm = context.packageManager
        val defaults = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        )
        val keys = defaults
            .mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            .filter { it.packageName != "android" }
            .mapNotNull { launcherEntryFor(it, list)?.key }
            .distinct()
        // Folders made before the first load (a restore) keep their places; the defaults don't repeat their apps.
        prefs.update { s ->
            if (s.favoritesSeeded) s else {
                val inFolders = s.folders.values.flatMapTo(HashSet()) { it.apps }
                s.copy(favorites = s.favorites.filter(::isFolderKey) + keys.filterNot { it in inFolders }, favoritesSeeded = true)
            }
        }
    }

    /**
     * The launcher entry for the activity a default intent resolved to. One package can have several (Phone and
     * Contacts share one on some phones), so match the activity itself, then its alias target, then any of them.
     */
    private fun launcherEntryFor(resolved: ActivityInfo, list: List<InstalledApp>): InstalledApp? {
        fun target(info: ActivityInfo) = info.targetActivity ?: info.name
        val candidates = list.filter { !it.isWork && it.info.componentName.packageName == resolved.packageName }
        return candidates.firstOrNull { it.info.componentName.className == resolved.name }
            ?: candidates.firstOrNull { candidate ->
                val info = if (Build.VERSION.SDK_INT >= 31) candidate.info.activityInfo
                else runCatching { context.packageManager.getActivityInfo(candidate.info.componentName, 0) }.getOrNull()
                info != null && target(info) == target(resolved)
            }
            ?: candidates.firstOrNull()
    }

    /**
     * Whether [key]'s activity is still in the package (disabled) and opens the same one as [live]: an icon picker's
     * alias swap. PackageManager only answers for the launcher's own profile.
     */
    private fun isDisabledAliasOf(key: String, live: InstalledApp): Boolean {
        fun target(info: ActivityInfo) = info.targetActivity ?: info.name
        val pm = context.packageManager
        val cn = ComponentName.unflattenFromString(key.substringBeforeLast('#')) ?: return false
        val old = runCatching { pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull() ?: return false
        val now = if (Build.VERSION.SDK_INT >= 31) live.info.activityInfo
        else runCatching { pm.getActivityInfo(live.info.componentName, 0) }.getOrNull() ?: return false
        return target(old) == target(now)
    }

    /**
     * Renders the icons [list] lacks: the home screen's first (favorites and the apps in its folders, whose icons the
     * folder rows show), so it fills in before the long list does, then the rest in A–Z order with hidden apps last.
     * Nothing is published before [published], the list itself.
     */
    private suspend fun loadIcons(
        list: List<InstalledApp>,
        profiles: List<UserHandle>,
        gen: Int,
        published: Deferred<List<AppEntry>?>,
    ) {
        val settings = prefs.settings.value
        val monochrome = settings.showIcons && settings.monochromeIcons
        // At least 48dp: the long-press sheet and the player draw icons that big whatever the list size.
        val size = (maxOf(settings.iconSize.homeDp, 48) * context.resources.displayMetrics.density).roundToInt()
        val live = list.associateBy { it.key }
        // Apps that are gone lose their icons now, so no publish of this reload carries them.
        if (!ifCurrent(gen) { iconCache.keys.retainAll(live.keys) }) return
        // Every icon is size x size, so one drawn for an earlier screen density gets redrawn.
        fun stale(app: InstalledApp) = iconCache[app.key]?.bitmap?.width != size
        val home = settings.homeAppKeys()
        val complete = renderInParallel(
            first = home.mapNotNull { live[it] }.filter(::stale),
            rest = rest@{
                val sorted = published.await() ?: return@rest emptyList()
                val favorites = home.toHashSet()
                sorted.mapNotNull { entry -> live[entry.key]?.takeIf { it.key !in favorites && stale(it) } }
                    .sortedBy { it.key in settings.hidden }
            },
            threads = LOAD_THREADS,
            dispatcher = workers,
            render = { app ->
                val icon = renderIcon(app, monochrome, size)
                // A newer refresh may have invalidated this icon while it rendered: never put a stale one back.
                icon == null || ifCurrent(gen) { iconCache[app.key] = icon }
            },
            // Over the icons showing, so one dropped to be drawn again (monochrome turned on, an app updated) keeps its
            // old look until its new one is ready rather than going blank; only installed apps' stay.
            publish = {
                published.await() != null && ifCurrent(gen) {
                    _icons.value = HashMap(_icons.value).apply {
                        keys.retainAll(live.keys)
                        putAll(iconCache)
                    }
                }
            },
        )
        if (!complete || published.await() == null) return
        ifCurrent(gen) {
            iconCache.keys.retainAll(live.keys)
            badgeCache.keys.retainAll(profiles.toSet())
            _icons.value = HashMap(iconCache)
        }
    }

    // Each layer moves to graphics memory (toHardware) only once nothing draws into it any more.
    private fun renderIcon(app: InstalledApp, monochrome: Boolean, size: Int): IconImage? = runCatching {
        val dpi = context.resources.displayMetrics.densityDpi
        if (!monochrome) return@runCatching IconImage(app.info.getBadgedIcon(dpi).renderTo(size).toHardware().asImageBitmap(), isGlyph = false)
        val mono = app.info.getIcon(dpi).renderMonochrome(size)
        if (!app.isWork) return@runCatching IconImage(mono.bitmap.toHardware(), mono.isGlyph)
        val pm = context.packageManager
        if (mono.isGlyph) {
            // The UI tints glyphs, which would flatten a badge drawn into one to a blob: keep it as its own layer.
            val badge = badgeCache[app.info.user]?.takeIf { it.width == size } ?: run {
                val blank = createBitmap(size, size).toDrawable(context.resources)
                pm.getUserBadgedIcon(blank, app.info.user).renderTo(size).toHardware().asImageBitmap().also { badgeCache[app.info.user] = it }
            }
            return@runCatching IconImage(mono.bitmap.toHardware(), isGlyph = true, badge = badge)
        }
        val plain = mono.bitmap.asAndroidBitmap().toDrawable(context.resources)
        IconImage(pm.getUserBadgedIcon(plain, app.info.user).renderTo(size).toHardware().asImageBitmap(), isGlyph = false)
    }.getOrNull()
}

/**
 * Calls [render] on every item of [first], then of the list [rest] returns (asked once, when [first] runs out), on
 * [threads] workers on [dispatcher]. Calls [publish] once all of [first] are done, then after every [publishEvery]
 * more; one that comes due before all of [first] are done is skipped, so every publish has them all. Stops as soon as
 * [render] or [publish] returns false (a newer load took over) and, between items, on cancellation. Returns whether
 * it got through every item.
 */
internal suspend fun <T> renderInParallel(
    first: List<T>,
    rest: suspend () -> List<T>,
    threads: Int,
    dispatcher: CoroutineDispatcher,
    publishEvery: Int = ICON_PUBLISH_EVERY,
    render: (T) -> Boolean,
    publish: suspend () -> Boolean,
): Boolean = coroutineScope {
    val next = AtomicInteger()
    val firstLeft = AtomicInteger(first.size)
    val sincePublish = AtomicInteger()
    val superseded = AtomicBoolean()
    val later = async(start = CoroutineStart.LAZY) { rest() }
    List(threads) {
        launch(dispatcher) {
            while (!superseded.get()) {
                val i = next.getAndIncrement()
                val isFirst = i < first.size
                val item = if (isFirst) first[i] else (later.await().getOrNull(i - first.size) ?: break)
                ensureActive()
                if (!render(item)) {
                    superseded.set(true)
                    break
                }
                val due = if (isFirst) firstLeft.decrementAndGet() == 0 else {
                    sincePublish.incrementAndGet() % publishEvery == 0 && firstLeft.get() == 0
                }
                if (!due) continue
                ensureActive()
                if (!publish()) {
                    superseded.set(true)
                    break
                }
            }
        }
    }.joinAll()
    // Never started when every worker stopped before reaching it.
    later.cancel()
    !superseded.get()
}
