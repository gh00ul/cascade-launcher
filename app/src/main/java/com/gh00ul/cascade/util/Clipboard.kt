package com.gh00ul.cascade.util

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

// EXTRA_IS_SENSITIVE is only a key in the clip's extras: Android 13+ honors it, older ones (and keyboards' clipboard
// suggestions on them) just ignore it, so it's set on every version rather than behind a version check.
/**
 * Puts [text] on the clipboard under [label]. Android 13+ confirms a copy itself, showing what was copied; before
 * that, a toast says "Copied". [sensitive] (a login code) has Android 13+ show dots in place of the text.
 */
@SuppressLint("InlinedApi")
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied ${if (sensitive) label.lowercase() else text}", Toast.LENGTH_SHORT).show()
}
