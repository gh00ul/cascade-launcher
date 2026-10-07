package com.gh00ul.cascade.testing

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivity

/**
 * The extras the permission controller answers with; PackageManager's names for them are system APIs. Activity reads
 * them in dispatchRequestPermissionsResult.
 */
private const val EXTRA_REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
private const val EXTRA_REQUEST_PERMISSIONS_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS"

/**
 * Answers the runtime permission request [activity] made last, as Android would once the user has [granted] it or not:
 * the permission is granted (or left denied), and the permission controller's screen, which requestPermissions started
 * for a result, returns the answer. It comes in through Activity.dispatchActivityResult, as on a phone, so the activity
 * stops waiting on that request (a request made while one is outstanding is cancelled at once, with an empty answer)
 * and ComponentActivity hands the answer on to the launcher that asked. Robolectric shows no dialog. Call on the main
 * thread (in runOnIdle). Returns the request it answered.
 */
fun answerPermissionRequest(activity: ComponentActivity, granted: Boolean): ShadowActivity.PermissionsRequest {
    val shadow = shadowOf(activity)
    val request = checkNotNull(shadow.lastRequestedPermission) { "Nothing asked for a permission" }
    val dialog = checkNotNull(shadow.nextStartedActivityForResult) { "No permission dialog was started" }
    check(dialog.requestCode == request.requestCode) { "The dialog started isn't for the last request" }
    val permissions = request.requestedPermissions
    if (granted) shadowOf(activity.application).grantPermissions(*permissions)
    val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    shadow.receiveResult(
        dialog.intent,
        Activity.RESULT_OK,
        Intent()
            .putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, permissions)
            .putExtra(EXTRA_REQUEST_PERMISSIONS_RESULTS, IntArray(permissions.size) { result }),
    )
    return request
}
