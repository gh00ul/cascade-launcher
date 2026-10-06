package com.gh00ul.cascade.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.ui.common.ExtraIcons
import com.gh00ul.cascade.ui.theme.GlassEdgeWidth
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import kotlin.math.roundToInt

/** The menu's corners: a little rounder than the folder pop-up's, as it holds tiles rather than rows. */
private val MenuShape = RoundedCornerShape(24.dp)
/** Between the card and the finger, so the finger doesn't cover it. */
private val MenuGap = 20.dp
private val TileShape = RoundedCornerShape(18.dp)

/**
 * Long-press on empty home space: a card that pops from the finger ([at], root coordinates), with Wallpaper, Widgets,
 * Favorites and Settings as tiles. It sits above the finger when there's room, else below, on the dim every pop-up
 * shares; tap outside or press Back to close it. Shows while [at] isn't null, and keeps its place while it closes.
 */
@Composable
internal fun HomeMenuPopup(
    at: Offset?,
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val shown = remember { Latest<Offset>() }.also { if (at != null) it.value = at }
    PopupLayer(visible = at != null, title = { "Home screen" }, onDismiss = onDismiss) { scale ->
        val point = shown.value ?: return@PopupLayer
        PointCard(point, scale, Modifier.fillMaxSize().padding(contentPadding)) {
            HomeMenuCard(onWallpaper, onWidgets, onFavorites, onSettings)
        }
    }
}

/** The menu's card alone: four tiles in a row, on the folder pop-up's glass. */
@Composable
internal fun HomeMenuCard(onWallpaper: () -> Unit, onWidgets: () -> Unit, onFavorites: () -> Unit, onSettings: () -> Unit) {
    val style = LocalLauncherStyle.current
    Surface(
        shape = MenuShape,
        color = lerp(style.scrim, style.content, CardLift),
        contentColor = style.content,
        border = BorderStroke(GlassEdgeWidth, style.glassEdge),
        // Taps between the tiles stay on the card instead of closing it.
        modifier = Modifier.pointerInput(Unit) { detectTapGestures {} },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MenuTile(ExtraIcons.Wallpaper, "Wallpaper", onWallpaper)
            // The way to a first widget: an empty stack takes no space on home, so there's nothing to long-press yet.
            MenuTile(ExtraIcons.Widgets, "Widgets", onWidgets)
            MenuTile(Icons.Outlined.FavoriteBorder, "Favorites", onFavorites)
            MenuTile(Icons.Outlined.Settings, "Settings", onSettings)
        }
    }
}

/** A tile: the glyph on a soft disc of the text color, and its name under it. Presses like a row does. */
@Composable
private fun MenuTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val style = LocalLauncherStyle.current
    val press = rememberPressIndication()
    Column(
        Modifier
            .width(76.dp)
            .clip(TileShape)
            .clickable(interactionSource = null, indication = press ?: LocalIndication.current, role = Role.Button, onClick = onClick)
            .pressScale(press)
            .padding(top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(style.content.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = style.chip, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * Lays [content] (the card) out around [at] (root coordinates): centered on it across, above it by [MenuGap] when it
 * fits there, else below, always inside this layout less [PopupMargin]. It scales by [scale] (read only in its layer)
 * around the finger's point.
 */
@Composable
private fun PointCard(at: Offset, scale: () -> Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val margin = PopupMargin.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val card = measurables.first().measure(
            Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0), maxHeight = (height - 2 * margin).coerceAtLeast(0)),
        )
        layout(width, height) {
            // The point came from the root's coordinates; this layout sits below the status bar.
            val here = coordinates
            val p = if (here == null) at else here.localPositionOf(here.findRootCoordinates(), at)
            val gap = MenuGap.roundToPx()
            val maxX = (width - margin - card.width).coerceAtLeast(margin)
            val maxY = (height - margin - card.height).coerceAtLeast(margin)
            val x = (p.x - card.width / 2f).roundToInt().coerceIn(margin, maxX)
            val above = (p.y - gap - card.height).roundToInt()
            val y = if (above >= margin) above.coerceAtMost(maxY) else (p.y + gap).roundToInt().coerceIn(margin, maxY)
            val origin = TransformOrigin(
                ((p.x - x) / card.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                ((p.y - y) / card.height.coerceAtLeast(1)).coerceIn(0f, 1f),
            )
            card.placeWithLayer(x, y) {
                val s = scale()
                scaleX = s
                scaleY = s
                transformOrigin = origin
            }
        }
    }
}
