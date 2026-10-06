package com.gh00ul.cascade.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.update.Updater

/** Home screen card for a Cascade update: offer it, show the download, then hand over to Android's installer. */
@Composable
fun UpdateCard(state: Updater.State, onUpdate: (Updater.Release) -> Unit, onDismiss: (Updater.Release) -> Unit) {
    val style = LocalLauncherStyle.current
    val (title, body, release) = when (state) {
        is Updater.State.Available -> Triple(
            "Cascade ${state.release.versionName} is available",
            "Tap Update to download and install it. The home screen restarts on the new version.",
            state.release,
        )
        is Updater.State.Downloading -> Triple(
            "Downloading Cascade ${state.release.versionName}",
            state.percent?.let { "$it%" } ?: "Starting…",
            state.release,
        )
        is Updater.State.Installing -> Triple(
            "Installing Cascade ${state.release.versionName}",
            "Confirm in Android's installer if it asks.",
            state.release,
        )
        is Updater.State.Failed -> Triple("Update didn't finish", state.message, state.release ?: return)
        else -> return
    }
    HomeCard(title, body) {
        when (state) {
            is Updater.State.Downloading -> {
                Spacer(Modifier.height(12.dp))
                val percent = state.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        color = style.accent,
                        trackColor = style.content.copy(alpha = 0.2f),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = style.accent,
                        trackColor = style.content.copy(alpha = 0.2f),
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            is Updater.State.Available, is Updater.State.Failed -> HomeCardActions(
                action = if (state is Updater.State.Failed) "Try again" else "Update",
                onAction = { onUpdate(release) },
                onDismiss = { onDismiss(release) },
            )
            // Installing: nothing to tap while Android's installer is up, but the card keeps its height.
            else -> Spacer(Modifier.padding(top = 8.dp).height(40.dp))
        }
    }
}
