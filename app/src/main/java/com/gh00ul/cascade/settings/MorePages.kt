package com.gh00ul.cascade.settings

import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.SettingsBackup
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.hasContactsAccess
import com.gh00ul.cascade.data.renderTo
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.update.Updater
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.LockService
import com.gh00ul.cascade.util.rememberPermissionPrompt
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which gesture's app is being picked. */
private enum class GestureApp { SWIPE_DOWN, DOUBLE_TAP }

/** What swipes and taps on empty home space do, and whether touches vibrate. */
@Composable
internal fun GesturesPage(settings: LauncherSettings, apps: List<AppEntry>, icons: Map<String, IconImage>, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val setup = rememberSetupState()
    var explainLock by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<GestureApp?>(null) }
    val lockNeedsService = settings.doubleTapAction == DoubleTapAction.LOCK_SCREEN && !setup.lockEnabled
    val byKey = remember(apps) { apps.associateBy { it.key } }
    // The chosen app's name, or a prompt while none is chosen (or it's gone).
    fun appSummary(key: String?) = key?.let { byKey[it]?.label } ?: "Choose an app"

    SettingsPage(title = SettingsScreen.GESTURES.title, onBack = nav.back) {
        RadioGroup(
            title = "Swipe down on home",
            options = SwipeDownAction.entries,
            selected = settings.swipeDownAction,
            key = "swipeDown",
            label = { a ->
                when (a) {
                    SwipeDownAction.NOTIFICATIONS -> "Open notifications"
                    SwipeDownAction.QUICK_SETTINGS -> "Open quick settings"
                    SwipeDownAction.SEARCH -> "Search apps"
                    SwipeDownAction.OPEN_APP -> "Open an app"
                    SwipeDownAction.NOTHING -> "Nothing"
                }
            },
            summary = { a -> if (a == SwipeDownAction.OPEN_APP) appSummary(settings.swipeDownApp) else null },
        ) { a ->
            // Picking the app sets the choice; tapping it again changes the app.
            if (a == SwipeDownAction.OPEN_APP) picking = GestureApp.SWIPE_DOWN
            else prefs.update { it.copy(swipeDownAction = a) }
        }

        RadioGroup(
            title = "Double-tap empty space",
            // Locking needs Android 9's global action.
            options = DoubleTapAction.entries.filter { it != DoubleTapAction.LOCK_SCREEN || LockService.isSupported },
            selected = settings.doubleTapAction,
            key = "doubleTap",
            label = { a ->
                when (a) {
                    DoubleTapAction.NOTHING -> "Nothing"
                    DoubleTapAction.LOCK_SCREEN -> "Lock the screen"
                    DoubleTapAction.NOTIFICATIONS -> "Open notifications"
                    DoubleTapAction.SEARCH -> "Search apps"
                    DoubleTapAction.OPEN_APP -> "Open an app"
                }
            },
            summary = { a ->
                when {
                    a == DoubleTapAction.OPEN_APP -> appSummary(settings.doubleTapApp)
                    a != DoubleTapAction.LOCK_SCREEN -> null
                    setup.lockEnabled -> "Turns the screen off, like the power button"
                    else -> "Needs Cascade's lock service, turned on once"
                }
            },
        ) { a ->
            if (a == DoubleTapAction.OPEN_APP) {
                picking = GestureApp.DOUBLE_TAP
            } else {
                prefs.update { it.copy(doubleTapAction = a) }
                if (a == DoubleTapAction.LOCK_SCREEN && !setup.lockEnabled) explainLock = true
            }
        }
        AnimatedVisibility(lockNeedsService) {
            SettingsGroup {
                SettingRow(
                    title = "Turn on the lock service",
                    summary = "Double-tap won't lock the screen until it's on",
                    icon = Icons.Filled.Lock,
                    onClick = { explainLock = true },
                )
            }
        }

        SettingsGroup("Letter strip") {
            SwitchRow(
                "Second letters",
                "The letter under your finger opens up to its second letters, Ma, Me, Mu, to scroll through on the way to the next",
                settings.secondLetters,
                key = "secondLetters",
            ) { on -> prefs.update { it.copy(secondLetters = on, stripApps = it.stripApps && !on) } }
            // The strip opens up one way or the other: turning either on turns the other off.
            SwitchRow(
                "App names",
                "The letter under your finger opens up to its apps by name, Mail, Maps, Messages, to scroll through. Let go on one to open it",
                settings.stripApps,
                key = "stripApps",
            ) { on -> prefs.update { it.copy(stripApps = on, secondLetters = it.secondLetters && !on) } }
        }

        SettingsGroup("Feedback") {
            SwitchRow("Vibration", "On long-press, swipes, the letter strip and the player's buttons", settings.haptics, key = "haptics") { on ->
                prefs.update { it.copy(haptics = on) }
            }
        }
    }

    picking?.let { gesture ->
        AppPickerDialog(
            title = if (gesture == GestureApp.SWIPE_DOWN) "Swipe down opens" else "Double-tap opens",
            apps = apps,
            icons = icons,
            onPick = { app ->
                prefs.update {
                    when (gesture) {
                        GestureApp.SWIPE_DOWN -> it.copy(swipeDownAction = SwipeDownAction.OPEN_APP, swipeDownApp = app.key)
                        GestureApp.DOUBLE_TAP -> it.copy(doubleTapAction = DoubleTapAction.OPEN_APP, doubleTapApp = app.key)
                    }
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }

    if (explainLock) LockServiceHelp(onDismiss = { explainLock = false })
}

/** How search on home behaves, and what it finds besides apps. */
@Composable
internal fun SearchPage(settings: LauncherSettings, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    var contactsAllowed by remember { mutableStateOf(hasContactsAccess(context)) }
    // Sent to App info to allow access: turn contacts on once it's allowed there.
    var awaitingAppInfo by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        contactsAllowed = hasContactsAccess(context)
        if (contactsAllowed && awaitingAppInfo) prefs.update { it.copy(searchContacts = true) }
        awaitingAppInfo = false
    }
    // After "Don't allow" twice, Android stops asking and the request fails at once; send the user to App info then.
    val contactsPrompt = rememberPermissionPrompt(Manifest.permission.READ_CONTACTS) { granted ->
        contactsAllowed = granted
        prefs.update { it.copy(searchContacts = granted) }
    }

    SettingsPage(title = SettingsScreen.SEARCH.title, onBack = nav.back) {
        SettingsGroup {
            SwitchRow("Search the web", "Offers a web search for what you type; Go searches the web when no app matches", settings.searchWeb, key = "searchWeb") { on ->
                prefs.update { it.copy(searchWeb = on) }
            }
            SwitchRow("Include hidden apps", "Hidden apps still show up when you search for them", settings.hiddenInSearch, key = "hiddenInSearch") { on ->
                prefs.update { it.copy(hiddenInSearch = on) }
            }
            SwitchRow("Open single match", "Opens the app as soon as it's the only one matching what you type", settings.autoLaunchSingleMatch, key = "autoLaunch") { on ->
                prefs.update { it.copy(autoLaunchSingleMatch = on) }
            }
        }
        SettingsGroup("Beyond apps") {
            SwitchRow("Calculator", "Answers sums like 24 × 7 or 20% of 85 as you type. Tap the answer or press Go to copy it.", settings.searchCalculator, key = "calculator") { on ->
                prefs.update { it.copy(searchCalculator = on) }
            }
            SwitchRow(
                "Commands",
                "10m starts a timer, 7:30 sets an alarm, nav home gets directions, yt lofi or play a song searches the app",
                settings.searchCommands,
                key = "commands",
            ) { on -> prefs.update { it.copy(searchCommands = on) } }
            SwitchRow(
                "Shortcuts and settings",
                "Finds things inside apps, like incognito or a new message, and Settings pages, like Wi-Fi or hotspot",
                settings.searchShortcuts,
                key = "shortcuts",
            ) { on -> prefs.update { it.copy(searchShortcuts = on) } }
            SwitchRow(
                "Contacts",
                when {
                    contactsPrompt.blocked && !contactsAllowed -> "Contacts access is blocked. Tap to allow it in App info."
                    settings.searchContacts && !contactsAllowed -> "Contacts access is off. Allow it in App info."
                    else -> "Finds people as you type, with buttons to text or call them. Read only while search is open."
                },
                settings.searchContacts && contactsAllowed,
                key = "contacts",
            ) { on ->
                when {
                    on && !hasContactsAccess(context) && contactsPrompt.blocked -> {
                        awaitingAppInfo = true
                        LauncherActions.openOwnAppInfo(context)
                    }
                    on && !hasContactsAccess(context) -> contactsPrompt.launch()
                    else -> prefs.update { it.copy(searchContacts = on) }
                }
            }
        }
        PageText("Search opens from the search bar above the app list, or by swiping down on home if you set it in Gestures.")
    }
}

/** A backup is a few KB; the cap keeps a wrong pick (a video) from filling memory. */
private const val BACKUP_MAX_BYTES = 1 shl 20

/** Save every setting to a file, or load one back after a reset or on a new phone. */
@Composable
internal fun BackupPage(nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val backupRequest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = SettingsBackup.encode(prefs.settings.value)
        // The app's scope, not the screen's, so the write still finishes if Settings closes right after the picker.
        val app = context.launcher
        app.scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeNewDocument(app.contentResolver, uri, text.toByteArray()) }.getOrDefault(false)
            }
            Toast.makeText(app, if (ok) "Settings backed up" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    // The picked backup, saved so a rotation or theme switch mid-read or mid-confirmation doesn't drop it; the
    // parsed backup and what it restores to are read again from it.
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pending by remember { mutableStateOf<Pair<(LauncherSettings) -> LauncherSettings, LauncherSettings>?>(null) }
    val restoreRequest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }
    LaunchedEffect(pendingUri) {
        pending = null
        val uri = pendingUri ?: return@LaunchedEffect
        // A failure when the file can't be read; null text when it's too big to be a backup.
        val read = withContext(Dispatchers.IO) {
            runCatching {
                // No stream means the provider couldn't open it: as unreadable as an exception.
                val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No stream")
                stream.use { it.readTextAtMost(BACKUP_MAX_BYTES) }
            }
        }
        val text = read.getOrNull()
        // Off the main thread too: org.json recurses, so a pathologically nested file could overflow the stack. Parsed
        // once, here: Restore only merges the result onto the settings as they are then.
        val parsed = text?.let {
            withContext(Dispatchers.Default) {
                runCatching { SettingsBackup.parse(it)?.let { merge -> merge to merge(prefs.settings.value) } }.getOrNull()
            }
        }
        when {
            read.isFailure -> {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                pendingUri = null
            }
            // Too big to be a backup, or read but not one.
            parsed == null -> {
                Toast.makeText(context, "That file isn't a Cascade settings backup", Toast.LENGTH_SHORT).show()
                pendingUri = null
            }
            else -> pending = parsed
        }
    }
    val closeRestore = { pendingUri = null; pending = null }

    SettingsPage(title = SettingsScreen.BACKUP.title, onBack = nav.back) {
        PageText("A backup holds your favorites, hidden and renamed apps, and every setting here. Restore it after a reset or on a new phone.")
        SettingsGroup {
            SettingRow(
                title = "Back up settings",
                summary = "Save them to a file you choose",
                key = "backup",
                onClick = {
                    try {
                        backupRequest.launch(SettingsBackup.fileName(LocalDate.now()))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can save files", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            SettingRow(
                title = "Restore settings",
                summary = "Load a backup file; you'll see what changes first",
                key = "restore",
                // Some providers type .json files loosely; decode checks the content either way.
                onClick = {
                    try {
                        restoreRequest.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open files", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    pending?.let { (merge, preview) ->
        AlertDialog(
            onDismissRequest = closeRestore,
            title = { Text("Restore settings?") },
            text = { Text(SettingsBackup.summary(settings, preview)) },
            confirmButton = {
                TextButton(onClick = {
                    // Merged again onto the latest settings, in one write; the file isn't parsed again, so Prefs' lock
                    // is held on the main thread only for the merge.
                    prefs.update(merge)
                    Toast.makeText(context, "Settings restored", Toast.LENGTH_SHORT).show()
                    closeRestore()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = closeRestore) { Text("Cancel") } },
        )
    }
}

/**
 * Writes [bytes] to [uri], a document the picker has just created. "wt" first, which truncates; some providers
 * (possibly Google Drive) refuse that mode or hand back no stream, and then plain "w" is just as good, since the new
 * document is empty. False when neither mode gives a stream; a failed write throws.
 */
private fun writeNewDocument(resolver: ContentResolver, uri: Uri, bytes: ByteArray): Boolean {
    // Only the ways a provider says it doesn't support "wt"; anything else fails the backup, as before.
    val truncating = try {
        resolver.openOutputStream(uri, "wt")
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
    val out = truncating ?: resolver.openOutputStream(uri, "w") ?: return false
    out.use { it.write(bytes) }
    return true
}

/** The stream as UTF-8 text, or null when it's longer than [limit] bytes. Reads at most [limit] + 1 bytes. */
private fun InputStream.readTextAtMost(limit: Int): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() <= limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n < 0) return out.toByteArray().decodeToString()
        out.write(buffer, 0, n)
    }
    return null
}

private const val SOURCE_URL = "https://github.com/gh00ul/cascade-launcher"

/** The version and updates, what Cascade has been allowed to do, and where the code lives. */
@Composable
internal fun AboutPage(settings: LauncherSettings, nav: SettingsNav) {
    val context = LocalContext.current
    val prefs = context.launcher.prefs
    val setup = rememberSetupState()
    val roleRequest = rememberDefaultHomeRequest(setup)
    val accessRequest = rememberNotificationAccessRequest()
    var explainLock by rememberSaveable { mutableStateOf(false) }
    val update by Updater.state.collectAsStateWithLifecycle()
    val version = rememberVersionName()

    SettingsPage(title = SettingsScreen.ABOUT.title, onBack = nav.back) {
        AboutHeader(version, update)
        SettingsGroup("Updates") {
            SwitchRow("Check automatically", "Looks on GitHub when home opens, at most every 6 hours", settings.autoUpdateCheck, key = "autoUpdate") { on ->
                prefs.update { it.copy(autoUpdateCheck = on) }
            }
        }
        SettingsGroup("Permissions") {
            StatusRow(
                title = "Default home app",
                summary = if (setup.isDefault) "Cascade is your home screen" else "Tap to make Cascade your home screen",
                ok = setup.isDefault,
                key = "defaultHome",
            ) { LauncherActions.requestDefaultLauncher(context, roleRequest) }
            StatusRow(
                title = "Notification access",
                summary = if (setup.hasAccess) "Dots, previews and the music player are on" else "Needed for dots, previews and the music player",
                ok = setup.hasAccess,
                key = "notificationAccess",
            ) { accessRequest.open(context) }
            if (LockService.isSupported) {
                StatusRow(
                    title = "Lock service",
                    summary = if (setup.lockEnabled) "For double-tap to lock" else "Only needed for double-tap to lock",
                    ok = setup.lockEnabled,
                    required = false,
                ) {
                    // Off, it's explained first, as on the Gestures page; on, straight to its switch.
                    if (setup.lockEnabled) LauncherActions.openAccessibilitySettings(context) else explainLock = true
                }
            }
        }
        SettingsGroup {
            SettingRow(
                title = "Source code",
                summary = "github.com/gh00ul/cascade-launcher",
                key = "source",
                icon = SettingsIcons.Code,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No browser to open it in", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            SettingRow(title = "License", summary = "MIT. Free and open source.")
        }
    }

    if (accessRequest.showHelp) NotificationAccessHelp(onDismiss = accessRequest::dismissHelp)
    if (explainLock) LockServiceHelp(onDismiss = { explainLock = false })
}

/** Cascade's icon, name and version, the update status, and the one button that fits it. */
@Composable
private fun AboutHeader(version: String, update: Updater.State) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val icon = remember(iconPx) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).renderTo(iconPx).asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).highlight("update"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
        Text("Cascade", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Before any check the button says it all.
        if (update != Updater.State.Idle) {
            Text(
                updateSummary(update).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val modifier = Modifier.padding(top = 12.dp)
        when (update) {
            is Updater.State.Available -> Button(onClick = { Updater.install(context, update.release) }, modifier = modifier) {
                Text("Update to ${update.release.versionName}")
            }
            is Updater.State.Failed -> {
                Text(update.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                val release = update.release
                if (release != null) {
                    Button(onClick = { Updater.install(context, release) }, modifier = modifier) { Text("Try again") }
                } else {
                    OutlinedButton(onClick = { Updater.check(context, force = true) }, modifier = modifier) { Text("Check for updates") }
                }
            }
            is Updater.State.Downloading -> {
                val percent = update.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, strokeCap = StrokeCap.Round, modifier = modifier.width(200.dp))
                } else {
                    LinearProgressIndicator(strokeCap = StrokeCap.Round, modifier = modifier.width(200.dp))
                }
            }
            is Updater.State.Installing -> Unit
            else -> OutlinedButton(
                onClick = { Updater.check(context, force = true) },
                enabled = update != Updater.State.Checking,
                modifier = modifier,
            ) { Text("Check for updates") }
        }
    }
}

/**
 * A permission or service and whether it's on. A [required] one that's off shows a warning; an optional one just
 * reads Off. Tap to go turn it on.
 */
@Composable
private fun StatusRow(title: String, summary: String, ok: Boolean, key: String? = null, required: Boolean = true, onClick: () -> Unit) {
    SettingRow(
        title = title,
        summary = summary,
        key = key,
        onClick = onClick,
        trailing = {
            when {
                ok -> Icon(Icons.Filled.CheckCircle, contentDescription = "On", tint = MaterialTheme.colorScheme.primary)
                required -> Icon(Icons.Filled.Warning, contentDescription = "Off", tint = MaterialTheme.colorScheme.error)
                else -> Text("Off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
