package com.gh00ul.cascade.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.R
import com.gh00ul.cascade.util.LauncherActions

/*
 * Help with the two accesses Android makes hard to give: notification access, greyed out for an app that wasn't
 * installed from an app store, and the lock service, an accessibility service.
 */

/**
 * For when the user went to allow notification access and came back without it. From Android 13 the switch of an app
 * installed outside an app store is greyed out as a "restricted setting" until it's allowed from App info's menu, and
 * nothing on the switch's page says how. Callers show it only on Android 13+.
 */
@Composable
internal fun NotificationAccessHelp(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification access is still off") },
        text = {
            Text(
                "If Cascade's switch was greyed out, Android is blocking it because Cascade wasn't installed from an " +
                    "app store. Open Cascade's App info, tap ⋮, choose “Allow restricted settings”, then come back and " +
                    "tap Allow again.",
                // At a large font it can be taller than the dialog has room for.
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                LauncherActions.openOwnAppInfo(context)
                onDismiss()
            }) { Text("App info") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Why double-tap to lock needs Cascade's lock service, and the way to its switch. */
@Composable
internal fun LockServiceHelp(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // The name the accessibility list shows for the service (the manifest's label), so the two can't drift apart.
    val service = stringResource(R.string.lock_service_label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on the lock service") },
        text = {
            Text(
                "Android lets apps lock the screen only through an accessibility service. Cascade's reads nothing on " +
                    "your screen: it only locks it when you double-tap home.\n\nIn the list, open “$service” and turn it on. " +
                    "If Android says the setting is restricted, open Cascade's App info, tap ⋮, choose “Allow restricted " +
                    "settings”, and try again.",
                // At a large font it can be taller than the dialog has room for.
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                LauncherActions.openAccessibilitySettings(context)
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * A Settings page's way to Android's notification access switch. It notes the trip, and when the user comes back to
 * the page with access still off on Android 13+, [showHelp] asks for [NotificationAccessHelp].
 */
@Stable
internal class NotificationAccessRequest(askedState: MutableState<Boolean>, helpState: MutableState<Boolean>) {
    private var asked by askedState

    /** The user went to allow access and came back without it: show [NotificationAccessHelp]. */
    var showHelp by helpState
        private set

    fun open(context: Context) {
        // Only while it's off (coming back after turning it off on purpose needs no help), and only a trip that opened
        // the page.
        val off = !LauncherActions.hasNotificationAccess(context)
        asked = LauncherActions.openNotificationAccess(context) && off
    }

    fun dismissHelp() {
        showHelp = false
    }

    /** Back on the page: once per trip to the switch, check whether it worked. */
    fun onResume(context: Context) {
        if (!asked) return
        asked = false
        showHelp = Build.VERSION.SDK_INT >= 33 && !LauncherActions.hasNotificationAccess(context)
    }
}

/** A [NotificationAccessRequest] for the page, checked each time the page resumes. */
@Composable
internal fun rememberNotificationAccessRequest(): NotificationAccessRequest {
    val context = LocalContext.current
    // Saved: Android may recreate the page, or end the process, while the user is in system settings.
    val asked = rememberSaveable { mutableStateOf(false) }
    val help = rememberSaveable { mutableStateOf(false) }
    val request = remember(asked, help) { NotificationAccessRequest(asked, help) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { request.onResume(context) }
    return request
}
