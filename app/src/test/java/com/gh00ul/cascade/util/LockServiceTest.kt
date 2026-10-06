package com.gh00ul.cascade.util

import android.app.Application
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [LockService.isEnabled] reads the system's list of enabled services, so it's right before the service connects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LockServiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val component get() = "${context.packageName}/com.gh00ul.cascade.util.LockService"

    private fun enable(services: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
    }

    @Test fun offWhileNothingIsEnabled() {
        assertFalse(LockService.isEnabled(context))
        enable("")
        assertFalse(LockService.isEnabled(context))
    }

    @Test fun onWhenListedAmongOtherServices() {
        enable("com.example.reader/com.example.reader.ReaderService:$component:com.example.keys/.KeyService")
        assertTrue(LockService.isEnabled(context))
    }

    @Test fun matchesTheShortFormInAnyCase() {
        enable("${context.packageName}/.util.LockService")
        assertTrue(LockService.isEnabled(context))
        enable(component.uppercase())
        assertTrue(LockService.isEnabled(context))
    }

    @Test fun similarNamesDontCount() {
        enable("${component}2:com.example/com.gh00ul.cascade.util.LockService")
        assertFalse(LockService.isEnabled(context))
    }

    @Test fun lockFailsWhileNotConnected() {
        enable(component)
        assertFalse(LockService.lock())
        assertTrue(LockService.isSupported)
    }
}
