package com.gh00ul.cascade.ui.home

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.LiveTimer
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.CalendarEvent
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.nextCalendarEvent
import com.gh00ul.cascade.util.sendFromLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** The wall clock the home screen reads. Screenshot tests provide a fixed instant. */
internal val LocalNow = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * Time, date, and a row of chips for what's next: the alarm, running timers/stopwatches/calls (ticking live),
 * the next calendar event and charging progress. Tap the time for alarms and the date for the calendar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClockHeader(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clock = LocalNow.current
    var now by remember { mutableLongStateOf(clock()) }
    // Bumped when the next alarm may have changed, so AlarmManager isn't asked again on every minute tick.
    var alarmChecks by remember { mutableIntStateOf(0) }
    // Registered only while home is visible: TIME_TICK would otherwise wake the process every minute while another app
    // is in front. Each start catches up on the time and the alarm, reading them after registering, so a tick that
    // arrives during registration is either delivered or already in the read.
    LifecycleStartEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                now = clock()
                if (intent.action != Intent.ACTION_TIME_TICK) alarmChecks++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        now = clock()
        alarmChecks++
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }

    val style = LocalLauncherStyle.current
    val locale = LocalConfiguration.current.locales[0]
    val is24h = DateFormat.is24HourFormat(context)
    // Formats are reused across minute ticks. A SimpleDateFormat keeps the time zone it was made in, so they are rebuilt
    // on the broadcasts that bump alarmChecks (which include a time zone change) and on each start.
    val dateFormat = remember(locale, alarmChecks) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale) }
    val date = dateFormat.format(Date(now))
    val alarm = remember(alarmChecks) { context.getSystemService(AlarmManager::class.java).nextAlarmClock }
    val timers by NotificationStore.timers.collectAsStateWithLifecycle()
    val event = rememberNextEvent(settings.showCalendar, now)
    val battery = rememberBattery(settings.showBattery)
    val openAlarms = Modifier.clickable(interactionSource = null, indication = null, onClickLabel = "Open alarms", role = Role.Button) {
        LauncherActions.openAlarms(context)
    }

    Column(modifier) {
        val timeFormat = remember(locale, is24h, alarmChecks) { SimpleDateFormat(if (is24h) "H:mm" else "h:mm", locale) }
        val time = timeFormat.format(Date(now))
        // Keyed on the style alone, so minute ticks update in place and only a style switch cross-fades. Not clipped
        // while the size animates: the clock's shadow draws past the bottom edge that endBelowBaseline sets.
        AnimatedContent(targetState = settings.clockStyle, transitionSpec = { Motion.swap(clip = false) }, label = "clockStyle") { clockStyle ->
            when (clockStyle) {
                ClockStyle.CLASSIC -> Text(time, style = style.clock, modifier = openAlarms.endBelowBaseline())
                ClockStyle.BOLD -> Text(time, style = style.clockBold, modifier = openAlarms.endBelowBaseline())
                // One two-line Text, so clockStacked's line height sets the gap between hours and minutes.
                ClockStyle.STACKED -> Column(openAlarms.clearAndSetSemantics { contentDescription = time }) {
                    val stackedFormat = remember(locale, is24h, alarmChecks) { SimpleDateFormat(if (is24h) "HH\nmm" else "hh\nmm", locale) }
                    Text(stackedFormat.format(Date(now)), style = style.clockStacked, modifier = Modifier.endBelowBaseline())
                }
            }
        }
        Text(
            date,
            style = style.date,
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClickLabel = "Open calendar", role = Role.Button) {
                LauncherActions.openCalendar(context)
            },
        )

        val showBatteryChip = battery != null && (battery.charging || battery.level <= LOW_BATTERY)
        // Chips fade in and out while their neighbours glide over, and the row folds away with the last one. Each keeps
        // its last value to draw while it leaves. Chips there at first composition start at rest; the battery (read on
        // start) and the event (queried on IO) fade in once, when they arrive.
        val shownTimers = rememberLatest(timers.takeIf { it.isNotEmpty() })
        val shownEvent = rememberLatest(event)
        val shownAlarm = rememberLatest(alarm)
        val shownBattery = rememberLatest(battery?.takeIf { showBatteryChip })
        AnimatedVisibility(
            visible = alarm != null || timers.isNotEmpty() || event != null || showBatteryChip,
            enter = Motion.ExpandDown,
            exit = Motion.CollapseUp,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                AnimatedVisibility(timers.isNotEmpty(), enter = ChipIn, exit = ChipOut) {
                    shownTimers?.let { TimerChips(it) }
                }
                AnimatedVisibility(event != null, enter = ChipIn, exit = ChipOut) {
                    shownEvent?.let { e ->
                        val (title, whenText, spokenWhen) = eventText(e, now, is24h, locale)
                        InfoChip(ExtraIcons.Event, title, "Next event: $title, $spokenWhen", "Open event", trailing = whenText) {
                            LauncherActions.openCalendarEvent(context, e.id, e.begin, e.end)
                        }
                    }
                }
                AnimatedVisibility(alarm != null, enter = ChipIn, exit = ChipOut) {
                    shownAlarm?.let { a ->
                        val (text, spoken) = alarmText(a.triggerTime, now, is24h, locale)
                        InfoChip(ExtraIcons.Alarm, text, "Alarm $spoken", "Open alarms") {
                            // That exact alarm when the clock app offers it, else the alarm list.
                            if (a.showIntent?.sendFromLauncher(context) != true) LauncherActions.openAlarms(context)
                        }
                    }
                }
                AnimatedVisibility(showBatteryChip, enter = ChipIn, exit = ChipOut) {
                    shownBattery?.let { b ->
                        val (text, spoken) = batteryText(b)
                        InfoChip(if (b.charging) ExtraIcons.Bolt else ExtraIcons.BatteryAlert, text, spoken, null, accent = !b.charging) {}
                    }
                }
            }
        }
    }
}

private const val LOW_BATTERY = 15

/** A chip arriving or leaving: it fades while its width opens from, or closes to, its start edge. */
private val ChipIn: EnterTransition = expandHorizontally(Motion.Size, Alignment.Start) + Motion.FadeIn
private val ChipOut: ExitTransition = shrinkHorizontally(Motion.Size, Alignment.Start) + Motion.FadeOut

/** [value], or while it's null the last non-null one, so a chip that's leaving still has something to draw. */
@Composable
private fun <T : Any> rememberLatest(value: T?): T? = remember { Latest<T>() }.also { if (value != null) it.value = value }.value

/** From the digits' baseline to the bottom of the clock; the date's own space above its capitals adds about 4dp. */
private val ClockToDate = 9.dp

/**
 * Ends the clock [ClockToDate] below its last baseline instead of below the font's descent, which grows with the type
 * size, so the date sits the same distance under the digits in every style. Digits have no descenders, and nothing
 * clips, so the glyphs still draw in full.
 */
private fun Modifier.endBelowBaseline() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[LastBaseline]
    val height = if (baseline == AlignmentLine.Unspecified) placeable.height else minOf(placeable.height, baseline + ClockToDate.roundToPx())
    layout(placeable.width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) { placeable.place(0, 0) }
}

/**
 * A pill with an icon and a label. [text] is ellipsized when space runs out; [trailing] (a time) never is.
 * TalkBack reads [description] instead of the raw text. When the text gets wider or narrower, the width glides there
 * if [animateSize].
 */
@Composable
private fun InfoChip(
    icon: ImageVector,
    text: String,
    description: String,
    clickLabel: String?,
    trailing: String? = null,
    accent: Boolean = false,
    animateSize: Boolean = true,
    onClick: () -> Unit,
) {
    val style = LocalLauncherStyle.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(style.scrim.copy(alpha = 0.30f))
            .then(if (clickLabel != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            // Inside the pill's clip and background, so they follow the width. Off for timer chips: their text changes
            // every second, and with a font that ignores tnum the width would glide on every tick.
            .then(if (animateSize) Modifier.animateContentSize(Motion.Size) else Modifier)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (accent) style.accent else style.content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = style.chip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
        )
        if (trailing != null) {
            Text("  ·  $trailing", style = style.chip, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/**
 * Running clocks from notifications, ticking every second while home is visible. The digits change in place, with no
 * animation per tick: that would draw frames every second for as long as a timer runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerChips(timers: List<LiveTimer>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = LocalNow.current
    val now by produceState(clock(), lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = clock()
                delay(1_000 - value % 1_000)
            }
        }
    }
    // The timers arrive and leave together, as one chip; inside, they still wrap like the chips around them.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (timer in timers) {
            val seconds = (if (timer.countDown) timer.base - now else now - timer.base).coerceAtLeast(0) / 1_000
            val time = DateUtils.formatElapsedTime(seconds)
            val label = timer.title.takeIf { it.isNotEmpty() && it.length <= 18 }
            val kind = if (timer.countDown) "remaining" else "elapsed"
            InfoChip(
                ExtraIcons.Timer,
                text = label ?: time,
                description = listOfNotNull(label, "${spokenDuration(seconds * 1_000)} $kind").joinToString(", "),
                clickLabel = "Open",
                trailing = if (label != null) time else null,
                animateSize = false,
            ) { timer.contentIntent?.sendFromLauncher(context) }
        }
    }
}

/**
 * The next calendar event, re-read every five minutes and on each return home, and only while home is visible:
 * the query crosses processes, so it never runs in the background.
 */
@Composable
private fun rememberNextEvent(enabled: Boolean, now: Long): CalendarEvent? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = LocalNow.current
    // Counts returns home. Unlike a RESUMED/not-RESUMED key, it doesn't change when home pauses on the way out.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val resumed = resumes
    val event by produceState<CalendarEvent?>(null, enabled, now / 300_000, resumed) {
        if (!enabled) {
            value = null
            return@produceState
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@produceState
        // A resume since this composition (the effect replays ON_RESUME when first composed resumed) relaunches this
        // with the new count, so leave the query to that run.
        if (resumes != resumed) return@produceState
        value = withContext(Dispatchers.IO) { nextCalendarEvent(context, clock()) }
    }
    // Drop an event that ended since the last query.
    return event?.takeIf { it.allDay || it.end > now }
}

/** A data class, so a broadcast that only changed the temperature or voltage doesn't recompose the header. */
internal data class Battery(val level: Int, val charging: Boolean, val fullInMs: Long)

/**
 * Battery level and charging state from the sticky ACTION_BATTERY_CHANGED broadcast, or null when [enabled] is off.
 * Listens only while home is visible, since the broadcast comes often while charging; the sticky intent catches up on
 * each start, and the last value stays meanwhile.
 */
@Composable
private fun rememberBattery(enabled: Boolean): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }
    LifecycleStartEffect(context, enabled) {
        if (!enabled) {
            battery = null
            return@LifecycleStartEffect onStopOrDispose {}
        }
        val manager = context.getSystemService(BatteryManager::class.java)
        fun read(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level < 0) return
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            // The status can still say FULL or CHARGING for a moment after unplugging; require power too.
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val charging = plugged && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            val fullIn = if (charging && Build.VERSION.SDK_INT >= 28) runCatching { manager.computeChargeTimeRemaining() }.getOrDefault(-1L) else -1L
            battery = Battery(level * 100 / scale, charging, fullIn)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        // Sticky: registering returns the current state right away.
        read(ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED))
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

/** Chip text and what TalkBack says. */
internal fun batteryText(b: Battery): Pair<String, String> = when {
    b.charging && b.level >= 100 -> "Charged" to "Battery charged"
    b.charging && b.fullInMs > 0 -> "${b.level}%  ·  full in ${duration(b.fullInMs)}" to
        "Charging, ${b.level} percent, full in ${spokenDuration(roundedUp(b.fullInMs))}"
    b.charging -> "${b.level}%  ·  charging" to "Charging, ${b.level} percent"
    else -> "${b.level}% battery" to "Battery low, ${b.level} percent"
}

internal fun clockPattern(locale: Locale, is24h: Boolean, withDay: Boolean) =
    DateFormat.getBestDateTimePattern(locale, (if (withDay) "EEE" else "") + if (is24h) "Hmm" else "hmma")

/** Chip text and what TalkBack says. */
internal fun alarmText(trigger: Long, now: Long, is24h: Boolean, locale: Locale): Pair<String, String> {
    val left = trigger - now
    return if (left in 0 until 24 * 60 * 60_000L) {
        val time = SimpleDateFormat(clockPattern(locale, is24h, withDay = false), locale).format(Date(trigger))
        "$time  ·  in ${duration(left)}" to "$time, in ${spokenDuration(roundedUp(left))}"
    } else {
        val time = SimpleDateFormat(clockPattern(locale, is24h, withDay = true), locale).format(Date(trigger))
        time to time
    }
}

/** Title, the when-part (never truncated), and the when-part as spoken. */
internal fun eventText(e: CalendarEvent, now: Long, is24h: Boolean, locale: Locale): Triple<String, String, String> {
    if (e.allDay) return Triple(e.title, "today", "today")
    // Today means [now]'s day, not the system clock's (DateUtils.isToday), so it follows LocalNow.
    fun at(time: Long) = SimpleDateFormat(clockPattern(locale, is24h, withDay = !sameDay(time, now)), locale).format(Date(time))
    return when {
        e.begin <= now -> Triple(e.title, "until ${at(e.end)}", "until ${at(e.end)}")
        e.begin - now < 60 * 60_000L -> Triple(e.title, "in ${duration(e.begin - now)}", "in ${spokenDuration(roundedUp(e.begin - now))}")
        else -> Triple(e.title, at(e.begin), at(e.begin))
    }
}

/** "25 min", "3h 10m". Rounds up so a countdown never shows "0 min" while time remains. */
internal fun duration(ms: Long): String {
    val minutes = roundedUp(ms) / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
}

/**
 * [ms] rounded up to whole minutes, the amount [duration] shows. Chips pass it to [spokenDuration] too: TIME_TICK
 * lands just after the minute, so flooring would have TalkBack say one minute less than the chip.
 */
private fun roundedUp(ms: Long) = (abs(ms) + 59_999) / 60_000 * 60_000

/** "3 hours 10 minutes", "45 seconds": the same amount as TalkBack should say it. */
internal fun spokenDuration(ms: Long): String {
    val total = abs(ms) / 1_000
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val seconds = total % 60
    fun unit(n: Long, name: String) = "$n $name" + if (n == 1L) "" else "s"
    return buildList {
        if (hours > 0) add(unit(hours, "hour"))
        if (minutes > 0) add(unit(minutes, "minute"))
        if (hours == 0L && minutes == 0L) add(unit(seconds, "second"))
    }.joinToString(" ")
}

/** Whether [a] and [b] fall on the same day in the device's time zone. */
private fun sameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
