package com.gh00ul.cascade.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.icu.text.AlphabeticIndex
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
) {
    val packageName: String get() = component.packageName
    val notificationKey: String get() = notificationKey(packageName, user)
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

class AppRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)

    /** Every launchable app, renames applied, sorted for the A–Z list. */
    val apps: StateFlow<List<AppEntry>> =
        combine(installed.filterNotNull(), prefs.settings.map { it.renames }.distinctUntilChanged()) { list, renames ->
            buildEntries(list, renames)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val iconCache = ConcurrentHashMap<String, IconImage>()
    /** The profile badge layer for glyph icons; the same for every app in a profile, so drawn once per size. */
    private val badgeCache = ConcurrentHashMap<UserHandle, ImageBitmap>()
    private val _icons = MutableStateFlow<Map<String, IconImage>>(emptyMap())
    val icons: StateFlow<Map<String, IconImage>> = _icons.asStateFlow()

    private var loadJob: Job? = null
    private val lock = Any()
    /** Bumped by every refresh; a load job only writes shared state while its own is current. Guarded by [lock]. */
    private var generation = 0

    private var lastLocales = context.resources.configuration.locales
    private var lastDensityDpi = context.resources.configuration.densityDpi

    private val callback = object : LauncherApps.Callback() {
        // Only a real uninstall: an update calls onPackageChanged instead. Apps that are just missing for now
        // (disabled, on an unmounted SD card, profile paused) keep their favorite and hidden state.
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            val suffix = "#${userManager.getSerialNumberForUser(user)}"
            fun stale(key: String) = key.startsWith("$packageName/") && key.endsWith(suffix)
            prefs.update { s -> s.copy(favorites = s.favorites.filterNot(::stale), hidden = s.hidden.filterNotTo(LinkedHashSet(), ::stale)) }
            refresh(packageName)
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(packageName)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            refresh(*packageNames)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            refresh(*packageNames)
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
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
        scope.launch {
            prefs.settings.map { it.monochromeIcons }.distinctUntilChanged().drop(1).collect { refresh(clearIcons = true) }
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

    /** Reloads the app list; icons of [changedPackages] (all, with [clearIcons]) are re-rendered, the rest come from cache. */
    fun refresh(vararg changedPackages: String, clearIcons: Boolean = false) {
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
        loadJob = scope.launch(Dispatchers.Default) {
            val me = Process.myUserHandle()
            val list = userManager.userProfiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                val other = user != me
                val managed = other && isManagedProfile(user)
                runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                    .filter { it.componentName.packageName != context.packageName }
                    .map { InstalledApp("${it.componentName.flattenToString()}#$serial", it.label.toString(), it, other, managed) }
            }
            if (!ifCurrent(gen) { retargetMovedKeys(installed.value, list); installed.value = list }) return@launch
            seedFavorites(list)
            loadIcons(list, gen)
        }
    }

    /** Runs [write] unless a newer refresh has started since generation [gen]; returns whether it ran. */
    private inline fun ifCurrent(gen: Int, write: () -> Unit): Boolean = synchronized(lock) {
        (gen == generation).also { if (it) write() }
    }

    /** Only a work profile gets the "work" tag; other kinds (app clones, ...) can only be told apart from Android 15. */
    private fun isManagedProfile(user: UserHandle): Boolean {
        if (Build.VERSION.SDK_INT < 35) return true
        val type = runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() ?: return true
        return type == UserManager.USER_TYPE_PROFILE_MANAGED
    }

    private fun buildEntries(list: List<InstalledApp>, renames: Map<String, String>): List<AppEntry> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val index = sectionIndex()
        return list
            .map { app ->
                val label = renames[app.key] ?: app.label
                val bucket = index.getBucketIndex(label.trim())
                // Digits, symbols and letters the index has no section for all go under "#", first.
                val section = index.getBucket(bucket)?.takeIf { it.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL }?.label
                val entry = AppEntry(
                    app.key, label, app.label, app.info.componentName, app.info.user, app.isWork, app.isManagedProfile,
                    section ?: "#",
                )
                entry to (if (section == null) -1 else bucket)
            }
            // Index order first, so each section is one run: the collator alone splits some (Czech "ch" sorts
            // after H, katakana ties with hiragana). Then by name within the section.
            .sortedWith(compareBy<Pair<AppEntry, Int>> { it.second }.thenBy(collator) { it.first.label })
            .map { it.first }
    }

    /**
     * The sections of the user's languages, then (as the system's own index does) other common alphabets so
     * their names aren't all lumped under "#": A–Z, Å/Ä/Ö after Z in Swedish, pinyin A–Z for Chinese, kana rows
     * for Japanese, initial consonants for Korean.
     */
    private fun sectionIndex(): AlphabeticIndex.ImmutableIndex<Any> {
        val locales = LocaleList.getDefault()
        return AlphabeticIndex<Any>(Locale.getDefault()).apply {
            // The default cap of 99 labels would thin out every alphabet, A–Z included.
            setMaxLabelCount(300)
            for (i in 0 until locales.size()) addLabels(locales[i])
            addLabels(Locale.ENGLISH, Locale.JAPANESE, Locale.KOREAN)
            // Ukrainian and Serbian together cover Cyrillic, as in the Contacts index.
            for (tag in listOf("th", "ar", "he", "el", "uk", "sr")) addLabels(Locale.forLanguageTag(tag))
        }.buildImmutableIndex()
    }

    /**
     * Keeps favorites, hidden flags and renames when an app's launcher activity is replaced: icon pickers
     * (Telegram, Signal) swap activity-aliases, and updates can rename the activity. [old] is the previous list.
     */
    private fun retargetMovedKeys(old: List<InstalledApp>?, new: List<InstalledApp>) {
        val settings = prefs.settings.value
        val live = new.mapTo(HashSet()) { it.key }
        val stale = (settings.favorites + settings.hidden + settings.renames.keys).filterTo(HashSet()) { it !in live }
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
        prefs.update { s ->
            s.copy(
                favorites = s.favorites.map { moves[it] ?: it }.distinct(),
                hidden = s.hidden.mapTo(LinkedHashSet()) { moves[it] ?: it },
                // A rename the new entry already has wins over the carried-over one.
                renames = buildMap {
                    s.renames.forEach { (k, v) -> if (k !in moves) put(k, v) }
                    s.renames.forEach { (k, v) -> moves[k]?.let { if (it !in this) put(it, v) } }
                },
            )
        }
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
        prefs.update { if (it.favoritesSeeded) it else it.copy(favorites = keys, favoritesSeeded = true) }
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

    private suspend fun loadIcons(list: List<InstalledApp>, gen: Int) {
        val favorites = prefs.settings.value.favorites.toSet()
        val monochrome = prefs.settings.value.monochromeIcons
        val size = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        var sincePublish = 0
        // Favorites first so the home screen fills in before the long list does.
        for (app in list.sortedBy { it.key !in favorites }) {
            yield()
            // Every icon is size x size, so one drawn for an earlier screen density gets redrawn.
            if (iconCache[app.key]?.bitmap?.width == size) continue
            val icon = renderIcon(app, monochrome, size) ?: continue
            if (!ifCurrent(gen) { iconCache[app.key] = icon }) return
            if (++sincePublish >= 16) {
                if (!ifCurrent(gen) { _icons.value = HashMap(iconCache) }) return
                sincePublish = 0
            }
        }
        ifCurrent(gen) {
            iconCache.keys.retainAll(list.mapTo(HashSet()) { it.key })
            _icons.value = HashMap(iconCache)
        }
    }

    private fun renderIcon(app: InstalledApp, monochrome: Boolean, size: Int): IconImage? = runCatching {
        val dpi = context.resources.displayMetrics.densityDpi
        if (!monochrome) return@runCatching IconImage(app.info.getBadgedIcon(dpi).renderTo(size).asImageBitmap(), isGlyph = false)
        val mono = app.info.getIcon(dpi).renderMonochrome(size)
        if (!app.isWork) return@runCatching mono
        val pm = context.packageManager
        if (mono.isGlyph) {
            // The UI tints glyphs, which would flatten a badge drawn into one to a blob: keep it as its own layer.
            val badge = badgeCache[app.info.user]?.takeIf { it.width == size } ?: run {
                val blank = BitmapDrawable(context.resources, Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
                pm.getUserBadgedIcon(blank, app.info.user).renderTo(size).asImageBitmap().also { badgeCache[app.info.user] = it }
            }
            return@runCatching IconImage(mono.bitmap, isGlyph = true, badge = badge)
        }
        val plain = BitmapDrawable(context.resources, mono.bitmap.asAndroidBitmap())
        IconImage(pm.getUserBadgedIcon(plain, app.info.user).renderTo(size).asImageBitmap(), isGlyph = false)
    }.getOrNull()

    private companion object {
        const val ICON_DP = 48
    }
}
