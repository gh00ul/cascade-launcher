package com.gh00ul.cascade.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class AppEntry(
    /** Stable id: component plus user serial, so work-profile twins stay distinct. */
    val key: String,
    val label: String,
    val originalLabel: String,
    val component: ComponentName,
    val user: UserHandle,
    val isWork: Boolean,
    /** Letter this app is filed under in the A–Z list ("#" for digits and symbols). */
    val section: String,
) {
    val packageName: String get() = component.packageName
    val notificationKey: String get() = notificationKey(packageName, user)
}

fun notificationKey(packageName: String, user: UserHandle) = "$packageName@${user.hashCode()}"

private class InstalledApp(val key: String, val label: String, val info: LauncherActivityInfo, val isWork: Boolean)

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
    private val _icons = MutableStateFlow<Map<String, IconImage>>(emptyMap())
    val icons: StateFlow<Map<String, IconImage>> = _icons.asStateFlow()

    private var loadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh(packageName)
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
        }
        ContextCompat.registerReceiver(context, profileReceiver, profileEvents, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            prefs.settings.map { it.monochromeIcons }.distinctUntilChanged().drop(1).collect {
                iconCache.clear()
                refresh()
            }
        }
        refresh()
    }

    /** Reloads the app list; icons of [changedPackages] are re-rendered, the rest come from cache. */
    fun refresh(vararg changedPackages: String) {
        for (pkg in changedPackages) iconCache.keys.removeIf { it.startsWith("$pkg/") }
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Default) {
            val me = Process.myUserHandle()
            val list = userManager.userProfiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                    .filter { it.componentName.packageName != context.packageName }
                    .map { InstalledApp("${it.componentName.flattenToString()}#$serial", it.label.toString(), it, user != me) }
            }
            installed.value = list
            seedFavorites(list)
            loadIcons(list)
        }
    }

    private fun buildEntries(list: List<InstalledApp>, renames: Map<String, String>): List<AppEntry> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        return list
            .map { app ->
                val label = renames[app.key] ?: app.label
                AppEntry(app.key, label, app.label, app.info.componentName, app.info.user, app.isWork, sectionOf(label))
            }
            .sortedWith(compareBy<AppEntry> { it.section != "#" }.thenComparator { a, b -> collator.compare(a.label, b.label) })
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
            .mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .filter { it != "android" }
            .distinct()
            .mapNotNull { pkg -> list.firstOrNull { !it.isWork && it.info.componentName.packageName == pkg }?.key }
        prefs.update { if (it.favoritesSeeded) it else it.copy(favorites = keys, favoritesSeeded = true) }
    }

    private suspend fun loadIcons(list: List<InstalledApp>) {
        val favorites = prefs.settings.value.favorites.toSet()
        val monochrome = prefs.settings.value.monochromeIcons
        val size = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
        var sincePublish = 0
        // Favorites first so the home screen fills in before the long list does.
        for (app in list.sortedBy { it.key !in favorites }) {
            yield()
            if (iconCache.containsKey(app.key)) continue
            renderIcon(app, monochrome, size)?.let { iconCache[app.key] = it }
            if (++sincePublish >= 16) {
                _icons.value = HashMap(iconCache)
                sincePublish = 0
            }
        }
        iconCache.keys.retainAll(list.mapTo(HashSet()) { it.key })
        _icons.value = HashMap(iconCache)
    }

    private fun renderIcon(app: InstalledApp, monochrome: Boolean, size: Int): IconImage? = runCatching {
        val dpi = context.resources.displayMetrics.densityDpi
        if (!monochrome) return@runCatching IconImage(app.info.getBadgedIcon(dpi).renderTo(size).asImageBitmap(), isGlyph = false)
        val mono = app.info.getIcon(dpi).renderMonochrome(size)
        if (!app.isWork) return@runCatching mono
        val plain = BitmapDrawable(context.resources, mono.bitmap.asAndroidBitmap())
        IconImage(context.packageManager.getUserBadgedIcon(plain, app.info.user).renderTo(size).asImageBitmap(), mono.isGlyph)
    }.getOrNull()

    private fun sectionOf(label: String): String {
        val first = label.trim().normalizedForSearch().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    private companion object {
        const val ICON_DP = 48
    }
}
