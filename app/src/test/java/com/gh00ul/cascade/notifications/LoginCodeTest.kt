package com.gh00ul.cascade.notifications

import android.app.Application
import android.content.ClipboardManager
import com.gh00ul.cascade.LauncherApplication
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Finding a login code in a notification: only where it says it's a code, and never an amount, a time or a phone number. */
class LoginCodeTest {
    private fun code(text: String, title: String = "") = findLoginCode(title, text)

    @Test fun commonShapes() {
        assertEquals("482913", code("482913 is your Instagram code. Don't share it."))
        assertEquals("482913", code("G-482913 is your Google verification code."))
        assertEquals("4821", code("Your Uber code: 4821. Never share this code with anyone."))
        assertEquals("123456", code("Use 123 456 to sign in to Microsoft"))
        assertEquals("123456", code("Your one-time passcode is 123-456"))
        assertEquals("0042", code("Use 0042 to log in"))
        assertEquals("98765432", code("Authentication code: 98765432"))
    }

    @Test fun theCodeCanBeInTheTitle() {
        assertEquals("334455", code("334455", title = "Verification code"))
    }

    @Test fun withoutACodeWordNothingCounts() {
        assertNull(code("Your order 12345 has shipped"))
        assertNull(code("Flight 4821 boards at gate 12"))
        assertNull(code("Your PIN has been changed"))
    }

    @Test fun amountsTimesDatesAndPhoneNumbersAreNotCodes() {
        assertNull(code("Your code: payment of $1234.56 received"))
        assertNull(code("Login at 10:30 from a new device"))
        assertNull(code("Sign in to review the 2026/10/06 statement"))
        assertNull(code("Call 206-555-0100 for help with your login"))
        assertNull(code("50% off with code SAVE50"))
        assertNull(code("Your verification code expires in 1,500 seconds"))
    }

    @Test fun theNumberNearestTheCodeWordWins() {
        assertEquals("7712", code("Your verification code is 7712. Reference 20261006."))
        assertEquals("778899", code("Expires at 10:30. Code: 778899"))
    }
}

/** Copying a login code as it arrives: once per notification, only while fresh, and only with the setting on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LauncherApplication::class)
class LoginCodeCopyTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun clip() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun notification(key: String, text: String, ageMs: Long = 0) =
        FakeNotifications.notification(key, "Google", text, ageMs).copy(code = findLoginCode("Google", text))

    @Test fun aFreshCodeIsCopiedOnce() {
        offerLoginCode(context, notification("copy|1", "G-482913 is your Google verification code"), now = FIXED_NOW)
        assertEquals("482913", clip())
        // Something else copied since; an update to the same notification doesn't take the clipboard back.
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("x", "mine"))
        offerLoginCode(context, notification("copy|1", "G-482913 is your Google verification code"), now = FIXED_NOW)
        assertEquals("mine", clip())
    }

    @Test fun anOldOneIsLeftAlone() {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("x", "mine"))
        offerLoginCode(context, notification("copy|2", "Your code is 551234", ageMs = 20 * 60_000L), now = FIXED_NOW)
        assertEquals("mine", clip())
    }

    @Test fun theSettingTurnsItOff() {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("x", "mine"))
        context.launcher.prefs.update { it.copy(copyLoginCodes = false) }
        offerLoginCode(context, notification("copy|3", "Your code is 661234"), now = FIXED_NOW)
        assertEquals("mine", clip())
        context.launcher.prefs.update { it.copy(copyLoginCodes = true) }
    }
}
