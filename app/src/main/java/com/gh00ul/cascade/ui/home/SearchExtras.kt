package com.gh00ul.cascade.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.Calculation
import com.gh00ul.cascade.data.Command
import com.gh00ul.cascade.data.Contact
import com.gh00ul.cascade.data.FoundShortcut
import com.gh00ul.cascade.data.SettingsPage
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.data.commandIntents
import com.gh00ul.cascade.data.contactInitial
import com.gh00ul.cascade.data.findContacts
import com.gh00ul.cascade.data.loadContactPhoto
import com.gh00ul.cascade.data.loadSearchShortcuts
import com.gh00ul.cascade.data.matchShortcuts
import com.gh00ul.cascade.data.renderTo
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/*
 * What search finds besides apps: the calculator's answer and commands, app shortcuts and Settings pages, and contacts.
 */

/**
 * The calculator's answer, at the top of search: "= 168" large, with the question under it. Tap (or Go) copies the
 * answer. With icons, the "=" sits on the icon column, so the answer starts where app names do.
 */
@Composable
internal fun CalculationRow(calculation: Calculation, showIcon: Boolean, iconSize: Dp, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val style = LocalLauncherStyle.current
    val press = rememberPressIndication()
    // Made once, not on every keystroke that changes the answer.
    val glyph = remember(style) { style.favorite.copy(color = style.content.copy(alpha = 0.7f)) }
    val autoSize = remember(style) { TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.favorite.fontSize) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(EnterTargetShape)
            .clickable(interactionSource = null, indication = press ?: LocalIndication.current, onClickLabel = "Copy", onClick = onCopy)
            .pressScale(press)
            // One item for TalkBack: "24 times 7 equals 168", then "double tap to copy".
            .semantics { contentDescription = calculation.spoken }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The texts are left out of the semantics: the row's description says it all, in words.
        if (showIcon) {
            Box(Modifier.size(iconSize).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text("=", style = glyph)
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            // Shrinks rather than cutting a long answer short.
            BasicText(
                if (showIcon) calculation.display else "= ${calculation.display}",
                style = style.favorite,
                maxLines = 1,
                autoSize = autoSize,
            )
            Text(calculation.expression, style = style.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Copies [calculation]'s answer. */
internal fun copyAnswer(context: Context, calculation: Calculation) = copyToClipboard(context, "Answer", calculation.plain)

/** A contact search found, with its photo when it has one and icons show. */
@Immutable
internal data class ContactResult(val contact: Contact, val photo: ImageBitmap?)

/** A pause in typing before contacts are looked up: the provider is another process, so not on every keystroke. */
private const val CONTACTS_DEBOUNCE_MS = 150L

/**
 * Contacts matching [query], looked up once typing pauses and only while search is [open] and [enabled]; each
 * keystroke cancels the lookup before it. Photos are decoded off the main thread at [photoPx] (none at 0) before the
 * rows show, so they never pop in, and kept for the rest of this search, so a contact still matching isn't decoded
 * again. Closing search keeps what's shown while it fades out.
 */
@Composable
internal fun rememberContactResults(query: String, enabled: Boolean, open: Boolean, photoPx: Int): List<ContactResult> {
    val context = LocalContext.current
    var results by remember { mutableStateOf(emptyList<ContactResult>()) }
    // Read and written on the main thread only: the IO work hands its decodes back.
    val photos = remember { HashMap<String, ImageBitmap?>() }
    LaunchedEffect(query, enabled, open, photoPx) {
        val q = query.trim()
        if (!enabled || q.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        if (!open) return@LaunchedEffect
        delay(CONTACTS_DEBOUNCE_MS)
        val found = findContacts(context, q)
        val missing = if (photoPx <= 0) emptyList() else found.mapNotNull { it.photo }.filter { it !in photos }
        if (missing.isNotEmpty()) {
            photos += withContext(Dispatchers.IO) { missing.associateWith { loadContactPhoto(context, it, photoPx) } }
        }
        results = found.map { c -> ContactResult(c, c.photo?.takeIf { photoPx > 0 }?.let { photos[it] }) }
    }
    return results
}

/**
 * A contact in search: its photo (or initial) on the icon column and its name, then Message and Call when it has a
 * number. Tap the row to open the contact.
 */
@Composable
internal fun ContactRow(
    result: ContactResult,
    showIcon: Boolean,
    iconSize: Dp,
    onOpen: () -> Unit,
    onMessage: (String) -> Unit,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLauncherStyle.current
    val contact = result.contact
    val phone = contact.phone
    val press = rememberPressIndication()
    Row(
        modifier
            .fillMaxWidth()
            .clip(EnterTargetShape)
            .clickable(interactionSource = null, indication = press ?: LocalIndication.current, onClickLabel = "Open contact", onClick = onOpen)
            .pressScale(press)
            .padding(start = 8.dp, end = if (phone == null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An app row's padding, on this part only: the buttons' touch targets fit the row's height as they are.
        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                ContactBadge(contact, result.photo, iconSize)
                Spacer(Modifier.width(16.dp))
            }
            Text(contact.name, style = style.app, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (phone != null) {
            IconButton(onClick = { onMessage(phone) }) { Icon(SearchIcons.Message, contentDescription = "Message ${contact.name}") }
            IconButton(onClick = { onCall(phone) }) { Icon(Icons.Filled.Call, contentDescription = "Call ${contact.name}") }
        }
    }
}

/** The contact's photo in a circle the size of an app icon, or its initial (a person without one) on a soft disc. */
@Composable
private fun ContactBadge(contact: Contact, photo: ImageBitmap?, size: Dp) {
    if (photo != null) {
        Image(
            photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    val style = LocalLauncherStyle.current
    // Decoration: TalkBack reads the name beside it, not the letter.
    Box(Modifier.size(size).background(style.content.copy(alpha = 0.16f), CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val initial = remember(contact.name) { contactInitial(contact.name) }
        if (initial != null) {
            // In proportion to the disc at any font scale.
            val fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() }
            Text(initial, color = style.content, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
        } else {
            Icon(Icons.Filled.Person, contentDescription = null, tint = style.content, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** Starts [intent] from search; false, after showing [failure], when no app takes it. */
internal fun startFromSearch(context: Context, intent: Intent, failure: String): Boolean {
    val started = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
    if (!started) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
    return started
}

/*
 * Commands, app shortcuts and Settings pages in search.
 */

/**
 * A row of search's that isn't an app: [icon] on the icon column (or before the title without icons), [title] in the
 * app rows' style, and [detail] under it. Tapping runs [onClick].
 */
@Composable
internal fun ActionRow(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    showIcon: Boolean,
    iconSize: Dp,
    clickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLauncherStyle.current
    val press = rememberPressIndication()
    Row(
        modifier
            .fillMaxWidth()
            .clip(EnterTargetShape)
            .clickable(interactionSource = null, indication = press ?: LocalIndication.current, onClickLabel = clickLabel, onClick = onClick)
            .pressScale(press)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (showIcon) iconSize else 24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(if (showIcon) 16.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = style.app, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = style.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A glyph on an action row's icon column, in the text color a step back from full. */
@Composable
internal fun ActionGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = LocalLauncherStyle.current.content.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
}

/** A command's row: what it will do, and where. [playerLabel] names the music app a [Command.Play] goes to. */
@Composable
internal fun CommandRow(
    command: Command,
    showIcon: Boolean,
    iconSize: Dp,
    playerLabel: String?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cascade's 12/24-hour setting, which the home clock follows too. */
    timeFormat: TimeFormat = TimeFormat.SYSTEM,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val now = LocalNow.current()
    val (title, detail) = when (command) {
        is Command.Timer -> "Start a ${timerLength(command.seconds)} timer" to "Clock"
        is Command.Alarm -> {
            val is24h = is24Hour(timeFormat, DateFormat.is24HourFormat(context))
            val time = SimpleDateFormat(clockPattern(locale, is24h, withDay = false), locale).format(Date(command.nextAt))
            "Set an alarm for $time" to "Clock · in ${duration(command.nextAt - now)}"
        }
        is Command.Directions -> "Directions to ${command.place}" to "Maps"
        is Command.MapSearch -> "Search Maps for “${command.query}”" to "Maps"
        is Command.SiteSearch -> "Search ${command.site.label} for “${command.query}”" to command.site.label
        is Command.Play -> "Play “${command.query}”" to (playerLabel ?: "Your music app")
    }
    val glyph = when (command) {
        is Command.Timer -> ExtraIcons.Timer
        is Command.Alarm -> ExtraIcons.Alarm
        is Command.Directions -> ExtraIcons.Navigation
        is Command.MapSearch -> Icons.Filled.Place
        is Command.SiteSearch -> Icons.Filled.Search
        is Command.Play -> Icons.Filled.PlayArrow
    }
    ActionRow({ ActionGlyph(glyph) }, title, detail, showIcon, iconSize, clickLabel = "Run", onClick = onRun, modifier = modifier)
}

/** "10 min", "1 h 30 min", "45 sec": a timer's length as its row says it. */
internal fun timerLength(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 }?.let { "$it sec" },
    ).joinToString(" ")
}

/**
 * Runs [command]: the first of its intents an app takes. A timer or alarm is set without opening the clock, so a toast
 * says it's done (and its chip shows under the clock). Returns whether something ran.
 */
internal fun runCommand(context: Context, command: Command, player: String?): Boolean {
    val ran = commandIntents(command, player).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    val done = when (command) {
        is Command.Timer -> "Timer started: ${timerLength(command.seconds)}"
        is Command.Alarm -> "Alarm set"
        else -> null
    }
    when {
        !ran -> Toast.makeText(context, "No app can do that", Toast.LENGTH_SHORT).show()
        done != null -> Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
    }
    return ran
}

/** A shortcut search found, with its icon when it has one and icons show. */
@Immutable
internal class ShortcutResult(val shortcut: FoundShortcut, val icon: ImageBitmap?)

/**
 * App shortcuts matching [query]. They're all read once, when search opens with [enabled] on; each query then only
 * matches them. Icons are drawn off the main thread at [iconPx] (none at 0) before the rows show, and kept for the
 * rest of this search.
 */
@Composable
internal fun rememberShortcutResults(query: String, enabled: Boolean, open: Boolean, apps: List<AppEntry>, iconPx: Int): List<ShortcutResult> {
    val context = LocalContext.current
    val densityDpi = LocalConfiguration.current.densityDpi
    var all by remember { mutableStateOf<List<FoundShortcut>?>(null) }
    var results by remember { mutableStateOf(emptyList<ShortcutResult>()) }
    // Read and written on the main thread only: the IO work hands its icons back.
    val icons = remember { HashMap<String, ImageBitmap?>() }
    LaunchedEffect(enabled, open) {
        if (enabled && open && all == null) all = withContext(Dispatchers.IO) { loadSearchShortcuts(context, apps) }
    }
    LaunchedEffect(query, all, enabled) {
        val found = if (!enabled) emptyList() else matchShortcuts(all.orEmpty(), query)
        val missing = if (iconPx <= 0) emptyList() else found.filter { it.id !in icons }
        if (missing.isNotEmpty()) {
            icons += withContext(Dispatchers.IO) {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                missing.associate { s ->
                    s.id to runCatching { launcherApps.getShortcutIconDrawable(s.info, densityDpi)?.renderTo(iconPx)?.asImageBitmap() }.getOrNull()
                }
            }
        }
        results = found.map { ShortcutResult(it, icons[it.id]) }
    }
    return results
}

/** Opens a shortcut search found; false, after a toast, when its app won't start it. */
internal fun openShortcut(context: Context, shortcut: FoundShortcut): Boolean = try {
    context.getSystemService(LauncherApps::class.java).startShortcut(shortcut.info, null, null)
    true
} catch (e: Exception) {
    Toast.makeText(context, "Couldn't open ${shortcut.label}", Toast.LENGTH_SHORT).show()
    false
}

/** Opens [page]: the first of its actions this phone has. */
internal fun openSettingsPage(context: Context, page: SettingsPage): Boolean {
    val opened = page.intents().any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    if (!opened) Toast.makeText(context, "Couldn't open ${page.title}", Toast.LENGTH_SHORT).show()
    return opened
}

/** Icons search needs that aren't in material-icons-core (paths from Material Icons, Apache 2.0). */
internal object SearchIcons {
    /** Material's "Sms": a speech bubble with three dots. */
    val Message = ImageVector.Builder("Message", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM9,11H7V9h2v2z" +
                    "M13,11h-2V9h2v2zM17,11h-2V9h2v2z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
