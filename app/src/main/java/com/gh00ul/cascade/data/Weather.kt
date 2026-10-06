package com.gh00ul.cascade.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** The weather at [place] when it was fetched, for the readout beside the date. */
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
) {
    val kind: WeatherKind get() = weatherKind(code)
    val description: String get() = weatherDescription(code, isDay)
}

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
 * Current weather from Open-Meteo (free, no key) for the place picked in Settings. Nothing polls: home calls [refresh]
 * on each start and every 30 minutes while it stays visible, and a fetch only goes out when the reading is 30 minutes
 * old, one at a time, and not within 10 minutes of a failed one. The last reading is kept on disk, so after a process
 * restart it shows at once instead of waiting for the network.
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

    // Not LauncherApplication's scope: the readout also runs in Settings and in tests, under a plain Application. One
    // task at a time and in order, so a fetch never overlaps another fetch or a clear, and needs no lock.
    private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    /** Created by the first task on [queue], and only touched there. */
    private var store: WeatherStore? = null
    /** A reading may be in [state] or on disk, so turning weather off has something to clear. */
    @Volatile private var holding = false
    @Volatile private var loadQueued = false

    /**
     * Brings [state] up to date for [settings]: fetches when weather is on, a place is picked, and the reading is
     * missing, 30 minutes old, or for another place or unit, or when [force]d. With weather off or no place, clears it.
     */
    fun refresh(context: Context, settings: LauncherSettings, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val place = settings.weatherPlace?.takeIf { settings.showWeather }
        // Off, and nothing fetched or loaded since it last was: there's nothing to clear, so starts cost nothing.
        if (place == null && !holding) return
        holding = place != null
        val fahrenheit = usesFahrenheit(settings.tempUnit, context.resources.configuration.locales[0])
        queue.launch { storeFor(app).update(place, fahrenheit, now, force) }
    }

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
        return withContext(Dispatchers.IO) { runCatching { parsePlaces(get(searchUrl(name, language))) } }
    }

    private fun storeFor(app: Context): WeatherStore =
        store ?: WeatherStore(app.getSharedPreferences("weather", Context.MODE_PRIVATE), _state, ::get).also { store = it }

    /** A GET with short timeouts: a late reading is no use, and the next return home tries again. */
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Cascade-launcher")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * [Weather]'s bookkeeping: the stored reading, and when a fetch last failed. Not thread-safe; Weather calls it from
 * one task at a time. [download] fetches a URL's body, and throws when it can't.
 */
internal class WeatherStore(
    private val prefs: SharedPreferences,
    private val state: MutableStateFlow<WeatherNow?>,
    private val download: (String) -> String,
) {
    private var loaded = false
    private var failedAt = 0L
    /** The place and unit that last failed: a failure holds back retries for those, not for a place picked since. */
    private var failedFor: Pair<WeatherPlace, Boolean>? = null

    /** Reads the stored reading into [state] once; one older than 3 hours is dropped instead. */
    fun load(now: Long) {
        if (loaded) return
        loaded = true
        val stored = prefs.getString(NOW, null)?.let(::parseWeather)
        if (stored != null && now - stored.fetchedAt in 0 until Weather.KEEP_MS) state.compareAndSet(null, stored)
        else if (prefs.contains(NOW)) prefs.edit().remove(NOW).apply()
    }

    /**
     * Clears the reading for a null [place] (weather off). Otherwise drops a reading for another place or unit, or
     * one hours old, and fetches when it's due or [force]d.
     */
    fun update(place: WeatherPlace?, fahrenheit: Boolean, now: Long, force: Boolean) {
        load(now)
        val current = state.value
        val expired = current != null && now - current.fetchedAt !in 0 until Weather.KEEP_MS
        if (current != null && (current.place != place || current.fahrenheit != fahrenheit || expired)) forget()
        if (place == null) return
        val lastFailure = if (failedFor == place to fahrenheit) failedAt else 0L
        if (!force && !fetchDue(now, state.value?.fetchedAt, lastFailure)) return
        val fetched = try {
            parseForecast(download(forecastUrl(place, fahrenheit)), place, fahrenheit, now)
        } catch (_: Exception) {
            null
        }
        if (fetched == null) {
            failedAt = now
            failedFor = place to fahrenheit
            return
        }
        failedFor = null
        state.value = fetched
        prefs.edit().putString(NOW, weatherJson(fetched)).apply()
    }

    private fun forget() {
        state.value = null
        if (prefs.contains(NOW)) prefs.edit().remove(NOW).apply()
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

/** Today's forecast for [place]. Coordinates use Locale.ROOT, so a German phone doesn't send "47,6062". */
internal fun forecastUrl(place: WeatherPlace, fahrenheit: Boolean): String {
    val coordinates = String.format(Locale.ROOT, "latitude=%.4f&longitude=%.4f", place.latitude, place.longitude)
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    return "https://api.open-meteo.com/v1/forecast?$coordinates&current=temperature_2m,weather_code,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1&temperature_unit=$unit"
}

internal fun searchUrl(name: String, language: String): String =
    "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=8&language=$language&format=json"

/**
 * The reading in an Open-Meteo forecast response, as fetched [now]. Null when the current temperature or weather code
 * is missing or the JSON is malformed; a missing high, low or day flag only leaves that part out (day by default).
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
    )
}.getOrNull()

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
    .toString()

/** The reading in [json] from [weatherJson], or null when it's malformed. */
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
    )
}.getOrNull()

/** [key] as a finite number, or null when it's missing, JSON null or not a number. */
private fun JSONObject.number(key: String): Double? = optDouble(key).takeIf { it.isFinite() }

/** The first number in the array at [key]: daily values come one per day. */
private fun JSONObject.firstNumber(key: String): Double? = optJSONArray(key)?.optDouble(0)?.takeIf { it.isFinite() }

/** [key] as trimmed text, or null when it's missing, JSON null or blank. optString would turn JSON null into "null". */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }
