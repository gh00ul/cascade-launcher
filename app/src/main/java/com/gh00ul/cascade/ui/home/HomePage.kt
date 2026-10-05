package com.gh00ul.cascade.ui.home

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.ui.theme.LauncherText
import com.gh00ul.cascade.util.LauncherActions
import java.text.SimpleDateFormat
import java.util.Date

/** The first screen: clock up top, favorites near the thumb. Fills the viewport, grows if favorites need more room. */
@Composable
fun HomePage(
    minHeight: Dp,
    bottomInset: Dp,
    favorites: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    notifications: Map<String, List<AppNotification>>,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onOpenNotification: (AppEntry, AppNotification) -> Unit,
    onEmptyLongPress: () -> Unit,
    onboarding: @Composable () -> Unit,
) {
    val longPress by rememberUpdatedState(onEmptyLongPress)
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
        if (favorites.isEmpty()) {
            Text(
                "Long-press any app to add it here.\nScroll down, or slide along the letters on the right, to see all apps.",
                style = LauncherText.small,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        for (app in favorites) {
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
            )
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

    Column(modifier) {
        Text(
            time,
            style = LauncherText.clock,
            modifier = Modifier.clickable(interactionSource = null, indication = null) { LauncherActions.openAlarms(context) },
        )
        Text(
            date,
            style = LauncherText.date,
            modifier = Modifier.clickable(interactionSource = null, indication = null) { LauncherActions.openCalendar(context) },
        )
        if (alarm != null) {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (is24h) "EEEHmm" else "EEEhmma")
            Text(
                "Alarm  ${SimpleDateFormat(pattern, locale).format(Date(alarm))}",
                style = LauncherText.small,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(interactionSource = null, indication = null) { LauncherActions.openAlarms(context) },
            )
        }
    }
}

@Composable
fun OnboardingCard(title: String, body: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.5f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Not now", color = Color.White.copy(alpha = 0.8f)) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun AllAppsHeader(onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 44.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Search apps", color = Color.White.copy(alpha = 0.75f))
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Launcher settings", tint = Color.White)
        }
    }
}

@Composable
fun SectionHeader(letter: String) {
    Text(
        letter,
        style = LauncherText.section.copy(color = MaterialTheme.colorScheme.primary),
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 2.dp),
    )
}
