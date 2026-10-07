package com.gh00ul.cascade.screenshots

import androidx.compose.ui.platform.LocalConfiguration
import android.Manifest
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.Configuration
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.data.DayForecast
import com.gh00ul.cascade.data.HourForecast
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TempUnit
import com.gh00ul.cascade.data.WIDGET_CALENDAR
import com.gh00ul.cascade.data.WIDGET_WEATHER
import com.gh00ul.cascade.data.Weather
import com.gh00ul.cascade.data.WeatherNow
import com.gh00ul.cascade.data.WeatherPlace
import com.gh00ul.cascade.settings.SettingsApp
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FIXED_ZONE
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.widgets.StackEntry
import com.gh00ul.cascade.ui.home.widgets.WidgetApp
import com.gh00ul.cascade.ui.home.widgets.WidgetOption
import com.gh00ul.cascade.ui.home.widgets.WidgetPickerContent
import com.gh00ul.cascade.ui.home.widgets.WidgetStack
import com.gh00ul.cascade.ui.home.widgets.WidgetStackContent
import com.gh00ul.cascade.ui.home.widgets.resetWidgetStackMemory
import com.gh00ul.cascade.ui.home.weatherIcon
import com.gh00ul.cascade.data.WeatherKind
import com.gh00ul.cascade.ui.common.ExtraIcons
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The widget stack under the clock, with Cascade's own calendar and weather widgets (Robolectric can't host Android
 * app widgets), its edit sheet and its Settings page. The agenda comes from [AgendaProvider], the weather from
 * Weather.showForTest: nothing goes to the network.
 */
class WidgetScreenshots : ScreenshotTest() {
    private val seattle = WeatherPlace("Seattle, Washington, United States", 47.61, -122.33)

    private fun settings(vararg stack: String, showWeather: Boolean = false) = LauncherSettings(
        favorites = favorites.map { it.key },
        widgetStack = stack.toList(),
        weatherPlace = seattle,
        tempUnit = TempUnit.CELSIUS,
        showWeather = showWeather,
    )

    @Before
    fun seed() {
        resetWidgetStackMemory()
        WidgetFixtures.installAgenda()
        Weather.showForTest(WidgetFixtures.reading(seattle))
    }

    /** The calendar first, with the weather a swipe away: today's three events, then tomorrow's first. */
    @Test fun homeCalendar() = snap("Home_WidgetCalendar", afterContent = WidgetFixtures.awaitAgenda) {
        HomeScreen(settings(WIDGET_CALENDAR, WIDGET_WEATHER), apps, favorites, icons, FakeNotifications.byApp(), widgets = {
            WidgetStack(settings(WIDGET_CALENDAR, WIDGET_WEATHER), onEdit = {})
        })
    }

    /** The weather first, with the readout beside the date off: the widget needs only the place. */
    @Test fun homeWeather() = snap("Home_WidgetWeather", afterContent = WidgetFixtures.awaitWeather) {
        HomeScreen(settings(WIDGET_WEATHER, WIDGET_CALENDAR), apps, favorites, icons, FakeNotifications.byApp(), widgets = {
            WidgetStack(settings(WIDGET_WEATHER, WIDGET_CALENDAR), onEdit = {})
        })
    }

    @Test fun calendar() = snap("Widget_Calendar", Frame.Component, afterContent = WidgetFixtures.awaitAgenda) {
        RowBackdrop { WidgetStack(settings(WIDGET_CALENDAR, WIDGET_WEATHER, "app:1"), onEdit = {}) }
    }

    @Test fun weather() = snap("Widget_Weather", Frame.Component, afterContent = WidgetFixtures.awaitWeather) {
        RowBackdrop { WidgetStack(settings(WIDGET_WEATHER), onEdit = {}) }
    }

    /** On the Galaxy Tab's 753dp width the card stops at 560dp and the next days fit beside the hours. */
    @Config(qualifiers = TABLET)
    @Test fun weatherWide() = snap("Widget_WeatherWide", Frame.Component, afterContent = WidgetFixtures.awaitWeather) {
        RowBackdrop { WidgetStack(settings(WIDGET_WEATHER, WIDGET_CALENDAR), onEdit = {}) }
    }

    @Test fun calendarNoAccess() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        snap("Widget_CalendarNoAccess", Frame.Component) {
            RowBackdrop { WidgetStack(settings(WIDGET_CALENDAR), onEdit = {}) }
        }
    }

    @Test fun weatherNoPlace() = snap("Widget_WeatherNoPlace", Frame.Component) {
        RowBackdrop { WidgetStack(settings(WIDGET_WEATHER).copy(weatherPlace = null), onEdit = {}) }
    }

    /** At 1.5x the hours still fit the card, without the chance of rain. */
    @Test fun weatherFont1_5x() = snap("Widget_Weather_Font1_5x", Frame.Component, afterContent = WidgetFixtures.awaitWeather) {
        FontScale(1.5f) { RowBackdrop { WidgetStack(settings(WIDGET_WEATHER), onEdit = {}) } }
    }

    /** At 2x only the weather now fits; the hours go rather than being cut off. */
    @Test fun weatherFont2x() = snap("Widget_Weather_Font2x", Frame.Component, afterContent = WidgetFixtures.awaitWeather) {
        FontScale(2f) { RowBackdrop { WidgetStack(settings(WIDGET_WEATHER), onEdit = {}) } }
    }

    @Test fun calendarFont2x() = snap("Widget_Calendar_Font2x", Frame.Component, afterContent = WidgetFixtures.awaitAgenda) {
        FontScale(2f) { RowBackdrop { WidgetStack(settings(WIDGET_CALENDAR, WIDGET_WEATHER, "app:1"), onEdit = {}) } }
    }

    /** The body ellipsized so Allow keeps its place on the card. */
    @Test fun calendarNoAccessFont1_5x() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        snap("Widget_CalendarNoAccess_Font1_5x", Frame.Component) {
            FontScale(1.5f) { RowBackdrop { WidgetStack(settings(WIDGET_CALENDAR), onEdit = {}) } }
        }
    }

    @Test fun calendarNoAccessFont2x() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        snap("Widget_CalendarNoAccess_Font2x", Frame.Component) {
            FontScale(2f) { RowBackdrop { WidgetStack(settings(WIDGET_CALENDAR), onEdit = {}) } }
        }
    }

    @Test fun weatherNoPlaceFont2x() = snap("Widget_WeatherNoPlace_Font2x", Frame.Component) {
        FontScale(2f) { RowBackdrop { WidgetStack(settings(WIDGET_WEATHER).copy(weatherPlace = null), onEdit = {}) } }
    }

    /** The edit sheet from a long press on the stack: the widgets in order, moves and Remove, then Add widget. */
    @Test fun sheet() = snap("Sheet_WidgetStack", Frame.Component, variants = listOf(Variant.DarkTheme), wallpaper = false) {
        SheetSurface {
            WidgetStackContent(
                entries = listOf(
                    StackEntry(WIDGET_CALENDAR, "Calendar", "Your agenda for today and the next two days", ExtraIcons.Event, null),
                    StackEntry(WIDGET_WEATHER, "Weather", "Now and the hours ahead in Seattle", weatherIcon(WeatherKind.PARTLY_CLOUDY, true), null),
                    StackEntry("app:1", "Inbox", "Mail · 4×2", null, FakeApps.iconBitmap("Mail", 0xFFD93025).asImageBitmap()),
                ),
                onMove = { _, _ -> },
                onRemove = {},
                onAdd = {},
            )
        }
    }

    /** The picker: Cascade's two (the calendar already in the stack), then apps' widgets with their sizes. */
    @Test fun picker() = snap("Sheet_WidgetPicker", Frame.Component, variants = listOf(Variant.DarkTheme), wallpaper = false) {
        SheetSurface {
            WidgetPickerContent(
                stack = listOf(WIDGET_CALENDAR),
                catalog = WidgetFixtures.catalog(),
                onBack = {},
                onPickBuiltIn = {},
                onPickApp = {},
                modifier = Modifier.heightIn(max = 900.dp),
            )
        }
    }

    private companion object {
        /** A Galaxy Tab S7 in portrait: 753 dp wide. */
        const val TABLET = "en-rUS-w753dp-h1205dp-large-notlong-notround-any-xhdpi-keyshidden-nonav"
    }
}

/** The Widgets page in Settings, in the light and dark theme, with the calendar and weather in the stack. */
@Config(application = LauncherApplication::class)
class WidgetSettingsScreenshots : ScreenshotTest() {
    @Test fun page() {
        val app = RuntimeEnvironment.getApplication() as LauncherApplication
        app.prefs.update {
            it.copy(
                widgetStack = listOf(WIDGET_CALENDAR, WIDGET_WEATHER),
                // A place, but nothing that refreshes on this page.
                weatherPlace = WeatherPlace("Seattle, Washington, United States", 47.61, -122.33),
            )
        }
        snap("Settings_Widgets", variants = Variant.Settings, wallpaper = false) { SettingsApp(SettingsScreen.WIDGETS, onExit = {}) }
    }
}

/**
 * [content] under the system font size [scale], as Android 14+ applies it: small text grows by about [scale], large
 * text by less (nonlinear font scaling). Only the text grows; the canvas and everything sized in dp stay the same.
 */
@Composable
internal fun FontScale(scale: Float, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = remember(context, configuration, scale) {
        Density(context.createConfigurationContext(Configuration(configuration).apply { fontScale = scale }))
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

/** What ModalBottomSheet draws around its content: the sheet's shape and color, with the drag handle on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = BottomSheetDefaults.ExpandedShape, color = BottomSheetDefaults.ContainerColor) {
        Column(Modifier.fillMaxWidth()) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}

/** The agenda, the forecast and the app widgets the shots show, all relative to [FIXED_NOW] (Monday 9:41 AM). */
internal object WidgetFixtures {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** October [day] 2026 at [hour]:[minute] in [FIXED_ZONE]. */
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, FIXED_ZONE).toInstant().toEpochMilli()

    /** Grants calendar access and answers the agenda's query: an all-day birthday, a meeting under way, lunch, tomorrow's dentist. */
    fun installAgenda() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        Robolectric.setupContentProvider(AgendaProvider::class.java, CalendarContract.AUTHORITY)
        val birthday = LocalDate.of(2026, 10, 5).toEpochDay() * 24 * HOUR
        AgendaProvider.rows = listOf(
            AgendaProvider.Row(1, "Mia's birthday", birthday, birthday + 24 * HOUR, allDay = true, color = 0xFFE67C73),
            AgendaProvider.Row(2, "Design review", at(5, 9, 30), at(5, 10, 30), color = 0xFF039BE5),
            AgendaProvider.Row(3, "Lunch with Sam", at(5, 12, 30), at(5, 13, 30), color = 0xFF33B679),
            AgendaProvider.Row(4, "Dentist", at(6, 9, 0), at(6, 10, 0), color = 0xFF8E24AA),
            AgendaProvider.Row(5, "Flight to Denver", at(7, 18, 5), at(7, 21, 0), color = 0xFF039BE5),
        )
    }

    /** Partly cloudy and 18° at 9:41, rain coming in the afternoon, then four days. */
    fun reading(place: WeatherPlace): WeatherNow {
        val temps = listOf(18, 19, 19, 17, 16, 15, 15, 14)
        val codes = listOf(2, 3, 61, 61, 80, 3, 2, 1)
        val rain = listOf(10, 25, 60, 70, 40, 15, 5, 0)
        val hours = List(8) { i -> HourForecast(at(5, 10 + i), temps[i], codes[i], isDay = 10 + i < 19, precipitation = rain[i]) }
        val days = listOf(
            DayForecast(LocalDate.of(2026, 10, 5).toEpochDay(), 61, 21, 12),
            DayForecast(LocalDate.of(2026, 10, 6).toEpochDay(), 3, 18, 10),
            DayForecast(LocalDate.of(2026, 10, 7).toEpochDay(), 0, 22, 9),
            DayForecast(LocalDate.of(2026, 10, 8).toEpochDay(), 80, 16, 9),
            DayForecast(LocalDate.of(2026, 10, 9).toEpochDay(), 2, 19, 8),
        )
        return WeatherNow(18, 21, 12, code = 2, isDay = true, fahrenheit = false, fetchedAt = FIXED_NOW, place = place, hours = hours, days = days)
    }

    /** Two apps with widgets, as the picker lists them; their previews can't load here, so the boxes stay empty. */
    fun catalog(): List<WidgetApp> = listOf(
        app("Clock", 0xFF1A73E8, "Digital clock" to "2×1", "World clock" to "4×2"),
        app("Mail", 0xFFD93025, "Inbox" to "4×2"),
    )

    private fun app(label: String, color: Long, vararg widgets: Pair<String, String>) = WidgetApp(
        key = label,
        label = label,
        icon = FakeApps.iconBitmap(label, color, sizePx = 96).asImageBitmap(),
        widgets = widgets.map { (name, size) ->
            WidgetOption(AppWidgetProviderInfo().apply { provider = ComponentName("com.example.${label.lowercase()}", "$label.$name") }, name, size)
        },
    )

    /** The agenda is read on Dispatchers.IO; wait for its rows. */
    val awaitAgenda: ComposeTestRule.() -> Unit = {
        waitUntil(5_000) { onAllNodes(hasText("Lunch with Sam")).fetchSemanticsNodes().isNotEmpty() }
    }

    val awaitWeather: ComposeTestRule.() -> Unit = {
        waitUntil(5_000) { onAllNodes(hasContentDescription("Weather:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Answers the agenda's Instances query with [rows], colors included, ignoring the selection. */
class AgendaProvider : ContentProvider() {
    class Row(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean = false, val color: Long = 0)

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = requireNotNull(projection)
        val cursor = MatrixCursor(columns)
        for (r in rows.sortedBy { it.begin }) {
            cursor.addRow(columns.map { column ->
                when (column) {
                    Instances.EVENT_ID -> r.id
                    Instances.TITLE -> r.title
                    Instances.BEGIN -> r.begin
                    Instances.END -> r.end
                    Instances.ALL_DAY -> if (r.allDay) 1 else 0
                    // All-day events are dated in UTC days, END_DAY inclusive; timed ones in local days.
                    Instances.START_DAY -> julianDay(r.begin, r.allDay)
                    Instances.END_DAY -> julianDay(if (r.allDay) r.end - 1 else r.end, r.allDay)
                    Instances.DISPLAY_COLOR -> r.color.toInt()
                    else -> null
                }
            })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        @Volatile var rows: List<Row> = emptyList()

        private fun julianDay(time: Long, utc: Boolean): Int {
            val day = 24 * 60 * 60 * 1000L
            val offset = if (utc) 0 else java.util.TimeZone.getDefault().getOffset(time)
            return Math.floorDiv(time + offset, day).toInt() + 2440588
        }
    }
}
