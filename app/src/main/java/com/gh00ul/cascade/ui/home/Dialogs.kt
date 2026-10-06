package com.gh00ul.cascade.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.folderLabel
import com.gh00ul.cascade.data.homeFolders
import com.gh00ul.cascade.launcher

/** A folder an app can go to, as [AppActionsContent] lists it: its id, name, and the icons its own icon shows. */
@Immutable
data class FolderChoice(val id: String, val label: String, val icons: List<IconImage?>)

/** The home screen's folders as choices, in home order, with the icons of up to four of their apps that have one. */
internal fun folderChoices(settings: LauncherSettings, icons: Map<String, IconImage>): List<FolderChoice> =
    settings.homeFolders().map { (id, folder) -> FolderChoice(id, folderLabel(folder.name), folder.apps.mapNotNull { icons[it] }.take(4)) }

@Composable
fun RenameDialog(app: AppEntry, onDismiss: () -> Unit) {
    val prefs = LocalContext.current.launcher.prefs
    var text by remember { mutableStateOf(app.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(app.originalLabel) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.rename(app.key, text.takeIf { it.trim() != app.originalLabel })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    prefs.rename(app.key, null)
                    onDismiss()
                }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Names a folder: a new one ([initial] is the suggested name, selected so typing replaces it) or one being renamed.
 * A blank name saves as none, which shows as "Folder".
 */
@Composable
fun FolderNameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun confirm() {
        onConfirm(text.text.trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::confirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Play category of [app], for a new folder's suggested name; -1 when it has none or can't be read. */
internal fun appCategory(context: Context, app: AppEntry): Int = runCatching {
    context.getSystemService(LauncherApps::class.java).getApplicationInfo(app.packageName, 0, app.user).category
}.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)

/** Folder glyphs that aren't in material-icons-core (paths from Material Icons, Apache 2.0). */
internal object FolderGlyphs {
    val NewFolder = glyph(
        "CreateNewFolder",
        "M20,6h-8l-2,-2L4,4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2L22,8c0,-1.11 -0.89,-2 " +
            "-2,-2zM20,18L4,18L4,6h5.17l2,2L20,8v10zM12,14h2v2h2v-2h2v-2h-2v-2h-2v2h-2z",
    )
    val MoveToFolder = glyph(
        "DriveFileMove",
        "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V6h5.17" +
            "l2,2H20V18zM12.16,12H8v2h4.16l-1.59,1.59L11.99,17L16,13.01L11.99,9l-1.41,1.41L12.16,12z",
    )
    /** The outlined folder with a minus in it. */
    val FolderMinus = glyph(
        "FolderMinus",
        "M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM20,18H4V6h5.17" +
            "l2,2H20V18zM8,12h8v2H8z",
    )

    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
