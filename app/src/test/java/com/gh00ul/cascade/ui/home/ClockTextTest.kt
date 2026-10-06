package com.gh00ul.cascade.ui.home

import android.app.Application
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FIXED_ZONE
import com.gh00ul.cascade.testing.withFixedZone
import com.gh00ul.cascade.util.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The clock header's chip text and what TalkBack says. Robolectric because DateFormat.getBestDateTimePattern needs
 * Android's ICU; the pattern is then formatted by the host JDK's SimpleDateFormat, as in the JVM screenshots. ICU's
 * en-US 12-hour patterns put a narrow no-break space (U+202F) before AM/PM, but Android swaps it for a plain space
 * for en and en-US (DateFormat.getCompatibleEnglishPattern), so plain spaces are expected here.
 *
 * Times are relative to [FIXED_NOW], Monday October 5 2026 at 9:41 AM, and formatted in [FIXED_ZONE].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ClockTextTest {
    private val locale = Locale.US

    @Test fun durationRoundsUpToWholeMinutes() {
        assertEquals("0 min", duration(0))
        assertEquals("1 min", duration(1))
        assertEquals("1 min", duration(MINUTE))
        assertEquals("2 min", duration(MINUTE + 1))
        assertEquals("25 min", duration(24 * MINUTE + 30 * SECOND))
        assertEquals("59 min", duration(59 * MINUTE))
        // Just under an hour rounds up into the hours form.
        assertEquals("1h 0m", duration(59 * MINUTE + 59 * SECOND))
        assertEquals("3h 10m", duration(3 * HOUR + 10 * MINUTE))
        assertEquals("25h 0m", duration(25 * HOUR))
    }

    @Test fun durationIgnoresTheSign() {
        assertEquals("25 min", duration(-(24 * MINUTE + 30 * SECOND)))
        assertEquals("1 min", duration(-1))
        assertEquals("3h 10m", duration(-(3 * HOUR + 10 * MINUTE)))
    }

    @Test fun spokenDurationUnits() {
        assertEquals("0 seconds", spokenDuration(0))
        assertEquals("0 seconds", spokenDuration(999))
        assertEquals("1 second", spokenDuration(SECOND))
        assertEquals("45 seconds", spokenDuration(45 * SECOND))
        assertEquals("59 seconds", spokenDuration(MINUTE - 1))
        assertEquals("1 minute", spokenDuration(MINUTE))
        // Seconds are only spoken under a minute.
        assertEquals("1 minute", spokenDuration(MINUTE + 30 * SECOND))
        assertEquals("2 minutes", spokenDuration(2 * MINUTE))
        assertEquals("1 hour", spokenDuration(HOUR))
        assertEquals("2 hours", spokenDuration(2 * HOUR + 30 * SECOND))
        assertEquals("1 hour 1 minute", spokenDuration(HOUR + MINUTE))
        assertEquals("3 hours 10 minutes", spokenDuration(3 * HOUR + 10 * MINUTE))
        assertEquals("45 seconds", spokenDuration(-45 * SECOND))
    }

    @Test fun clockPatterns() {
        assertEquals("h:mm a", clockPattern(locale, is24h = false, withDay = false))
        assertEquals("HH:mm", clockPattern(locale, is24h = true, withDay = false))
        assertEquals("EEE h:mm a", clockPattern(locale, is24h = false, withDay = true))
        assertEquals("EEE HH:mm", clockPattern(locale, is24h = true, withDay = true))
    }

    @Test fun alarmWithinADayShowsTimeAndCountdown() = withFixedZone {
        val trigger = FIXED_NOW + 7 * HOUR + 25 * MINUTE
        assertEquals("5:06 PM  ·  in 7h 25m" to "5:06 PM, in 7 hours 25 minutes", alarmText(trigger, FIXED_NOW, false, locale))
        assertEquals("17:06  ·  in 7h 25m" to "17:06, in 7 hours 25 minutes", alarmText(trigger, FIXED_NOW, true, locale))
        val almostADay = FIXED_NOW + 24 * HOUR - MINUTE
        assertEquals("9:40 AM  ·  in 23h 59m" to "9:40 AM, in 23 hours 59 minutes", alarmText(almostADay, FIXED_NOW, false, locale))
    }

    @Test fun alarmADayOrMoreAwayOrPastShowsTheWeekday() = withFixedZone {
        val tomorrow = FIXED_NOW + 24 * HOUR
        assertEquals("Tue 9:41 AM" to "Tue 9:41 AM", alarmText(tomorrow, FIXED_NOW, false, locale))
        assertEquals("Tue 09:41" to "Tue 09:41", alarmText(tomorrow, FIXED_NOW, true, locale))
        val past = FIXED_NOW - MINUTE
        assertEquals("Mon 9:40 AM" to "Mon 9:40 AM", alarmText(past, FIXED_NOW, false, locale))
    }

    /** TIME_TICK lands a few ms after the minute: TalkBack must say the chip's rounded-up minutes, not one less. */
    @Test fun alarmSpokenMinutesMatchTheChip() = withFixedZone {
        val trigger = FIXED_NOW + 25 * MINUTE
        val tick = FIXED_NOW + 3 * SECOND
        assertEquals("10:06 AM  ·  in 25 min" to "10:06 AM, in 25 minutes", alarmText(trigger, tick, false, locale))
        val soon = FIXED_NOW + 30 * SECOND
        assertEquals("9:41 AM  ·  in 1 min" to "9:41 AM, in 1 minute", alarmText(soon, FIXED_NOW, false, locale))
    }

    @Test fun allDayEventSaysToday() = withFixedZone {
        val offsite = CalendarEvent(1, "Offsite", at(5, 0, 0), at(6, 0, 0), allDay = true)
        assertEquals(Triple("Offsite", "today", "today"), eventText(offsite, FIXED_NOW, false, locale))
    }

    @Test fun ongoingEventEndingTodayShowsTheEndTime() = withFixedZone {
        val e = event(at(5, 9, 30), at(5, 10, 30))
        assertEquals(Triple(TITLE, "until 10:30 AM", "until 10:30 AM"), eventText(e, FIXED_NOW, false, locale))
        assertEquals(Triple(TITLE, "until 10:30", "until 10:30"), eventText(e, FIXED_NOW, true, locale))
        // Beginning right now counts as under way.
        val starting = event(FIXED_NOW, at(5, 10, 30))
        assertEquals(Triple(TITLE, "until 10:30 AM", "until 10:30 AM"), eventText(starting, FIXED_NOW, false, locale))
    }

    /** "Today" is now's day, not the system clock's: Robolectric's own clock is not set to [FIXED_NOW] here. */
    @Test fun ongoingEventEndingTomorrowShowsTheWeekday() = withFixedZone {
        val e = event(at(5, 9, 0), at(6, 1, 0))
        assertEquals(Triple(TITLE, "until Tue 1:00 AM", "until Tue 1:00 AM"), eventText(e, FIXED_NOW, false, locale))
        assertEquals(Triple(TITLE, "until Tue 01:00", "until Tue 01:00"), eventText(e, FIXED_NOW, true, locale))
        // Late in the evening, an end just past midnight is tomorrow too.
        val late = event(at(5, 23, 0), at(6, 0, 30))
        assertEquals(Triple(TITLE, "until Tue 12:30 AM", "until Tue 12:30 AM"), eventText(late, at(5, 23, 30), false, locale))
    }

    @Test fun eventWithinTheHourCountsDown() = withFixedZone {
        val e = event(FIXED_NOW + 25 * MINUTE, FIXED_NOW + 55 * MINUTE)
        assertEquals(Triple(TITLE, "in 25 min", "in 25 minutes"), eventText(e, FIXED_NOW, false, locale))
        // The same minutes on a tick that lands just after the minute.
        assertEquals(Triple(TITLE, "in 25 min", "in 25 minutes"), eventText(e, FIXED_NOW + 3 * SECOND, false, locale))
        // Rounding up turns 59m30s into an hour.
        val almost = event(FIXED_NOW + 59 * MINUTE + 30 * SECOND, FIXED_NOW + 2 * HOUR)
        assertEquals(Triple(TITLE, "in 1h 0m", "in 1 hour"), eventText(almost, FIXED_NOW, false, locale))
    }

    @Test fun eventLaterTodayShowsTheBareTime() = withFixedZone {
        val inAnHour = event(FIXED_NOW + HOUR, FIXED_NOW + 2 * HOUR)
        assertEquals(Triple(TITLE, "10:41 AM", "10:41 AM"), eventText(inAnHour, FIXED_NOW, false, locale))
        val afternoon = event(at(5, 13, 0), at(5, 14, 0))
        assertEquals(Triple(TITLE, "1:00 PM", "1:00 PM"), eventText(afternoon, FIXED_NOW, false, locale))
        assertEquals(Triple(TITLE, "13:00", "13:00"), eventText(afternoon, FIXED_NOW, true, locale))
        // Just after midnight, this morning is today.
        val morning = event(at(6, 8, 0), at(6, 9, 0))
        assertEquals(Triple(TITLE, "8:00 AM", "8:00 AM"), eventText(morning, at(6, 0, 10), false, locale))
    }

    @Test fun eventTomorrowShowsTheWeekday() = withFixedZone {
        val e = event(at(6, 8, 0), at(6, 9, 0))
        assertEquals(Triple(TITLE, "Tue 8:00 AM", "Tue 8:00 AM"), eventText(e, FIXED_NOW, false, locale))
        assertEquals(Triple(TITLE, "Tue 08:00", "Tue 08:00"), eventText(e, FIXED_NOW, true, locale))
        // Late in the evening, an event just past midnight is tomorrow's.
        val night = event(at(6, 0, 45), at(6, 1, 15))
        assertEquals(Triple(TITLE, "Tue 12:45 AM", "Tue 12:45 AM"), eventText(night, at(5, 23, 30), false, locale))
    }

    @Test fun batteryCharged() {
        assertEquals("Charged" to "Battery charged", batteryText(Battery(100, charging = true, fullInMs = -1)))
        assertEquals("Charged" to "Battery charged", batteryText(Battery(100, charging = true, fullInMs = 5 * MINUTE)))
    }

    @Test fun batteryChargingWithTimeToFull() {
        assertEquals(
            "62%  ·  full in 48 min" to "Charging, 62 percent, full in 48 minutes",
            batteryText(Battery(62, charging = true, fullInMs = 48 * MINUTE)),
        )
        // Spoken in the chip's rounded-up minutes.
        assertEquals(
            "62%  ·  full in 48 min" to "Charging, 62 percent, full in 48 minutes",
            batteryText(Battery(62, charging = true, fullInMs = 47 * MINUTE + 30 * SECOND)),
        )
        assertEquals(
            "80%  ·  full in 2h 5m" to "Charging, 80 percent, full in 2 hours 5 minutes",
            batteryText(Battery(80, charging = true, fullInMs = 2 * HOUR + 5 * MINUTE)),
        )
    }

    @Test fun batteryChargingWithUnknownTimeToFull() {
        assertEquals("62%  ·  charging" to "Charging, 62 percent", batteryText(Battery(62, charging = true, fullInMs = -1)))
        assertEquals("62%  ·  charging" to "Charging, 62 percent", batteryText(Battery(62, charging = true, fullInMs = 0)))
    }

    @Test fun batteryLowWhileNotCharging() {
        assertEquals("12% battery" to "Battery low, 12 percent", batteryText(Battery(12, charging = false, fullInMs = -1)))
    }

    private fun event(begin: Long, end: Long) = CalendarEvent(7, TITLE, begin, end, allDay = false)

    /** October [day] 2026 at [hour]:[minute] in [FIXED_ZONE]. */
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, FIXED_ZONE).toInstant().toEpochMilli()

    private companion object {
        const val TITLE = "Design review"
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
    }
}
