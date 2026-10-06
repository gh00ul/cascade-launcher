package com.gh00ul.cascade.notifications

import android.content.Context
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.util.copyToClipboard

/*
 * Login codes: "482913 is your verification code" lands on the clipboard as it arrives, so it's ready to paste into
 * the login screen without a trip to Messages and back. Only from notifications that say it's a code.
 */

/** Words that make a number a login code; without one, a number is an order, an amount or a time, and is left alone. */
private val CodeWords = Regex(
    """\b(codes?|otp|passcode|pass code|verification|verify|one[- ]time|2fa|two[- ]factor|authenticat\w*|log ?in|sign[- ]?in|pin)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * A code: 4 to 8 digits, or two groups of 3 or 4 split by a space or dash ("123 456"), maybe after a short capital
 * prefix and a dash ("G-123456"). It stands alone: no letter, digit, currency sign or separator glued to its front, and
 * no letter, digit, percent sign, or decimal, time, date or phone continuation after it.
 */
private val Candidate = Regex(
    """(?<![\p{L}\p{N}$€£¥₹#/.,+-])(?:[A-Z]{1,4}-)?(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\p{L}\p{N}%]|[.,:/-]\p{N}| \p{N})""",
)

/**
 * The login code in a notification's [title] and [text], digits only, or null. Several numbers: the one nearest a word
 * like "code", then the longer. A number in the title is often the sender (a short code like 72975 that Messages shows
 * as the title), so it counts only when the text has none and the title itself says it's a code.
 */
internal fun findLoginCode(title: String, text: String): String? {
    val all = "$title\n$text"
    val words = CodeWords.findAll(all).map { it.range }.toList()
    if (words.isEmpty()) return null
    val found = Candidate.findAll(all).toList()
    val inText = found.filter { it.range.first > title.length }
    val candidates = inText.ifEmpty { if (CodeWords.containsMatchIn(title)) found else emptyList() }
    return candidates
        .map { match ->
            val body = match.groups[1]!!
            val digits = body.value.filter(Char::isDigit)
            val distance = words.minOf { w ->
                when {
                    w.last < match.range.first -> match.range.first - w.last
                    w.first > match.range.last -> w.first - match.range.last
                    else -> 0
                }
            }
            Triple(digits, distance, digits.length)
        }
        .minWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
        ?.first
}

/** How recent a notification must be for its code to be copied: an old one reposted is no new login. */
private const val FRESH_MS = 10 * 60_000L

/** Codes already copied, by notification key and code, so an update to the same notification doesn't copy again. */
private val copied = LinkedHashSet<String>()

/**
 * Copies [notification]'s login code once, when it's fresh and Settings allow it. Main thread, like the listener's
 * callbacks. The clip is marked sensitive, so Android shows it as dots rather than the code.
 */
internal fun offerLoginCode(context: Context, notification: AppNotification, now: Long = System.currentTimeMillis()) {
    val code = notification.code ?: return
    if (now - notification.postTime > FRESH_MS) return
    if (!context.launcher.prefs.settings.value.copyLoginCodes) return
    if (!copied.add("${notification.key}|$code")) return
    // Bounded: only the last few matter, for updates to notifications still showing.
    if (copied.size > 64) copied.remove(copied.first())
    copyToClipboard(context, "Login code", code, sensitive = true)
}
