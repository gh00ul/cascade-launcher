package com.gh00ul.cascade.screenshots

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.HomeApp
import com.gh00ul.cascade.data.HomeFolder
import com.gh00ul.cascade.data.HomeItem
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.folderKey
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NowPlayingState
import com.gh00ul.cascade.ui.home.AllAppsHeader
import com.gh00ul.cascade.ui.home.AlphabetWave
import com.gh00ul.cascade.ui.home.AppMenuCard
import com.gh00ul.cascade.ui.home.AppMenuTarget
import com.gh00ul.cascade.ui.home.ContextMenuPopup
import com.gh00ul.cascade.ui.home.FolderMenuCard
import com.gh00ul.cascade.ui.home.FolderMenuTarget
import com.gh00ul.cascade.ui.home.FolderPopup
import com.gh00ul.cascade.ui.home.HomeMenuPopup
import com.gh00ul.cascade.ui.home.HomePage
import com.gh00ul.cascade.ui.home.HomeStripAlpha
import com.gh00ul.cascade.ui.home.MenuTarget
import com.gh00ul.cascade.ui.home.ListAppRow
import com.gh00ul.cascade.ui.home.OpenFolder
import com.gh00ul.cascade.ui.home.SearchOverlay
import com.gh00ul.cascade.ui.home.SectionHeader
import com.gh00ul.cascade.ui.home.animateHomeAlpha
import com.gh00ul.cascade.ui.home.homeScrim
import com.gh00ul.cascade.ui.home.listCover
import com.gh00ul.cascade.ui.home.rememberMusicGlow
import com.gh00ul.cascade.ui.home.rememberHomeSnapFling
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.ui.theme.colorScheme
import kotlinx.coroutines.launch

/**
 * A stand-in for the system wallpaper. White text gets a dusk sky with dark hills, dark text a pale daytime sky
 * with clouds; both have busy patches so text shadows and scrims get tested. It is always laid out for the whole
 * screen; a shorter frame shows the slice starting [top] (a fraction of the screen height) down.
 */
@Composable
internal fun Wallpaper(darkText: Boolean, modifier: Modifier = Modifier, top: Float = 0f) {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = maxOf(screenHeight, size.height)
        translate(top = -top * h) { scene(darkText, w, h) }
    }
}

private fun DrawScope.scene(darkText: Boolean, w: Float, h: Float) {
    if (!darkText) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0F1B3D), Color(0xFF3B2A6B), Color(0xFFB4536B), Color(0xFFF2A65A))), size = Size(w, h))
        drawCircle(Color(0x55FFE0B2), radius = w * 0.16f, center = Offset(w * 0.72f, h * 0.62f))
        drawCircle(Color(0xFFFFE0B2), radius = w * 0.09f, center = Offset(w * 0.72f, h * 0.62f))
        drawPath(hills(w, h, 0.66f, 0.06f, 1.3f), Color(0xFF2B1B3F))
        drawPath(hills(w, h, 0.76f, 0.05f, 2.1f), Color(0xFF160E24))
        for (i in 0 until 40) {
            val x = (i * 97 % 101) / 101f * w
            val y = (i * 53 % 89) / 89f * h * 0.4f
            drawCircle(Color.White.copy(alpha = 0.25f + (i % 4) * 0.12f), radius = 1.5f + i % 3, center = Offset(x, y))
        }
    } else {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFBFE3FF), Color(0xFFF7E3C8), Color(0xFFF2C9A0))), size = Size(w, h))
        for ((cx, cy, r) in listOf(Triple(0.2f, 0.12f, 0.12f), Triple(0.75f, 0.3f, 0.16f), Triple(0.35f, 0.55f, 0.1f), Triple(0.8f, 0.78f, 0.14f))) {
            val c = Offset(w * cx, h * cy)
            val rr = w * r
            drawCircle(Color.White.copy(alpha = 0.85f), rr, c)
            drawCircle(Color.White.copy(alpha = 0.85f), rr * 0.8f, c + Offset(rr * 0.9f, rr * 0.2f))
            drawCircle(Color.White.copy(alpha = 0.85f), rr * 0.7f, c - Offset(rr * 0.9f, -rr * 0.25f))
        }
        drawPath(hills(w, h, 0.86f, 0.04f, 1.6f), Color(0xFFB7D59B))
    }
}

private fun hills(w: Float, h: Float, base: Float, amplitude: Float, waves: Float) = Path().apply {
    moveTo(0f, h)
    lineTo(0f, h * base)
    val steps = 48
    for (i in 0..steps) {
        val x = w * i / steps
        val y = h * (base - amplitude * kotlin.math.sin(i.toFloat() / steps * Math.PI.toFloat() * waves).let { it * it })
        lineTo(x, y)
    }
    lineTo(w, h)
    close()
}

/** What MainActivity sets up around the launcher: the text style for [darkText] and the wallpaper behind it. */
@Composable
internal fun LauncherSurface(darkText: Boolean, modifier: Modifier = Modifier, wallpaperTop: Float = 0f, content: @Composable () -> Unit) {
    val accent = colorScheme(dark = !darkText).primary
    val style = remember(darkText, accent) { LauncherStyle(darkText, accent) }
    CompositionLocalProvider(LocalLauncherStyle provides style, LocalContentColor provides style.content) {
        Box(modifier) {
            Wallpaper(darkText, Modifier.matchParentSize(), wallpaperTop)
            content()
        }
    }
}

/** Rows as they sit on the home page: the home screen's light scrim and the list's side padding. */
@Composable
internal fun RowBackdrop(content: @Composable () -> Unit) {
    val style = LocalLauncherStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(style.scrim, alpha = 0.12f) }
            .padding(start = 20.dp, end = 44.dp, top = 16.dp, bottom = 16.dp),
    ) { content() }
}

private sealed interface ListRow {
    val key: String

    data class Section(val letter: String) : ListRow {
        override val key = "section:$letter"
    }

    data class App(val app: AppEntry) : ListRow {
        override val key = "app:${app.key}"
        val expandKey = "all:${app.key}"
    }
}

/** Same as LauncherScreen's buildRows. */
private fun buildRows(apps: List<AppEntry>): List<ListRow> = buildList {
    var section: String? = null
    val seen = HashSet<String>()
    for (app in apps) {
        if (app.section != section) {
            section = app.section
            if (seen.add(app.section)) add(ListRow.Section(app.section))
        }
        add(ListRow.App(app))
    }
}

/** Index of the first A–Z row: the home page and the all-apps header come before it. */
internal const val FIRST_APP_ROW = 2

/**
 * LauncherScreen's layout with its state passed in: the scrim, the home page, the A–Z list, the alphabet strip and
 * search. LauncherScreen itself reads the app repository through `context.launcher`, which these tests don't start.
 * The scrim and the list rows are LauncherScreen's own (`homeScrim`, `ListAppRow`), fed the same per-entry states.
 * Search comes and goes on the same transition as LauncherScreen's, which also fades the list and the strip; at rest
 * it draws as it always did. Insets are zero under Robolectric, so they are left out. Callbacks do nothing, except
 * [onSearchDismiss] (search's back and taps outside), for tests that close search the way a user would, and a tap on a
 * folder, which pops it open as in LauncherScreen. [items] are the home rows, apps and folders; by default [favorites].
 */
@Composable
internal fun HomeScreen(
    settings: LauncherSettings,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    icons: Map<String, IconImage>,
    notifications: Map<String, List<AppNotification>> = emptyMap(),
    expandedKey: String? = null,
    media: NowPlayingState? = null,
    mediaResting: Boolean = false,
    onboarding: @Composable () -> Unit = {},
    resume: @Composable () -> Unit = {},
    /** Under the clock, as LauncherScreen fills HomePage's slot: the widget stack. */
    widgets: @Composable () -> Unit = {},
    firstItem: Int = 0,
    searchOpen: Boolean = false,
    onSearchDismiss: () -> Unit = {},
    items: List<HomeItem>? = null,
    /** For tests that scroll it, or read where it came to rest. */
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = firstItem),
    /** For tests that tap, long-press or move favorites; moving them is off unless [onReorderFavorites] is given. */
    onLaunch: (AppEntry) -> Unit = {},
    onAppLongPress: (AppEntry) -> Unit = {},
    /** A favorite held for its menu started to move instead, and the menu closed. */
    onAppMenuClose: () -> Unit = {},
    onReorderFavorites: ((List<String>) -> Unit)? = null,
    /** A status bar's height over the list's top, as LauncherScreen pads it on a phone; none by default. */
    statusTop: Dp = 0.dp,
) {
    val style = LocalLauncherStyle.current
    val density = LocalDensity.current
    // LauncherScreen hands these on as the states it collects; rows and favorites read their own entries.
    val iconsState = rememberUpdatedState(icons)
    val notificationsState = rememberUpdatedState(notifications)
    val scope = rememberCoroutineScope()
    val mediaApp = remember(apps, media?.packageName) {
        media?.let { m -> apps.firstOrNull { it.packageName == m.packageName && !it.isWork } }
    }
    val rows = remember(apps, settings.hidden) { buildRows(apps.filter { it.key !in settings.hidden }) }
    val letterRows = remember(rows) {
        buildMap { rows.forEachIndexed { i, row -> if (row is ListRow.Section && row.letter !in this) put(row.letter, i + FIRST_APP_ROW) } }
    }
    val letters = remember(letterRows) { letterRows.keys.toList() }
    val searchTransition = updateTransition(searchOpen, label = "search")
    val listAlpha = searchTransition.animateHomeAlpha()
    val homeRows = items ?: remember(favorites) { favorites.map(::HomeApp) }
    var openFolder by remember { mutableStateOf<OpenFolder?>(null) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    val shownFolder = openFolder?.let { open -> homeRows.firstOrNull { it.key == folderKey(open.id) } as? HomeFolder }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val homeHeight = maxHeight - statusTop
        val homeHeightPx = with(density) { homeHeight.toPx() }.coerceAtLeast(1f)
        val progress = { listState.listCover(homeHeightPx) }

        val glow = rememberMusicGlow(media, settings, style)
        Box(Modifier.fillMaxSize().homeScrim(style.scrim, settings.wallpaperDim.alpha, progress) { glow.value })

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Hidden under search, as in LauncherScreen.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = listAlpha.value
                },
            contentPadding = PaddingValues(top = statusTop, bottom = 16.dp),
            flingBehavior = rememberHomeSnapFling(listState, homeHeightPx),
        ) {
            item(key = "home", contentType = "home") {
                HomePage(
                    minHeight = homeHeight,
                    bottomInset = 0.dp,
                    favorites = homeRows,
                    showFavoritesHint = homeRows.isEmpty() && (settings.favorites.isEmpty() || apps.isNotEmpty()),
                    icons = iconsState,
                    notifications = notificationsState,
                    settings = settings,
                    onLaunch = { app, _ -> onLaunch(app) },
                    onAppLongPress = { app, anchor ->
                        onAppLongPress(app)
                        menu = AppMenuTarget(app, anchor)
                    },
                    onFolderLongPress = { folder, anchor -> menu = FolderMenuTarget(folder.id, anchor) },
                    onMenuClose = {
                        onAppMenuClose()
                        menu = null
                    },
                    onOpenNotification = { _, _ -> },
                    onEmptyLongPress = { menuAt = it },
                    onEmptyDoubleTap = {},
                    expandedKey = expandedKey,
                    onToggleExpand = {},
                    media = media,
                    mediaApp = mediaApp,
                    mediaResting = mediaResting,
                    onOpenMedia = {},
                    onHideMedia = {},
                    resume = resume,
                    widgets = widgets,
                    onOpenFolder = { folder, bounds -> openFolder = OpenFolder(folder.id, bounds) },
                    onReorderFavorites = onReorderFavorites,
                    // As LauncherScreen finds them: the personal app with that package.
                    appIcon = { pkg -> apps.firstOrNull { it.packageName == pkg && !it.isWork }?.let { icons[it.key] } },
                    onboarding = onboarding,
                )
            }
            item(key = "header", contentType = "header") {
                AllAppsHeader(iconSize = settings.iconSize.listDp.dp, showIcons = settings.showIcons, onSearch = {}, onSettings = {})
            }
            items(rows, key = { it.key }, contentType = { if (it is ListRow.Section) 0 else 1 }) { row ->
                when (row) {
                    is ListRow.Section -> SectionHeader(row.letter, settings.iconSize.listDp.dp, settings.showIcons)
                    is ListRow.App -> ListAppRow(
                        app = row.app,
                        expandKey = row.expandKey,
                        icons = iconsState,
                        notifications = notificationsState,
                        showIcon = settings.showIcons,
                        iconSize = settings.iconSize.listDp.dp,
                        expanded = expandedKey == row.expandKey,
                        onLaunch = { _, _ -> },
                        onLongPress = { app, anchor -> menu = AppMenuTarget(app, anchor) },
                        onOpenNotification = { _, _ -> },
                        onToggleExpand = {},
                    )
                }
            }
        }

        if (letters.isNotEmpty()) {
            searchTransition.AnimatedVisibility(
                visible = { open -> !open },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.8f),
                enter = Motion.LayerIn,
                exit = Motion.LayerOut,
            ) {
                AlphabetWave(
                    letters = letters,
                    onLetter = { letter -> letterRows[letter]?.let { index -> scope.launch { listState.scrollToItem(index) } } },
                    modifier = Modifier.fillMaxHeight(),
                    restAlpha = { HomeStripAlpha + (1f - HomeStripAlpha) * progress() },
                )
            }
        }

        FolderPopup(
            folder = shownFolder,
            anchor = openFolder?.anchor,
            icons = iconsState,
            notifications = notificationsState,
            showIcons = settings.showIcons,
            homeIconSize = settings.iconSize.homeDp.dp,
            iconSize = settings.iconSize.listDp.dp,
            onLaunch = { _, _ -> },
            onAppLongPress = { _, _ -> },
            onOptions = {},
            onDismiss = { openFolder = null },
        )

        HomeMenuPopup(at = menuAt, onWallpaper = {}, onWidgets = {}, onFavorites = {}, onSettings = {}, onDismiss = { menuAt = null })

        // As LauncherScreen draws them, with the cards' actions doing nothing: the real ones need LauncherApplication.
        ContextMenuPopup(target = menu, iconOrigin = 8.dp + settings.iconSize.listDp.dp / 2, onDismiss = { menu = null }) { target ->
            when (target) {
                is AppMenuTarget -> AppMenuCard(
                    app = target.app,
                    icon = icons[target.app.key],
                    showIcon = settings.showIcons,
                    isFavorite = target.app.key in settings.favorites,
                    isHidden = target.app.key in settings.hidden,
                    notifications = notifications[target.app.notificationKey].orEmpty(),
                    shortcuts = emptyList(),
                    canUninstall = true,
                    showHidePlayer = false,
                    onOpenNotification = {},
                    onClearNotifications = {},
                    onShortcut = {},
                    onHidePlayer = {},
                    onToggleFavorite = {},
                    onRename = {},
                    onToggleHidden = {},
                    onAppInfo = {},
                    onUninstall = {},
                )
                is FolderMenuTarget -> (homeRows.firstOrNull { it.key == folderKey(target.folderId) } as? HomeFolder)?.let { folder ->
                    FolderMenuCard(folder, folder.apps.take(4).map { icons[it.key] }, onRename = {}, onEditApps = {}, onRemove = {})
                }
            }
        }

        searchTransition.AnimatedVisibility(visible = { open -> open }, enter = Motion.LayerIn, exit = Motion.LayerOut) {
            SearchOverlay(
                apps = apps,
                icons = icons,
                showIcons = settings.showIcons,
                iconSize = settings.iconSize.listDp.dp,
                excluded = if (settings.hiddenInSearch) emptySet() else settings.hidden,
                searchWeb = settings.searchWeb,
                autoLaunchSingleMatch = settings.autoLaunchSingleMatch,
                onLaunch = { _, _ -> },
                onLongPress = { app, anchor -> menu = AppMenuTarget(app, anchor) },
                onDismiss = onSearchDismiss,
            )
        }
    }
}
