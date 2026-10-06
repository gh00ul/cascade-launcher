package com.gh00ul.cascade.testing

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/** The time zone screenshots render in. */
val FIXED_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/**
 * The instant fake data is relative to, and the time screenshots show: Monday, October 5 2026, 9:41 AM in
 * [FIXED_ZONE]. A fixed time keeps PNGs identical from run to run and from machine to machine.
 */
val FIXED_NOW: Long = ZonedDateTime.of(2026, 10, 5, 9, 41, 0, 0, FIXED_ZONE).toInstant().toEpochMilli()

/** Runs [block] with [FIXED_ZONE] as the JVM default time zone, then restores the previous one. */
inline fun <T> withFixedZone(block: () -> T): T {
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(FIXED_ZONE))
    try {
        return block()
    } finally {
        TimeZone.setDefault(saved)
    }
}
