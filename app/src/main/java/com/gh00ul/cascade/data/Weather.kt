package com.gh00ul.cascade.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/** The weather at [place] when it was fetched: now for the readout beside the date, and the forecast for the widget. */
data class WeatherNow(
    val temperature: Int,
    /** Today's high and low, when Open-Meteo has them. */
    val high: Int?,
    val low: Int?,
    /** A WMO weather interpretation code, as Open-Meteo's weather_code gives it. */
    val code: Int,
    val isDay: Boolean,
    /** The temperatures are in Fahrenheit, else Celsius. */
    val fahrenheit: Boolean,
    /** When it was asked for, in epoch milliseconds. */
    val fetchedAt: Long,
    val place: WeatherPlace,
    /** The hours from the one under way when it was fetched, at most [HOURS_KEPT]. */
    val hours: List<HourForecast> = emptyList(),
    /** The days from today at the place, as many as came. */
    val days: List<DayForecast> = emptyList(),
) {
    val kind: WeatherKind get() = weatherKind(code)
    val description: String get() = weatherDescription(code, isDay)
}

/**
 * One hour of the forecast, starting at [time] (epoch milliseconds): its temperature, WMO code, whether it's day, and
 * the chance of precipitation in percent when Open-Meteo has one.
 */
data class HourForecast(val time: Long, val temperature: Int, val code: Int, val isDay: Boolean, val precipitation: Int?) {
    val kind: WeatherKind get() = weatherKind(code)
}

/** One day of the forecast: its date at the place (an epoch day), its WMO code, and its high and low. */
data class DayForecast(val day: Long, val code: Int, val high: Int, val low: Int) {
    val kind: WeatherKind get() = weatherKind(code)
}

/** Hours of the forecast kept with a reading: enough for the widget's next hours while the reading lasts. */
internal const val HOURS_KEPT = 12

/** What the sky is doing, as coarse as one small icon can show. */
enum class WeatherKind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** The kind of weather a WMO code stands for. Codes it doesn't know read as cloudy, which is rarely far off. */
fun weatherKind(wmoCode: Int): WeatherKind = when (wmoCode) {
    0 -> WeatherKind.CLEAR
    1, 2 -> WeatherKind.PARTLY_CLOUDY
    3 -> WeatherKind.CLOUDY
    45, 48 -> WeatherKind.FOG
    in 51..57 -> WeatherKind.DRIZZLE
    in 61..67, in 80..82 -> WeatherKind.RAIN
    in 71..77, 85, 86 -> WeatherKind.SNOW
    in 95..99 -> WeatherKind.THUNDER
    else -> WeatherKind.CLOUDY
}

/** A word or two for TalkBack. A clear sky is sunny by day. */
fun weatherDescription(wmoCode: Int, isDay: Boolean): String = when (weatherKind(wmoCode)) {
    WeatherKind.CLEAR -> if (isDay) "Sunny" else "Clear"
    WeatherKind.PARTLY_CLOUDY -> "Partly cloudy"
    WeatherKind.CLOUDY -> "Cloudy"
    WeatherKind.FOG -> "Fog"
    WeatherKind.DRIZZLE -> "Drizzle"
    WeatherKind.RAIN -> "Rain"
    WeatherKind.SNOW -> "Snow"
    WeatherKind.THUNDER -> "Thunderstorm"
}

/** Regions that measure temperature in Fahrenheit. */
private val FAHRENHEIT_REGIONS = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

/**
 * Whether temperatures show in Fahrenheit. AUTO follows the temperature unit picked in Android 14's regional
 * preferences when there is one (the locale's "mu" extension), else the region's convention.
 */
fun usesFahrenheit(unit: TempUnit, locale: Locale): Boolean = when (unit) {
    TempUnit.CELSIUS -> false
    TempUnit.FAHRENHEIT -> true
    TempUnit.AUTO -> when (locale.getUnicodeLocaleType("mu")) {
        "fahrenhe" -> true
        "celsius" -> false
        else -> locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_REGIONS
    }
}

/**
 * Current weather and the forecast from Open-Meteo (free, no key) for the place picked in Settings, in one request.
 * Nothing polls: home calls [refresh] on each start and every 30 minutes while it stays visible, and a fetch only goes
 * out when the reading is 30 minutes old, one at a time, and not within 10 minutes of a failed one. The last reading is
 * kept on disk, so after a process restart it shows at once instead of waiting for the network.
 *
 * Weather is wanted ([wantedPlace]) while a place is picked and either the readout beside the date is on or the
 * weather widget is in the stack: the widget needs only the place, so it works with the readout off.
 */
object Weather {
    /** A reading this old is fetched again. */
    internal const val MAX_AGE_MS = 30 * 60_000L
    /** After a failed fetch (offline, or the service is down), returns home don't try again for this long. */
    internal const val RETRY_MS = 10 * 60_000L
    /** A stored reading older than this is dropped rather than shown. */
    internal const val KEEP_MS = 3 * 60 * 60_000L

    private val _state = MutableStateFlow<WeatherNow?>(null)
    /**
     * The last reading. Until the next refresh it can be for a place or unit Settings no longer asks for, so the
     * readout reads it through rememberWeather, which checks.
     */
    val state: StateFlow<WeatherNow?> = _state.asStateFlow()

    private val _failedFor = MutableStateFlow<Pair<WeatherPlace, Boolean>?>(null)
    /**
     * The place and unit (true for Fahrenheit) whose latest fetch failed, until a fetch succeeds: with no reading to
     * show, the widget says so instead of waiting quietly. It only reports; retries keep to the 10-minute backoff.
     */
    val failedFor: StateFlow<Pair<WeatherPlace, Boolean>?> = _failedFor.asStateFlow()

    // Not LauncherApplication's scope: the readout also runs in Settings and in tests, under a plain Application. One
    // task at a time and in order, so a fetch never overlaps another fetch or a clear, and needs no lock.
    private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    /** Created by the first task on [queue], and only touched there. */
    private var store: WeatherStore? = null
    /** A reading may be in [state] or on disk, so turning weather off has something to clear. */
    @Volatile private var holding = false
    @Volatile private var loadQueued = false

    /**
     * Brings [state] up to date for [settings]: fetches when weather is wanted ([wantedPlace]) and the reading is
     * missing, 30 minutes old, or for another place or unit, or when [force]d. With neither the readout nor the widget
     * showing it, or no place, clears it.
     */
    fun refresh(context: Context, settings: LauncherSettings, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val place = wantedPlace(settings)
        // Off, and nothing fetched or loaded since it last was: there's nothing to clear, so starts cost nothing.
        if (place == null && !holding) return
        holding = place != null
        val fahrenheit = usesFahrenheit(settings.tempUnit, context.resources.configuration.locales[0])
        val forecast = WIDGET_WEATHER in settings.widgetStack
        queue.launch { storeFor(app).update(place, fahrenheit, now, force, needsForecast = forecast) }
    }

    /** The place weather is fetched for: the picked one, while the readout beside the date or the weather widget shows. */
    fun wantedPlace(settings: LauncherSettings): WeatherPlace? =
        settings.weatherPlace?.takeIf { settings.showWeather || WIDGET_WEATHER in settings.widgetStack }

    /** Reads the stored reading into [state], once and off the main thread, so home has it before any fetch. */
    internal fun load(context: Context) {
        if (loadQueued) return
        loadQueued = true
        holding = true
        val app = context.applicationContext
        queue.launch { storeFor(app).load(System.currentTimeMillis()) }
    }

    /**
     * Shows [reading] as the current one, for screenshot tests. Nothing is stored, and it's stamped with this clock
     * (a test's fixed clock may not be the one [refresh] reads), so it's fresh and no fetch is due.
     */
    @VisibleForTesting
    internal fun showForTest(reading: WeatherNow) {
        holding = true
        _state.value = reading.copy(fetchedAt = System.currentTimeMillis())
    }

    /**
     * Places matching [query], for the picker in Settings. Open-Meteo answers fewer than two letters with nothing, so
     * those (and a blank query) don't ask.
     */
    suspend fun search(query: String): Result<List<WeatherPlace>> {
        val name = query.trim()
        if (name.length < 2) return Result.success(emptyList())
        // toLanguageTag has the current codes ("he", not the "iw" that Locale.language keeps on Android).
        val language = Locale.getDefault().toLanguageTag().substringBefore('-').lowercase(Locale.ROOT)
        // Typing on cancels this search; its request is disconnected rather than left running to its timeouts.
        return withContext(Dispatchers.IO) { runCatching { parsePlaces(responseBodyCancellable(request(searchUrl(name, language)))) } }
    }

    private fun storeFor(app: Context): WeatherStore =
        store ?: WeatherStore(app.getSharedPreferences("weather", Context.MODE_PRIVATE), _state, _failedFor, ::get).also { store = it }

    /** A GET with short timeouts: a late reading is no use, and the next return home tries again. */
    private fun get(url: String): String = responseBody(request(url))

    /** The request for [url], not yet sent: nothing touches the network until its status or body is read. */
    private fun request(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "Cascade-launcher")
    }
}

/**
 * The body of the answer on [connection] when its status is 200; any other status throws [HttpStatusException]. The
 * status comes first because for an error status Android's body read throws a bare FileNotFoundException instead.
 * Disconnects either way.
 */
internal fun responseBody(connection: HttpURLConnection): String {
    try {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code)
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/**
 * [responseBody], with the blocking read tied to the calling coroutine: cancelling it disconnects [connection], which
 * makes the connect or read under way throw at once instead of running on for up to 20 seconds.
 */
internal suspend fun responseBodyCancellable(connection: HttpURLConnection): String = coroutineScope {
    // disconnect() is safe from another thread; it's how HttpURLConnection is meant to be cancelled.
    val disconnectOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }
    try {
        // Cancelled already: a disconnect before connecting does nothing, so don't connect at all.
        ensureActive()
        responseBody(connection)
    } finally {
        disconnectOnCancel.cancel()
    }
}

/**
 * [Weather]'s bookkeeping: the stored reading, and when a fetch last failed. Not thread-safe; Weather calls it from
 * one task at a time. [download] fetches a URL's body, and throws when it can't.
 */
internal class WeatherStore(
    private val prefs: SharedPreferences,
    private val state: MutableStateFlow<WeatherNow?>,
    /**
     * The place and unit that last failed, until a fetch succeeds: a failure holds back retries for those, not for a
     * place picked since. A flow so [Weather.failedFor] can show it.
     */
    private val failedFor: MutableStateFlow<Pair<WeatherPlace, Boolean>?> = MutableStateFlow(null),
    private val download: (String) -> String,
) {
    private var loaded = false
    private var failedAt = 0L

    /** Reads the stored reading into [state] once; one older than 3 hours is dropped instead. */
    fun load(now: Long) {
        if (loaded) return
        loaded = true
        // Not getString: a value of another type there would throw, and this runs where nothing catches it.
        val stored = (prefs.all[NOW] as? String)?.let(::parseWeather)
        if (stored != null && now - stored.fetchedAt in 0 until Weather.KEEP_MS) state.compareAndSet(null, stored)
        else if (prefs.contains(NOW)) prefs.edit { remove(NOW) }
    }

    /**
     * Clears the reading for a null [place] (weather off). Otherwise drops a reading for another place or unit, or
     * one hours old, and fetches when it's due or [force]d.
     */
    fun update(place: WeatherPlace?, fahrenheit: Boolean, now: Long, force: Boolean, needsForecast: Boolean = false) {
        load(now)
        val current = state.value
        val expired = current != null && now - current.fetchedAt !in 0 until Weather.KEEP_MS
        if (current != null && (current.place != place || current.fahrenheit != fahrenheit || expired)) forget()
        if (place == null) return
        val lastFailure = if (failedFor.value == place to fahrenheit) failedAt else 0L
        // With the weather widget on home, a reading without the hours (stored by a build before the forecast) is fetched
        // again, or the widget would show only the current conditions until it's half an hour old.
        val fresh = state.value?.takeIf { !needsForecast || it.hours.isNotEmpty() }
        if (!force && !fetchDue(now, fresh?.fetchedAt, lastFailure)) return
        val fetched = try {
            parseForecast(download(forecastUrl(place, fahrenheit)), place, fahrenheit, now)
        } catch (_: Exception) {
            null
        }
        if (fetched == null) {
            failedAt = now
            failedFor.value = place to fahrenheit
            return
        }
        failedFor.value = null
        state.value = fetched
        prefs.edit { putString(NOW, weatherJson(fetched)) }
    }

    private fun forget() {
        state.value = null
        if (prefs.contains(NOW)) prefs.edit { remove(NOW) }
    }

    private companion object {
        const val NOW = "now"
    }
}

/**
 * Whether to fetch at [now]: there's no reading or it's 30 minutes old ([fetchedAt]), and no fetch failed in the last
 * 10 minutes ([failedAt], 0 for none). A timestamp in the future means the clock moved back, which counts as due.
 */
internal fun fetchDue(now: Long, fetchedAt: Long?, failedAt: Long): Boolean =
    (fetchedAt == null || now - fetchedAt !in 0 until Weather.MAX_AGE_MS) && now - failedAt !in 0 until Weather.RETRY_MS

/**
 * Now, the hours ahead and five days for [place], in one request; times come in the place's own time zone. Coordinates
 * use Locale.ROOT, so a German phone doesn't send "47,6062".
 */
internal fun forecastUrl(place: WeatherPlace, fahrenheit: Boolean): String {
    val coordinates = String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", place.latitude, place.longitude)
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    return "https://api.open-meteo.com/v1/forecast?$coordinates&current=temperature_2m,weather_code,is_day" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5&temperature_unit=$unit"
}

internal fun searchUrl(name: String, language: String): String =
    "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=8&language=$language&format=json"

/**
 * The reading in an Open-Meteo forecast response, as fetched [now]. Null when the current temperature or weather code
 * is missing or the JSON is malformed; a missing high, low or day flag only leaves that part out (day by default), and
 * the forecast keeps only the hours and days that came whole.
 */
internal fun parseForecast(json: String, place: WeatherPlace, fahrenheit: Boolean, now: Long): WeatherNow? = runCatching {
    val root = JSONObject(json)
    val current = root.getJSONObject("current")
    val temperature = current.number("temperature_2m") ?: return null
    val code = current.number("weather_code") ?: return null
    val daily = root.optJSONObject("daily")
    WeatherNow(
        temperature = temperature.roundToInt(),
        high = daily?.firstNumber("temperature_2m_max")?.roundToInt(),
        low = daily?.firstNumber("temperature_2m_min")?.roundToInt(),
        code = code.toInt(),
        isDay = current.number("is_day")?.let { it != 0.0 } ?: true,
        fahrenheit = fahrenheit,
        fetchedAt = now,
        place = place,
        hours = root.optJSONObject("hourly")?.let { parseHours(it, root.optInt("utc_offset_seconds"), now) }.orEmpty(),
        days = daily?.let(::parseDays).orEmpty(),
    )
}.getOrNull()

/**
 * The hours in Open-Meteo's hourly block, from the one under way at [now] on, at most [HOURS_KEPT]. Times are the
 * place's local ones, [offsetSeconds] from UTC. An hour without a time, temperature or code is skipped.
 */
private fun parseHours(hourly: JSONObject, offsetSeconds: Int, now: Long): List<HourForecast> {
    val times = hourly.optJSONArray("time") ?: return emptyList()
    val temperatures = hourly.optJSONArray("temperature_2m") ?: return emptyList()
    val codes = hourly.optJSONArray("weather_code") ?: return emptyList()
    val precipitation = hourly.optJSONArray("precipitation_probability")
    val day = hourly.optJSONArray("is_day")
    val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
    val hours = mutableListOf<HourForecast>()
    for (i in 0 until times.length()) {
        val time = runCatching { LocalDateTime.parse(times.getString(i)).toEpochSecond(offset) * 1_000 }.getOrNull() ?: continue
        if (time <= now - HOUR_MS) continue
        val temperature = temperatures.numberAt(i) ?: continue
        val code = codes.numberAt(i) ?: continue
        hours += HourForecast(
            time = time,
            temperature = temperature.roundToInt(),
            code = code.toInt(),
            isDay = day?.numberAt(i)?.let { it != 0.0 } ?: true,
            precipitation = precipitation?.numberAt(i)?.roundToInt(),
        )
        if (hours.size == HOURS_KEPT) break
    }
    return hours
}

/** The days in Open-Meteo's daily block; a day without a date, code, high or low is skipped. */
private fun parseDays(daily: JSONObject): List<DayForecast> {
    val dates = daily.optJSONArray("time") ?: return emptyList()
    val codes = daily.optJSONArray("weather_code") ?: return emptyList()
    val highs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
    val lows = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
    return (0 until dates.length()).mapNotNull { i ->
        val day = runCatching { LocalDate.parse(dates.getString(i)).toEpochDay() }.getOrNull() ?: return@mapNotNull null
        DayForecast(
            day = day,
            code = codes.numberAt(i)?.toInt() ?: return@mapNotNull null,
            high = highs.numberAt(i)?.roundToInt() ?: return@mapNotNull null,
            low = lows.numberAt(i)?.roundToInt() ?: return@mapNotNull null,
        )
    }
}

private const val HOUR_MS = 60 * 60_000L

/**
 * The places in an Open-Meteo geocoding response, named "Seattle, Washington, United States": the name, region and
 * country, leaving out blank and repeated parts ("Singapore", not "Singapore, Singapore"). Results without a name or
 * valid coordinates are skipped, and so are exact repeats, which a list keyed on the place couldn't show; a response
 * that isn't JSON has none.
 */
internal fun parsePlaces(json: String): List<WeatherPlace> = runCatching {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    List(results.length()) { results.optJSONObject(it) }.mapNotNull { result ->
        val name = result?.text("name") ?: return@mapNotNull null
        val latitude = result.number("latitude")?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val longitude = result.number("longitude")?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        val parts = listOfNotNull(name, result.text("admin1"), result.text("country")).distinctBy { it.lowercase(Locale.ROOT) }
        WeatherPlace(parts.joinToString(", "), latitude, longitude)
    }.distinct()
}.getOrDefault(emptyList())

/** [now] as JSON, for the "weather" preferences file. */
internal fun weatherJson(now: WeatherNow): String = JSONObject()
    .put("temperature", now.temperature)
    .apply { now.high?.let { put("high", it) } }
    .apply { now.low?.let { put("low", it) } }
    .put("code", now.code)
    .put("isDay", now.isDay)
    .put("fahrenheit", now.fahrenheit)
    .put("fetchedAt", now.fetchedAt)
    .put("place", JSONObject(placeJson(now.place)))
    .put("hours", JSONArray(now.hours.map { h ->
        JSONObject().put("time", h.time).put("temperature", h.temperature).put("code", h.code).put("isDay", h.isDay)
            .apply { h.precipitation?.let { put("precipitation", it) } }
    }))
    .put("days", JSONArray(now.days.map { d -> JSONObject().put("day", d.day).put("code", d.code).put("high", d.high).put("low", d.low) }))
    .toString()

/**
 * The reading in [json] from [weatherJson], or null when it's malformed. One stored before the forecast was fetched
 * reads with none, and a malformed hour or day is left out.
 */
internal fun parseWeather(json: String): WeatherNow? = runCatching {
    val obj = JSONObject(json)
    WeatherNow(
        temperature = obj.getInt("temperature"),
        high = if (obj.has("high")) obj.getInt("high") else null,
        low = if (obj.has("low")) obj.getInt("low") else null,
        code = obj.getInt("code"),
        isDay = obj.getBoolean("isDay"),
        fahrenheit = obj.getBoolean("fahrenheit"),
        fetchedAt = obj.getLong("fetchedAt"),
        place = parsePlace(obj.getJSONObject("place").toString()) ?: return null,
        hours = obj.optJSONArray("hours").objects().mapNotNull { h ->
            runCatching {
                val precipitation = if (h.has("precipitation")) h.getInt("precipitation") else null
                HourForecast(h.getLong("time"), h.getInt("temperature"), h.getInt("code"), h.getBoolean("isDay"), precipitation)
            }.getOrNull()
        },
        days = obj.optJSONArray("days").objects().mapNotNull { d ->
            runCatching { DayForecast(d.getLong("day"), d.getInt("code"), d.getInt("high"), d.getInt("low")) }.getOrNull()
        },
    )
}.getOrNull()

/** The objects in an array, skipping anything else; none for a missing array. */
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

/** [key] as a finite number, or null when it's missing, JSON null or not a number. */
private fun JSONObject.number(key: String): Double? = optDouble(key).takeIf { it.isFinite() }

/** The first number in the array at [key]: daily values come one per day. */
private fun JSONObject.firstNumber(key: String): Double? = optJSONArray(key)?.numberAt(0)

/** The number at [index], or null when it's missing, JSON null or not a number. */
private fun JSONArray.numberAt(index: Int): Double? = optDouble(index).takeIf { it.isFinite() }

/** [key] as trimmed text, or null when it's missing, JSON null or blank. optString would turn JSON null into "null". */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }
