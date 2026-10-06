package com.gh00ul.cascade.data

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.provider.AlarmClock
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FIXED_ZONE
import com.gh00ul.cascade.testing.FakeApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * What search does besides finding apps: commands ("10m", "7:30", "nav home", "yt lofi"), Settings pages and app
 * shortcuts. Times are from Monday 9:41 AM ([FIXED_NOW]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchCommandsTest {
    private fun parse(q: String) = parseCommand(q, FIXED_NOW, FIXED_ZONE)

    private fun alarmAt(q: String): String? = (parse(q) as? Command.Alarm)?.let {
        val at = Instant.ofEpochMilli(it.nextAt).atZone(FIXED_ZONE)
        "${at.dayOfWeek.name.take(3)} %02d:%02d".format(it.hour, it.minute).also { _ -> assertEquals(it.hour, at.hour) }
    }

    @Test fun timers() {
        assertEquals(Command.Timer(600), parse("10m"))
        assertEquals(Command.Timer(600), parse("10 min"))
        assertEquals(Command.Timer(5400), parse("1h30m"))
        assertEquals(Command.Timer(5400), parse("1h 30m"))
        assertEquals(Command.Timer(90), parse("90s"))
        assertEquals(Command.Timer(300), parse("timer 5"))
        assertEquals(Command.Timer(120), parse("Timer for 2 minutes"))
        assertNull("A bare number is a number", parse("10"))
        assertNull(parse("0m"))
        assertNull("More than a day", parse("25h"))
    }

    @Test fun alarmsComeAtTheNextSuchTime() {
        // 7:30 this morning is gone, so 7:30 means this evening; 10:15 is still to come this morning.
        assertEquals("MON 19:30", alarmAt("7:30"))
        assertEquals("MON 10:15", alarmAt("10:15"))
        assertEquals("TUE 07:30", alarmAt("7:30am"))
        assertEquals("MON 18:00", alarmAt("6pm"))
        assertEquals("MON 19:00", alarmAt("alarm 7"))
        assertEquals("MON 19:30", alarmAt("19:30"))
        assertEquals("TUE 07:30", alarmAt("07:30"))
        assertEquals("MON 12:30", alarmAt("12:30"))
        assertNull(parse("13:75"))
        assertNull("A bare number is a number", parse("7"))
        assertNull(parse("2048"))
    }

    @Test fun keywordCommandsKeepWhatWasTyped() {
        assertEquals(Command.Directions("Pike Place Market"), parse("navigate to Pike Place Market"))
        assertEquals(Command.Directions("home"), parse("nav home"))
        assertEquals(Command.MapSearch("coffee"), parse("maps coffee"))
        assertEquals(Command.SiteSearch(Site.YOUTUBE, "lofi beats"), parse("yt  lofi beats"))
        assertEquals(Command.SiteSearch(Site.WIKIPEDIA, "Kotlin"), parse("w Kotlin"))
        assertEquals(Command.SiteSearch(Site.GITHUB, "compose"), parse("gh compose"))
        assertEquals(Command.Play("Blinding Lights"), parse("play Blinding Lights"))
    }

    @Test fun nothingElseIsACommand() {
        for (q in listOf("", "maps", "play", "yt", "Phone", "24*7", "Calendar", "nav")) assertNull(q, parse(q))
    }

    @Test fun onlyTimersAndAlarmsWinOverApps() {
        assertTrue(parse("10m")!!.exact)
        assertTrue(parse("7:30")!!.exact)
        assertFalse("\"play store\" is the Play Store", parse("play store")!!.exact)
        assertFalse(parse("yt lofi")!!.exact)
    }

    @Test fun intents() {
        val timer = commandIntents(Command.Timer(600)).single()
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(600, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertTrue(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))

        val alarm = commandIntents(parse("6pm")!!).single()
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action)
        assertEquals(18, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, 0))

        val directions = commandIntents(Command.Directions("Pike Place"))
        assertEquals("google.navigation:q=Pike%20Place", directions.first().dataString)
        assertEquals("geo:0,0?q=Pike%20Place", directions.last().dataString)

        assertEquals(
            "https://www.youtube.com/results?search_query=lofi%20beats",
            commandIntents(Command.SiteSearch(Site.YOUTUBE, "lofi beats")).single().dataString,
        )

        val play = commandIntents(Command.Play("Blinding Lights"), player = "com.spotify.music")
        assertEquals("The last player first", "com.spotify.music", play.first().`package`)
        assertNull("then whichever app takes it", play.last().`package`)
    }

    @Test fun settingsPages() {
        assertEquals("Hotspot & tethering", findSettingsPages("hotspot").first().title)
        assertEquals("Wi-Fi", findSettingsPages("wi").first().title)
        assertEquals("Do Not Disturb", findSettingsPages("dnd").first().title)
        assertEquals(listOf("Battery", "Battery Saver"), findSettingsPages("batt").map { it.title })
        assertTrue("Not for one letter", findSettingsPages("b").isEmpty())
        assertEquals("Two letters match titles only", listOf("Cast"), findSettingsPages("ca").map { it.title })
        assertTrue("Not from inside a word", findSettingsPages("ooth").isEmpty())
    }

    @Test fun shortcutsMatchFromAWordsStart() {
        val context = RuntimeEnvironment.getApplication()
        fun shortcut(id: String, label: String, long: String = "") = FoundShortcut(
            ShortcutInfo.Builder(context, id).setShortLabel(label).setIntent(Intent(Intent.ACTION_VIEW)).build(),
            label,
            long,
            FakeApps.byLabel("Browser"),
        )
        val all = listOf(shortcut("tab", "New tab"), shortcut("incognito", "Incognito", "New incognito tab"), shortcut("history", "History"))
        assertEquals(listOf("Incognito"), matchShortcuts(all, "inc").map { it.label })
        assertEquals("Its long label counts too", listOf("New tab", "Incognito"), matchShortcuts(all, "new").map { it.label })
        assertTrue("Not from inside a word", matchShortcuts(all, "cog").isEmpty())
        assertTrue("Not for one letter", matchShortcuts(all, "h").isEmpty())
    }
}
