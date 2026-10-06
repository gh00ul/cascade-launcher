package com.gh00ul.cascade.util

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Locks the screen for a double-tap on the home page, and does nothing else: from Android 9 an accessibility service
 * can lock the phone the way the power button does, without device admin. It asks for no events and can't read
 * windows (res/xml/lock_service.xml); the user turns it on in the system's accessibility settings.
 */
class LockService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        // Only while connected: cleared on unbind and destroy, so it never outlives the service.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LockService? = null

        @get:ChecksSdkIntAtLeast(api = 28)
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 28

        /** Locks the screen. False when the service isn't connected (turned off, or not yet bound) or below Android 9. */
        fun lock(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        /** Whether the user turned it on, from the secure setting, so it's right before the service connects too. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val component = ComponentName(context, LockService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }
    }
}
