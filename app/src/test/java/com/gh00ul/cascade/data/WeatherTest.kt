package com.gh00ul.cascade.data

import android.app.Application
import android.content.Context
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.home.weatherIcon
import com.gh00ul.cascade.ui.home.weatherText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

// Robolectric for the real org.json (on the plain JVM it is a stub that returns defaults) and SharedPreferences.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeatherTest {
    private val seattle = WeatherPlace("Seattle, Washington, United States", 47.60621, -122.33207)
    private val zurich = WeatherPlace("Zürich, Zurich, Switzerland", 47.36667, 8.55)
    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_791_300_000_000L

    /** Trimmed from a real response for Seattle. */
    private val forecast = """
        {"latitude":47.595562,"longitude":-122.32443,"generationtime_ms":0.0597238540649414,"utc_offset_seconds":-25200,
         "timezone":"America/Los_Angeles","timezone_abbreviation":"GMT-7","elevation":56.0,
         "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
         "current":{"time":"2026-10-06T09:30","interval":900,"temperature_2m":17.6,"weather_code":2,"is_day":1},
         "daily_units":{"time":"iso8601","temperature_2m_max":"°C","temperature_2m_min":"°C"},
         "daily":{"time":["2026-10-06"],"temperature_2m_max":[21.4],"temperature_2m_min":[11.5]}}
    """.trimIndent()

    private val places = """
        {"results":[
          {"id":5809844,"name":"Seattle","latitude":47.60621,"longitude":-122.33207,"elevation":56.0,"feature_code":"PPLA2",
           "country_code":"US","admin1_id":5815135,"timezone":"America/Los_Angeles","population":737015,
           "country":"United States","admin1":"Washington","admin2":"King"},
          {"id":1880252,"name":"Singapore","latitude":1.28967,"longitude":103.85007,"country":"Singapore","admin1":"Singapore"},
          {"id":5128581,"name":"New York","latitude":40.71427,"longitude":-74.00597,"country":"United States","admin1":"New York"},
          {"id":5128582,"name":"New York","latitude":40.71427,"longitude":-74.00597,"country":"United States","admin1":"New York"},
          {"id":1,"name":"  Smalltown ","latitude":10.5,"longitude":20.25,"admin1":"","country":null},
          {"id":2,"name":"No Coordinates","country":"Nowhere"},
          {"id":3,"name":"Off The Map","latitude":95.0,"longitude":20.0},
          {"id":4,"name":null,"latitude":1.0,"longitude":2.0},
          {"id":5,"latitude":1.0,"longitude":2.0}
        ],"generationtime_ms":0.61}
    """.trimIndent()

    private fun reading(
        temperature: Int = 18,
        code: Int = 0,
        isDay: Boolean = true,
        high: Int? = 21,
        low: Int? = 12,
        fahrenheit: Boolean = false,
        fetchedAt: Long = t0,
        place: WeatherPlace = seattle,
    ) = WeatherNow(temperature, high, low, code, isDay, fahrenheit, fetchedAt, place)

    // parseForecast

    @Test fun parsesCurrentConditionsAndTodaysRange() {
        assertEquals(reading(temperature = 18, high = 21, low = 12, code = 2), parseForecast(forecast, seattle, fahrenheit = false, now = t0))
    }

    @Test fun keepsTheUnitAndTheNightFlag() {
        val night = forecast.replace("\"is_day\":1", "\"is_day\":0").replace("17.6", "-3.4")
        val parsed = parseForecast(night, zurich, fahrenheit = true, now = t0 + 5)
        assertEquals(reading(temperature = -3, code = 2, isDay = false, fahrenheit = true, fetchedAt = t0 + 5, place = zurich), parsed)
    }

    @Test fun missingRangeOrDayFlagOnlyLeavesThatOut() {
        val noDaily = """{"current":{"temperature_2m":17.6,"weather_code":3}}"""
        assertEquals(reading(code = 3, high = null, low = null), parseForecast(noDaily, seattle, false, t0))
        val nullRange = forecast.replace("[21.4]", "[null]").replace("[11.5]", "[]")
        assertEquals(reading(code = 2, high = null, low = null), parseForecast(nullRange, seattle, false, t0))
    }

    @Test fun malformedOrIncompleteForecastIsNull() {
        for (json in listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"error":true,"reason":"Latitude must be in range of -90 to 90°."}""",
            """{"current":{"weather_code":2,"is_day":1}}""",
            """{"current":{"temperature_2m":null,"weather_code":2}}""",
            """{"current":{"temperature_2m":"warm","weather_code":2}}""",
            """{"current":{"temperature_2m":17.6,"is_day":1}}""",
            """{"current":"none"}""",
        )) {
            assertNull(json, parseForecast(json, seattle, false, t0))
        }
    }

    // parsePlaces

    @Test fun namesPlacesByNameRegionAndCountryWithoutRepeats() {
        assertEquals(
            listOf(
                WeatherPlace("Seattle, Washington, United States", 47.60621, -122.33207),
                WeatherPlace("Singapore", 1.28967, 103.85007),
                WeatherPlace("New York, United States", 40.71427, -74.00597),
                WeatherPlace("Smalltown", 10.5, 20.25),
            ),
            parsePlaces(places),
        )
    }

    @Test fun noResultsOrMalformedSearchIsEmpty() {
        // Open-Meteo leaves "results" out when nothing matches.
        assertEquals(emptyList<WeatherPlace>(), parsePlaces("""{"generationtime_ms":0.2}"""))
        assertEquals(emptyList<WeatherPlace>(), parsePlaces("""{"results":[]}"""))
        assertEquals(emptyList<WeatherPlace>(), parsePlaces("not json"))
        assertEquals(emptyList<WeatherPlace>(), parsePlaces("""{"results":"none"}"""))
    }

    @Test fun shortQueriesDontReachTheNetwork() = runBlocking {
        for (query in listOf("", "   ", "a", " b ")) {
            assertEquals(query, Result.success(emptyList<WeatherPlace>()), Weather.search(query))
        }
    }

    // Codes, units and text

    @Test fun wmoCodesMapToKinds() {
        val expected = mapOf(
            WeatherKind.CLEAR to listOf(0),
            WeatherKind.PARTLY_CLOUDY to listOf(1, 2),
            WeatherKind.CLOUDY to listOf(3),
            WeatherKind.FOG to listOf(45, 48),
            WeatherKind.DRIZZLE to listOf(51, 53, 55, 56, 57),
            WeatherKind.RAIN to listOf(61, 63, 65, 66, 67, 80, 81, 82),
            WeatherKind.SNOW to listOf(71, 73, 75, 77, 85, 86),
            WeatherKind.THUNDER to listOf(95, 96, 99),
        )
        for ((kind, codes) in expected) for (code in codes) assertEquals("code $code", kind, weatherKind(code))
        for (code in listOf(-1, 4, 44, 50, 58, 70, 90, 100)) assertEquals("code $code", WeatherKind.CLOUDY, weatherKind(code))
    }

    @Test fun descriptionsSaySunnyOnlyForAClearDay() {
        assertEquals("Sunny", weatherDescription(0, isDay = true))
        assertEquals("Clear", weatherDescription(0, isDay = false))
        assertEquals("Partly cloudy", weatherDescription(2, isDay = false))
        assertEquals("Cloudy", weatherDescription(3, isDay = true))
        assertEquals("Fog", weatherDescription(48, isDay = true))
        assertEquals("Drizzle", weatherDescription(53, isDay = true))
        assertEquals("Rain", weatherDescription(81, isDay = true))
        assertEquals("Snow", weatherDescription(75, isDay = true))
        assertEquals("Thunderstorm", weatherDescription(95, isDay = true))
    }

    @Test fun autoUnitFollowsTheRegion() {
        for (tag in listOf("en-US", "es-US", "en-LR", "my-MM", "en-BS", "en-BZ", "en-KY", "en-PW", "en-FM", "en-MH")) {
            assertTrue(tag, usesFahrenheit(TempUnit.AUTO, Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("en-GB", "en-CA", "de-DE", "fr-FR", "ja-JP", "en", "")) {
            assertFalse(tag, usesFahrenheit(TempUnit.AUTO, Locale.forLanguageTag(tag)))
        }
    }

    @Test fun autoUnitFollowsTheRegionalPreferenceWhenSet() {
        // Android 14's regional preferences put the chosen unit in the locale.
        assertFalse(usesFahrenheit(TempUnit.AUTO, Locale.forLanguageTag("en-US-u-mu-celsius")))
        assertTrue(usesFahrenheit(TempUnit.AUTO, Locale.forLanguageTag("de-DE-u-mu-fahrenhe")))
        // Kelvin isn't offered, so the region decides.
        assertTrue(usesFahrenheit(TempUnit.AUTO, Locale.forLanguageTag("en-US-u-mu-kelvin")))
    }

    @Test fun forcedUnitIgnoresTheLocale() {
        assertFalse(usesFahrenheit(TempUnit.CELSIUS, Locale.US))
        assertTrue(usesFahrenheit(TempUnit.FAHRENHEIT, Locale.GERMANY))
    }

    @Test fun readoutShowsTheTemperatureAndSpeaksTheRest() {
        assertEquals("18°" to "Weather: Sunny, 18 degrees, high 21, low 12", weatherText(reading()))
        assertEquals("-4°" to "Weather: Snow, -4 degrees, high -1, low -9", weatherText(reading(-4, code = 73, high = -1, low = -9)))
        assertEquals("64°" to "Weather: Clear, 64 degrees", weatherText(reading(64, isDay = false, high = null, low = null, fahrenheit = true)))
        assertEquals("7°" to "Weather: Rain, 7 degrees, low 3", weatherText(reading(7, code = 63, high = null, low = 3)))
    }

    @Test fun clearAndPartlyCloudyNightsGetAMoon() {
        assertEquals(ExtraIcons.ClearDay, weatherIcon(WeatherKind.CLEAR, isDay = true))
        assertEquals(ExtraIcons.Bedtime, weatherIcon(WeatherKind.CLEAR, isDay = false))
        assertEquals(ExtraIcons.PartlyCloudyDay, weatherIcon(WeatherKind.PARTLY_CLOUDY, isDay = true))
        assertEquals(ExtraIcons.PartlyCloudyNight, weatherIcon(WeatherKind.PARTLY_CLOUDY, isDay = false))
        assertEquals(ExtraIcons.Rainy, weatherIcon(WeatherKind.DRIZZLE, isDay = false))
        // Every kind has an icon, day and night, and building them parses every path.
        for (kind in WeatherKind.entries) for (day in listOf(true, false)) assertNotNull(weatherIcon(kind, day).root)
    }

    // Requests

    @Test fun forecastUrlUsesDotsWhateverTheLocale() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(
                "https://api.open-meteo.com/v1/forecast?latitude=47.6062&longitude=-122.3321&current=temperature_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5&temperature_unit=fahrenheit",
                forecastUrl(seattle, fahrenheit = true),
            )
            assertTrue(forecastUrl(zurich, fahrenheit = false).endsWith("&temperature_unit=celsius"))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test fun searchUrlEncodesTheName() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo+%26+co&count=8&language=pt&format=json",
            searchUrl("São Paulo & co", "pt"),
        )
    }

    @Test fun fetchIsDueThirtyMinutesAfterTheLastReading() {
        assertTrue(fetchDue(t0, fetchedAt = null, failedAt = 0L))
        assertFalse(fetchDue(t0, fetchedAt = t0, failedAt = 0L))
        assertFalse(fetchDue(t0 + 30 * minute - 1, fetchedAt = t0, failedAt = 0L))
        assertTrue(fetchDue(t0 + 30 * minute, fetchedAt = t0, failedAt = 0L))
        // The clock moved back.
        assertTrue(fetchDue(t0, fetchedAt = t0 + hour, failedAt = 0L))
    }

    @Test fun failedFetchHoldsOffRetriesForTenMinutes() {
        assertFalse(fetchDue(t0, fetchedAt = null, failedAt = t0))
        assertFalse(fetchDue(t0 + 10 * minute - 1, fetchedAt = null, failedAt = t0))
        assertTrue(fetchDue(t0 + 10 * minute, fetchedAt = null, failedAt = t0))
        assertFalse(fetchDue(t0 + 5 * minute, fetchedAt = t0 - hour, failedAt = t0))
        assertTrue(fetchDue(t0, fetchedAt = null, failedAt = t0 + minute))
    }

    @Test fun storedReadingSurvivesARoundTrip() {
        for (now in listOf(reading(), reading(-12, code = 95, isDay = false, high = null, low = null, fahrenheit = true, place = zurich))) {
            assertEquals(now, parseWeather(weatherJson(now)))
        }
        assertNull(parseWeather("not json"))
        assertNull(parseWeather("""{"temperature":18}"""))
    }

    // WeatherStore: what refresh does, with a fake network

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("weather-test", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<WeatherNow?>(null)
    private val requests = mutableListOf<String>()
    private var response: () -> String = { forecast }
    private fun store() = WeatherStore(prefs, state) { url -> requests += url; response() }

    @Test fun fetchesWhenEmptyThenOnlyOnceTheReadingIsHalfAnHourOld() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        assertEquals(reading(code = 2), state.value)
        assertEquals(1, requests.size)
        store.update(seattle, false, t0 + 29 * minute, force = false)
        assertEquals(1, requests.size)
        store.update(seattle, false, t0 + 30 * minute, force = false)
        assertEquals(2, requests.size)
        assertEquals(t0 + 30 * minute, state.value?.fetchedAt)
    }

    @Test fun forceFetchesAFreshReadingAnyway() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        store.update(seattle, false, t0 + minute, force = true)
        assertEquals(2, requests.size)
    }

    @Test fun failureKeepsTheLastReadingAndBacksOff() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        response = { throw IOException("offline") }
        store.update(seattle, false, t0 + 31 * minute, force = false)
        assertEquals(2, requests.size)
        assertEquals(reading(code = 2), state.value)
        store.update(seattle, false, t0 + 40 * minute, force = false)
        assertEquals(2, requests.size)
        store.update(seattle, false, t0 + 41 * minute, force = false)
        assertEquals(3, requests.size)
        // A response that isn't a forecast counts as a failure too.
        response = { "{}" }
        store.update(seattle, false, t0 + 52 * minute, force = false)
        store.update(seattle, false, t0 + 53 * minute, force = false)
        assertEquals(4, requests.size)
    }

    @Test fun aFailureDoesntHoldBackANewPlace() {
        val store = store()
        response = { throw IOException("offline") }
        store.update(seattle, false, t0, force = false)
        response = { forecast }
        store.update(zurich, false, t0 + minute, force = false)
        assertEquals(2, requests.size)
        assertEquals(zurich, state.value?.place)
    }

    @Test fun anotherPlaceOrUnitDropsTheReadingBeforeFetching() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        response = { throw IOException("offline") }
        store.update(zurich, false, t0 + minute, force = false)
        assertNull(state.value)
        assertNull(prefs.getString("now", null))
        response = { forecast }
        store.update(zurich, false, t0 + 11 * minute, force = false)
        assertEquals(zurich, state.value?.place)
        // Switching to Fahrenheit fetches again at once, though the Celsius reading is a minute old.
        store.update(zurich, true, t0 + 12 * minute, force = false)
        assertEquals(4, requests.size)
        assertTrue(requests.last().endsWith("temperature_unit=fahrenheit"))
        assertEquals(reading(code = 2, fahrenheit = true, fetchedAt = t0 + 12 * minute, place = zurich), state.value)
    }

    @Test fun turningWeatherOffClearsTheReadingAndTheStoredCopy() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        assertNotNull(prefs.getString("now", null))
        store.update(null, false, t0 + minute, force = false)
        assertNull(state.value)
        assertNull(prefs.getString("now", null))
        assertEquals(1, requests.size)
    }

    /** A new process: memory is empty, and a new store reads the same file. */
    private fun restart(): WeatherStore {
        state.value = null
        return store()
    }

    @Test fun storedReadingShowsAfterARestartWithoutAFetch() {
        store().update(seattle, false, t0, force = false)
        val restarted = restart()
        restarted.load(t0 + 10 * minute)
        assertEquals(reading(code = 2), state.value)
        restarted.update(seattle, false, t0 + 10 * minute, force = false)
        assertEquals(1, requests.size)
    }

    @Test fun storedReadingHoursOldShowsUntilTheFetchReplacesIt() {
        store().update(seattle, false, t0, force = false)
        val restarted = restart()
        restarted.load(t0 + 2 * hour)
        assertEquals(reading(code = 2), state.value)
        restarted.update(seattle, false, t0 + 2 * hour, force = false)
        assertEquals(2, requests.size)
        assertEquals(t0 + 2 * hour, state.value?.fetchedAt)
    }

    @Test fun storedReadingOlderThanThreeHoursIsDropped() {
        store().update(seattle, false, t0, force = false)
        restart().load(t0 + 3 * hour)
        assertNull(state.value)
        assertNull(prefs.getString("now", null))
    }

    @Test fun readingHoursOldGoesOnTheNextRefreshEvenWhenTheFetchFails() {
        val store = store()
        store.update(seattle, false, t0, force = false)
        response = { throw IOException("offline") }
        store.update(seattle, false, t0 + 2 * hour, force = false)
        assertEquals(reading(code = 2), state.value)
        store.update(seattle, false, t0 + 3 * hour, force = false)
        assertNull(state.value)
        assertEquals(3, requests.size)
    }
}
