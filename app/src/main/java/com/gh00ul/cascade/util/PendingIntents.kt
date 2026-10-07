package com.gh00ul.cascade.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Sends a PendingIntent that opens another app's screen (a notification, a media player).
 * The launcher is in the foreground, so it vouches for the activity start. Returns false if the intent is dead.
 */
fun PendingIntent.sendFromLauncher(context: Context): Boolean {
    val options = ActivityOptions.makeBasic().apply {
        when {
            // Android 16 split "allowed" into if-visible and always; home only sends these while it's on screen.
            Build.VERSION.SDK_INT >= 36 ->
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            // Deprecated from Android 16 on, but the only way to opt in on 14 and 15.
            Build.VERSION.SDK_INT >= 34 -> @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }
    return try {
        send(context, 0, null, null, null, null, options.toBundle())
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }
}
