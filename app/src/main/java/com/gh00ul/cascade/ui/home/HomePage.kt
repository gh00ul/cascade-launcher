package com.gh00ul.cascade.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.sendFromLauncher
import java.text.SimpleDateFormat
import java.util.Date

/** The first screen: clock up top, favorites near the thumb. Fills the viewport, grows if favorites need more room. */
@Composable
fun HomePage(
    minHeight: Dp,
    bottomInset: Dp,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    notifications: Map<String, List<AppNotification>>,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenNotification: (AppEntry, AppNotification) -> Unit,
    onEmptyLongPress: () -> Unit,
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

    @Composable
    fun Player(state: NowPlayingState, app: AppEntry?, expandKey: String, expanded: Boolean) {
        val notificationsForApp = app?.let { notifications[it.notificationKey] }.orEmpty()
        MediaRow(
            state = state,
            appLabel = app?.label,
            icon = app?.let { icons[it.key] },
            notifications = notificationsForApp,
            showArt = settings.showIcons,
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
            .pointerInput(Unit) { detectTapGestures(onLongPress = { longPress() }) }
            .padding(start = 20.dp, end = 44.dp, top = 28.dp, bottom = bottomInset + 28.dp),
    ) {
        Clock(Modifier.padding(horizontal = 8.dp))
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
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Bottom) +
                fadeIn(tween(220, 90)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow), shrinkTowards = Alignment.Bottom) +
                fadeOut(tween(90)),
        ) {
            val sessionId = lastFloating.value?.sessionId ?: return@AnimatedVisibility
            AnimatedContent(
                targetState = sessionId,
                transitionSpec = { (fadeIn(tween(220, 90)) togetherWith fadeOut(tween(90))) using SizeTransform(clip = true) },
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

        if (favorites.isEmpty()) {
            Text(
                "Long-press any app to add it here.\nScroll down, or slide along the letters at the edge, to see all apps.",
                style = style.small,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        for (app in favorites) {
            key(app.key) {
                val hosting = media != null && app.key == hostKey
                val lastHosted = remember { Latest<NowPlayingState>() }.also { if (hosting) it.value = media }
                AnimatedContent(
                    targetState = hosting,
                    transitionSpec = {
                        (fadeIn(tween(220, 90)) togetherWith fadeOut(tween(90))) using
                            SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow) }
                    },
                    label = "favorite",
                ) { hosted ->
                    val hostedState = if (hosted) (media?.takeIf { app.key == hostKey } ?: lastHosted.value) else null
                    if (hostedState != null) {
                        Player(hostedState, app, "fav:${app.key}", expandedKey == "fav:${app.key}")
                    } else {
                        AppRow(
                            app = app,
                            icon = icons[app.key],
                            notifications = notifications[app.notificationKey].orEmpty(),
                            showIcon = settings.showIcons,
                            showPreview = settings.showNotificationPreviews,
                            large = true,
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

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                now = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = System.currentTimeMillis() }

    val locale = LocalConfiguration.current.locales[0]
    val is24h = DateFormat.is24HourFormat(context)
    val time = SimpleDateFormat(if (is24h) "H:mm" else "h:mm", locale).format(Date(now))
    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now))
    val alarm = remember(now) { context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }
    val style = LocalLauncherStyle.current

    Column(modifier) {
        Text(
            time,
            style = style.clock,
            modifier = Modifier.clickable(
                interactionSource = null, indication = null,
                onClickLabel = "Open alarms", role = Role.Button,
            ) { LauncherActions.openAlarms(context) },
        )
        Text(
            date,
            style = style.date,
            modifier = Modifier.clickable(
                interactionSource = null, indication = null,
                onClickLabel = "Open calendar", role = Role.Button,
            ) { LauncherActions.openCalendar(context) },
        )
        if (alarm != null) {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (is24h) "EEEHmm" else "EEEhmma")
            Text(
                "Alarm  ${SimpleDateFormat(pattern, locale).format(Date(alarm))}",
                style = style.small,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(
                        interactionSource = null, indication = null,
                        onClickLabel = "Open alarms", role = Role.Button,
                    ) {
                        // That exact alarm when the clock app offers it, else the alarm list.
                        val shown = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.showIntent?.sendFromLauncher(context)
                        if (shown != true) LauncherActions.openAlarms(context)
                    },
            )
        }
    }
}

@Composable
fun OnboardingCard(title: String, body: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val style = LocalLauncherStyle.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = style.scrim.copy(alpha = 0.55f),
        contentColor = style.content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = style.content.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = style.content.copy(alpha = 0.8f)) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun AllAppsHeader(onSearch: () -> Unit, onSettings: () -> Unit) {
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
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Search apps", color = style.content.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Launcher settings", tint = style.content)
        }
    }
}

@Composable
fun SectionHeader(letter: String) {
    Text(
        letter,
        style = LocalLauncherStyle.current.section,
        // Lets screen readers jump letter to letter with heading navigation.
        modifier = Modifier
            .semantics { heading() }
            .padding(start = 28.dp, top = 18.dp, bottom = 2.dp),
    )
}
