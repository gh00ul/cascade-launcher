package com.gh00ul.cascade.ui.home.widgets

import com.gh00ul.cascade.ui.common.rememberReplaySkip
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.data.WIDGET_CALENDAR
import com.gh00ul.cascade.data.WIDGET_WEATHER
import com.gh00ul.cascade.data.WeatherKind
import com.gh00ul.cascade.data.WidgetHost
import com.gh00ul.cascade.data.addWidget
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.home.weatherIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.min
import kotlin.math.roundToInt

/** An Android app widget the picker offers: its provider, name, and size in home screen cells ("4×2"). */
@Immutable
internal class WidgetOption(val info: AppWidgetProviderInfo, val label: String, val size: String)

/** An app's widgets, under the app's name and icon. */
@Immutable
internal class WidgetApp(val key: String, val label: String, val icon: ImageBitmap?, val widgets: List<WidgetOption>)

/**
 * Every app widget installed, in every profile, grouped by app (a work app apart from its personal twin, with the work
 * badge on its icon) and sorted by name. Blocking: call off the main thread.
 */
internal fun loadWidgetCatalog(context: Context): List<WidgetApp> {
    val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val pm = context.packageManager
    val density = context.resources.displayMetrics.density
    val iconPx = (40 * density).roundToInt()
    val profiles = runCatching { context.getSystemService(UserManager::class.java).userProfiles }.getOrNull() ?: listOf(Process.myUserHandle())
    val collator = Collator.getInstance()
    return profiles.flatMap { user ->
        val providers = runCatching { manager.getInstalledProvidersForProfile(user) }.getOrNull().orEmpty()
        providers.groupBy { it.provider.packageName }.map { (pkg, infos) ->
            val app = runCatching { launcherApps.getApplicationInfo(pkg, 0, user) }.getOrNull()
            val icon = app?.let { runCatching { pm.getUserBadgedIcon(it.loadIcon(pm), user).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull() }
            WidgetApp(
                key = "$pkg@${user.hashCode()}",
                label = app?.loadLabel(pm)?.toString()?.trim()?.ifEmpty { null } ?: pkg,
                icon = icon,
                widgets = infos.map { WidgetOption(it, it.loadLabel(pm)?.trim().orEmpty().ifEmpty { "Widget" }, cellSize(it, density)) }
                    .sortedWith(compareBy(collator) { it.label }),
            )
        }
    }.sortedWith(compareBy(collator) { it.label })
}

/** "4×2": [info]'s size in home screen cells, as the widget asks for it, or else worked out from its minimum size. */
internal fun cellSize(info: AppWidgetProviderInfo, density: Float): String {
    if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
        return "${info.targetCellWidth}×${info.targetCellHeight}"
    }
    return "${cells(info.minWidth / density)}×${cells(info.minHeight / density)}"
}

/** Home screen cells for a widget's minimum span in dp: n cells take 70n − 30 dp, Android's sizing rule. */
internal fun cells(dp: Float): Int = ((dp + 30f) / 70f).roundToInt().coerceAtLeast(1)

/** [info]'s preview image (else its icon), scaled to fit [maxWidth] by [maxHeight] px. Blocking: call off the main thread. */
private fun loadPreview(context: Context, info: AppWidgetProviderInfo, maxWidth: Int, maxHeight: Int): ImageBitmap? = runCatching {
    val drawable = info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) ?: return null
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) return@runCatching drawable.toBitmap(maxHeight, maxHeight).asImageBitmap()
    val scale = min(maxWidth / w.toFloat(), maxHeight / h.toFloat()).coerceAtMost(1f)
    drawable.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)).asImageBitmap()
}.getOrNull()

/** The installed app widgets, loaded off the main thread once [load] is true; null until then. */
@Composable
internal fun rememberWidgetCatalog(load: Boolean): List<WidgetApp>? {
    val context = LocalContext.current
    val catalog by produceState<List<WidgetApp>?>(null, load) {
        if (load && value == null) value = withContext(Dispatchers.IO) { loadWidgetCatalog(context) }
    }
    return catalog
}

/** Adds to the stack: [builtIn] takes WIDGET_CALENDAR or WIDGET_WEATHER, [app] an app widget. */
internal class WidgetAdder(val builtIn: (String) -> Unit, val app: (AppWidgetProviderInfo) -> Unit)

/**
 * Adding to the stack. A built-in widget goes in at once. An app widget is bound first (asking the user once when
 * Cascade isn't allowed to bind it yet) and set up through its own screen when it has one; it goes in when that's done,
 * and a cancelled step leaves nothing behind. Works on home and in Settings (see WidgetHost.onResume for the
 * difference).
 */
@Composable
internal fun rememberWidgetAdder(): WidgetAdder {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val width = stackPageWidthDp()
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (activity != null && !WidgetHost.onBindResult(activity, result.resultCode)) setupFailed(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { WidgetHost.onPause() }
    // The resume replayed when this first appears isn't a return from a setup screen.
    val replayedResume = rememberReplaySkip(Lifecycle.State.RESUMED)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!replayedResume.consume()) WidgetHost.onResume(activity ?: context) }
    return remember(context, activity, width, bind) {
        WidgetAdder(
            builtIn = { entry -> context.launcher.prefs.update { it.copy(widgetStack = addWidget(it.widgetStack, entry)) } },
            app = { info ->
                if (activity != null) {
                    when (val start = WidgetHost.begin(context, info, width, StackHeight.value.toInt())) {
                        WidgetHost.Begin.Bound -> if (!WidgetHost.setUp(activity)) setupFailed(context)
                        // The dialog can't open (no app on this phone handles it): the id is freed, and nothing is added.
                        is WidgetHost.Begin.Ask -> runCatching { bind.launch(start.intent) }.onFailure {
                            WidgetHost.onBindResult(activity, Activity.RESULT_CANCELED)
                            addFailed(context)
                        }
                        WidgetHost.Begin.Failed -> addFailed(context)
                    }
                }
            },
        )
    }
}

private fun setupFailed(context: Context) = Toast.makeText(context, "Couldn't open the widget's setup", Toast.LENGTH_SHORT).show()

private fun addFailed(context: Context) = Toast.makeText(context, "Couldn't add the widget", Toast.LENGTH_SHORT).show()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val PreviewWidth = 96.dp
private val PreviewHeight = 64.dp
private val PreviewShape = RoundedCornerShape(12.dp)

/**
 * The picker: Cascade's calendar and weather, then every app's widgets under the app's name, each with its preview and
 * size. A built-in widget already in [stack] can't be picked again. [catalog] is null while it loads. Drawn in the
 * surrounding theme (the dark sheet on home, Settings' own), on the sheet's 24dp edge.
 */
@Composable
internal fun WidgetPickerContent(
    stack: List<String>,
    catalog: List<WidgetApp>?,
    onBack: (() -> Unit)?,
    onPickBuiltIn: (String) -> Unit,
    onPickApp: (AppWidgetProviderInfo) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
) {
    LazyColumn(modifier, contentPadding = contentPadding) {
        item(key = "title") {
            Row(Modifier.padding(start = if (onBack != null) 8.dp else 24.dp, end = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Spacer(Modifier.width(4.dp))
                }
                Text("Add a widget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
        }
        builtIns(stack, onPickBuiltIn)
        if (catalog == null) {
            item(key = "loading") { PickerNote("Finding your apps' widgets…") }
        } else if (catalog.isEmpty()) {
            item(key = "none") { PickerNote("No apps with widgets are installed.") }
        }
        for (app in catalog.orEmpty()) {
            item(key = "app:${app.key}", contentType = "app") { AppHeader(app) }
            items(app.widgets, key = { "${app.key}/${it.info.provider.className}" }, contentType = { "widget" }) { option ->
                PickerRow(
                    title = option.label,
                    summary = option.size,
                    onClick = { onPickApp(option.info) },
                ) { WidgetPreview(option.info) }
            }
        }
    }
}

private fun LazyListScope.builtIns(stack: List<String>, onPick: (String) -> Unit) {
    item(key = "cascade") { PickerLabel("Cascade") }
    item(key = WIDGET_CALENDAR) {
        val added = WIDGET_CALENDAR in stack
        PickerRow(
            title = "Calendar",
            summary = if (added) "Already in the stack" else "Your agenda for today and the next two days",
            enabled = !added,
            onClick = { onPick(WIDGET_CALENDAR) },
        ) { BuiltInPreview(ExtraIcons.Event) }
    }
    item(key = WIDGET_WEATHER) {
        val added = WIDGET_WEATHER in stack
        PickerRow(
            title = "Weather",
            summary = if (added) "Already in the stack" else "Now, the hours ahead and the days to come",
            enabled = !added,
            onClick = { onPick(WIDGET_WEATHER) },
        ) { BuiltInPreview(weatherIcon(WeatherKind.PARTLY_CLOUDY, isDay = true)) }
    }
}

@Composable
private fun PickerLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() }.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** An app's name over its widgets, with its icon on the previews' column. */
@Composable
private fun AppHeader(app: WidgetApp) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A widget to pick: its [preview] in a fixed box, its name and [summary]. Dimmed and inert when not [enabled]. */
@Composable
private fun PickerRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = "Add to the stack", onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(PreviewWidth, PreviewHeight).clip(PreviewShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BuiltInPreview(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
}

/** The widget's own preview image, loaded when its row first shows. */
@Composable
private fun WidgetPreview(info: AppWidgetProviderInfo) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val preview by produceState<ImageBitmap?>(null, info) {
        val (w, h) = with(density) { PreviewWidth.roundToPx() to PreviewHeight.roundToPx() }
        value = withContext(Dispatchers.IO) { loadPreview(context, info, w, h) }
    }
    preview?.let { Image(it, contentDescription = null, modifier = Modifier.padding(4.dp)) }
}
