package com.gh00ul.cascade.data

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import androidx.core.net.toUri
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What search can do besides find: start a timer ("10m"), set an alarm ("7:30"), get directions ("nav home"), search
 * Maps or an app ("maps coffee", "yt lofi"), or play something ("play blinding lights").
 */
sealed interface Command {
    /**
     * Whether it's what was typed even when apps match too. A timer or an alarm reads like nothing else; a keyword
     * search ("play store") might be an app's name, so it waits behind any app that matches.
     */
    val exact: Boolean

    /** [seconds] long, at most a day. */
    data class Timer(val seconds: Int) : Command {
        override val exact get() = true
    }

    /** At [hour] (0 to 23) and [minute]; [nextAt] is when that next comes round. */
    data class Alarm(val hour: Int, val minute: Int, val nextAt: Long) : Command {
        override val exact get() = true
    }

    data class Directions(val place: String) : Command {
        override val exact get() = false
    }

    data class MapSearch(val query: String) : Command {
        override val exact get() = false
    }

    data class SiteSearch(val site: Site, val query: String) : Command {
        override val exact get() = false
    }

    /** Asks the music app (the one that played last, when it takes the request) to find and play [query]. */
    data class Play(val query: String) : Command {
        override val exact get() = false
    }
}

/** Sites and apps a keyword searches: "yt lofi" searches YouTube. [url] has `%s` where the encoded query goes. */
enum class Site(val label: String, val keywords: List<String>, val url: String) {
    YOUTUBE("YouTube", listOf("yt", "youtube"), "https://www.youtube.com/results?search_query=%s"),
    SPOTIFY("Spotify", listOf("sp", "spotify"), "spotify:search:%s"),
    WIKIPEDIA("Wikipedia", listOf("w", "wiki", "wikipedia"), "https://en.wikipedia.org/w/index.php?search=%s"),
    REDDIT("Reddit", listOf("r", "reddit"), "https://www.reddit.com/search/?q=%s"),
    GITHUB("GitHub", listOf("gh", "github"), "https://github.com/search?q=%s"),
}

// A unit ends where letters do: "1h30m" is an hour and thirty minutes.
private val TimerUnits = Regex("""(\d{1,4})\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])""")
private val TimerShape = Regex("""^(?:timer(?:\s+for)?\s+)?((?:\d{1,4}\s*(?:hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\s*)+)$""")
private val TimerMinutes = Regex("""^timer(?:\s+for)?\s+(\d{1,4})$""")
private val AlarmShape = Regex("""^(alarm(?:\s+(?:at|for))?\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|a|p)?$""")
private val DirectionsShape = Regex("""^(?:nav|navigate(?:\s+to)?|directions(?:\s+to)?|drive\s+to)\s+(.+)$""")
private val MapShape = Regex("""^maps?\s+(.+)$""")
private val PlayShape = Regex("""^play\s+(.+)$""")
private val KeywordShape = Regex("""^(\S+)\s+(.+)$""")

/** The longest timer a command starts: a day. */
private const val MAX_TIMER_SECONDS = 24 * 60 * 60

/**
 * The command [query] spells, or null. [now] and [zone] decide which "7:30" comes next: in the morning or the
 * evening, today or tomorrow.
 */
fun parseCommand(query: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Command? {
    // Lowercased letter by letter, so it stays as long as what was typed and a match's range maps back onto it: a
    // whole-string lowercase() turns a capital İ into two characters.
    val q = buildString { query.trim().forEach { append(it.lowercaseChar()) } }.replace(Regex("\\s+"), " ")
    if (q.isEmpty()) return null
    timer(q)?.let { return it }
    alarm(q, now, zone)?.let { return it }
    DirectionsShape.matchEntire(q)?.let { return Command.Directions(original(query, it.groups[1]!!.range)) }
    MapShape.matchEntire(q)?.let { return Command.MapSearch(original(query, it.groups[1]!!.range)) }
    PlayShape.matchEntire(q)?.let { return Command.Play(original(query, it.groups[1]!!.range)) }
    KeywordShape.matchEntire(q)?.let { m ->
        val site = Site.entries.firstOrNull { m.groupValues[1] in it.keywords } ?: return null
        return Command.SiteSearch(site, original(query, m.groups[2]!!.range))
    }
    return null
}

/** The part of the query typed at [range] of its trimmed, lowercased, single-spaced form, in its own case. */
private fun original(query: String, range: IntRange): String {
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    return normalized.substring(range).trim()
}

private fun timer(q: String): Command.Timer? {
    val seconds = TimerMinutes.matchEntire(q)?.let { it.groupValues[1].toInt() * 60 } ?: run {
        val body = TimerShape.matchEntire(q)?.groupValues?.get(1) ?: return null
        TimerUnits.findAll(body).sumOf { m ->
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2].first()) {
                'h' -> n * 3600
                'm' -> n * 60
                else -> n
            }
        }
    }
    return Command.Timer(seconds).takeIf { seconds in 1..MAX_TIMER_SECONDS }
}

private fun alarm(q: String, now: Long, zone: ZoneId): Command.Alarm? {
    val m = AlarmShape.matchEntire(q) ?: return null
    val keyword = m.groupValues[1].isNotEmpty()
    val hourText = m.groupValues[2]
    val minuteText = m.groupValues[3]
    val meridiem = m.groupValues[4].firstOrNull()
    // A bare number is a number: an alarm needs the word, a minute, or am/pm ("7:30", "7am", "alarm 7").
    if (!keyword && minuteText.isEmpty() && meridiem == null) return null
    val minute = minuteText.ifEmpty { "0" }.toInt()
    var hour = hourText.toInt()
    if (minute > 59) return null
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val candidates = when {
        meridiem != null -> {
            if (hour !in 1..12) return null
            listOf(hour % 12 + if (meridiem == 'p') 12 else 0)
        }
        // Written the 24-hour way ("19:30", "07:30", "0:15"): just that.
        hour > 12 || hour == 0 || hourText.length == 2 && hourText[0] == '0' -> {
            if (hour > 23) return null
            listOf(hour)
        }
        // "7:30" is whichever of 7:30 and 19:30 comes first.
        else -> listOf(hour % 12, hour % 12 + 12)
    }
    val next = candidates
        .map { h -> today.withHour(h).withMinute(minute).withSecond(0).withNano(0).let { if (it.isAfter(today)) it else it.plusDays(1) } }
        .minOrNull()!!
    hour = next.hour
    return Command.Alarm(hour, minute, next.atZone(zone).toInstant().toEpochMilli())
}

/** What runs [command]; [player] is the music app that played last, which a [Command.Play] goes to first. */
fun commandIntents(command: Command, player: String? = null): List<Intent> = when (command) {
    is Command.Timer -> listOf(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )
    is Command.Alarm -> listOf(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )
    // Turn-by-turn in Google Maps, else whatever map app shows the place.
    is Command.Directions -> listOf(
        Intent(Intent.ACTION_VIEW, ("google.navigation:q=" + Uri.encode(command.place)).toUri()),
        Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(command.place)).toUri()),
    )
    is Command.MapSearch -> listOf(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(command.query)).toUri()))
    is Command.SiteSearch -> listOf(Intent(Intent.ACTION_VIEW, command.site.url.format(Uri.encode(command.query)).toUri()))
    is Command.Play -> {
        fun play() = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, command.query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        listOfNotNull(player?.let { play().setPackage(it) }, play())
    }
}
