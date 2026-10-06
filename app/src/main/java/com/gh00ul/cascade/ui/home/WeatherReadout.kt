package com.gh00ul.cascade.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherKind
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.usesFahrenheit
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.LauncherActions
import kotlinx.coroutines.delay

/**
 * The weather for the date line: "☀ 18°" (ClockHeader puts the dot before it). Draws nothing (zero size) while weather
 * is off, no place is picked or no reading has come yet, and fades in once when the first one does; after that it
 * changes only when what it shows does. Tap for the forecast.
 *
 * It also asks for the readings: [Weather.refresh] on each start, then every 30 minutes while it stays visible. The
 * loop runs in repeatOnLifecycle(STARTED), so it's cancelled when home stops, and a refresh only goes to the network
 * when the reading is 30 minutes old (see BATTERY.md). It needs nothing from MainActivity, so Settings can show it too.
 */
@Composable
fun WeatherReadout(settings: LauncherSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(settings)
    val on = settings.showWeather && settings.weatherPlace != null
    // Restarted when what the reading is for changes, so a new place or unit is fetched at once, and turning weather
    // off clears it.
    LaunchedEffect(lifecycle, on, settings.weatherPlace, settings.tempUnit) {
        if (!on) {
            Weather.refresh(context, latest)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Weather.refresh(context, latest)
                // A refresh interval, not motion, so delay() is right here; ON_STOP cancels it with the block.
                delay(Weather.MAX_AGE_MS)
            }
        }
    }

    val now = rememberWeather(settings)
    // Kept while it fades out, like the chips' last values.
    val shown = remember { Latest<WeatherNow>() }.also { if (now != null) it.value = now }.value
    val style = LocalLauncherStyle.current
    // In the date's own units, so the icon keeps pace with the text under a larger font size.
    val iconSize = with(LocalDensity.current) { style.date.fontSize.toDp() }
    AnimatedVisibility(now != null, modifier, enter = Motion.FadeIn, exit = Motion.FadeOut, label = "weather") {
        shown?.let { w ->
            val (text, spoken) = weatherText(w)
            Row(
                Modifier
                    .clickable(interactionSource = null, indication = null, onClickLabel = "Open forecast", role = Role.Button) {
                        LauncherActions.webSearch(context, "weather ${w.place.name}")
                    }
                    .clearAndSetSemantics { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Keyed on what's drawn, so a refresh that brings the same icon and temperature doesn't animate. Not
                // clipped while the width changes: the text's shadow draws past its bounds.
                AnimatedContent(
                    weatherIcon(w.kind, w.isDay) to text,
                    transitionSpec = { Motion.swap(clip = false) },
                    label = "weatherValue",
                ) { (icon, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.size(iconSize))
                        Spacer(Modifier.width(4.dp))
                        Text(value, style = style.date, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The reading for [settings]' place and unit, or null when weather is off, no place is picked or nothing has come
 * yet. [Weather.state] can still hold the previous place's or unit's reading for a moment; that one never shows.
 */
@Composable
fun rememberWeather(settings: LauncherSettings): WeatherNow? {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val place = settings.weatherPlace?.takeIf { settings.showWeather }
    // After a process restart, the stored reading shows before any fetch.
    LaunchedEffect(place != null) { if (place != null) Weather.load(context) }
    val now by Weather.state.collectAsStateWithLifecycle()
    val fahrenheit = usesFahrenheit(settings.tempUnit, locale)
    return now?.takeIf { place != null && it.place == place && it.fahrenheit == fahrenheit }
}

/** The condition's icon; a clear or partly cloudy sky gets a moon at night. */
internal fun weatherIcon(kind: WeatherKind, isDay: Boolean): ImageVector = when (kind) {
    WeatherKind.CLEAR -> if (isDay) ExtraIcons.ClearDay else ExtraIcons.Bedtime
    WeatherKind.PARTLY_CLOUDY -> if (isDay) ExtraIcons.PartlyCloudyDay else ExtraIcons.PartlyCloudyNight
    WeatherKind.CLOUDY -> ExtraIcons.Cloud
    WeatherKind.FOG -> ExtraIcons.Foggy
    WeatherKind.DRIZZLE, WeatherKind.RAIN -> ExtraIcons.Rainy
    WeatherKind.SNOW -> ExtraIcons.WeatherSnowy
    WeatherKind.THUNDER -> ExtraIcons.Thunderstorm
}

/** What the readout shows ("18°") and what TalkBack says ("Weather: Sunny, 18 degrees, high 21, low 12"). */
internal fun weatherText(now: WeatherNow): Pair<String, String> {
    val spoken = buildString {
        append("Weather: ${now.description}, ${now.temperature} degrees")
        now.high?.let { append(", high $it") }
        now.low?.let { append(", low $it") }
    }
    return "${now.temperature}°" to spoken
}
