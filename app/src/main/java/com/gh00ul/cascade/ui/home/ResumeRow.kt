package com.gh00ul.cascade.ui.home

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.notifications.LastPlayed
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle

/**
 * Outputs you listen on privately or in the car: wired and USB headphones, Bluetooth audio and hearing aids. Not the
 * phone's speaker or earpiece, and not a Bluetooth call link (SCO). Constants, so older Androids just never report
 * the newer types.
 */
@SuppressLint("InlinedApi")
private val LISTENING_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

internal fun isListeningDevice(type: Int) = type in LISTENING_TYPES

/**
 * The ids of the connected listening devices ([LISTENING_TYPES]), or none while [enabled] is off. Watched only while
 * home is visible (BATTERY.md); each start reads what's connected then, so headphones paired while away still count
 * on the way back.
 */
@Composable
internal fun rememberListeningDevices(enabled: Boolean): Set<Int> {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(emptySet<Int>()) }
    LifecycleStartEffect(context, enabled) {
        if (!enabled) {
            devices = emptySet()
            return@LifecycleStartEffect onStopOrDispose {}
        }
        val audio = context.getSystemService(AudioManager::class.java)
        fun read() {
            devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isListeningDevice(it.type) }.mapTo(HashSet()) { it.id }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = read()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = read()
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        read()
        onStopOrDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return devices
}

private val ResumeShape = RoundedCornerShape(20.dp)

/**
 * Listen mode's row: "Resume Spotify" with the track it stopped on, on the resting player's neutral wash and with its
 * play button, so it reads as the player waiting to start. Tap to resume; long-press for "Not now". While [resuming],
 * the button spins until the player takes the row's place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResumeRow(
    appLabel: String,
    icon: IconImage?,
    played: LastPlayed,
    showIcon: Boolean,
    /** Icon size of the favorites around it, as for the player. */
    iconSize: Dp,
    monochrome: Boolean,
    resuming: Boolean,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalLauncherStyle.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val colors = remember(style, monochrome) { mediaColors(null, style, monochrome) }
    val track = listOf(played.title, played.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    val press = rememberPressIndication()

    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .drawBehind {
                val brush = Brush.horizontalGradient(
                    listOf(colors.washStart, colors.washEnd),
                    startX = if (rtl) size.width else 0f,
                    endX = if (rtl) 0f else size.width,
                )
                drawRoundRect(brush, cornerRadius = CornerRadius(20.dp.toPx()))
            }
            .clip(ResumeShape)
            .combinedClickable(
                interactionSource = null,
                indication = press ?: LocalIndication.current,
                hapticFeedbackEnabled = false,
                onClick = onResume,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                },
            )
            .pressScale(press)
            .clearAndSetSemantics {
                contentDescription = listOf("Resume $appLabel", track).filter { it.isNotBlank() }.joinToString(", ")
                onClick("Resume") { onResume(); true }
                customActions = listOf(CustomAccessibilityAction("Not now") { onDismiss(); true })
            }
            .heightIn(min = maxOf(64.dp, iconSize + 24.dp))
            .padding(start = if (showIcon) 4.dp else 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            // The player's art slot: the app's icon, as the player shows it before a cover arrives.
            AppIcon(icon, iconSize + 8.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Resume $appLabel", style = style.mediaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.ifEmpty { "Headphones connected" }, style = style.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.button), contentAlignment = Alignment.Center) {
            Icon(ExtraIcons.Play, contentDescription = null, tint = colors.onButton, modifier = Modifier.size(24.dp))
            if (resuming) {
                CircularProgressIndicator(Modifier.size(40.dp), color = colors.onButton.copy(alpha = 0.7f), strokeWidth = 2.dp, trackColor = Color.Transparent)
            }
        }
    }
}
