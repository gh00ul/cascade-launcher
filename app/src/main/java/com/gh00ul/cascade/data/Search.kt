package com.gh00ul.cascade.data

import java.text.Normalizer

private val combiningMarks = Regex("\\p{Mn}+")

// Letters with no decomposition, so stripping marks alone never reaches their ASCII spelling.
private val letterFolds = mapOf(
    'đ' to "d", 'ð' to "d", 'ı' to "i", 'ł' to "l", 'ø' to "o", 'ħ' to "h", 'ŧ' to "t",
    'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'þ' to "th", 'ς' to "σ",
)

/**
 * Lowercase, strip accents and fold stroked letters so "Café" matches "cafe" and "Cài đặt" matches "cai dat".
 * NFKD (not NFD) also maps fullwidth forms, ligatures and the compatibility jamo a Korean IME is composing.
 */
fun String.normalizedForSearch(): String {
    val base = Normalizer.normalize(this, Normalizer.Form.NFKD).replace(combiningMarks, "").lowercase()
    if (base.none { it in letterFolds }) return base
    return buildString(base.length + 4) {
        for (c in base) { val f = letterFolds[c]; if (f != null) append(f) else append(c) }
    }
}

/** A label as [score] reads it: normalized, split into words, and those words' initials. */
private class SearchKey(val text: String) {
    val words = text.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
    val initials = words.joinToString("") { it.take(1) }
}

/** [apps]' labels as search keys, in the same order. [original] is null where it normalizes to the same as [label]. */
private class SearchIndex(val apps: List<AppEntry>, val label: List<SearchKey>, val original: List<SearchKey?>)

/**
 * The index of the last list searched. Search runs on the main thread on every keystroke, and normalizing every
 * label is most of its cost; while the list stays the same, typing only scores. Keyed by identity: the repository
 * publishes a new list for every change (an install, a rename), which then gets a new index.
 */
@Volatile private var lastIndex: SearchIndex? = null

private fun searchIndex(apps: List<AppEntry>): SearchIndex {
    lastIndex?.let { if (it.apps === apps) return it }
    val label = apps.map { SearchKey(it.label.normalizedForSearch()) }
    val original = apps.mapIndexed { i, app ->
        app.originalLabel.takeIf { it != app.label }?.normalizedForSearch()?.takeIf { it != label[i].text }?.let(::SearchKey)
    }
    return SearchIndex(apps, label, original).also { lastIndex = it }
}

/**
 * Best matches first: prefix, then word start, then initials ("gm" → Google Maps), then substring, then fuzzy. Apps
 * whose keys are in [exclude] never match; they stay in the index, so it isn't rebuilt when that set changes.
 */
fun searchApps(apps: List<AppEntry>, query: String, exclude: Set<String> = emptySet()): List<AppEntry> {
    val q = query.trim().normalizedForSearch()
    if (q.isEmpty()) return emptyList()
    val index = searchIndex(apps)
    return apps
        .mapIndexedNotNull { i, app ->
            if (app.key in exclude) return@mapIndexedNotNull null
            // An unrenamed app's original label is its label; scoring it again can't raise the score.
            val score = maxOf(score(index.label[i], q), index.original[i]?.let { score(it, q) } ?: 0)
            if (score > 0) app to score else null
        }
        .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
        .map { it.first }
}

private fun score(key: SearchKey, q: String): Int {
    val label = key.text
    if (label.startsWith(q)) return 100
    if (key.words.any { it.startsWith(q) }) return 80
    if (key.initials.startsWith(q)) return 60
    if (q in label) return 40
    var matched = 0
    for (c in label) if (matched < q.length && c == q[matched]) matched++
    return if (q.length >= 2 && matched == q.length) 20 else 0
}
