package com.gh00ul.cascade.ui.home.widgets

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gh00ul.cascade.data.DayForecast
import com.gh00ul.cascade.data.HourForecast
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherKind
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.ui.home.LocalNow
import com.gh00ul.cascade.ui.home.is24Hour
import com.gh00ul.cascade.ui.home.rememberWeatherAt
import com.gh00ul.cascade.ui.home.weatherIcon
import com.gh00ul.cascade.ui.home.weatherText
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.localDay
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.TimeZone

/** Hours shown under the current weather. */
private const val HOURS_SHOWN = 5
/** Days shown beside the hours on a wide card. */
private const val DAYS_SHOWN = 4
/** A chance of rain worth showing, in percent. */
private const val NOTABLE_RAIN = 20
/** From this page width the days fit beside the hours: a tablet, or a phone in landscape. */
private val WideFrom = 460.dp

/**
 * The weather widget: the temperature now, big, with the sky, today's high and low and the place; under it the next
 * five hours (with the chance of rain when it's notable) and, on a wide card, the next four days. Tap for the forecast
 * on the web.
 *
 * It needs only a place: it shows with the readout beside the date off, since weather is wanted while either shows
 * (Weather.wantedPlace). The refreshes are the readout's loop while that's on; while it's off, this widget runs the
 * same loop (each start, then every 30 minutes while home is visible, see BATTERY.md), so there is only ever one.
 * Without a place it offers to pick one in Settings.
 */
@Composable
internal fun WeatherWidget(settings: LauncherSettings, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(settings)
    val place = settings.weatherPlace
    val ownsRefresh = place != null && !settings.showWeather
    LaunchedEffect(lifecycle, ownsRefresh, place, settings.tempUnit) {
        if (!ownsRefresh) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Weather.refresh(context, latest)
                // A refresh interval, not motion, so delay() is right here; ON_STOP cancels it with the block.
                delay(Weather.MAX_AGE_MS)
            }
        }
    }
    val reading = rememberWeatherAt(place, settings.tempUnit)
    when {
        place == null -> WidgetMessage(
            weatherIcon(WeatherKind.PARTLY_CLOUDY, isDay = true),
            "Weather",
            "Pick your place in Settings to see the weather and the hours ahead here.",
            modifier,
            action = "Pick a place",
            onAction = { SettingsActivity.open(context, SettingsScreen.CLOCK) },
        )
        reading == null -> WidgetMessage(
            weatherIcon(WeatherKind.CLOUDY, isDay = true),
            shortName(place),
            "The weather shows here once it has loaded.",
            modifier,
            onClick = { LauncherActions.webSearch(context, "weather ${place.name}") },
            onLongPress = onLongPress,
        )
        else -> Forecast(reading, is24Hour(settings.timeFormat, DateFormat.is24HourFormat(context)), onLongPress, modifier)
    }
}

/** "Seattle" for "Seattle, Washington, United States". */
private fun shortName(place: WeatherPlace) = place.name.substringBefore(',').trim()

@Composable
private fun Forecast(w: WeatherNow, is24h: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val style = LocalLauncherStyle.current
    val text = rememberWidgetText()
    val locale = LocalConfiguration.current.locales[0]
    val now = LocalNow.current()
    val (_, spoken) = weatherText(w)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .widgetClick("Open forecast", onLongPress) { LauncherActions.webSearch(context, "weather ${w.place.name}") }
            .clearAndSetSemantics { contentDescription = "$spoken, in ${shortName(w.place)}" },
    ) {
        val wide = maxWidth >= WideFrom
        val hours = remember(w, now) { forecastHours(w.hours, now) }
        val days = remember(w, now, wide) { if (wide) forecastDays(w.days, now) else emptyList() }
        val showRain = hours.any { (it.precipitation ?: 0) >= NOTABLE_RAIN }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(w.kind, w.isDay), contentDescription = null, tint = style.content, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Text("${w.temperature}°", style = text.temperature, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.description, style = text.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val range = if (w.high != null && w.low != null) "H:${w.high}°  L:${w.low}°  ·  " else ""
                    Text(range + shortName(w.place), style = text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            if (hours.isNotEmpty() || days.isNotEmpty()) {
                val hourFormat = remember(locale, is24h) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24h) "Hm" else "ha"), locale) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    for (h in hours) HourSlot(h, hourFormat, showRain, Modifier.weight(1f))
                    if (days.isNotEmpty()) {
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .width(1.dp)
                                .height(64.dp)
                                .align(Alignment.CenterVertically)
                                .background(style.content.copy(alpha = 0.15f)),
                        )
                        for (d in days) DaySlot(d, LocalDate.ofEpochDay(d.day).dayOfWeek.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** An hour: its time, sky and temperature, and the chance of rain when any hour shown has a notable one. */
@Composable
private fun HourSlot(h: HourForecast, format: SimpleDateFormat, showRain: Boolean, modifier: Modifier = Modifier) {
    val text = rememberWidgetText()
    Slot(format.format(Date(h.time)), weatherIcon(h.kind, h.isDay), "${h.temperature}°", modifier) {
        if (showRain) {
            val rain = h.precipitation?.takeIf { it >= NOTABLE_RAIN }
            Text(if (rain != null) "$rain%" else "", style = text.rain, maxLines = 1)
        }
    }
}

/** A day: its [name], sky, high and low. */
@Composable
private fun DaySlot(d: DayForecast, name: String, modifier: Modifier = Modifier) {
    val text = rememberWidgetText()
    Slot(name, weatherIcon(d.kind, isDay = true), "${d.high}°", modifier) {
        Text("${d.low}°", style = text.small, maxLines = 1)
    }
}

@Composable
private fun Slot(
    label: String,
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    below: @Composable () -> Unit,
) {
    val style = LocalLauncherStyle.current
    val text = rememberWidgetText()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = text.small, maxLines = 1)
        Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.padding(vertical = 4.dp).size(22.dp))
        Text(value, style = text.value, maxLines = 1)
        below()
    }
}

/** The hours the widget shows at [now]: those still ahead, at most five. */
internal fun forecastHours(hours: List<HourForecast>, now: Long): List<HourForecast> = hours.filter { it.time > now }.take(HOURS_SHOWN)

/** The days a wide widget shows at [now]: the ones after today in [zone], at most four. */
internal fun forecastDays(days: List<DayForecast>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<DayForecast> {
    val today = localDay(now, zone)
    return days.filter { it.day > today }.take(DAYS_SHOWN)
}
