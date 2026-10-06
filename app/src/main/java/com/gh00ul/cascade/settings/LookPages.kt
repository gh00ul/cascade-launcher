package com.gh00ul.cascade.settings

import android.Manifest
import android.app.Activity
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.ClockStyle
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TempUnit
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.data.WallpaperDim
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.hasCalendarAccess
import kotlinx.coroutines.delay

internal fun textColorName(c: TextColor) = when (c) {
    TextColor.AUTO -> "Automatic"
    TextColor.LIGHT -> "White"
    TextColor.DARK -> "Dark"
}

internal fun iconSizeName(s: IconSize) = when (s) {
    IconSize.SMALL -> "Small"
    IconSize.MEDIUM -> "Medium"
    IconSize.LARGE -> "Large"
    IconSize.XL -> "XL"
}

private fun dimName(d: WallpaperDim) = when (d) {
    WallpaperDim.OFF -> "Off"
    WallpaperDim.LOW -> "Low"
    WallpaperDim.MEDIUM -> "Medium"
    WallpaperDim.HIGH -> "High"
}

private fun clockStyleName(s: ClockStyle) = when (s) {
    ClockStyle.CLASSIC -> "Classic"
    ClockStyle.BOLD -> "Bold"
    ClockStyle.STACKED -> "Stacked"
}

/** How home looks: text over the wallpaper, icons, and the status bar. Changes show in the preview at the top. */
@Composable
internal fun AppearancePage(settings: LauncherSettings, favorites: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val wallpaper = rememberWallpaperBrush()
    val autoDark = previewDarkText(TextColor.AUTO)
    val darkText = previewDarkText(settings.textColor)
    val sampleIcon = favorites.firstOrNull()?.let { icons[it.key] }

    SettingsPage(
        title = SettingsScreen.LOOK.title,
        onBack = nav.back,
        pinned = { HomePreview(settings, favorites, icons, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) },
    ) {
        SettingsGroup("Text") {
            SettingRow(
                title = "Text color",
                summary = when (settings.textColor) {
                    TextColor.AUTO -> if (autoDark) "Automatic: dark, for your light wallpaper" else "Automatic: white, for your dark wallpaper"
                    TextColor.LIGHT -> "White with a soft shadow"
                    TextColor.DARK -> "Dark with a soft glow"
                },
                key = "textColor",
                below = {
                    TilePicker(TextColor.entries, settings.textColor, ::textColorName, tileHeight = 64.dp, onSelect = { c ->
                        prefs.update { it.copy(textColor = c) }
                    }) { option, _ ->
                        Box(Modifier.matchParentSize().background(wallpaper))
                        SampleText(dark = if (option == TextColor.AUTO) autoDark else option == TextColor.DARK)
                    }
                },
            )
            SettingRow(
                title = "Wallpaper dimming",
                summary = if (darkText) "Lightens the wallpaper behind dark text" else "Darkens the wallpaper so text stands out",
                key = "dim",
                below = {
                    TilePicker(WallpaperDim.entries, settings.wallpaperDim, ::dimName, tileHeight = 64.dp, onSelect = { d ->
                        prefs.update { it.copy(wallpaperDim = d) }
                    }) { option, _ ->
                        Box(Modifier.matchParentSize().background(wallpaper))
                        Box(Modifier.matchParentSize().background((if (darkText) Color.White else Color.Black).copy(alpha = option.alpha)))
                        SampleText(dark = darkText)
                    }
                },
            )
        }
        SettingsGroup("Icons") {
            SwitchRow("App icons", "An icon beside each app's name", settings.showIcons, key = "showIcons") { on ->
                prefs.update { it.copy(showIcons = on) }
            }
            SettingRow(
                title = "Icon size",
                summary = if (settings.showIcons) "Names grow and shrink with the icons" else "Turn on app icons to use this",
                key = "iconSize",
                enabled = settings.showIcons,
                below = {
                    TilePicker(IconSize.entries, settings.iconSize, ::iconSizeName, enabled = settings.showIcons, tileHeight = 64.dp, onSelect = { s ->
                        prefs.update { it.copy(iconSize = s) }
                    }) { option, _ ->
                        AppIcon(sampleIcon, (option.homeDp * 3 / 4).dp)
                    }
                },
            )
            SwitchRow(
                "Monochrome icons",
                if (settings.showIcons) "Themed icons where apps have them, gray otherwise" else "Turn on app icons to use this",
                settings.monochromeIcons,
                key = "monochrome",
                enabled = settings.showIcons,
            ) { on -> prefs.update { it.copy(monochromeIcons = on) } }
        }
        SettingsGroup("Screen") {
            SwitchRow("Hide status bar", "On the home screen. Swipe down from the top edge to see it for a moment.", settings.hideStatusBar, key = "statusBar") { on ->
                prefs.update { it.copy(hideStatusBar = on) }
            }
            SettingRow(
                title = "Wallpaper",
                summary = "Change it in your phone's wallpaper picker",
                key = "wallpaper",
                icon = ExtraIcons.Wallpaper,
                onClick = { LauncherActions.openWallpaperPicker(context) },
            )
        }
    }
}

/** "Aa" as home would draw it over the wallpaper: white with a shadow, or dark with a glow. */
@Composable
private fun SampleText(dark: Boolean) {
    val style = remember(dark) { LauncherStyle(dark, Color.White).favorite.copy(fontSize = 22.sp) }
    Text("Aa", style = style)
}

/** The clock and what sits under it: weather, the next alarm, running timers, the next event, the battery. */
@Composable
internal fun ClockPage(settings: LauncherSettings, favorites: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    var pickingPlace by rememberSaveable { mutableStateOf(false) }
    val system24h = remember { DateFormat.is24HourFormat(context) }

    var calendarAllowed by remember { mutableStateOf(hasCalendarAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { calendarAllowed = hasCalendarAccess(context) }
    // After "Don't allow" twice, Android stops asking and the request fails at once; send the user to App info then.
    var calendarBlocked by remember { mutableStateOf(false) }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
        val activity = context as? Activity
        calendarBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
        prefs.update { it.copy(showCalendar = granted) }
    }
    val place = settings.weatherPlace

    SettingsPage(
        title = SettingsScreen.CLOCK.title,
        onBack = nav.back,
        pinned = { HomePreview(settings, favorites, icons, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) },
    ) {
        SettingsGroup("Clock") {
            SettingRow(
                title = "Style",
                key = "clockStyle",
                below = {
                    TilePicker(ClockStyle.entries, settings.clockStyle, ::clockStyleName, tileHeight = 84.dp, onSelect = { s ->
                        prefs.update { it.copy(clockStyle = s) }
                    }) { option, selected -> ClockSample(option, selected, settings.timeFormat == TimeFormat.H24 || (settings.timeFormat == TimeFormat.SYSTEM && system24h)) }
                },
            )
            SettingRow(
                title = "Time format",
                summary = when (settings.timeFormat) {
                    TimeFormat.SYSTEM -> "Follows your phone, ${if (system24h) "24-hour" else "12-hour"} now"
                    TimeFormat.H12 -> "1:30 PM shows as 1:30"
                    TimeFormat.H24 -> "1:30 PM shows as 13:30"
                },
                key = "timeFormat",
                below = {
                    SegmentedChoice(TimeFormat.entries, settings.timeFormat, { f ->
                        when (f) {
                            TimeFormat.SYSTEM -> "Automatic"
                            TimeFormat.H12 -> "12-hour"
                            TimeFormat.H24 -> "24-hour"
                        }
                    }) { f -> prefs.update { it.copy(timeFormat = f) } }
                },
            )
            SwitchRow("Date", "The day and date under the clock", settings.showDate, key = "date") { on ->
                prefs.update { it.copy(showDate = on) }
            }
        }
        SettingsGroup("Weather") {
            SwitchRow(
                "Weather",
                if (place == null) "Pick a place to turn it on" else "Now and today's high and low, beside the date",
                settings.showWeather && place != null,
                key = "weather",
            ) { on ->
                if (on && place == null) pickingPlace = true else prefs.update { it.copy(showWeather = on) }
            }
            SettingRow(
                title = "Location",
                summary = place?.name ?: "Not set",
                key = "weatherPlace",
                onClick = { pickingPlace = true },
                onClickLabel = "Change",
            )
            SettingRow(
                title = "Units",
                key = "units",
                below = {
                    SegmentedChoice(TempUnit.entries, settings.tempUnit, { u ->
                        when (u) {
                            TempUnit.AUTO -> "Automatic"
                            TempUnit.CELSIUS -> "°C"
                            TempUnit.FAHRENHEIT -> "°F"
                        }
                    }) { u -> prefs.update { it.copy(tempUnit = u) } }
                },
            )
        }
        PageText("Weather comes from Open-Meteo. Only the place you pick is sent, about twice an hour while home is open.")
        SettingsGroup("Under the clock") {
            SwitchRow("Next alarm", "With a countdown when it's less than a day away", settings.showAlarm, key = "alarm") { on ->
                prefs.update { it.copy(showAlarm = on) }
            }
            SwitchRow("Timers and stopwatches", "Running timers, stopwatches and calls, ticking live", settings.showTimers, key = "timers") { on ->
                prefs.update { it.copy(showTimers = on) }
            }
            SwitchRow(
                "Next calendar event",
                when {
                    calendarBlocked -> "Calendar access is blocked. Tap to allow it in App info."
                    settings.showCalendar && !calendarAllowed -> "Calendar access is off. Allow it in App info."
                    else -> "What's coming up today, with how long until it starts"
                },
                settings.showCalendar && calendarAllowed,
                key = "calendar",
            ) { on ->
                when {
                    on && !hasCalendarAccess(context) && calendarBlocked -> LauncherActions.openOwnAppInfo(context)
                    on && !hasCalendarAccess(context) -> calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                    else -> prefs.update { it.copy(showCalendar = on) }
                }
            }
            SwitchRow("Battery", "Time to full while charging, and a warning when low", settings.showBattery, key = "battery") { on ->
                prefs.update { it.copy(showBattery = on) }
            }
            SwitchRow(
                "Always show battery",
                if (settings.showBattery) "The level all the time, not only while charging or low" else "Turn on Battery to use this",
                settings.batteryAlways,
                enabled = settings.showBattery,
            ) { on -> prefs.update { it.copy(batteryAlways = on) } }
        }
    }

    if (pickingPlace) {
        PlaceDialog(
            onPick = { picked ->
                prefs.update { it.copy(weatherPlace = picked, showWeather = true) }
                pickingPlace = false
            },
            onDismiss = { pickingPlace = false },
        )
    }
}

/** A clock style's look at a glance: 12:45 (or 13:45) in its weight and layout. */
@Composable
private fun ClockSample(style: ClockStyle, selected: Boolean, is24h: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val hours = if (is24h) "13" else "1"
    when (style) {
        ClockStyle.CLASSIC -> Text("$hours:45", style = TextStyle(color = color, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp))
        ClockStyle.BOLD -> Text("$hours:45", style = TextStyle(color = color, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp))
        ClockStyle.STACKED -> Text(
            "${hours.padStart(2, '0')}\n45",
            textAlign = TextAlign.Center,
            style = TextStyle(color = color, fontSize = 26.sp, lineHeight = 25.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
        )
    }
}

/** Search Open-Meteo's places as you type (after a pause), and pick one. */
@Composable
private fun PlaceDialog(onPick: (WeatherPlace) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherPlace>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Each keystroke restarts this, so only a pause in typing searches.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            status = null
            return@LaunchedEffect
        }
        delay(400)
        status = "Searching…"
        Weather.search(q).fold(
            onSuccess = {
                results = it
                status = if (it.isEmpty()) "No places match “$q”." else null
            },
            onFailure = {
                results = emptyList()
                status = "Couldn't search. Check your connection."
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weather location") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("City or town") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                    items(results, key = { "${it.name}@${it.latitude},${it.longitude}" }) { place ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(place) }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(place.name, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
