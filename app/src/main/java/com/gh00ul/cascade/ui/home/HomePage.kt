package com.gh00ul.cascade.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.key
import com.gh00ul.cascade.notifications.NowPlayingState
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.common.rememberEntry
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.sendFromLauncher
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The first screen: clock up top, favorites near the thumb. Fills the viewport, grows if favorites need more room.
 * [icons] and [notifications] are read entry by entry, inside each favorite, so a new icon map or a notification regroup
 * recomposes only the favorites whose own entry changed, not the page.
 */
@Composable
fun HomePage(
    minHeight: Dp,
    bottomInset: Dp,
    favorites: List<AppEntry>,
    showFavoritesHint: Boolean,
    icons: State<Map<String, IconImage>>,
    notifications: State<Map<String, List<AppNotification>>>,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenNotification: (AppEntry, AppNotification) -> Unit,
    onEmptyLongPress: () -> Unit,
    onEmptyDoubleTap: () -> Unit,
    expandedKey: String?,
    onToggleExpand: (String) -> Unit,
    media: NowPlayingState?,
    mediaApp: AppEntry?,
    mediaResting: Boolean,
    onOpenMedia: () -> Unit,
    onHideMedia: () -> Unit,
    onboarding: @Composable () -> Unit,
) {
    val style = LocalLauncherStyle.current
    val longPress by rememberUpdatedState(onEmptyLongPress)
    val doubleTap by rememberUpdatedState(onEmptyDoubleTap)
    // Keyed on this, so the gesture restarts as the setting changes; with no double-tap action, none is watched for.
    val doubleTapOn = settings.doubleTapAction != DoubleTapAction.NOTHING

    @Composable
    fun Player(state: NowPlayingState, app: AppEntry?, expandKey: String, expanded: Boolean) {
        val icon by rememberEntry(icons, app?.key, referentialEqualityPolicy())
        val notificationsForApp by rememberEntry(notifications, app?.notificationKey)
        MediaRow(
            state = state,
            appLabel = app?.label,
            icon = icon,
            notifications = notificationsForApp.orEmpty(),
            showArt = settings.showIcons,
            iconSize = settings.iconSize.homeDp.dp,
            // Settings disables Monochrome while icons are hidden, so it mustn't grey the player then either.
            monochrome = settings.showIcons && settings.monochromeIcons,
            resting = mediaResting,
            expanded = expanded,
            onOpen = onOpenMedia,
            // Sessions without a launchable app have no options sheet; long-press hides them instead.
            onLongClick = app?.let { { onAppLongPress(it) } } ?: onHideMedia.takeIf { !state.isPlaying },
            onLongClickLabel = if (app != null) "App options" else "Hide player",
            onToggleExpand = { onToggleExpand(expandKey) },
            onNotificationClick = { n -> app?.let { onOpenNotification(it, n) } },
            onHide = onHideMedia,
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pointerInput(doubleTapOn) {
                detectTapGestures(onDoubleTap = { _: Offset -> doubleTap() }.takeIf { doubleTapOn }, onLongPress = { longPress() })
            }
            .padding(start = 20.dp, end = 44.dp, top = 28.dp, bottom = bottomInset + 28.dp),
    ) {
        ClockHeader(settings, Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(20.dp))
        onboarding()
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(32.dp))

        // The playing app's favorite row becomes the player; if it isn't a favorite, a temporary row sits on top.
        val hostKey = media?.let { m -> favorites.firstOrNull { it.packageName == m.packageName && !it.isWork }?.key }
        val floating = media?.takeIf { hostKey == null }
        // Every temporary player expands as "media": tie that to the session it was opened on, so the next one starts collapsed.
        val mediaExpandedFor = remember { Latest<Int>() }.also {
            if (expandedKey != "media") it.value = null else if (it.value == null) it.value = floating?.sessionId
        }
        // A temporary player whose app becomes a favorite keeps its notifications open in that row.
        LaunchedEffect(hostKey) {
            if (hostKey != null && expandedKey == "media" && mediaExpandedFor.value == media?.sessionId) onToggleExpand("fav:$hostKey")
        }
        // Targets are small keys, not the state itself: AnimatedContent remembers every target it has seen.
        val lastFloating = remember { Latest<NowPlayingState>() }.also { if (floating != null) it.value = floating }
        val lastFloatingApp = remember { Latest<AppEntry>() }.also {
            if (floating != null) it.value = mediaApp?.takeIf { app -> app.packageName == floating.packageName }
        }
        AnimatedVisibility(
            visible = floating != null,
            enter = Motion.ExpandUp,
            exit = Motion.CollapseDown,
        ) {
            val sessionId = lastFloating.value?.sessionId ?: return@AnimatedVisibility
            AnimatedContent(
                targetState = sessionId,
                transitionSpec = { Motion.swap() },
                label = "floatingPlayer",
            ) { id ->
                val held = remember { Latest<NowPlayingState>() }
                val heldApp = remember { Latest<AppEntry>() }
                if (floating?.sessionId == id) {
                    held.value = floating
                    heldApp.value = mediaApp?.takeIf { it.packageName == floating.packageName }
                } else if (held.value == null && lastFloating.value?.sessionId == id) {
                    held.value = lastFloating.value
                    heldApp.value = lastFloatingApp.value
                }
                val state = held.value ?: return@AnimatedContent
                Player(state, heldApp.value, "media", expandedKey == "media" && mediaExpandedFor.value == state.sessionId)
            }
        }

        // First run: where favorites go and how to add one. It folds away once the first favorite lands. Shown while no
        // favorite resolves to an app, including when every stored one is missing (disabled, profile paused). Before the
        // first load it trusts the stored keys so it doesn't flash.
        AnimatedVisibility(
            visible = showFavoritesHint,
            enter = Motion.ExpandUp,
            exit = Motion.CollapseDown,
        ) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text("No favorites yet", style = style.app)
                Text(
                    "Long-press any app below to add it here. Scroll down, or slide along the letters, to see them all.",
                    style = style.small,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        for (app in favorites) {
            key(app.key) {
                val hosting = media != null && app.key == hostKey
                val lastHosted = remember { Latest<NowPlayingState>() }.also { if (hosting) it.value = media }
                AnimatedContent(
                    targetState = hosting,
                    transitionSpec = { Motion.swap() },
                    label = "favorite",
                ) { hosted ->
                    val hostedState = if (hosted) (media?.takeIf { app.key == hostKey } ?: lastHosted.value) else null
                    if (hostedState != null) {
                        Player(hostedState, app, "fav:${app.key}", expandedKey == "fav:${app.key}")
                    } else {
                        val icon by rememberEntry(icons, app.key, referentialEqualityPolicy())
                        val appNotifications by rememberEntry(notifications, app.notificationKey)
                        AppRow(
                            app = app,
                            icon = icon,
                            notifications = appNotifications.orEmpty(),
                            showIcon = settings.showIcons,
                            showPreview = settings.showNotificationPreviews,
                            large = true,
                            iconSize = settings.iconSize.homeDp.dp,
                            onClick = { onLaunch(app, it) },
                            onLongClick = { onAppLongPress(app) },
                            onNotificationClick = { onOpenNotification(app, it) },
                            expanded = expandedKey == "fav:${app.key}",
                            onToggleExpand = { onToggleExpand("fav:${app.key}") },
                        )
                    }
                }
            }
        }
    }
}

/** Cards on the home page share the player's corners. */
private val HomeCardShape = RoundedCornerShape(20.dp)

/**
 * The home page's card: title and body on the same see-through scrim and corners as the player, then [content]
 * (the buttons, or a progress bar). The onboarding prompts and the update card are all this card.
 */
@Composable
internal fun HomeCard(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    val style = LocalLauncherStyle.current
    Surface(
        shape = HomeCardShape,
        color = style.scrim.copy(alpha = 0.55f),
        contentColor = style.content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = style.content.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
            content()
        }
    }
}

/**
 * A [HomeCard]'s buttons: "Not now", then the action. The action is filled with the text color, like the player's
 * neutral play button, so it reads in both text modes; the Material primary is a pastel on the white card.
 */
@Composable
internal fun HomeCardActions(action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val style = LocalLauncherStyle.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("Not now", color = style.content.copy(alpha = 0.8f)) }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = style.content, contentColor = style.scrim),
        ) { Text(action) }
    }
}

@Composable
fun OnboardingCard(title: String, body: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    HomeCard(title, body) { HomeCardActions(action, onAction, onDismiss) }
}

/** Search and settings above the A–Z list. The pill's glyph and text line up with the rows' icons and labels below. */
@Composable
fun AllAppsHeader(iconSize: Dp, showIcons: Boolean, onSearch: () -> Unit, onSettings: () -> Unit) {
    val style = LocalLauncherStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 44.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = style.content.copy(alpha = 0.12f),
            contentColor = style.content,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Row(Modifier.padding(start = searchPillStart(showIcons), end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchGlyph(showIcons, iconSize)
                Text("Search apps", color = style.content.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Launcher settings", tint = style.content)
        }
    }
}

/**
 * A letter above its apps in the A–Z list. With icons it is centered on the rows' icon column, [iconSize] wide from
 * 28dp, so letters, icons and the search glyph share one axis; without icons it starts where the labels do.
 */
@Composable
fun SectionHeader(letter: String, iconSize: Dp, showIcon: Boolean) {
    Text(
        letter,
        style = LocalLauncherStyle.current.section,
        textAlign = if (showIcon) TextAlign.Center else TextAlign.Start,
        // Lets screen readers jump letter to letter with heading navigation.
        modifier = Modifier
            .semantics { heading() }
            .padding(start = 28.dp, top = 12.dp, bottom = 2.dp)
            .then(if (showIcon) Modifier.width(iconSize) else Modifier),
    )
}
