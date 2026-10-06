package com.gh00ul.cascade.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo

/** An app shortcut search can find ("New incognito tab"), with the app it opens in. */
class FoundShortcut(val info: ShortcutInfo, val label: String, val longLabel: String, val app: AppEntry) {
    /** Unique across apps and profiles: shortcut ids are only unique within an app. */
    val id: String get() = "${app.key}/${info.id}"
}

/**
 * Every enabled static and dynamic shortcut of [apps], read once per search. Cross-process and per profile, so off
 * the main thread; empty unless Cascade is the default home app, the only one Android lets read them.
 */
fun loadSearchShortcuts(context: Context, apps: List<AppEntry>): List<FoundShortcut> = runCatching {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    if (!launcherApps.hasShortcutHostPermission()) return emptyList()
    val byPackage = apps.groupBy { it.packageName to it.user }
    val query = LauncherApps.ShortcutQuery()
        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
    launcherApps.profiles.flatMap { user ->
        launcherApps.getShortcuts(query, user).orEmpty().mapNotNull { info ->
            if (!info.isEnabled) return@mapNotNull null
            val owners = byPackage[info.`package` to user] ?: return@mapNotNull null
            val app = owners.firstOrNull { it.component == info.activity } ?: owners.first()
            val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            FoundShortcut(info, label, info.longLabel?.toString()?.trim().orEmpty(), app)
        }
    }
}.getOrDefault(emptyList())

/** A shortcut matches only from a word's start ("inc" finds New incognito tab), never from somewhere inside one. */
private const val SHORTCUT_MIN_SCORE = 60

/** The shortcuts in [all] matching [query], best first, at most [limit]; none for a single letter. */
fun matchShortcuts(all: List<FoundShortcut>, query: String, limit: Int = 3): List<FoundShortcut> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    return all
        .mapNotNull { s ->
            val score = maxOf(matchScore(s.label, q), if (s.longLabel.isEmpty()) 0 else matchScore(s.longLabel, q))
            if (score >= SHORTCUT_MIN_SCORE) s to score else null
        }
        .sortedWith(compareByDescending<Pair<FoundShortcut, Int>> { it.second }.thenBy { it.first.label.length })
        .take(limit)
        .map { it.first }
}
