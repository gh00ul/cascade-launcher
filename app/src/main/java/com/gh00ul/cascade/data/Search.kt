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
