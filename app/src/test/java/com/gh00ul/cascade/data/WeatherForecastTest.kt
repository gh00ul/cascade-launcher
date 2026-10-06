package com.gh00ul.cascade.data

import android.app.Application
import android.content.Context
import com.gh00ul.cascade.ui.home.widgets.forecastDays
import com.gh00ul.cascade.ui.home.widgets.forecastHours
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The forecast that comes in the same request as the current weather: the hours and days the weather widget shows,
 * what's kept of them, and when weather is fetched at all now that the widget wants it too.
 */
// Robolectric for the real org.json (on the plain JVM it is a stub that returns defaults) and SharedPreferences.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeatherForecastTest {
    private val seattle = WeatherPlace("Seattle, Washington, United States", 47.60621, -122.33207)
    private val pdt = ZoneOffset.ofHours(-7)
    /** October 6 2026, 9:30 AM in Seattle, when the response below was fetched. */
    private val now = at(6, 9, 30)

    /** Trimmed from a real response for Seattle: the hours from 7 AM, five days. */
    private val forecast = """
        {"latitude":47.595562,"longitude":-122.32443,"generationtime_ms":0.08,"utc_offset_seconds":-25200,
         "timezone":"America/Los_Angeles","timezone_abbreviation":"GMT-7","elevation":56.0,
         "current":{"time":"2026-10-06T09:30","interval":900,"temperature_2m":17.6,"weather_code":2,"is_day":1},
         "hourly_units":{"time":"iso8601","temperature_2m":"°C","weather_code":"wmo code","precipitation_probability":"%","is_day":""},
         "hourly":{
           "time":["2026-10-06T07:00","2026-10-06T08:00","2026-10-06T09:00","2026-10-06T10:00","2026-10-06T11:00",
                   "2026-10-06T12:00","2026-10-06T13:00","2026-10-06T14:00","2026-10-06T15:00","2026-10-06T16:00",
                   "2026-10-06T17:00","2026-10-06T18:00","2026-10-06T19:00","2026-10-06T20:00","2026-10-06T21:00",
                   "2026-10-06T22:00"],
           "temperature_2m":[13.1,14.4,16.0,17.6,18.9,19.5,null,20.4,20.0,19.1,17.8,16.2,15.0,14.1,13.5,13.0],
           "weather_code":[3,3,2,2,1,1,2,61,61,80,3,3,2,1,0,0],
           "precipitation_probability":[0,0,5,null,10,15,20,45,60,35,10,5,0,0,0,0],
           "is_day":[1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0]},
         "daily_units":{"time":"iso8601","weather_code":"wmo code","temperature_2m_max":"°C","temperature_2m_min":"°C"},
         "daily":{"time":["2026-10-06","2026-10-07","2026-10-08","2026-10-09","2026-10-10"],
           "weather_code":[61,3,null,80,0],
           "temperature_2m_max":[21.4,18.2,17.0,15.6,19.5],
           "temperature_2m_min":[11.5,10.4,9.9,8.6,7.4]}}
    """.trimIndent()

    /** October [day] 2026 at [hour]:[minute] in Seattle. */
    private fun at(day: Int, hour: Int, minute: Int = 0) = OffsetDateTime.of(2026, 10, day, hour, minute, 0, 0, pdt).toInstant().toEpochMilli()

    private fun day(day: Int) = LocalDate.of(2026, 10, day).toEpochDay()

    @Test fun keepsTheHoursFromTheOneUnderWay() {
        val hours = parseForecast(forecast, seattle, fahrenheit = false, now = now)!!.hours
        // 9 AM is under way at 9:30; 7 and 8 AM are over. 1 PM has no temperature, and twelve hours are kept.
        assertEquals(HOURS_KEPT, hours.size)
        assertEquals(listOf(9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21).map { at(6, it) }, hours.map { it.time })
        assertEquals(HourForecast(at(6, 9), 16, 2, isDay = true, precipitation = 5), hours[0])
        // A missing chance of rain is left out, not read as 0; temperatures round half up.
        assertEquals(HourForecast(at(6, 10), 18, 2, isDay = true, precipitation = null), hours[1])
        assertEquals(HourForecast(at(6, 12), 20, 1, isDay = true, precipitation = 15), hours[3])
        assertEquals(HourForecast(at(6, 15), 20, 61, isDay = true, precipitation = 60), hours[5])
        assertEquals(HourForecast(at(6, 19), 15, 2, isDay = false, precipitation = 0), hours[9])
    }

    @Test fun keepsTheDaysThatCameWhole() {
        val reading = parseForecast(forecast, seattle, fahrenheit = false, now = now)!!
        // October 8 has no weather code.
        assertEquals(
            listOf(DayForecast(day(6), 61, 21, 12), DayForecast(day(7), 3, 18, 10), DayForecast(day(9), 80, 16, 9), DayForecast(day(10), 0, 20, 7)),
            reading.days,
        )
        // Today's range still comes from the first day.
        assertEquals(21, reading.high)
        assertEquals(12, reading.low)
        assertEquals(18, reading.temperature)
    }

    @Test fun hoursAreInTheirPlacesOwnTime() {
        // The same local times from a place seven hours ahead of UTC are fourteen hours earlier.
        val bangkokish = forecast.replace("-25200", "25200")
        val hours = parseForecast(bangkokish, seattle, false, now - 14 * HOUR)!!.hours
        assertEquals(at(6, 9) - 14 * HOUR, hours.first().time)
    }

    @Test fun missingOrBrokenBlocksLeaveTheForecastOut() {
        val current = """{"current":{"temperature_2m":17.6,"weather_code":3}}"""
        val plain = parseForecast(current, seattle, false, now)!!
        assertTrue(plain.hours.isEmpty())
        assertTrue(plain.days.isEmpty())
        for (broken in listOf(
            forecast.replace("\"weather_code\":[3,3,2", "\"weather_code_\":[3,3,2"),
            forecast.replace("\"temperature_2m\":[13.1", "\"temperature_2m\":\"none\",\"x\":[13.1"),
            forecast.replace("\"2026-10-06T09:00\"", "\"9 AM\"").replace("\"2026-10-06T10:00\"", "null"),
        )) {
            val reading = parseForecast(broken, seattle, false, now)
            // The current weather still reads; only what broke is missing.
            assertEquals(18, reading?.temperature)
            assertTrue(reading!!.hours.none { it.time == at(6, 9) || it.time == at(6, 10) })
        }
        // Without its own day flags every hour counts as day.
        val noFlags = forecast.replace("\"is_day\":[1,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0]", "\"x\":[]")
        assertTrue(parseForecast(noFlags, seattle, false, now)!!.hours.all { it.isDay })
    }

    @Test fun storedForecastSurvivesARoundTrip() {
        val reading = parseForecast(forecast, seattle, fahrenheit = false, now = now)!!
        assertEquals(reading, parseWeather(weatherJson(reading)))
        // A reading stored before the forecast was fetched reads with none.
        val old = """{"temperature":18,"high":21,"low":12,"code":2,"isDay":true,"fahrenheit":false,"fetchedAt":$now,
            "place":{"name":"Seattle","lat":47.6,"lon":-122.3}}"""
        val parsed = parseWeather(old)!!
        assertTrue(parsed.hours.isEmpty() && parsed.days.isEmpty())
        // A broken hour or day is dropped; the rest stay.
        val broken = weatherJson(reading).replace("\"high\":18", "\"high\":\"warm\"")
        assertEquals(reading.days.size - 1, parseWeather(broken)!!.days.size)
    }

    @Test fun theStoreKeepsTheForecastAcrossARestart() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("forecast-test", Context.MODE_PRIVATE)
        val state = MutableStateFlow<WeatherNow?>(null)
        WeatherStore(prefs, state) { forecast }.update(seattle, false, now, force = false)
        val fetched = state.value!!
        assertEquals(HOURS_KEPT, fetched.hours.size)
        state.value = null
        WeatherStore(prefs, state) { error("no network after a restart") }.load(now + 10 * 60_000L)
        assertEquals(fetched, state.value)
    }

    @Test fun weatherIsWantedForTheReadoutOrTheWidget() {
        val base = LauncherSettings(weatherPlace = seattle)
        assertNull(Weather.wantedPlace(base))
        assertEquals(seattle, Weather.wantedPlace(base.copy(showWeather = true)))
        assertEquals(seattle, Weather.wantedPlace(base.copy(widgetStack = listOf(WIDGET_CALENDAR, WIDGET_WEATHER))))
        assertNull(Weather.wantedPlace(base.copy(widgetStack = listOf(WIDGET_CALENDAR))))
        // No place, nothing to fetch, whatever shows.
        assertNull(Weather.wantedPlace(LauncherSettings(showWeather = true, widgetStack = listOf(WIDGET_WEATHER))))
    }

    @Test fun theWidgetShowsTheHoursAheadAndTheDaysAfterToday() {
        val reading = parseForecast(forecast, seattle, fahrenheit = false, now = now)!!
        // At 9:30 the next hours start at 10; a reading 2 hours old still has five ahead.
        assertEquals(listOf(10, 11, 12, 14, 15).map { at(6, it) }, forecastHours(reading.hours, now).map { it.time })
        assertEquals(listOf(14, 15, 16, 17, 18).map { at(6, it) }, forecastHours(reading.hours, now + 2 * HOUR + 31 * 60_000L).map { it.time })
        assertEquals(emptyList<HourForecast>(), forecastHours(reading.hours, at(7, 0)))
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        assertEquals(listOf(day(7), day(9), day(10)), forecastDays(reading.days, now, zone).map { it.day })
        assertEquals(listOf(day(9), day(10)), forecastDays(reading.days, at(8, 23, 59), zone).map { it.day })
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
