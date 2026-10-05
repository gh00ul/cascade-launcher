package com.gh00ul.cascade.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build

/**
 * Sends a PendingIntent that opens another app's screen (a notification, a media player).
 * The launcher is in the foreground, so it vouches for the activity start. Returns false if the intent is dead.
 */
@Suppress("DEPRECATION")
fun PendingIntent.sendFromLauncher(context: Context): Boolean {
    val options = ActivityOptions.makeBasic().apply {
        if (Build.VERSION.SDK_INT >= 34) {
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
