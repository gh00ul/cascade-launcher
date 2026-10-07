package com.gh00ul.cascade.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.os.Build
import android.os.Process
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.data.TimeFormat
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.home.AppRow
import com.gh00ul.cascade.ui.home.ClockHeader
import com.gh00ul.cascade.ui.home.LocalNow
import com.gh00ul.cascade.ui.home.is24Hour
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.colorScheme
import com.gh00ul.cascade.ui.theme.rememberWallpaperSupportsDarkText
import java.text.SimpleDateFormat
import java.util.Date

/** How much smaller than life the preview draws the home screen. */
private const val PreviewScale = 0.6f

/** The home page's own width at most, so the preview's rows are as wide as on a phone and the wallpaper shows beside them. */
private val PhoneWidth = 420.dp

/** The card's height; a large font can make it taller. */
internal val PreviewHeight = 240.dp

/**
 * The home screen in miniature, live: the real clock header and favorite rows, drawn smaller over the wallpaper's
 * colors with the current text color, dimming, icons and clock settings, so a change shows before you go home. The
 * wallpaper itself can't be read without a storage permission, so its colors stand in for it. Touches never reach
 * the clock or rows; the whole card is one button when [onClick] is given.
 */
@Composable
internal fun HomePreview(
    settings: LauncherSettings,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val darkText = previewDarkText(settings.textColor)
    val configuration = LocalConfiguration.current
    val accent = remember(darkText, context, configuration) { colorScheme(context, dark = !darkText).primary }
    val style = remember(darkText, accent) { LauncherStyle(darkText, accent) }
    val wallpaper = rememberWallpaperBrush()
    val rows = remember(favorites) { favorites.take(2).ifEmpty { SampleApps } }
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = PreviewHeight)
            .clip(shape)
            .background(wallpaper)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clearAndSetSemantics {
                contentDescription = "Preview of your home screen"
                if (onClick != null) {
                    role = Role.Button
                    onClick("Change how it looks") { onClick(); true }
                }
            },
    ) {
        if (settings.wallpaperDim.alpha > 0f) Box(Modifier.matchParentSize().background(style.scrim.copy(alpha = settings.wallpaperDim.alpha)))
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * PreviewScale, density.fontScale),
            LocalLauncherStyle provides style,
            LocalContentColor provides style.content,
        ) {
            if (!settings.hideStatusBar) PreviewStatusBar(style, settings.timeFormat)
            // The card's height, with the clock at the top and the rows at the bottom; taller when a large font needs
            // more room (here in the preview's scaled dp), rather than cutting off the rows.
            Column(
                Modifier.heightIn(min = PreviewHeight / PreviewScale).widthIn(max = PhoneWidth).padding(top = StatusBarHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // The home page's own padding (HomePage), so the clock and rows sit where they will.
                ClockHeader(settings, Modifier.padding(start = 28.dp, end = 52.dp, top = 20.dp))
                Column(Modifier.padding(start = 20.dp, end = 44.dp, bottom = 20.dp)) {
                    rows.forEachIndexed { i, app ->
                        AppRow(
                            app = app,
                            icon = icons[app.key],
                            // One sample notification shows what previews (and the dot) look like.
                            notifications = if (i == 0) SampleNotification else emptyList(),
                            showIcon = settings.showIcons,
                            showPreview = settings.showNotificationPreviews,
                            large = true,
                            iconSize = settings.iconSize.homeDp.dp,
                            onClick = {},
                            onLongClick = {},
                            onNotificationClick = {},
                        )
                    }
                }
            }
        }
        // On top of everything, so no touch reaches the clock or the rows underneath.
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick)
                    else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ),
        )
    }
}

private val StatusBarHeight = 32.dp

/** A stand-in status bar (the time and a battery), so hiding the real one shows in the preview. */
@Composable
private fun PreviewStatusBar(style: LauncherStyle, format: TimeFormat) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val clock = LocalNow.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Ticks with the minute while Settings is visible, so it agrees with the clock below it.
    val now by produceState(clock(), lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = clock()
                delay(60_000 - value % 60_000)
            }
        }
    }
    val time = remember(format, locale, now / 60_000) {
        val is24h = is24Hour(format, DateFormat.is24HourFormat(context))
        SimpleDateFormat(if (is24h) "H:mm" else "h:mm", locale).format(Date(now))
    }
    Row(
        Modifier.fillMaxWidth().height(StatusBarHeight).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = TextStyle(color = style.content, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(width = 22.dp, height = 11.dp).border(1.5.dp, style.content.copy(alpha = 0.9f), RoundedCornerShape(3.dp))) {
            Box(Modifier.padding(2.dp).fillMaxHeight().width(13.dp).background(style.content.copy(alpha = 0.9f), RoundedCornerShape(1.dp)))
        }
    }
}

/** Whether the preview's text is dark: as on home, Automatic follows the wallpaper. */
@Composable
internal fun previewDarkText(textColor: TextColor): Boolean {
    val wallpaperDarkText = rememberWallpaperSupportsDarkText()
    return when (textColor) {
        TextColor.AUTO -> wallpaperDarkText
        TextColor.LIGHT -> false
        TextColor.DARK -> true
    }
}

/**
 * A gradient through the wallpaper's main colors (Android 8.1+ reports up to three without any permission), standing
 * in for the wallpaper image. Read once per screen: Settings is open for moments, and a new wallpaper opens it anew.
 */
@Composable
internal fun rememberWallpaperBrush(): Brush {
    val context = LocalContext.current
    return remember(context) {
        val stops = if (Build.VERSION.SDK_INT >= 27) {
            runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
                ?.let { listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor).map { color -> Color(color.toArgb()) } }
                .orEmpty()
        } else emptyList()
        when (stops.size) {
            0 -> Brush.linearGradient(DefaultWallpaper)
            1 -> Brush.linearGradient(listOf(stops[0], stops[0].copy(alpha = 1f).darker()))
            else -> Brush.linearGradient(stops)
        }
    }
}

private val DefaultWallpaper = listOf(Color(0xFF41507A), Color(0xFF232A40), Color(0xFF15181F))

private fun Color.darker() = Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha)

/** Rows to show when no favorite resolves to an app yet: names only, with the icons' placeholder circles. */
private val SampleApps = listOf("Phone", "Messages").mapIndexed { i, label ->
    AppEntry(
        key = "sample:$i",
        label = label,
        originalLabel = label,
        component = ComponentName("sample", "sample$i"),
        user = Process.myUserHandle(),
        isWork = false,
        isManagedProfile = false,
        section = label.take(1),
    )
}

private val SampleNotification = listOf(
    AppNotification(
        key = "preview",
        title = "Preview",
        text = "Your latest notification shows here",
        postTime = 0L,
        contentIntent = null,
        autoCancel = false,
        clearable = false,
    ),
)
