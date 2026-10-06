package com.gh00ul.cascade.ui.home.widgets

import android.Manifest
import android.app.Activity
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.common.rememberReplaySkip
import com.gh00ul.cascade.ui.home.LocalNow
import com.gh00ul.cascade.ui.home.clockPattern
import com.gh00ul.cascade.ui.home.is24Hour
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.AgendaEvent
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.hasCalendarAccess
import com.gh00ul.cascade.util.localDay
import com.gh00ul.cascade.util.upcomingEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.TimeZone

/** Days the agenda covers: today and the next two. */
private const val AGENDA_DAYS = 3
/** Events asked for; the card shows as many as fit. */
private const val AGENDA_LIMIT = 8
/** How often the agenda is read again while home stays visible. */
private const val REQUERY_MS = 5 * 60_000L

private val RowShape = RoundedCornerShape(10.dp)

/** The agenda as it was read at [now]. */
private class Agenda(val now: Long, val events: List<AgendaEvent>)

/**
 * The calendar widget: an agenda of today and the next two days, by day, all-day events first, in each calendar's
 * color, with times in the clock's format. As many rows as fit on the card show. Tap an event to open it, a day's
 * name to open the calendar.
 *
 * The provider is read on each return home and every five minutes while home stays visible, never in the background
 * (see BATTERY.md). Without calendar access it offers to ask for it; once Android stops asking, the button opens App
 * info instead.
 */
@Composable
internal fun CalendarWidget(settings: LauncherSettings, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = LocalNow.current
    var allowed by remember { mutableStateOf(hasCalendarAccess(context)) }
    // Read during composition for the first frame; the replayed first resume doesn't read it again.
    val replayedResume = rememberReplaySkip(Lifecycle.State.RESUMED)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!replayedResume.consume()) allowed = hasCalendarAccess(context) }
    // After "Don't allow" twice, Android stops asking and the request fails at once; send the user to App info then.
    var blocked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        val activity = context as? Activity
        blocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
    }
    val agenda by produceState<Agenda?>(null, allowed, lifecycle, clock) {
        if (!allowed) {
            value = null
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = clock()
                value = Agenda(now, withContext(Dispatchers.IO) { upcomingEvents(context, now, AGENDA_DAYS, AGENDA_LIMIT) })
                // A refresh interval, not motion, so delay() is right here; ON_STOP cancels it with the block.
                delay(REQUERY_MS - now % REQUERY_MS)
            }
        }
    }

    val shown = agenda
    when {
        !allowed -> WidgetMessage(
            ExtraIcons.Event,
            "Your next events",
            if (blocked) "Calendar access is blocked. Allow it in App info to see what's coming up here."
            else "Allow calendar access to see what's coming up today and the next two days.",
            modifier,
            action = if (blocked) "App info" else "Allow",
            onAction = { if (blocked) LauncherActions.openOwnAppInfo(context) else request.launch(Manifest.permission.READ_CALENDAR) },
        )
        // The first read is on its way; it takes a moment.
        shown == null -> Box(modifier.fillMaxSize())
        shown.events.isEmpty() -> WidgetMessage(
            ExtraIcons.Event,
            "Nothing coming up",
            "Your calendar is clear today and the next two days.",
            modifier,
            onClick = { LauncherActions.openCalendar(context) },
            onLongPress = onLongPress,
        )
        else -> AgendaList(shown, is24Hour(settings.timeFormat, DateFormat.is24HourFormat(context)), onLongPress, modifier)
    }
}

/** A row of the agenda: a day's name, or an event under it with its time already formatted. */
internal sealed interface AgendaRow {
    data class Day(val label: String) : AgendaRow
    data class Event(val event: AgendaEvent, val time: String, val now: Boolean) : AgendaRow
}

/**
 * [events] (sorted by day, as upcomingEvents returns them) as rows: each day's name, then its events. An all-day event
 * reads "All day", one under way at [now] "Now", any other its start in [timeFormat]. Today is [today], and [dayName]
 * names the days after tomorrow.
 */
internal fun agendaRows(events: List<AgendaEvent>, now: Long, today: Long, timeFormat: SimpleDateFormat, dayName: (Long) -> String): List<AgendaRow> =
    buildList {
        var day: Long? = null
        for (e in events) {
            if (e.day != day) {
                day = e.day
                add(AgendaRow.Day(when (e.day) {
                    today -> "Today"
                    today + 1 -> "Tomorrow"
                    else -> dayName(e.day)
                }))
            }
            val ongoing = !e.event.allDay && e.event.begin <= now
            val time = when {
                e.event.allDay -> "All day"
                ongoing -> "Now"
                else -> timeFormat.format(Date(e.event.begin))
            }
            add(AgendaRow.Event(e, time, ongoing))
        }
    }

@Composable
private fun AgendaList(agenda: Agenda, is24h: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val style = LocalLauncherStyle.current
    val text = rememberWidgetText()
    val locale = LocalConfiguration.current.locales[0]
    val rows = remember(agenda, is24h, locale) {
        val timeFormat = SimpleDateFormat(clockPattern(locale, is24h, withDay = false), locale)
        agendaRows(agenda.events, agenda.now, localDay(agenda.now, TimeZone.getDefault()), timeFormat) { day ->
            LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        }
    }
    // One column for every time, as wide as the widest, so the titles line up at any font size.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timeWidth = remember(rows, text, density) {
        with(density) { rows.maxOf { if (it is AgendaRow.Event) measurer.measure(it.time, text.secondary).size.width else 0 }.toDp() }
    }
    val headers = remember(rows) { rows.indices.filterTo(HashSet()) { rows[it] is AgendaRow.Day } }
    val noColor = style.content.copy(alpha = 0.5f)

    FitColumn(headers, modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
        rows.forEachIndexed { i, row ->
            when (row) {
                is AgendaRow.Day -> Text(
                    row.label.uppercase(locale),
                    style = text.label,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (i == 0) 0.dp else 6.dp)
                        .clip(RowShape)
                        .widgetClick("Open calendar", onLongPress) { LauncherActions.openCalendar(context) }
                        .semantics { heading() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                is AgendaRow.Event -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .widgetClick("Open event", onLongPress) {
                            LauncherActions.openCalendarEvent(context, row.event.event.id, row.event.event.begin, row.event.event.end)
                        }
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = row.event.color.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) } ?: noColor
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(row.time, style = if (row.now) text.accent else text.secondary, maxLines = 1, softWrap = false, modifier = Modifier.width(timeWidth))
                    Spacer(Modifier.width(10.dp))
                    Text(row.event.event.title, style = text.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Lays its children out top to bottom while they fit, so a long agenda ends on a whole row; a day's name left at the
 * bottom without any of its events goes too. [headers] are the indices of the children that are day names.
 */
@Composable
private fun FitColumn(headers: Set<Int>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = ArrayList<Placeable>(measurables.size)
        var height = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            if (height + placeable.height > constraints.maxHeight) break
            placeables += placeable
            height += placeable.height
        }
        while (placeables.isNotEmpty() && placeables.lastIndex in headers) height -= placeables.removeAt(placeables.lastIndex).height
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (placeable in placeables) {
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}
