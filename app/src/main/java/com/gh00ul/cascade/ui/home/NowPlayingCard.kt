package com.gh00ul.cascade.ui.home

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.notifications.NowPlayingState
import com.gh00ul.cascade.ui.common.AppIcon
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlinx.coroutines.delay

/** Music controls on the home page while something is playing (or paused). Tap the card to open the player. */
@Composable
fun NowPlayingCard(state: NowPlayingState, appIcon: IconImage?, appLabel: String?, onOpen: () -> Unit) {
    val style = LocalLauncherStyle.current
    val position by produceState(state.positionAt(SystemClock.elapsedRealtime()), state) {
        while (state.isPlaying) {
            value = state.positionAt(SystemClock.elapsedRealtime())
            delay(500)
        }
        value = state.positionAt(SystemClock.elapsedRealtime())
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = style.scrim.copy(alpha = 0.4f),
        contentColor = style.content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.art != null) {
                    Image(
                        state.art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    AppIcon(appIcon, 44.dp, Modifier.padding(4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = state.subtitle.ifEmpty { appLabel.orEmpty() }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = TextStyle(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(0.7f),
                        )
                    }
                }
                ControlButton(ExtraIcons.SkipPrevious, "Previous", enabled = state.canSkipPrevious, onClick = state::previous)
                IconButton(
                    onClick = state::playPause,
                    modifier = Modifier.size(44.dp).background(style.content, CircleShape),
                ) {
                    Icon(
                        if (state.isPlaying) ExtraIcons.Pause else ExtraIcons.Play,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = style.scrim,
                    )
                }
                ControlButton(ExtraIcons.SkipNext, "Next", enabled = state.canSkipNext, onClick = state::next)
            }
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    color = style.content,
                    trackColor = style.content.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 12.dp)
                        .height(3.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = description) }
}
