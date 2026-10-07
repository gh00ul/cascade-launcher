package com.gh00ul.cascade.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.edit

/** Where [rememberPermissionPrompt] notes each permission the user has refused at least once. */
private const val PROMPTS_FILE = "permission_prompts"

/**
 * A runtime permission request that knows when Android has stopped asking. After two refusals (or "Don't ask again"
 * before Android 11) a request fails at once, with no dialog, and only App info can allow the permission; callers send
 * the user there while [blocked].
 */
@Stable
internal class PermissionPrompt(private val blockedState: State<Boolean>, private val ask: () -> Unit) {
    /** Android won't show its dialog for this permission again: it can only be allowed in App info. */
    val blocked: Boolean get() = blockedState.value

    /** Asks Android for the permission; the answer goes to the onResult the prompt was made with. */
    fun launch() = ask()
}

/**
 * A [PermissionPrompt] for [permission]; [onResult] gets whether it was granted, after [PermissionPrompt.blocked] is
 * updated.
 */
@Composable
internal fun rememberPermissionPrompt(permission: String, onResult: (granted: Boolean) -> Unit): PermissionPrompt {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val blocked = rememberSaveable(permission) { mutableStateOf(false) }
    // Saved: the dialog can outlive the page (a rotation, or the process dying behind it), and the answer comes back
    // to the page made again.
    val rationaleBefore = rememberSaveable(permission) { mutableStateOf(false) }
    // A refusal with no rationale before or after: a closed dialog, or a block noted by no earlier session (one from
    // before this was kept). A second one in a row can only be the block, so it counts as one.
    val silentRefusal = rememberSaveable(permission) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationaleNow = activity.showsRationale(permission)
        val refusals = refusals(context)
        // Read without getBoolean, which throws if the file ever held something else under this key.
        val deniedOnce = refusals.all[permission] as? Boolean == true
        val silent = !granted && !rationaleNow && !rationaleBefore.value && !deniedOnce
        // A rationale due, before or after, means the user has refused once; so does a second silent refusal. Noted so
        // a later session knows it too.
        if ((rationaleNow || rationaleBefore.value || (silent && silentRefusal.value)) && !deniedOnce) {
            refusals.edit { putBoolean(permission, true) }
        }
        blocked.value = permissionBlocked(granted, rationaleBefore.value, rationaleNow, deniedOnce || (silent && silentRefusal.value))
        silentRefusal.value = silent
        onResult(granted)
    }
    return remember(permission, context, activity, launcher) {
        PermissionPrompt(blocked) {
            // Read before asking: afterwards it can't tell a closed dialog from a block.
            rationaleBefore.value = activity.showsRationale(permission)
            // Opened here rather than when the page is first drawn (home's first frame, for the calendar widget): the
            // file is then read in on Android's loader thread while the dialog is up, ready for the answer.
            refusals(context)
            launcher.launch(permission)
        }
    }
}

private fun refusals(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PROMPTS_FILE, Context.MODE_PRIVATE)

/**
 * Whether a request's answer means Android has stopped asking. A block leaves no rationale to show, but so does
 * closing the first dialog with Back or a tap outside, which refuses nothing; so it's a block only when the user had
 * refused before: a rationale was due right before this request ([rationaleBefore]), or a refusal was noted in an
 * earlier session or follows from a second silent refusal in a row ([deniedOnce]).
 */
internal fun permissionBlocked(granted: Boolean, rationaleBefore: Boolean, rationaleNow: Boolean, deniedOnce: Boolean): Boolean =
    !granted && !rationaleNow && (rationaleBefore || deniedOnce)

private fun Activity?.showsRationale(permission: String): Boolean =
    this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
