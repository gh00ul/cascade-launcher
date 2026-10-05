package com.gh00ul.cascade.data

import java.text.Normalizer

private val combiningMarks = Regex("\\p{Mn}+")

/** Lowercase and strip accents so "Café" matches "cafe". */
fun String.normalizedForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

/** Best matches first: prefix, then word start, then initials ("gm" → Google Maps), then substring, then fuzzy. */
fun searchApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim().normalizedForSearch()
    if (q.isEmpty()) return emptyList()
    return apps
        .mapNotNull { app ->
            val score = maxOf(score(app.label.normalizedForSearch(), q), score(app.originalLabel.normalizedForSearch(), q))
            if (score > 0) app to score else null
        }
        .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
        .map { it.first }
}

private fun score(label: String, q: String): Int {
    if (label.startsWith(q)) return 100
    val words = label.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
    if (words.any { it.startsWith(q) }) return 80
    if (words.joinToString("") { it.take(1) }.startsWith(q)) return 60
    if (q in label) return 40
    var matched = 0
    for (c in label) if (matched < q.length && c == q[matched]) matched++
    return if (q.length >= 2 && matched == q.length) 20 else 0
}
